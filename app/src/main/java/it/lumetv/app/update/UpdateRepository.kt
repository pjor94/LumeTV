package it.lumetv.app.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface UpdateStore {
    var lastSuccess: Long
    var lastAttempt: Long
    var retryAfter: Long
    var rateLimitUntil: Long
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

    fun invalidate() { store.lastSuccess = 0; store.cached = null }

    fun nextCheckDelay(): Long {
        val time = now()
        val fresh = if (UpdatePolicy.recent(store.lastSuccess, time, UpdatePolicy.CHECK_INTERVAL))
            store.lastSuccess + UpdatePolicy.CHECK_INTERVAL - time else 0
        val retry = if (time >= store.lastAttempt) maxOf(store.retryAfter, store.rateLimitUntil) - time else 0
        return maxOf(0, fresh, retry)
    }

    suspend fun refresh(includePrereleases: Boolean, force: Boolean = false): UpdateInfo? = mutex.withLock {
        val time = now()
        if (time >= store.lastAttempt && store.rateLimitUntil > time) {
            if (force) throw UpdateRateLimitException(store.rateLimitUntil)
            return available(includePrereleases)
        }
        if (!force && nextCheckDelay() > 0) return available(includePrereleases)
        store.lastAttempt = time
        // Also bounds checks interrupted by standby or process death. Success clears this wait.
        store.retryAfter = time + UpdatePolicy.RETRY_INTERVAL
        try {
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
            store.retryAfter = 0
            store.rateLimitUntil = 0
            best
        } catch (limited: UpdateRateLimitException) {
            store.rateLimitUntil = maxOf(now() + UpdatePolicy.RETRY_INTERVAL, limited.retryAt)
            store.retryAfter = store.rateLimitUntil
            throw limited
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            store.retryAfter = now() + UpdatePolicy.RETRY_INTERVAL
            throw error
        }
    }
}
