package it.lumetv.app.data

import it.lumetv.app.BuildConfig
import it.lumetv.core.PersonalM3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class PlaylistFetchException(val status: Int = 0, val retryAt: Long = 0, message: String) : IOException(message)
class PlaylistDownload(val text: String?, val finalUrl: String, val etag: String? = null, val modified: String? = null)
fun interface PlaylistFetcher { suspend fun fetch(url: String, etag: String?, modified: String?): PlaylistDownload }

object PersonalPlaylistNetwork {
    fun allowed(uri: URI): Boolean = uri.scheme.equals("https", true) ||
        BuildConfig.DEBUG && uri.scheme.equals("http", true) && uri.host == "10.0.2.2"

    /** Every request, redirect and HLS/DASH child request passes through this policy. */
    fun client(base: OkHttpClient, root: String, scopedHeaders: Set<String> = emptySet(),
               policy: (URI) -> Boolean = ::allowed): OkHttpClient {
        val origin = root.toHttpUrl().let { Triple(it.scheme, it.host, it.port) }
        return base.newBuilder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            var request = chain.request()
            var redirects = 0
            while (true) {
                val target = URI(request.url.toString())
                if (!policy(target) || target.rawUserInfo != null) throw IOException("La sorgente personale richiede HTTPS.")
                if (request.url.let { Triple(it.scheme, it.host, it.port) } != origin) {
                    request = request.newBuilder().apply {
                        (scopedHeaders + setOf("Authorization", "Cookie")).filterNot { it.equals("User-Agent", true) }
                            .forEach(::removeHeader)
                    }.build()
                }
                val response = chain.proceed(request)
                if (response.code !in setOf(301, 302, 303, 307, 308)) return@addInterceptor response
                val next = response.header("Location")?.let(request.url::resolve)
                response.close()
                if (next == null || redirects++ >= 5) throw IOException("La sorgente ha restituito troppi reindirizzamenti.")
                request = request.newBuilder().url(next).build()
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }.build()
    }

    fun fetcher(http: OkHttpClient = HttpClient().client): PlaylistFetcher = PlaylistFetcher { url, etag, modified ->
        val request = Request.Builder().url(url).header("User-Agent", HttpClient.USER_AGENT)
            .apply { etag?.let { header("If-None-Match", it) }; modified?.let { header("If-Modified-Since", it) } }.build()
        client(http, url).newCall(request).awaitResponse().use { response ->
            if (response.code == 304) return@PlaylistFetcher PlaylistDownload(null, response.request.url.toString(),
                response.header("ETag"), response.header("Last-Modified"))
            if (!response.isSuccessful) {
                val retry = retryAfter(response.header("Retry-After"), System.currentTimeMillis())
                throw PlaylistFetchException(response.code, retry, when (response.code) {
                    401, 403 -> "Accesso alla lista negato. Controlla l’indirizzo e le credenziali."
                    404, 410 -> "La lista non è più disponibile a questo indirizzo."
                    429 -> "Il servizio richiede una pausa. Riprova più tardi."
                    else -> "Il servizio della lista non risponde. La copia salvata resta disponibile."
                })
            }
            val text = withContext(Dispatchers.IO) {
                val input = response.body?.byteStream() ?: throw IOException("La lista è vuota.")
                input.use { decodePlaylistBytes(it.readPlaylistBytes()) }
            }
            PlaylistDownload(text, response.request.url.toString(), response.header("ETag"), response.header("Last-Modified"))
        }
    }

    suspend fun detectMime(url: String, headers: Map<String, String>, http: OkHttpClient): String? {
        val path = URI(url).path.orEmpty().lowercase()
        if (path.endsWith(".m3u8")) return "application/x-mpegURL"
        if (path.endsWith(".mpd")) return "application/dash+xml"
        if (listOf(".mp4", ".ts", ".mp3", ".aac", ".m4a", ".ogg", ".webm", ".mkv").any(path::endsWith)) return null
        val probe = client(http, url, headers.keys).newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        return probe.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw PlaylistFetchException(response.code, message = "Il canale personale non autorizza la riproduzione.")
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            when {
                contentType.contains("mpegurl") -> "application/x-mpegURL"
                contentType == "application/dash+xml" -> contentType
                else -> {
                    val beginning = withContext(Dispatchers.IO) { response.peekBody(2048).string().trimStart('\uFEFF', ' ', '\n', '\r') }
                    when { beginning.startsWith("#EXTM3U") -> "application/x-mpegURL"; "<MPD" in beginning -> "application/dash+xml"; else -> null }
                }
            }
        }
    }
}

internal fun retryAfter(value: String?, now: Long): Long {
    if (value == null) return 0
    value.trim().toLongOrNull()?.takeIf { it >= 0 }?.let { return now + it.coerceAtMost(86_400) * 1000 }
    val date = runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return 0
    return date.coerceIn(now, now + 86_400_000)
}

internal fun java.io.InputStream.readPlaylistBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > PersonalM3uParser.MAX_BYTES) throw IOException("La lista supera il limite di 8 MiB.")
        output.write(buffer, 0, count)
    }
}

internal fun decodePlaylistBytes(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: java.nio.charset.CharacterCodingException) { throw IOException("Salva la lista con codifica UTF-8 e importala nuovamente.") }

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
