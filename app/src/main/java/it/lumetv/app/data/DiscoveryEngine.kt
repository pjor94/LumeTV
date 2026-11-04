package it.lumetv.app.data

import it.lumetv.core.Channel
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class DiscoveryPeriod { NOW, SOON, TONIGHT }

data class DiscoveryProgramme(val channel: Channel, val programme: Programme) {
    val key: String get() = "${channel.id}:${programme.start}"
    fun isLive(now: Long) = programme.start <= now && programme.end > now
    fun remainingMinutes(now: Long) = ((programme.end - now + 59_999) / 60_000).coerceAtLeast(0)
    fun startsInMinutes(now: Long) = ((programme.start - now + 59_999) / 60_000).coerceAtLeast(0)
    fun endingSoon(now: Long) = isLive(now) && programme.end - now < 10 * 60_000 &&
        (programme.end - now).toDouble() / (programme.end - programme.start) < 0.2
    fun progress(now: Long) = ((now - programme.start).toDouble() / (programme.end - programme.start) * 1000)
        .toInt().coerceIn(0, 1000)
}

/** A local projection of the EPG. No provider requests or inferred genre classifications. */
object DiscoveryEngine {
    fun select(channels: List<Channel>, programmes: Map<String, List<Programme>>, favorites: Set<String>,
               period: DiscoveryPeriod, favoritesOnly: Boolean, query: String, now: Long,
               zone: ZoneId = ZoneId.systemDefault()): List<DiscoveryProgramme> {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val evening = day.atTime(20, 30).atZone(zone).toInstant().toEpochMilli()
        val midnight = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val words = searchable(query).split(' ').filter(String::isNotBlank)
        return channels.distinctBy { it.id }.filter { !favoritesOnly || it.id in favorites }.flatMap { channel ->
            val normalized = GuideSchedule.normalize(programmes[channel.epgId].orEmpty())
            // The next entry wins an overlap, consistently with the guide's shared timeline.
            val shows = normalized.mapIndexed { index, p ->
                p.copy(end = minOf(p.end, normalized.getOrNull(index + 1)?.start ?: p.end))
            }.filter { it.end > now }
            val candidates = when (period) {
                DiscoveryPeriod.NOW -> shows.filter { it.start <= now }.takeLast(1)
                DiscoveryPeriod.SOON -> shows.filter { it.start > now && it.start <= now + 90 * 60_000 }
                DiscoveryPeriod.TONIGHT -> shows.filter { it.start < midnight && it.end > evening }
            }.filter { p ->
                words.isEmpty() || searchable("${channel.name} ${p.title} ${p.description}")
                    .let { text -> words.all(text::contains) }
            }
            candidates.take(if (period == DiscoveryPeriod.TONIGHT) 2 else 1).map { DiscoveryProgramme(channel, it) }
        }.sortedWith { a, b ->
            val titleMatch = if (words.isEmpty()) 0 else {
                fun matches(p: DiscoveryProgramme) = searchable(p.programme.title).let { text -> words.all(text::contains) }
                matches(b).compareTo(matches(a))
            }
            val preferred = (b.channel.id in favorites).compareTo(a.channel.id in favorites)
            val time = a.programme.start.compareTo(b.programme.start)
            val number = compareValuesBy(a, b, { it.channel.number }, { it.channel.name }, { it.key })
            when {
                titleMatch != 0 -> titleMatch
                period == DiscoveryPeriod.SOON -> time.takeIf { it != 0 } ?: preferred.takeIf { it != 0 } ?: number
                preferred != 0 -> preferred
                period == DiscoveryPeriod.NOW -> a.endingSoon(now).compareTo(b.endingSoon(now)).takeIf { it != 0 } ?: number
                else -> time.takeIf { it != 0 } ?: number
            }
        }
    }

    private fun searchable(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()
}

/** Stable programme selection survives new metadata, reordering, and clock ticks. */
class DiscoveryController {
    var period = DiscoveryPeriod.NOW
        private set
    var favoritesOnly = false
        private set
    var query = ""
        private set
    var selectedKey: String? = null
        private set
    var items = emptyList<DiscoveryProgramme>()
        private set
    val selected get() = items.firstOrNull { it.key == selectedKey }
    private var selectedChannelId: String? = null
    private var channels = emptyList<Channel>()
    private var programmes = emptyMap<String, List<Programme>>()
    private var favorites = emptySet<String>()
    private var now = 0L

    fun submit(channels: List<Channel>, programmes: Map<String, List<Programme>>, favorites: Set<String>, now: Long) {
        this.channels = channels; this.programmes = programmes; this.favorites = favorites; this.now = now
        rebuild()
    }
    fun tick(now: Long) { this.now = now; rebuild() }
    fun changePeriod(value: DiscoveryPeriod) { period = value; rebuild() }
    fun filterFavorites(value: Boolean) { favoritesOnly = value; rebuild() }
    fun search(value: String) { query = value.take(120); rebuild() }
    fun select(key: String) {
        items.firstOrNull { it.key == key }?.let { selectedKey = it.key; selectedChannelId = it.channel.id }
    }
    fun move(offset: Int) {
        if (items.isEmpty()) return
        val index = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        select(items[(index + offset).coerceIn(0, items.lastIndex)].key)
    }
    fun selectChannel(id: String): Boolean = items.firstOrNull { it.channel.id == id }?.let { select(it.key); true } ?: false
    fun restore(period: DiscoveryPeriod, favorites: Boolean, query: String, key: String?) {
        this.period = period; favoritesOnly = favorites; this.query = query.take(120); selectedKey = key
        selectedChannelId = key?.substringBefore(':')
    }
    private fun rebuild() {
        val oldIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
        items = DiscoveryEngine.select(channels, programmes, favorites, period, favoritesOnly, query, now)
        val selection = items.firstOrNull { it.key == selectedKey }
            ?: items.firstOrNull { it.channel.id == selectedChannelId }
            ?: items.getOrNull(oldIndex.coerceAtMost((items.size - 1).coerceAtLeast(0)))
        selectedKey = selection?.key
        if (selection != null) selectedChannelId = selection.channel.id
    }
}
