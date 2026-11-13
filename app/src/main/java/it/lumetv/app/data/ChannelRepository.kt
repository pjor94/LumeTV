package it.lumetv.app.data

import android.content.Context
import android.util.AtomicFile
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.core.Channel
import it.lumetv.core.ChannelNavigation
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChannelRepository(context: Context, private val http: Transport) {
    private class BuiltinCache {
        val loading = Mutex()
        val refreshing = Mutex()
        @Volatile var channels: List<Channel>? = null
    }
    companion object {
        private val catalogs = java.util.WeakHashMap<Context, BuiltinCache>()
    }
    private val app = context.applicationContext
    private val shared = synchronized(catalogs) { catalogs.getOrPut(app) { BuiltinCache() } }
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
    val personal = PersonalPlaylistRepository.get(app)
    val lastUpdated: Long get() = store.lastSuccess
    // Read only. Every mutation below copies the set before passing it to an editor.
    private val favoriteIds get() = prefs.getStringSet("favorites", emptySet()).orEmpty()
    val recentIds: List<String> get() = prefs.getString("recent_channels", "").orEmpty().split(',').filter { it.isNotEmpty() }
    fun recent(channels: List<Channel>): List<Channel> = recentIds.mapNotNull { id -> channels.firstOrNull { it.id == id } }
    var lastChannelId: String?
        get() = prefs.getString("last_channel", null)
        private set(value) { prefs.edit().putString("last_channel", value).apply() }
    val lastIntegratedChannelId: String? get() = prefs.getString("last_tv_channel", prefs.getString("last_channel", null))

    fun recordPlayback(channel: Channel) {
        synchronized(IntegrationStore.cacheLock) {
            val now = System.currentTimeMillis()
            val editor = prefs.edit()
            if (!channel.isRadio && !channel.isPersonal) editor.putString("last_tv_channel", channel.id).putLong("last_tv_played_at", now)
            else if (channel.isRadio && !channel.isPersonal && !prefs.contains("last_tv_channel")) {
                // Preserve the pre-radio last TV entry when upgrading an existing installation.
                prefs.getString("last_channel", null)?.takeIf { id -> shared.channels.orEmpty().any {it.id==id && !it.isPersonal} }?.let { editor.putString("last_tv_channel", it)
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

    suspend fun nextChannel(channels: List<Channel>, currentId: String?, direction: Int, current: Channel? = null): Channel? {
        val playing = channels.firstOrNull { it.id == currentId } ?: current
        if(playing?.isPersonal==true)return personal.next(playing,direction,if(zapFavoritesOnly)favoriteIds else null)
        val scoped = channels.filter {!it.isVod && (playing==null || it.sourceId==playing.sourceId && it.kind==playing.kind)}
        return ChannelNavigation.next(scoped, currentId, direction, if (zapFavoritesOnly) favoriteIds else null)
    }

    suspend fun find(id:String?):Channel? = id?.let { key -> builtins().firstOrNull {it.id==key} ?: personal.channel(key) }
    fun favorites():Set<String> = favoriteIds.toSet()

    private suspend fun builtins(): List<Channel> = shared.channels ?: shared.loading.withLock {
        shared.channels ?: (sync.local() + radios.local()).also { shared.channels = it }
    }

    suspend fun local(): List<Channel> = (builtins() + personal.local()).also {
        IntegrationStore.catalogChanged(app, it.filter { c -> !c.isRadio && !c.isPersonal }, lastUpdated)
    }
    suspend fun refresh(force: Boolean = false): List<Channel>? = shared.refreshing.withLock {
        var failure: Exception? = null
        suspend fun attempt(block: suspend () -> List<Channel>?): List<Channel>? = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error; null }
        val tv = attempt { sync.refresh(force) }
        val radio = attempt { radios.refresh(force) }
        val users = personal.refreshAll(force)
        if (tv == null && radio == null && !users) { failure?.let { throw it }; return@withLock null }
        val previous = builtins()
        val official = (tv ?: previous.filterNot(Channel::isRadio)) + (radio ?: previous.filter(Channel::isRadio))
        shared.channels = official
        (official + personal.local()).also {
            IntegrationStore.catalogChanged(app, it.filter { c -> !c.isRadio && !c.isPersonal }, lastUpdated)
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
