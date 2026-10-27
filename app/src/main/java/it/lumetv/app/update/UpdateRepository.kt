package it.lumetv.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface UpdateStore {
    var lastSuccess: Long
    var lastAttempt: Long
    var cached: String?
}

internal interface UpdateFeed {
    suspend fun text(url: String, limit: Int): String
}

internal class UpdateRepository(
    private val network: UpdateFeed,
    private val store: UpdateStore,
    private val installedCode: Int,
    private val sdk: Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    fun available(includePrereleases: Boolean): UpdateInfo? = runCatching {
        store.cached?.let { UpdateInfo.parse(it) }?.takeIf { it.eligible(installedCode, sdk, includePrereleases) }
    }.getOrNull()

    fun invalidate() { store.lastSuccess = 0; store.lastAttempt = 0; store.cached = null }

    suspend fun refresh(includePrereleases: Boolean, force: Boolean = false): UpdateInfo? = mutex.withLock {
        val time = now()
        if (!force && (UpdatePolicy.recent(store.lastSuccess, time, UpdatePolicy.CHECK_INTERVAL) ||
                    UpdatePolicy.recent(store.lastAttempt, time, UpdatePolicy.RETRY_INTERVAL))) return available(includePrereleases)
        store.lastAttempt = time
        val feed = network.text(UpdatePolicy.FEED_URL, 2 * 1024 * 1024)
        val releases = withContext(Dispatchers.Default) { UpdateRelease.parseFeed(feed, includePrereleases) }
        var best: UpdateInfo? = null
        // Bound unauthenticated API/CDN traffic even after years of releases.
        for (release in releases.take(20)) {
            val manifest = network.text(release.manifestUrl, 16 * 1024)
            val info = withContext(Dispatchers.Default) { UpdateInfo.parse(manifest, release) }
            if (info.eligible(installedCode, sdk, includePrereleases) && info.versionCode > (best?.versionCode ?: 0)) best = info
        }
        // Failed/partial responses never erase a previously verified candidate.
        currentCoroutineContext().ensureActive()
        store.cached = best?.json()
        store.lastSuccess = now()
        best
    }
}
