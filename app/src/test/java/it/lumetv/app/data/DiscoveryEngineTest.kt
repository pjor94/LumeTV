package it.lumetv.app.data

import it.lumetv.core.Channel
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DiscoveryEngineTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val now = Instant.parse("2026-11-04T19:45:00Z").toEpochMilli() // 20:45 local
    private fun at(minutes: Int) = now + minutes * 60_000
    private fun channel(id: String, number: Int = 1, name: String = id) = Channel(id, name, number, id, "", "", "", "direct", "")
    private val one = channel("rai1", 1, "Rai 1")
    private val two = channel("la7", 7, "La7")
    private fun p(title: String, start: Int, end: Int, description: String = "") = Programme(title, at(start), at(end), description)
    private fun select(channels: List<Channel>, data: Map<String, List<Programme>>, period: DiscoveryPeriod = DiscoveryPeriod.NOW,
                       favorites: Set<String> = emptySet(), only: Boolean = false, query: String = "", clock: Long = now) =
        DiscoveryEngine.select(channels, data, favorites, period, only, query, clock, zone)

    @Test fun `now picks exactly one current programme and resolves overlapping entries`() {
        val list = select(listOf(one), mapOf(one.epgId to listOf(p("Expired", -60, -20), p("Old", -20, 30),
            p("Current", -5, 15), p("Future", 15, 90), p("Invalid", 20, 10))))
        assertEquals(listOf("Current"), list.map { it.programme.title })
        assertEquals(at(15), list.single().programme.end)
    }

    @Test fun `favorites lead and nearly finished shows follow other shows within their group`() {
        val three = channel("three", 3)
        val four = channel("four", 4)
        val data = mapOf(one.epgId to listOf(p("Ending favorite", -90, 3)), two.epgId to listOf(p("Favorite", -5, 40)),
            three.epgId to listOf(p("Ending", -90, 3)), four.epgId to listOf(p("Fresh", -5, 40)))
        assertEquals(listOf("Favorite", "Ending favorite", "Fresh", "Ending"),
            select(listOf(one, two, three, four), data, favorites = setOf(one.id, two.id)).map { it.programme.title })
    }

    @Test fun `short programmes just started are not labeled as ending`() {
        assertFalse(DiscoveryProgramme(one, p("Brief", -1, 4)).endingSoon(now))
        assertTrue(DiscoveryProgramme(one, p("Film", -90, 3)).endingSoon(now))
        assertEquals(1000, DiscoveryProgramme(one, p("Film", -90, -1)).progress(now))
    }

    @Test fun `soon returns the next matching show per channel within ninety minutes`() {
        val data = mapOf(one.epgId to listOf(p("Now", -5, 10), p("Next", 10, 15), p("Later", 30, 90)),
            two.epgId to listOf(p("First", 5, 100)))
        assertEquals(listOf("First", "Next"), select(listOf(one, two), data, DiscoveryPeriod.SOON).map { it.programme.title })
        assertEquals(listOf("Later"), select(listOf(one), data, DiscoveryPeriod.SOON, query = "Later").map { it.programme.title })
        assertTrue(select(listOf(one), mapOf(one.id to listOf(p("Too late", 91, 120))), DiscoveryPeriod.SOON).isEmpty())
    }

    @Test fun `evening includes overlaps and midnight crossings but caps each channel at two programmes`() {
        val data = mapOf(one.id to listOf(p("Before evening", -90, -16), p("At evening", -30, 30),
            p("Film", 30, 130), p("Late", 130, 230)), two.id to listOf(p("Across midnight", 180, 230), p("Tomorrow", 200, 260)))
        val list = select(listOf(one, two), data, DiscoveryPeriod.TONIGHT)
        assertEquals(listOf("At evening", "Film", "Across midnight"), list.map { it.programme.title })
    }

    @Test fun `evening uses local calendar dates across daylight saving boundaries`() {
        for (date in listOf("2026-03-29", "2026-10-25")) {
            val day = java.time.LocalDate.parse(date)
            val clock = day.atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
            val start = day.atTime(20, 30).atZone(zone).toInstant().toEpochMilli()
            val show = Programme("Evening", start, start + 3_600_000)
            assertEquals("Evening", select(listOf(one), mapOf(one.id to listOf(show)), DiscoveryPeriod.TONIGHT, clock = clock).single().programme.title)
        }
    }

    @Test fun `search is accent punctuation and case insensitive and uses descriptions and channel names`() {
        val data = mapOf(one.id to listOf(p("Città d’arte", -5, 40, "Un viaggio a Napoli")))
        for (q in listOf("CITTA", "citta d arte", "Rai-1", "viaggio napoli")) {
            assertEquals(1, select(listOf(one), data, query = q).size)
        }
        assertTrue(select(listOf(one), data, query = "Napoli Roma").isEmpty())
    }

    @Test fun `missing guide and empty favorites are valid empty results`() {
        assertTrue(select(listOf(one), emptyMap()).isEmpty())
        assertTrue(select(listOf(one), mapOf(one.id to listOf(p("Now", -5, 40))), only = true).isEmpty())
    }

    @Test fun `selection survives metadata changes reordering and programme expiry`() {
        val controller = DiscoveryController()
        val data = mapOf(one.id to listOf(p("First", -10, 10), p("Next", 10, 60)), two.id to listOf(p("Other", -5, 60)))
        controller.submit(listOf(one, two), data, emptySet(), now)
        controller.select(controller.items.first { it.channel.id == one.id }.key)
        val key = controller.selectedKey
        controller.submit(listOf(one, two), data.mapValues { (_, shows) -> shows.map { it.copy(title = it.title + " updated") } }, setOf(two.id), now)
        assertEquals(key, controller.selectedKey)
        controller.tick(at(11))
        assertEquals(one.id, controller.selected?.channel?.id)
        assertEquals("Next updated", controller.selected?.programme?.title)
        controller.search("unmatched"); assertNull(controller.selected)
        controller.search(""); assertEquals(one.id, controller.selected?.channel?.id)
    }

    @Test fun `restored future selection survives returning from the player`() {
        val data = mapOf(one.id to listOf(p("Now", -5, 10), p("Future", 10, 60)))
        val controller = DiscoveryController()
        controller.restore(DiscoveryPeriod.SOON, true, "future", "${one.id}:${at(10)}")
        controller.submit(listOf(one), data, setOf(one.id), now)
        assertEquals("Future", controller.selected?.programme?.title)
        assertTrue(controller.favoritesOnly)
        assertEquals("future", controller.query)
    }
}
