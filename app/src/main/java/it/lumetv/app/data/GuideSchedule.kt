package it.lumetv.app.data

import it.lumetv.core.Channel
import java.time.Instant
import java.time.ZoneId

/** A real interval on the shared time axis. Null programmes represent missing source data. */
internal data class GuideSlot(val start: Long, val end: Long, val programme: Programme?)

internal object GuideSchedule {
    const val MINUTE = 60_000L
    const val WINDOW = 150 * MINUTE
    const val HALF_HOUR = 30 * MINUTE

    fun normalize(shows: List<Programme>): List<Programme> = shows
        .filter { it.title.isNotBlank() && it.start > 0 && it.end > it.start }
        .groupBy { it.start }
        // Repeated/alternative feed records must never produce stacked focus targets.
        .map { (_, records) -> records.maxWith(compareBy<Programme> { it.end }
            .thenBy { it.description.length }.thenBy { it.title.length }.thenBy { it.title }) }
        .sortedBy { it.start }

    fun slots(shows: List<Programme>, from: Long, until: Long): List<GuideSlot> {
        if (until <= from) return emptyList()
        val result = mutableListOf<GuideSlot>()
        var cursor = from
        shows.forEachIndexed { index, show ->
            val start = maxOf(show.start, from)
            // A later entry wins an overlap; never stretch a cell to fill a missing interval.
            val end = minOf(show.end, shows.getOrNull(index + 1)?.start ?: Long.MAX_VALUE, until)
            if (end <= start || end <= cursor || start >= until) return@forEachIndexed
            if (start > cursor) result += GuideSlot(cursor, start, null)
            result += GuideSlot(start, end, show)
            cursor = end
        }
        if (cursor < until) result += GuideSlot(cursor, until, null)
        return result
    }

    fun halfHour(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val local = Instant.ofEpochMilli(time).atZone(zone)
        return local.withMinute(local.minute / 30 * 30).withSecond(0).withNano(0).toInstant().toEpochMilli()
    }

    fun evening(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        maxOf(time, Instant.ofEpochMilli(time).atZone(zone).withHour(21).withMinute(0)
            .withSecond(0).withNano(0).toInstant().toEpochMilli())
}

/** Navigation is independent of Android focus/layout, including rapid remote repeats. */
internal class GuideNavigator {
    var channelId: String? = null
        private set
    var programmeStart: Long? = null
        private set
    var anchor = 0L
        private set
    var windowStart = 0L
        private set
    var favoritesOnly = false
        private set
    var rowStart = 0
        private set
    var rowCount = 5
        private set
    private var allChannels = emptyList<Channel>()
    private var favorites = emptySet<String>()
    private var schedules = emptyMap<String, List<Programme>>()
    private var now = 0L

    val channels: List<Channel> get() = if (favoritesOnly) allChannels.filter { it.id in favorites } else allChannels
    val channel: Channel? get() = channels.firstOrNull { it.id == channelId }
    val shows: List<Programme> get() = channel?.let { schedules[it.epgId] }.orEmpty()
    val programme: Programme? get() = shows.firstOrNull { it.start == programmeStart }
    val end: Long get() = maxOf(GuideSchedule.halfHour(now) + GuideSchedule.WINDOW,
        minOf(now + 24 * 60 * GuideSchedule.MINUTE, schedules.values.maxOfOrNull { list -> list.maxOfOrNull { it.end } ?: now } ?: now))
    val canGoEarlier: Boolean get() = windowStart > GuideSchedule.halfHour(now)
    val canGoLater: Boolean get() = windowStart + GuideSchedule.WINDOW < end
    val covered: Int get() = channels.count { c -> schedules[c.epgId].orEmpty().any { it.end > now } }

    fun submit(items: List<Channel>, programmes: Map<String, List<Programme>>, favoriteIds: Set<String>,
               currentId: String?, time: Long) {
        now = time
        allChannels = items
        favorites = favoriteIds
        schedules = programmes.mapValues { GuideSchedule.normalize(it.value) }
        if (windowStart == 0L || windowStart > end || windowStart + 24 * 60 * GuideSchedule.MINUTE < now) {
            windowStart = GuideSchedule.halfHour(now); anchor = now; programmeStart = null
        }
        if (channels.none { it.id == channelId }) {
            channelId = channels.firstOrNull { it.id == currentId }?.id ?: channels.firstOrNull()?.id
            programmeStart = null
        }
        if (programme == null) pickAtAnchor()
        revealRow()
    }

    fun tick(time: Long) { now = time }
    fun shows(channel: Channel): List<Programme> = schedules[channel.epgId].orEmpty()
    fun slots(channel: Channel, from: Long = windowStart, until: Long = windowStart + GuideSchedule.WINDOW) =
        GuideSchedule.slots(shows(channel), from, until)

    fun selected(slot: GuideSlot): Boolean = if (programmeStart != null) slot.programme?.start == programmeStart
        else slot.programme == null && anchor >= slot.start && anchor < slot.end

    fun select(channel: Channel, slot: GuideSlot) {
        channelId = channel.id
        programmeStart = slot.programme?.start
        anchor = anchor.coerceIn(slot.start, slot.end - 1)
        revealRow()
    }

    fun moveChannel(direction: Int, page: Boolean = false): Boolean {
        val list = channels
        if (list.isEmpty()) return false
        val position = list.indexOfFirst { it.id == channelId }.coerceAtLeast(0)
        val target = (position + direction * if (page) rowCount else 1).coerceIn(0, list.lastIndex)
        if (target == position) return false
        channelId = list[target].id
        pickAtAnchor()
        revealRow()
        return true
    }

    fun moveProgramme(direction: Int): Boolean {
        val channel = channel ?: return false
        val from = minOf(windowStart, GuideSchedule.halfHour(now))
        val cells = slots(channel, from, end)
        val position = cells.indexOfFirst(::selected)
        val target = (position + direction).coerceIn(0, cells.lastIndex)
        if (position < 0 || position == target) return false
        val slot = cells[target]
        programmeStart = slot.programme?.start
        anchor = slot.start
        if (slot.start < windowStart) windowStart = maxOf(from, GuideSchedule.halfHour(slot.start))
        else if (slot.end > windowStart + GuideSchedule.WINDOW) {
            windowStart = maxOf(windowStart, GuideSchedule.halfHour(slot.start))
        }
        clampWindow()
        return true
    }

    fun pageTime(direction: Int) {
        windowStart += direction * 60 * GuideSchedule.MINUTE
        clampWindow()
        anchor = windowStart + minOf(15 * GuideSchedule.MINUTE, GuideSchedule.WINDOW - 1)
        pickAtAnchor()
    }

    fun jump(time: Long) {
        windowStart = GuideSchedule.halfHour(time); clampWindow()
        anchor = time.coerceIn(windowStart, windowStart + GuideSchedule.WINDOW - 1)
        pickAtAnchor()
    }

    fun filterFavorites(value: Boolean) {
        favoritesOnly = value; rowStart = 0
        if (channels.none { it.id == channelId }) channelId = channels.firstOrNull()?.id
        pickAtAnchor()
        revealRow()
    }

    fun selectChannel(id: String) {
        if (allChannels.none { it.id == id }) return
        if (channels.none { it.id == id }) favoritesOnly = false
        channelId = id; pickAtAnchor(); revealRow()
    }

    fun resizeRows(count: Int) { rowCount = count.coerceIn(1, 5); revealRow() }

    fun restore(id: String?, start: Long?, time: Long, window: Long, onlyFavorites: Boolean, firstRow: Int = 0) {
        channelId = id; programmeStart = start; anchor = time
        windowStart = window; favoritesOnly = onlyFavorites; rowStart = firstRow.coerceAtLeast(0)
    }

    private fun pickAtAnchor() {
        programmeStart = channel?.let { c -> slots(c, anchor, anchor + 1).firstOrNull()?.programme?.start }
    }
    private fun clampWindow() {
        windowStart = windowStart.coerceIn(GuideSchedule.halfHour(now), maxOf(GuideSchedule.halfHour(now), end - GuideSchedule.WINDOW))
    }
    private fun revealRow() {
        val position = channels.indexOfFirst { it.id == channelId }.coerceAtLeast(0)
        if (position < rowStart) rowStart = position
        if (position >= rowStart + rowCount) rowStart = position - rowCount + 1
        rowStart = rowStart.coerceIn(0, maxOf(0, channels.size - rowCount))
    }
}
