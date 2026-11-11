package it.lumetv.app.data

import android.content.Context
import android.util.AtomicFile
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.core.Channel
import it.lumetv.core.ChannelNavigation
import java.io.File
import kotlinx.coroutines.CancellationException

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
        override fun read(): String? = synchronized(IntegrationStore.cacheLock) { if (cache.baseFile.exists() || File(cache.baseFile.path + ".bak").exists())
            cache.openRead().bufferedReader().use { it.readText() } else null }
        override fun write(text: String) = synchronized(IntegrationStore.cacheLock) {
            val output = cache.startWrite()
            try { output.write(text.toByteArray(Charsets.UTF_8)); cache.finishWrite(output) }
            catch (error: Exception) { cache.failWrite(output); throw error }
        }
    }
    private val sync = CatalogSync(http, store, { app.assets.open("channels.m3u").bufferedReader().use { it.readText() } })
    private val radios = RadioRepository(app, http)
    val lastUpdated: Long get() = store.lastSuccess
    private val favoriteIds get() = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
    val recentIds: List<String> get() = prefs.getString("recent_channels", "").orEmpty().split(',').filter { it.isNotEmpty() }
    fun recent(channels: List<Channel>): List<Channel> = recentIds.mapNotNull { id -> channels.firstOrNull { it.id == id } }
    var lastChannelId: String?
        get() = prefs.getString("last_channel", null)
        private set(value) { prefs.edit().putString("last_channel", value).apply() }

    fun recordPlayback(channel: Channel) {
        synchronized(IntegrationStore.cacheLock) {
            val now = System.currentTimeMillis()
            val editor = prefs.edit()
            if (!channel.isRadio) editor.putString("last_tv_channel", channel.id).putLong("last_tv_played_at", now)
            else if (!prefs.contains("last_tv_channel")) {
                // Preserve the pre-radio last TV entry when upgrading an existing installation.
                prefs.getString("last_channel", null)?.let { editor.putString("last_tv_channel", it)
                    .putLong("last_tv_played_at", prefs.getLong("last_played_at", 0)) }
            }
            editor.putString("last_channel", channel.id).putLong("last_played_at", now)
                .putString("recent_channels", (listOf(channel.id) + recentIds.filterNot { it == channel.id }).take(40).joinToString(",")).apply()
        }
        IntegrationStore.changed(app)
    }
    var zapFavoritesOnly: Boolean
        get() = prefs.getBoolean("zap_favorites_only", false)
        set(value) { prefs.edit().putBoolean("zap_favorites_only", value).apply() }

    fun nextChannel(channels: List<Channel>, currentId: String?, direction: Int): Channel? =
        ChannelNavigation.next(channels, currentId, direction, if (zapFavoritesOnly) favoriteIds else null)

    suspend fun local(): List<Channel> = (sync.local() + radios.local()).also {
        IntegrationStore.catalogChanged(app, it.filterNot(Channel::isRadio), lastUpdated)
    }
    suspend fun refresh(force: Boolean = false): List<Channel>? {
        var failure: Exception? = null
        suspend fun attempt(block: suspend () -> List<Channel>?): List<Channel>? = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error; null }
        val tv = attempt { sync.refresh(force) }
        val radio = attempt { radios.refresh(force) }
        if (tv == null && radio == null) { failure?.let { throw it }; return null }
        return ((tv ?: sync.local()) + (radio ?: radios.local())).also {
            IntegrationStore.catalogChanged(app, it.filterNot(Channel::isRadio), lastUpdated)
        }
    }

    fun isFavorite(channel: Channel) = channel.id in favoriteIds
    fun toggleFavorite(channel: Channel): Boolean {
        val added = synchronized(IntegrationStore.cacheLock) {
        val ids = favoriteIds.toMutableSet()
        val added = if (channel.id in ids) { ids.remove(channel.id); false } else { ids.add(channel.id); true }
        prefs.edit().putStringSet("favorites", ids).apply()
        added
        }
        IntegrationStore.changed(app)
        return added
    }

}
