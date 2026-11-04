package it.lumetv.app.data

import it.lumetv.core.Channel
import org.junit.Assert.*
import org.junit.Test

class ProgrammeRemindersTest {
    private val now = 1_000_000L
    private val channel = Channel("one", "Rai 1", 1, "one", "", "", "", "direct", "")
    private fun item(minutes: Int) = DiscoveryProgramme(channel, Programme("Future", now + minutes * 60_000, now + (minutes + 60) * 60_000))

    @Test fun `reminders persist as data and are delivered once two minutes before starting`() {
        val book = ReminderBook()
        assertTrue(book.toggle(item(10), now))
        val restored = ReminderBook(book.entries)
        assertTrue(restored.contains(item(10)))
        assertNull(restored.takeDue(setOf(channel.id), now + 7 * 60_000))
        assertEquals(item(10).key, restored.takeDue(setOf(channel.id), now + 8 * 60_000)?.key)
        assertNull(restored.takeDue(setOf(channel.id), now + 9 * 60_000))
    }

    @Test fun `cancelled expired and removed channel reminders never interrupt viewing`() {
        val book = ReminderBook()
        book.toggle(item(10), now)
        assertFalse(book.toggle(item(10), now))
        assertNull(book.takeDue(setOf(channel.id), now + 8 * 60_000))
        book.toggle(item(10), now)
        assertNull(book.takeDue(emptySet(), now + 8 * 60_000))
        book.toggle(item(10), now)
        assertNull(book.takeDue(setOf(channel.id), now + 15 * 60_000))
        assertTrue(book.entries.isEmpty())
    }

    @Test fun `starting now cannot be scheduled and duplicate records do not produce duplicate alerts`() {
        val book = ReminderBook()
        assertFalse(book.toggle(item(0), now))
        val reminder = ProgrammeReminder(channel.id, item(10).programme.start, item(10).programme.end, "Future")
        val restored = ReminderBook(listOf(reminder, reminder))
        assertEquals(1, restored.entries.size)
        assertNotNull(restored.takeDue(setOf(channel.id), now + 8 * 60_000))
        assertNull(restored.takeDue(setOf(channel.id), now + 8 * 60_000))
    }
}
