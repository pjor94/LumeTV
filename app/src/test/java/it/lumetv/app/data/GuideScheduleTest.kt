package it.lumetv.app.data

import it.lumetv.core.Channel
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class GuideScheduleTest {
    private val now = Instant.parse("2026-10-27T19:12:00Z").toEpochMilli()
    private val base = GuideSchedule.halfHour(now, ZoneId.of("Europe/Rome"))
    private fun at(minutes: Int) = base + minutes * GuideSchedule.MINUTE
    private fun show(title: String, start: Int, end: Int) = Programme(title, at(start), at(end))
    private fun channel(id: String) = Channel(id, id, 1, id, "", "", "", "direct", "")
    private val one = channel("one")
    private val two = channel("two")

    @Test fun `slots preserve exact duration, clip boundaries and expose missing intervals`() {
        val shows = GuideSchedule.normalize(listOf(show("Short", 30, 35), show("Current", -20, 20), show("Later", 60, 180)))
        val cells = GuideSchedule.slots(shows, at(0), at(150))
        assertEquals(listOf("Current", null, "Short", null, "Later"), cells.map { it.programme?.title })
        assertEquals(at(0), cells.first().start)
        assertEquals(at(150), cells.last().end)
        assertEquals(5 * GuideSchedule.MINUTE, cells[2].end - cells[2].start)
        assertTrue(cells.zipWithNext().all { (a, b) -> a.end == b.start })
    }

    @Test fun `duplicate starts and overlapping source entries never overlap on screen`() {
        val shows = GuideSchedule.normalize(listOf(show("News", 0, 60), show("News", 0, 60),
            show("Film", 30, 90), Programme("", at(90), at(120)), show("Invalid", 90, 80)))
        assertEquals(2, shows.size)
        val cells = GuideSchedule.slots(shows, at(0), at(150))
        assertEquals(at(30), cells.first().end)
        assertEquals(at(30), cells[1].start)
        assertNull(cells.last().programme)
    }

    @Test fun `vertical movement preserves time even when a channel has a gap`() {
        val nav = GuideNavigator()
        nav.submit(listOf(one, two), mapOf("one" to listOf(show("News", 0, 30), show("Film", 30, 120)),
            "two" to listOf(show("Later", 180, 240))), emptySet(), one.id, now)
        assertEquals("News", nav.programme?.title)
        val origin = nav.windowStart
        val anchor = nav.anchor
        nav.moveChannel(1)
        assertNull(nav.programme)
        assertEquals(anchor, nav.anchor)
        assertEquals(origin, nav.windowStart)
        nav.moveChannel(-1)
        assertEquals("News", nav.programme?.title)
    }

    @Test fun `horizontal movement reaches short programmes and scrolls beyond the first window`() {
        val nav = GuideNavigator()
        nav.submit(listOf(one), mapOf("one" to listOf(show("Now", 0, 30), show("Short", 30, 35),
            show("Film", 35, 180), show("Late", 180, 240))), emptySet(), one.id, now)
        assertTrue(nav.moveProgramme(1)); assertEquals("Short", nav.programme?.title)
        assertTrue(nav.moveProgramme(1)); assertEquals("Film", nav.programme?.title)
        assertTrue(nav.moveProgramme(1)); assertEquals("Late", nav.programme?.title)
        assertTrue(nav.windowStart > base)
        assertFalse(nav.moveProgramme(1))
    }

    @Test fun `refresh keeps selection and window while updating programme metadata`() {
        val nav = GuideNavigator()
        val data = mapOf("one" to listOf(show("Now", 0, 30), show("Film", 30, 240)))
        nav.submit(listOf(one), data, emptySet(), one.id, now)
        nav.moveProgramme(1)
        val start = nav.windowStart
        nav.submit(listOf(one), data.mapValues { (_, shows) -> shows.map { it.copy(title = it.title + " updated") } },
            emptySet(), one.id, now + GuideSchedule.MINUTE)
        assertEquals("Film updated", nav.programme?.title)
        assertEquals(start, nav.windowStart)
        nav.tick(now + 90 * GuideSchedule.MINUTE)
        assertEquals(start, nav.windowStart)
    }

    @Test fun `favorites and channel removal have a valid fallback without triggering playback`() {
        val nav = GuideNavigator()
        nav.submit(listOf(one, two), emptyMap(), setOf(two.id), one.id, now)
        nav.filterFavorites(true)
        assertEquals(two, nav.channel)
        nav.submit(listOf(one, two), emptyMap(), emptySet(), one.id, now)
        assertTrue(nav.channels.isEmpty()); assertNull(nav.channel)
        nav.selectChannel(one.id)
        assertFalse(nav.favoritesOnly); assertEquals(one, nav.channel)
    }

    @Test fun `window is bounded by downloaded coverage and evening uses the device time zone`() {
        val nav = GuideNavigator()
        nav.submit(listOf(one), mapOf("one" to listOf(show("Long", 0, 600))), emptySet(), one.id, now)
        repeat(30) { nav.pageTime(1) }
        assertFalse(nav.canGoLater)
        assertEquals(at(600), nav.windowStart + GuideSchedule.WINDOW)
        repeat(30) { nav.pageTime(-1) }
        assertFalse(nav.canGoEarlier)
        assertEquals(base, nav.windowStart)
        assertEquals(Instant.parse("2026-10-27T20:00:00Z").toEpochMilli(), GuideSchedule.evening(now, ZoneId.of("Europe/Rome")))
    }

    @Test fun `repeated daylight saving hour retains the actual instant and elapsed durations`() {
        val zone = ZoneId.of("Europe/Rome")
        val first = Instant.parse("2026-10-25T00:42:00Z").toEpochMilli()
        val second = Instant.parse("2026-10-25T01:42:00Z").toEpochMilli()
        assertEquals(60 * GuideSchedule.MINUTE, GuideSchedule.halfHour(second, zone) - GuideSchedule.halfHour(first, zone))
        val shows = listOf(Programme("First", first, second), Programme("Second", second, second + 60 * GuideSchedule.MINUTE))
        val cells = GuideSchedule.slots(shows, first, second + 60 * GuideSchedule.MINUTE)
        assertEquals(2, cells.size)
        assertEquals(60 * GuideSchedule.MINUTE, cells.first().end - cells.first().start)
    }

    @Test fun `saved selection restores the channel viewport after activity recreation`() {
        val channels = (0 until 30).map { channel("channel" + it) }
        val nav = GuideNavigator()
        nav.submit(channels, emptyMap(), emptySet(), null, now)
        repeat(2) { nav.moveChannel(1, page = true) }
        val restored = GuideNavigator()
        restored.restore(nav.channelId, nav.programmeStart, nav.anchor, nav.windowStart, nav.favoritesOnly, nav.rowStart)
        restored.submit(channels, emptyMap(), emptySet(), null, now)
        assertEquals(nav.channelId, restored.channelId)
        assertEquals(nav.rowStart, restored.rowStart)
        assertEquals(nav.windowStart, restored.windowStart)
    }

    @Test fun `removed programme preserves channel and time rather than jumping to another channel`() {
        val nav = GuideNavigator()
        nav.submit(listOf(one, two), mapOf("one" to listOf(show("News", 0, 30))), emptySet(), one.id, now)
        val anchor = nav.anchor
        val window = nav.windowStart
        nav.submit(listOf(one, two), emptyMap(), emptySet(), two.id, now + GuideSchedule.MINUTE)
        assertEquals(one.id, nav.channelId)
        assertNull(nav.programme)
        assertEquals(anchor, nav.anchor)
        assertEquals(window, nav.windowStart)
    }
}
