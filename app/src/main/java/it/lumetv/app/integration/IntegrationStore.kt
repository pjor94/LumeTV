package it.lumetv.app.integration

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import it.lumetv.app.BuildConfig
import it.lumetv.app.data.CatalogValidator
import it.lumetv.app.data.Programme
import it.lumetv.core.Channel
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream

/** Shared with catalog/guide file I/O: a provider query never creates a synchronizer or accesses the network. */
object IntegrationStore {
    val cacheLock = Any()
    private var catalog: List<Channel>? = null
    private var guide: Map<String, List<Programme>>? = null
    private var catalogTime: Long? = null
    private var guideTime: Long? = null

    fun allowed(context: Context): Boolean = context.getSharedPreferences("lume", Context.MODE_PRIVATE)
        .getBoolean("aura_integration", false)

    fun setAllowed(context: Context, allowed: Boolean) {
        synchronized(cacheLock) {
            context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().putBoolean("aura_integration", allowed).commit()
        }
        changed(context)
    }

    fun catalogChanged(context: Context, channels: List<Channel>, updated: Long) {
        synchronized(cacheLock) { catalog = channels.toList(); catalogTime = updated.takeIf { it > 0 } }
        changed(context)
    }

    fun guideChanged(context: Context, programmes: Map<String, List<Programme>>, updated: Long) {
        synchronized(cacheLock) { guide = programmes.mapValues { it.value.toList() }; guideTime = updated.takeIf { it > 0 } }
        changed(context)
    }

    fun changed(context: Context) {
        context.contentResolver.notifyChange(Uri.parse("content://${IntegrationContract.AUTHORITY}/v1/snapshot"), null)
        context.contentResolver.notifyChange(Uri.parse("content://${IntegrationContract.AUTHORITY}/v1/capabilities"), null)
    }

    fun snapshot(context: Context): String = synchronized(cacheLock) {
        if (!allowed(context)) return@synchronized IntegrationContract.snapshot(BuildConfig.VERSION_NAME, false,
            emptyList(), emptySet(), emptyMap(), null, null, null, null, System.currentTimeMillis())
        val prefs = context.getSharedPreferences("lume", Context.MODE_PRIVATE)
        if (catalog == null) {
            val cached = runCatching { read(context, "channels.m3u", 2 * 1024 * 1024)?.let(CatalogValidator::parse) }.getOrNull()
            catalog = cached ?: runCatching { context.assets.open("channels.m3u").bufferedReader().use { CatalogValidator.parse(it.readText()) } }.getOrDefault(emptyList())
            catalogTime = if (cached != null) prefs.getLong("catalog_updated", 0).takeIf { it > 0 } else null
        }
        if (guide == null) {
            guide = runCatching {
                val root = JSONObject(read(context, "guide.json", 8 * 1024 * 1024) ?: "{}")
                root.keys().asSequence().take(1000).associateWith { id ->
                    val shows = root.getJSONArray(id)
                    (0 until minOf(shows.length(), 96)).map { index -> shows.getJSONObject(index).let {
                        Programme(it.getString("title").take(300), it.getLong("start"), it.getLong("end"))
                    } }
                }
            }.getOrDefault(emptyMap())
            guideTime = context.getSharedPreferences("guide", Context.MODE_PRIVATE).getLong("updated", 0).takeIf { it > 0 }
        }
        IntegrationContract.snapshot(BuildConfig.VERSION_NAME, true, catalog.orEmpty(),
            prefs.getStringSet("favorites", emptySet()).orEmpty().toSet(), guide.orEmpty(), catalogTime, guideTime,
            prefs.getString("last_channel", null), prefs.getLong("last_played_at", 0).takeIf { it > 0 }, System.currentTimeMillis())
    }

    private fun read(context: Context, name: String, maximum: Int): String? {
        val cache = AtomicFile(File(context.filesDir, name))
        if (!cache.baseFile.exists() && !File(cache.baseFile.path + ".bak").exists()) return null
        return cache.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= maximum)
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
