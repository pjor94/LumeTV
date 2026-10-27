package it.lumetv.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.AtomicFile
import android.util.Log
import android.util.LruCache
import it.lumetv.core.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class LogoArtwork(val bitmap: Bitmap, val lightSurface: Boolean)

/** Original images are downloaded directly and cached privately on this device. */
class ChannelLogoRepository(context: Context, http: HttpClient, private val scope: CoroutineScope) {
    private val directory = File(context.applicationContext.filesDir, "channel-artwork")
    private val index = AtomicFile(File(directory, "index.json"))
    private val indexLock = Any()
    @Volatile private var sources = emptyMap<String, String>()
    private val version = MutableStateFlow(0)
    val revisions = version.asStateFlow()
    private data class Cached(val artwork: LogoArtwork, val checked: Long, val etag: String?, val modified: String?)
    private val memory = object : LruCache<String, Cached>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Cached) = value.artwork.bitmap.allocationByteCount
    }
    private val requests = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<Cached?>>()
    private val retryAfter = ConcurrentHashMap<String, Long>()
    private val downloads = Semaphore(3)
    private val fetcher = LogoFetcher(http.client)

    suspend fun local() = withContext(Dispatchers.IO) {
        synchronized(indexLock) {
            directory.mkdirs()
            sources = runCatching {
                if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) return@runCatching emptyMap()
                require(index.baseFile.length() <= 256 * 1024)
                val root = JSONObject(index.openRead().bufferedReader().use { it.readText() })
                root.keys().asSequence().take(512).mapNotNull { key ->
                    LogoPolicy.url(root.optString(key))?.let { key to it.toString() }
                }.toMap()
            }.getOrDefault(emptyMap())
        }
        prune()
    }

    /** Called after parsing channel metadata, independently of programme freshness. */
    fun updateSources(logos: Map<String, String>) {
        val valid = logos.entries.take(512).mapNotNull { (key, value) ->
            LogoPolicy.url(value)?.let { key.take(200) to it.toString() }
        }.toMap()
        if (valid.isEmpty()) return
        synchronized(indexLock) {
            val merged = (sources + valid).entries.toList().takeLast(512).associate { it.toPair() }
            if (merged == sources) return
            runCatching {
                directory.mkdirs()
                write(index, JSONObject(merged).toString().toByteArray(Charsets.UTF_8))
            }.onFailure { Log.w("LumeLogos", "index_write_failed") }
            sources = merged
        }
        version.update { it + 1 }
    }

    /** Callback always runs on the activity's main scope; recycled views cancel only their subscription. */
    fun request(channel: Channel, display: (LogoArtwork?) -> Unit): Job = scope.launch {
        val urls = LogoPolicy.candidates(channel, sources)
        for (url in urls) {
            val cached = withContext(Dispatchers.IO) { cached(url) }
            if (cached != null) {
                display(cached.artwork)
                if (LogoPolicy.fresh(cached.checked, System.currentTimeMillis())) return@launch
            }
            if ((retryAfter[url] ?: 0) > System.currentTimeMillis()) {
                if (cached != null) return@launch
                continue
            }
            val task = requests.withLock {
                inFlight[url] ?: scope.async(Dispatchers.IO) { download(url, cached) }.also { deferred ->
                    inFlight[url] = deferred
                    deferred.invokeOnCompletion {
                        scope.launch { requests.withLock { if (inFlight[url] === deferred) inFlight.remove(url) } }
                    }
                }
            }
            val result = task.await()
            if (result != null) { display(result.artwork); return@launch }
        }
        display(null)
    }

    private fun cached(url: String): Cached? {
        memory.get(url)?.let { return it }
        return runCatching {
            val image = AtomicFile(file(url, ".image"))
            if (!image.baseFile.exists() && !File(image.baseFile.path + ".bak").exists()) return@runCatching null
            require(image.baseFile.length() <= LogoPolicy.MAX_BYTES)
            val bytes = image.openRead().use { it.readBytes() }
            require(bytes.size <= LogoPolicy.MAX_BYTES)
            val metadata = runCatching { JSONObject(AtomicFile(file(url, ".json")).openRead().bufferedReader().use { it.readText() }) }.getOrDefault(JSONObject())
            Cached(decode(bytes), metadata.optLong("checked"), metadata.optString("etag").takeIf { it.isNotBlank() },
                metadata.optString("modified").takeIf { it.isNotBlank() }).also { memory.put(url, it) }
        }.getOrNull()
    }

    private suspend fun download(url: String, cached: Cached?): Cached? = downloads.withPermit {
        try {
            currentCoroutineContext().ensureActive()
            val downloaded = fetcher.fetch(requireNotNull(LogoPolicy.url(url)), cached?.etag, cached?.modified)
            val artwork = downloaded.bytes?.let(::decode) ?: cached?.artwork ?: throw LogoDownloadException()
            val saved = Cached(artwork, System.currentTimeMillis(), downloaded.etag, downloaded.modified)
            runCatching {
                directory.mkdirs()
                downloaded.bytes?.let { write(AtomicFile(file(url, ".image")), it) }
                val metadata = JSONObject().put("checked", saved.checked).put("etag", saved.etag).put("modified", saved.modified)
                write(AtomicFile(file(url, ".json")), metadata.toString().toByteArray(Charsets.UTF_8))
                prune()
            }.onFailure { Log.w("LumeLogos", "image_write_failed") }
            memory.put(url, saved)
            retryAfter.remove(url)
            saved
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val wait = if (error is LogoDownloadException && error.status in setOf(403, 404, 429)) 6 * 60 * 60_000L else 15 * 60_000L
            retryAfter[url] = System.currentTimeMillis() + wait
            cached
        }
    }

    private fun decode(bytes: ByteArray): LogoArtwork {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 && bounds.outWidth.toLong() * bounds.outHeight <= 6_000_000)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) inSampleSize *= 2
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        val pixels = IntArray(decoded.width * decoded.height)
        decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        var left = decoded.width; var top = decoded.height; var right = -1; var bottom = -1
        var luminance = 0.0; var weight = 0.0
        pixels.forEachIndexed { index, color ->
            val alpha = Color.alpha(color)
            if (alpha > 24) {
                val x = index % decoded.width; val y = index / decoded.width
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                val opacity = alpha / 255.0
                luminance += (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255 * opacity
                weight += opacity
            }
        }
        require(weight > 0 && right >= left && bottom >= top)
        // Trim transparent padding only. Brand colours and opaque backgrounds are preserved.
        val trimmed = Bitmap.createBitmap(decoded, left, top, right - left + 1, bottom - top + 1)
        return LogoArtwork(trimmed, luminance / weight < 0.36)
    }

    private fun file(url: String, suffix: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, hash + suffix)
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.extension == "image" }.orEmpty()
        var bytes = files.sumOf { it.length() }
        if (bytes <= 24L * 1024 * 1024) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (bytes > 20L * 1024 * 1024) {
                val length = file.length()
                if (file.delete()) { bytes -= length; File(directory, file.nameWithoutExtension + ".json").delete() }
            }
        }
    }
}
