package it.lumetv.app.home

import it.lumetv.app.data.Programme
import it.lumetv.app.integration.SharedCatalog
import it.lumetv.app.integration.SharedChannel
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import org.junit.Assert.*
import org.junit.Test

class HomePublicationTest {
    private val now = 1_000L
    private fun channel(number: Int, favorite: Boolean = true) = SharedChannel("%024x".format(number), "Canale $number", number,
        "Rai", null, favorite, false, Programme("Ora", 1, 2_000), Programme("Dopo", 2_000, 3_000))
    private fun content(vararg channels: SharedChannel) = SharedCatalog(channels.toList(), 1, 1, null, null)
    private val enabled = HomeConfiguration(enabled = true, createRequested = true)
    private class Store : HomeRowStore {
        val rows = mutableListOf<HomeRow>()
        val programs = mutableMapOf<Long, MutableList<HomeProgram>>()
        var nextId = 1L; var writes = 0
        override fun rows() = rows.toList()
        override fun createRow() = HomeRow(nextId++, false).also { rows += it }
        override fun deleteRow(id: Long) { rows.removeAll { it.id == id }; programs.remove(id) }
        override fun programs(rowId: Long) = programs[rowId].orEmpty().toList()
        override fun writeProgram(rowId: Long, existingId: Long?, channel: SharedChannel, position: Int, fingerprint: String) {
            val items = programs.getOrPut(rowId) { mutableListOf() }
            items.removeAll { it.id == existingId }; items += HomeProgram(existingId ?: nextId++, channel.id, true, fingerprint); writes++
        }
        override fun deleteProgram(id: Long) { programs.values.forEach { it.removeAll { row -> row.id == id } } }
    }
    private fun HomePublication.config() = enabled.copy(rowId = rowId, publishedIds = ids, suppressedIds = suppressed, createRequested = false)

    @Test fun `metadata projection excludes private sources and strips playback and guide identifiers`() {
        val raw = Channel("1".repeat(24), "  Uno\nTV  ", 1, "private-epg", "Group", "https://example.com/logo?secret=1",
            "https://example.com/live?token=secret", "rai", "secret", mapOf("Authorization" to "secret"))
        val personal = raw.copy(id = "2".repeat(24), sourceId = "user_private", name = "PERSONAL")
        val projected = SharedCatalog.project(listOf(raw, personal), setOf(raw.id, personal.id),
            mapOf(raw.epgId to listOf(Programme("  Ora\nTV  ", 1, 2_000, "secret description"))), 1, 1, raw.id, 1, now)
        assertEquals(1, projected.channels.size)
        val result = projected.channels.single()
        assertEquals("Uno TV", result.name); assertEquals("Ora TV", result.current?.title)
        assertEquals("", result.current?.description); assertNull(result.logo)
        assertFalse(projected.toString().contains("secret")); assertFalse(projected.toString().contains("private-epg"))
    }

    @Test fun `favorites are ordered and bounded while radio is excluded`() {
        val store = Store()
        val source = content(*(40 downTo 1).map { channel(it) }.toTypedArray())
        val result = HomePublicationPlanner.synchronize(enabled, source.copy(channels = source.channels + channel(0).copy(radio = true)), store, now)
        assertEquals((1..24).map { channel(it).id }, result.ids.toList())
        assertEquals(24, store.writes); assertEquals(2_000L, result.nextUpdate)
    }

    @Test fun `manual selection is independent of favorites and current catalogue presence`() {
        val a = channel(1, false); val b = channel(2)
        val config = enabled.copy(selection = HomeSelection.MANUAL, manualIds = setOf(a.id, "f".repeat(24)))
        val result = HomePublicationPlanner.synchronize(config, content(a, b), Store(), now)
        assertEquals(setOf(a.id), result.ids)
    }

    @Test fun `repeated sync preserves database IDs and does not rewrite unchanged cards`() {
        val store = Store(); val source = content(channel(1), channel(2))
        val first = HomePublicationPlanner.synchronize(enabled, source, store, now)
        val before = store.programs(first.rowId!!)
        val second = HomePublicationPlanner.synchronize(first.config(), source, store, now)
        assertEquals(first.rowId, second.rowId); assertEquals(before, store.programs(first.rowId))
        assertEquals(1, store.rows.size); assertEquals(2, store.writes)
    }

    @Test fun `changed metadata updates the same card and catalog removal deletes its card`() {
        val store = Store(); val first = HomePublicationPlanner.synchronize(enabled, content(channel(1), channel(2)), store, now)
        val id = store.programs(first.rowId!!).first { it.key == channel(1).id }.id
        val changed = content(channel(1).copy(name = "Nuovo nome", current = null, next = null))
        val second = HomePublicationPlanner.synchronize(first.config(), changed, store, now)
        assertEquals(setOf(channel(1).id), second.ids)
        assertEquals(id, store.programs(first.rowId).single().id); assertEquals(3, store.writes)
        assertTrue(second.nextUpdate > now)
    }

    @Test fun `a hidden or hard deleted card stays removed across guide updates`() {
        for (hardDelete in listOf(false, true)) {
            val store = Store(); val source = content(channel(1), channel(2))
            val first = HomePublicationPlanner.synchronize(enabled, source, store, now)
            val items = store.programs.getValue(first.rowId!!)
            if (hardDelete) items.removeAt(0) else items[0] = items[0].copy(browsable = false)
            val second = HomePublicationPlanner.synchronize(first.config(), source, store, now)
            assertEquals(setOf(channel(1).id), second.suppressed)
            assertEquals(setOf(channel(2).id), second.ids)
            val writes = store.writes
            val third = HomePublicationPlanner.synchronize(second.config(), source, store, now)
            assertEquals(second.ids, third.ids); assertEquals(writes, store.writes)
        }
    }

    @Test fun `removing the whole row requires an explicit request before recreating it`() {
        val store = Store(); val source = content(channel(1))
        val first = HomePublicationPlanner.synchronize(enabled, source, store, now)
        store.deleteRow(first.rowId!!)
        val removed = HomePublicationPlanner.synchronize(first.config(), source, store, now)
        assertEquals(HomeStatus.REMOVED, removed.status); assertTrue(store.rows.isEmpty())
        val restored = HomePublicationPlanner.synchronize(first.config().copy(createRequested = true), source, store, now)
        assertNotEquals(first.rowId, restored.rowId); assertEquals(setOf(channel(1).id), restored.ids)
    }

    @Test fun `revocation removes rows and queued work cannot recreate them`() {
        val store = Store(); val source = content(channel(1))
        val first = HomePublicationPlanner.synchronize(enabled, source, store, now)
        val disabled = first.config().copy(enabled = false)
        repeat(2) {
            val result = HomePublicationPlanner.synchronize(disabled, source, store, now)
            assertEquals(HomeStatus.OFF, result.status); assertTrue(store.rows.isEmpty()); assertTrue(store.programs.isEmpty())
        }
    }

    @Test fun `interrupted creation and duplicate rows reconcile without duplicating cards`() {
        val store = Store(); store.rows += HomeRow(20, true); store.rows += HomeRow(21, false)
        val source = content(channel(1)); val fingerprint = HomePublicationPlanner.fingerprint(channel(1), 0)
        store.programs[20] = mutableListOf(HomeProgram(30, channel(1).id, true, fingerprint), HomeProgram(31, channel(1).id, true, fingerprint))
        val result = HomePublicationPlanner.synchronize(enabled, source, store, now)
        assertEquals(20L, result.rowId); assertEquals(HomeStatus.PUBLISHED, result.status)
        assertEquals(1, store.rows.size); assertEquals(1, store.programs(20).size); assertEquals(0, store.writes)
    }

    @Test fun `no favorites produces an empty row without publishing the rest of the catalog`() {
        val store = Store()
        val result = HomePublicationPlanner.synchronize(enabled, content(channel(1, false)), store, now)
        assertEquals(HomeStatus.EMPTY, result.status); assertEquals(0, store.writes)
    }
}
