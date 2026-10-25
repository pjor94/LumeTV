package it.lumetv.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GuideSyncTest {
    private class Store : GuideStore {
        override var updated = 0L
        override var attempted = 0L
        override var retryAfter = 0L
        override var failure = ""
        var content = emptyMap<String, List<Programme>>()
        var failWrite = false
        override fun read() = content
        override fun write(programmes: Map<String, List<Programme>>) {
            if (failWrite) throw IOException("disk full")
            content = programmes
        }
    }
    private class Source : GuideSource {
        var calls = 0
        var error: Exception? = null
        var empty = false
        override suspend fun fetch(wanted: Set<String>, now: Long): Map<String, List<Programme>> {
            calls++
            error?.let { throw it }
            return if (empty) emptyMap() else mapOf("rai1" to listOf(Programme("Current", now - 1000, now + 86_400_000)))
        }
    }
    private val wanted = setOf("rai1")

    @Test fun `resume checks are deduplicated and refresh after six hours`() = runTest {
        val store = Store(); val source = Source(); var time = 100_000L
        val sync = GuideSync(store, source, { time })
        List(4) { async { sync.refresh(wanted) } }.awaitAll()
        assertEquals(1, source.calls)
        time += GuideSync.FRESH_FOR - 1
        assertNull(sync.refresh(wanted))
        time++
        assertNotNull(sync.refresh(wanted))
        assertEquals(2, source.calls)
    }

    @Test fun `failed first download retries without restarting the app`() = runTest {
        val store = Store(); val source = Source().apply { error = IOException("offline") }; var time = 100_000L
        val sync = GuideSync(store, source, { time })
        assertFails { sync.refresh(wanted) }
        source.error = null
        assertNull(sync.refresh(wanted))
        time += GuideSync.ERROR_WAIT
        assertNotNull(sync.refresh(wanted))
        assertEquals("", store.failure)
        assertEquals(2, source.calls)
    }

    @Test fun `manual refresh bypasses ordinary failure wait and retains anti spam interval`() = runTest {
        val store = Store(); val source = Source().apply { error = IOException("offline") }; var time = 100_000L
        val sync = GuideSync(store, source, { time })
        assertFails { sync.refresh(wanted) }
        source.error = null
        assertNull(sync.refresh(wanted, true))
        time += GuideSync.MINIMUM_WAIT
        assertNotNull(sync.refresh(wanted, true))
    }

    @Test fun `rate limiting is respected even for manual refresh`() = runTest {
        val store = Store(); val source = Source().apply { error = SourceException("pause", 429) }; var time = 100_000L
        val sync = GuideSync(store, source, { time })
        assertFails { sync.refresh(wanted) }
        source.error = null
        time += GuideSync.ERROR_WAIT
        assertNull(sync.refresh(wanted, true))
        assertEquals(1, source.calls)
        time += GuideSync.RATE_LIMIT_WAIT
        assertNotNull(sync.refresh(wanted))
    }

    @Test fun `empty download and disk failure both preserve cached programmes`() = runTest {
        val store = Store(); val source = Source(); var time = 100_000L
        val sync = GuideSync(store, source, { time })
        val original = sync.refresh(wanted)
        time += GuideSync.FRESH_FOR
        source.empty = true
        assertFails { sync.refresh(wanted) }
        assertEquals(original, store.content)
        source.empty = false; store.failWrite = true; time += GuideSync.ERROR_WAIT
        assertFails { sync.refresh(wanted) }
        assertEquals(original, store.content)
        assertEquals("storage", store.failure)
        assertEquals(100_000L, store.updated)
    }

    @Test fun `expired local programmes do not suppress a new download`() = runTest {
        val store = Store().apply {
            updated = 90_000
            content = mapOf("rai1" to listOf(Programme("Expired", 50_000, 80_000)))
        }
        val sync = GuideSync(store, Source(), { 100_000L })
        assertTrue(sync.local().isEmpty())
        assertNotNull(sync.refresh(wanted))
    }

    @Test fun `interrupted request is retried after a short cooldown`() = runTest {
        val store = Store(); val source = Source().apply { error = CancellationException() }; var time = 100_000L
        val sync = GuideSync(store, source, { time })
        try { sync.refresh(wanted); fail() } catch (_: CancellationException) { }
        source.error = null
        assertNull(sync.refresh(wanted))
        time += 60_000
        assertNotNull(sync.refresh(wanted))
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        try { block(); fail("Expected failure") } catch (_: IOException) { }
    }
}
