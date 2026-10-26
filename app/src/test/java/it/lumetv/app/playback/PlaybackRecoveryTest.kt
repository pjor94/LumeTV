package it.lumetv.app.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryTest {
    private val temporary = PlaybackFailure("temporary", true)
    private class Harness(scope: TestScope) {
        var online = true
        var retries = 0
        var waiting = 0
        val scheduled = mutableListOf<Int>()
        val errors = mutableListOf<String>()
        val recovery = PlaybackRecovery(scope.backgroundScope, { online }, { retries++ }, { waiting++ },
            { attempt, maximum -> assertEquals(3, maximum); scheduled += attempt }, { errors += it })
            .apply { start() }
    }

    @Test fun `three retries use backoff and stop after the budget is exhausted`() = runTest {
        val h = Harness(this)
        for ((index, wait) in listOf(2_000L, 5_000L, 10_000L).withIndex()) {
            h.recovery.onFailure(temporary)
            h.recovery.onFailure(temporary) // Repeated callbacks must not queue another retry.
            advanceTimeBy(wait - 1); runCurrent()
            assertEquals(index, h.retries)
            advanceTimeBy(1); runCurrent()
            assertEquals(index + 1, h.retries)
        }
        h.recovery.onFailure(temporary)
        assertEquals(listOf(1, 2, 3), h.scheduled)
        assertEquals(listOf("temporary"), h.errors)
        h.recovery.onNetworkChanged(true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, h.retries)
    }

    @Test fun `offline waits without spending retries and reconnects once when the network returns`() = runTest {
        val h = Harness(this).apply { online = false }
        h.recovery.onFailure(temporary)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(0, h.retries)
        assertEquals(1, h.waiting)
        h.online = true
        h.recovery.onNetworkChanged(true)
        h.recovery.onNetworkChanged(true)
        runCurrent()
        assertEquals(1, h.retries)
        assertFalse(h.recovery.isRecovering)
        h.recovery.onFailure(temporary)
        assertEquals(listOf(1, 2), h.scheduled)
    }

    @Test fun `network loss during backoff pauses the same attempt`() = runTest {
        val h = Harness(this)
        h.recovery.onFailure(temporary)
        advanceTimeBy(1_000)
        h.online = false
        h.recovery.onNetworkChanged(false)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, h.retries)
        h.online = true
        h.recovery.onNetworkChanged(true)
        runCurrent()
        assertEquals(1, h.retries)
        assertEquals(listOf(1, 1), h.scheduled)
    }

    @Test fun `immediate main thread reconnect does not leave a completed retry pending`() = runTest {
        var online = false
        var retries = 0
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val recovery = PlaybackRecovery(scope, { online }, { retries++ }, {}, { _, _ -> }, { fail(it) })
        recovery.start()
        recovery.onFailure(temporary)
        online = true
        recovery.onNetworkChanged(true)
        assertEquals(1, retries)
        assertFalse(recovery.isRecovering)
    }

    @Test fun `network state is rechecked even if a callback was missed`() = runTest {
        val h = Harness(this)
        h.recovery.onFailure(temporary)
        h.online = false
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, h.retries)
        assertTrue(h.recovery.isRecovering)
        h.online = true
        h.recovery.onNetworkChanged(true)
        runCurrent()
        assertEquals(1, h.retries)
    }

    @Test fun `catalog standby or channel change cancels pending retries and network recovery`() = runTest {
        val h = Harness(this)
        h.recovery.onFailure(temporary)
        h.recovery.stop()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, h.retries)
        h.recovery.start(); h.online = false
        h.recovery.onFailure(temporary)
        h.recovery.stop(); h.online = true
        h.recovery.onNetworkChanged(true)
        h.recovery.onFailure(temporary)
        runCurrent()
        assertEquals(0, h.retries)
        assertFalse(h.recovery.isRecovering)
    }

    @Test fun `brief playback does not reset the budget but thirty healthy seconds do`() = runTest {
        val h = Harness(this)
        h.recovery.onFailure(temporary)
        advanceTimeBy(2_000); runCurrent()
        h.recovery.onPlaying()
        advanceTimeBy(29_999); runCurrent()
        h.recovery.onFailure(temporary)
        assertEquals(listOf(1, 2), h.scheduled)
        advanceTimeBy(5_000); runCurrent()
        h.recovery.onPlaying()
        advanceTimeBy(PlaybackRecovery.STABLE_PLAYBACK); runCurrent()
        h.recovery.onFailure(temporary)
        assertEquals(listOf(1, 2, 1), h.scheduled)
    }

    @Test fun `buffering interrupts the healthy playback interval`() = runTest {
        val h = Harness(this)
        h.recovery.onFailure(temporary)
        advanceTimeBy(2_000); runCurrent()
        h.recovery.onPlaying()
        advanceTimeBy(20_000)
        h.recovery.onBuffering()
        advanceTimeBy(20_000)
        h.recovery.onFailure(temporary)
        assertEquals(listOf(1, 2), h.scheduled)
    }

    @Test fun `stalled loading is bounded and canceled when playback starts`() = runTest {
        val h = Harness(this)
        h.recovery.onBuffering()
        advanceTimeBy(PlaybackRecovery.STALL_TIMEOUT); runCurrent()
        assertTrue(h.recovery.isRecovering)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, h.retries)
        h.recovery.onBuffering()
        h.recovery.onPlaying()
        advanceTimeBy(PlaybackRecovery.STALL_TIMEOUT); runCurrent()
        assertEquals(1, h.retries)
        assertFalse(h.recovery.isRecovering)
    }

    @Test fun `successful playback cancels a pending retry`() = runTest {
        val h = Harness(this)
        h.recovery.onFailure(temporary)
        h.recovery.onPlaying()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, h.retries)
    }

    @Test fun `terminal failures never restart on network changes and manual start resets state`() = runTest {
        val h = Harness(this)
        h.recovery.onFailure(PlaybackFailure("DRM", false))
        h.recovery.onNetworkChanged(true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, h.retries)
        assertEquals(listOf("DRM"), h.errors)
        h.recovery.start()
        h.recovery.onFailure(temporary)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, h.retries)
    }
}
