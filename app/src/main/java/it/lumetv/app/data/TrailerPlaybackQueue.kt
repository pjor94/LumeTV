package it.lumetv.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Fallback also covers deleted videos and playback failures, not just a missing language. */
internal class TrailerPlaybackQueue(trailers: List<TmdbTrailer>) {
    private val candidates = candidates(trailers)
    private var index = 0

    suspend fun next(resolver: TrailerResolver, loading: (TmdbTrailer) -> Unit): TrailerStream? {
        while (index < candidates.size) {
            currentCoroutineContext().ensureActive()
            val candidate = candidates[index++]
            loading(candidate)
            try {
                return withTimeout(20_000) { resolver.resolve(candidate) }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the same language before trying English. Never open an external app.
            }
        }
        return null
    }

    companion object {
        fun candidates(values: List<TmdbTrailer>): List<TmdbTrailer> = TmdbTrailers.ranked(values.filter {it.site=="YouTube"})
            .let { rows -> rows.filter { it.language == "it" }.take(3) + rows.filter { it.language == "en" }.take(3) }
    }
}
