package it.lumetv.app.data

import org.junit.Assert.*
import org.junit.Test

class ProgrammeWindowTest {
    private val first = Programme("Prima", 60_000, 120_000)
    private val second = Programme("Seconda", 120_000, 240_000)

    @Test fun `current programme reports elapsed progress and rounded remaining minutes`() {
        val window = ProgrammeWindow.at(listOf(second, first), 90_000)
        assertEquals(first, window.current)
        assertEquals(second, window.next)
        assertEquals(500, window.progress)
        assertEquals(1L, window.remainingMinutes)
    }

    @Test fun `programme switches at its exact end without keeping stale details`() {
        val window = ProgrammeWindow.at(listOf(first, second), first.end)
        assertEquals(second, window.current)
        assertNull(window.next)
        assertEquals(0, window.progress)
        assertEquals(2L, window.remainingMinutes)
    }

    @Test fun `missing guide and schedule gaps preserve the next programme without fake progress`() {
        val empty = ProgrammeWindow.at(emptyList(), 100_000)
        assertNull(empty.current); assertNull(empty.next)
        assertEquals(0, empty.progress)
        val gap = ProgrammeWindow.at(listOf(second), 100_000)
        assertNull(gap.current)
        assertEquals(second, gap.next)
        assertEquals(0L, gap.remainingMinutes)
    }

    @Test fun `invalid and expired programmes are not presented as current or next`() {
        val window = ProgrammeWindow.at(listOf(first, Programme("", 120_000, 240_000),
            Programme("Invalid", 150_000, 140_000), Programme("Missing date", 0, 500_000)), 140_000)
        assertNull(window.current); assertNull(window.next)
    }
}
