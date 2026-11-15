package it.lumetv.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Session timer. The injected clock includes sleep and is independent of wall-clock changes. */
internal class SleepTimer(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val changed: () -> Unit,
    private val expired: () -> Unit,
) {
    companion object { val MINUTES = listOf(15, 30, 60, 90) }
    private var deadline = 0L
    private var job: Job? = null
    val remaining: Long get() = if (deadline == 0L) 0 else (deadline - now()).coerceAtLeast(0)
    val isExpired: Boolean get() = deadline != 0L && now() >= deadline

    fun start(minutes: Int) {
        require(minutes in MINUTES)
        cancel()
        deadline = now() + minutes * 60_000L
        job = scope.launch {
            while (remaining > 0) {
                changed()
                delay(minOf(1_000L, remaining))
            }
            deadline = 0
            job = null
            expired()
        }
    }

    fun cancel() {
        deadline = 0
        job?.cancel()
        job = null
    }
}
