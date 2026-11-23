package it.lumetv.app.ui

import it.lumetv.app.data.Programme
import it.lumetv.core.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveProgrammeLoaderTest {
    private val channels = (0..5).map { Channel("$it", "Canale $it", it, "epg$it", "", "", "", "personal", "$it", sourceId = "user_fixture") }

    @Test fun `scrolling finishes the active read and fetches only missing channels in the latest window`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reads = mutableListOf<List<Channel>>()
        val guide = LiveProgrammeLoader(this, { rows, now ->
            reads += rows
            gate.await()
            rows.associate { it.epgId to Programme(it.name, now - 100, now + 600_000) }
        }, changed = {}, clock = { 1_000L })

        guide.request(channels.subList(0, 3))
        runCurrent()
        assertNull(guide.result("epg0"))
        guide.request(channels.subList(1, 4))
        guide.request(listOf(channels[5], channels[4], channels[2]))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(channels.subList(0, 3), listOf(channels[5], channels[4])), reads)

        guide.stop()
        guide.request(channels.subList(0, 3).reversed())
        advanceUntilIdle()
        assertEquals(2, reads.size)
        assertEquals("Canale 0", guide.result("epg0")?.programme?.title)
    }

    @Test fun `checked absence and an expired programme trigger their own refresh`() = runTest {
        var now = 1_000L
        val reads = mutableListOf<List<Channel>>()
        val rows = channels.take(2)
        val guide = LiveProgrammeLoader(this, { requested, at ->
            reads += requested
            requested.filter { it == rows.first() }.associate { it.epgId to Programme("In onda $at", at, at + 1_000) }
        }, changed = {}, clock = { now })

        guide.request(rows)
        advanceUntilIdle()
        assertNotNull(guide.result("epg1"))
        assertNull(guide.result("epg1")?.programme)
        now = 2_000L
        assertNull(guide.result("epg0"))
        guide.request(rows)
        advanceUntilIdle()
        assertEquals(listOf(rows, rows.take(1)), reads)
        assertEquals("In onda 2000", guide.result("epg0")?.programme?.title)

        now = 31_000L
        assertNull(guide.result("epg1"))
    }

    @Test fun `a late read cannot publish programmes from before a guide update`() = runTest {
        val oldRead = CompletableDeferred<Unit>()
        var revision = 0
        val changes = mutableListOf<Set<String>>()
        val guide = LiveProgrammeLoader(this, { rows, now ->
            val value = revision
            if (value == 0) withContext(NonCancellable) { oldRead.await() }
            rows.associate { it.epgId to Programme("Guida $value", now - 100, now + 60_000) }
        }, changed = { changes += it }, clock = { 1_000L })

        guide.request(channels.take(1))
        runCurrent()
        revision++
        guide.invalidate()
        runCurrent()
        oldRead.complete(Unit)
        advanceUntilIdle()
        assertEquals("Guida 1", guide.result("epg0")?.programme?.title)
        assertEquals(listOf(setOf("epg0")), changes)
    }
}
