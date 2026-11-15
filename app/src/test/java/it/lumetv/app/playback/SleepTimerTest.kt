package it.lumetv.app.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    @Test fun `session timer expires once after the chosen interval`() = runTest {
        var stops = 0
        val timer = SleepTimer(backgroundScope, { testScheduler.currentTime }, {}, { stops++ })
        timer.start(15); runCurrent()
        advanceTimeBy(899_999); runCurrent()
        assertEquals(0, stops); assertEquals(1, timer.remaining)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, stops); assertEquals(0, timer.remaining)
        advanceTimeBy(120_000); runCurrent(); assertEquals(1, stops)
    }

    @Test fun `replacement and cancellation cannot leave an older expiry scheduled`() = runTest {
        var stops = 0
        val timer = SleepTimer(backgroundScope, { testScheduler.currentTime }, {}, { stops++ })
        timer.start(15); advanceTimeBy(600_000); timer.start(30)
        advanceTimeBy(900_000); runCurrent(); assertEquals(0, stops)
        timer.cancel(); advanceTimeBy(1_800_000); runCurrent()
        assertEquals(0, stops); assertEquals(0, timer.remaining)
    }

    @Test fun `elapsed clock catches up after sleep before a scheduled tick`() = runTest {
        var elapsed = 10_000L
        var stops = 0
        val timer = SleepTimer(backgroundScope, { elapsed }, {}, { stops++ })
        timer.start(15); runCurrent()
        elapsed += 900_001
        assertTrue(timer.isExpired); assertEquals(0, timer.remaining)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, stops); assertFalse(timer.isExpired)
    }

    @Test fun `stopping on expiration cancels recovery and cannot restart playback`() = runTest {
        var retry = 0
        var online = false
        lateinit var recovery: PlaybackRecovery
        val timer = SleepTimer(backgroundScope, { testScheduler.currentTime }, {}, { recovery.stop() })
        recovery = PlaybackRecovery(backgroundScope, { online }, { retry++ }, {}, { _, _ -> }, {})
        recovery.start(); recovery.onFailure(PlaybackFailure("offline", true)); timer.start(15)
        advanceTimeBy(900_000); runCurrent(); online = true; recovery.onNetworkChanged(true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, retry)
    }

    @Test fun `timer accepts only the supported durations`() = runTest {
        val timer = SleepTimer(backgroundScope, { testScheduler.currentTime }, {}, {})
        assertThrows(IllegalArgumentException::class.java) { timer.start(-1) }
        assertThrows(IllegalArgumentException::class.java) { timer.start(Int.MAX_VALUE) }
        assertEquals("Disattivato", sleepTimerLabel(0))
        assertEquals("15:00 rimanenti", sleepTimerLabel(899_001))
    }
}
