package it.lumetv.app.data

import android.content.Context
import android.util.AtomicFile
import it.lumetv.core.Channel
import java.io.File

class ChannelRepository(context: Context, private val http: Transport) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("lume", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(app.filesDir, "channels.m3u"))
    private val store = object : CatalogStore {
        override var lastSuccess: Long
            get() = prefs.getLong("catalog_updated", 0)
            set(value) { prefs.edit().putLong("catalog_updated", value).apply() }
        override var lastAttempt: Long
            get() = prefs.getLong("catalog_attempted", 0)
            set(value) { prefs.edit().putLong("catalog_attempted", value).apply() }
        override fun read(): String? = if (cache.baseFile.exists() || File(cache.baseFile.path + ".bak").exists())
            cache.openRead().bufferedReader().use { it.readText() } else null
        override fun write(text: String) {
            val output = cache.startWrite()
            try { output.write(text.toByteArray(Charsets.UTF_8)); cache.finishWrite(output) }
            catch (error: Exception) { cache.failWrite(output); throw error }
        }
    }
    private val sync = CatalogSync(http, store, { app.assets.open("channels.m3u").bufferedReader().use { it.readText() } })
    val lastUpdated: Long get() = store.lastSuccess
    private val favoriteIds get() = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
    var lastChannelId: String?
        get() = prefs.getString("last_channel", null)
        set(value) { prefs.edit().putString("last_channel", value).apply() }

    suspend fun local(): List<Channel> = sync.local()
    suspend fun refresh(force: Boolean = false): List<Channel>? = sync.refresh(force)

    fun isFavorite(channel: Channel) = channel.id in favoriteIds
    fun toggleFavorite(channel: Channel): Boolean {
        val ids = favoriteIds.toMutableSet()
        val added = if (channel.id in ids) { ids.remove(channel.id); false } else { ids.add(channel.id); true }
        prefs.edit().putStringSet("favorites", ids).apply()
        return added
    }

}
