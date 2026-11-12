package it.lumetv.app.home

import it.lumetv.app.integration.SharedCatalog
import it.lumetv.app.integration.SharedChannel
import java.security.MessageDigest

internal enum class HomeSelection { FAVORITES, MANUAL }

internal data class HomeConfiguration(
    val enabled: Boolean = false, val selection: HomeSelection = HomeSelection.FAVORITES,
    val manualIds: Set<String> = emptySet(), val suppressedIds: Set<String> = emptySet(),
    val publishedIds: Set<String> = emptySet(), val rowId: Long? = null, val createRequested: Boolean = false,
)

internal data class HomeRow(val id: Long, val browsable: Boolean)
internal data class HomeProgram(val id: Long, val key: String, val browsable: Boolean, val fingerprint: String)
internal enum class HomeStatus { OFF, PUBLISHED, HIDDEN, REMOVED, EMPTY, ERROR }
internal data class HomePublication(val rowId: Long?, val status: HomeStatus, val ids: Set<String>,
                                    val suppressed: Set<String>, val nextUpdate: Long)

internal interface HomeRowStore {
    fun rows(): List<HomeRow>
    fun createRow(): HomeRow
    fun deleteRow(id: Long)
    fun programs(rowId: Long): List<HomeProgram>
    fun writeProgram(rowId: Long, existingId: Long?, channel: SharedChannel, position: Int, fingerprint: String)
    fun deleteProgram(id: Long)
}

/** Reconciles only our preview row. A Home removal is a user preference, including hard deletions. */
internal object HomePublicationPlanner {
    const val LIMIT = 24
    fun selected(config: HomeConfiguration, content: SharedCatalog): List<SharedChannel> = content.channels
        .filter { !it.radio && when (config.selection) {
            HomeSelection.FAVORITES -> it.favorite
            HomeSelection.MANUAL -> it.id in config.manualIds
        } }
        .sortedWith(compareBy<SharedChannel> { it.number ?: Int.MAX_VALUE }.thenBy { it.name }.thenBy { it.id })

    fun fingerprint(channel: SharedChannel, position: Int): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf("1", channel.id, channel.name, channel.number, channel.logo, channel.current?.title,
            channel.current?.start, channel.current?.end, position).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun synchronize(config: HomeConfiguration, content: SharedCatalog, store: HomeRowStore, now: Long): HomePublication {
        val rows = store.rows()
        if (!config.enabled) {
            rows.forEach { store.deleteRow(it.id) }
            return HomePublication(null, HomeStatus.OFF, emptySet(), config.suppressedIds, 0)
        }
        val row = rows.firstOrNull { it.id == config.rowId } ?: rows.firstOrNull()
            ?: if (config.createRequested) store.createRow() else return HomePublication(null, HomeStatus.REMOVED,
                emptySet(), config.suppressedIds, now + 6 * 60 * 60_000L)
        rows.filterNot { it.id == row.id }.forEach { store.deleteRow(it.id) }
        val existing = store.programs(row.id)
        val suppressed = config.suppressedIds.toMutableSet()
        existing.filterNot { it.browsable }.forEach { suppressed += it.key }
        // A missing item that we published previously must not be resurrected by the next guide refresh.
        if (row.id == config.rowId) suppressed += config.publishedIds - existing.map { it.key }.toSet()
        val selected = selected(config, content).filterNot { it.id in suppressed }.take(LIMIT)
        val ids = selected.map { it.id }.toSet()
        val kept = mutableMapOf<String, HomeProgram>()
        existing.forEach { program ->
            if (program.key !in ids || kept.putIfAbsent(program.key, program) != null) store.deleteProgram(program.id)
        }
        selected.forEachIndexed { position, channel ->
            val old = kept[channel.id]
            val fingerprint = fingerprint(channel, position)
            if (old?.fingerprint != fingerprint) store.writeProgram(row.id, old?.id, channel, position, fingerprint)
        }
        val next = selected.flatMap { listOfNotNull(it.current?.end, it.next?.start) }.filter { it > now }.minOrNull()
        val status = when { selected.isEmpty() -> HomeStatus.EMPTY; row.browsable -> HomeStatus.PUBLISHED; else -> HomeStatus.HIDDEN }
        return HomePublication(row.id, status, ids, suppressed, minOf(next ?: Long.MAX_VALUE, now + 6 * 60 * 60_000L))
    }
}
