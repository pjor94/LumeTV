package it.lumetv.app.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread coordinator. A channel change, catalog or standby cancels all recovery work. */
internal class PlaybackRecovery(
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean,
    private val retry: () -> Unit,
    private val onWaiting: () -> Unit,
    private val onScheduled: (attempt: Int, maximum: Int) -> Unit,
    private val onExhausted: (String) -> Unit,
) {
    private var active = false
    private var attempts = 0
    private var waiting = false
    private var pending: Job? = null
    private var stable: Job? = null
    private var stalled: Job? = null
    private var failure: PlaybackFailure? = null
    val isRecovering get() = waiting || pending != null

    fun start() {
        stop()
        active = true
        attempts = 0
    }

    fun stop() {
        active = false
        pending?.cancel(); pending = null
        stable?.cancel(); stable = null
        stalled?.cancel(); stalled = null
        waiting = false
        failure = null
    }

    fun onFailure(error: PlaybackFailure) {
        if (!active) return
        stalled?.cancel(); stalled = null
        onNotPlaying()
        if (!error.recoverable || attempts >= RETRY_DELAYS.size) {
            stop()
            onExhausted(error.message)
            return
        }
        failure = error
        if (isRecovering) return
        schedule()
    }

    fun onNetworkChanged(online: Boolean) {
        if (!active || failure == null) return
        if (online && waiting) {
            waiting = false
            schedule(immediate = true)
        } else if (!online && pending != null) {
            pending?.cancel(); pending = null
            waitForNetwork()
        }
    }

    fun onBuffering() {
        if (!active || isRecovering || stalled != null) return
        onNotPlaying()
        stalled = scope.launch {
            delay(STALL_TIMEOUT)
            stalled = null
            onFailure(PlaybackFailure("La diretta impiega troppo tempo a rispondere. Riprova o scegli un altro canale.", true))
        }
    }

    fun onPlaying() {
        if (!active) return
        pending?.cancel(); pending = null
        stalled?.cancel(); stalled = null
        waiting = false
        failure = null
        if (stable == null) stable = scope.launch {
            // A briefly working stream must not grant an unlimited new retry budget.
            delay(STABLE_PLAYBACK)
            attempts = 0
            stable = null
        }
    }

    fun onNotPlaying() {
        stable?.cancel(); stable = null
    }

    private fun schedule(immediate: Boolean = false) {
        if (!isOnline()) { waitForNetwork(); return }
        onScheduled(attempts + 1, RETRY_DELAYS.size)
        pending = scope.launch(start = CoroutineStart.LAZY) {
            delay(if (immediate) 0 else RETRY_DELAYS[attempts])
            pending = null
            if (!isOnline()) { waitForNetwork(); return@launch }
            attempts++
            retry()
        }
        pending?.start()
    }

    private fun waitForNetwork() {
        waiting = true
        onWaiting()
    }

    companion object {
        private val RETRY_DELAYS = longArrayOf(2_000, 5_000, 10_000)
        const val STALL_TIMEOUT = 45_000L
        const val STABLE_PLAYBACK = 30_000L
    }
}
