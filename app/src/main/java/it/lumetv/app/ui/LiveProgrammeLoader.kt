package it.lumetv.app.ui

import it.lumetv.app.data.Programme
import it.lumetv.core.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Keeps guide reads and results independent of RecyclerView's moving channel window. */
internal class LiveProgrammeLoader(
    private val scope: CoroutineScope,
    private val read: suspend (List<Channel>, Long) -> Map<String, Programme>,
    private val changed: (Set<String>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(val programme: Programme?, val checkedAt: Long) {
        fun validAt(now: Long): Boolean = now >= checkedAt && now < (programme?.end ?: checkedAt + 30_000)
    }

    private val cache = object : LinkedHashMap<String, Result>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 256
    }
    private var wanted = emptyList<Channel>()
    private var task: Job? = null
    private var retryAfter = 0L

    fun result(epgId: String): Result? = cache[epgId]?.takeIf { it.validAt(clock()) }

    fun request(channels: List<Channel>) {
        wanted = channels.distinctBy { it.epgId }.take(48)
        readMissing()
    }

    fun stop() {
        wanted = emptyList()
        task?.cancel()
        task = null
        retryAfter = 0
    }

    fun invalidate() {
        val affected = cache.keys.toSet()
        cache.clear()
        task?.cancel()
        task = null
        retryAfter = 0
        if (affected.isNotEmpty()) changed(affected)
        readMissing()
    }

    private fun readMissing() {
        if (task?.isActive == true || clock() < retryAfter || wanted.none { result(it.epgId) == null }) return
        task = scope.launch {
            while (true) {
                val now = clock()
                val missing = wanted.asSequence().filter { cache[it.epgId]?.validAt(now) != true }.take(12).toList()
                if (missing.isEmpty()) return@launch
                val programmes = try {
                    read(missing, now)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureActive()
                    retryAfter = clock() + 1_000
                    return@launch
                }
                ensureActive()
                val affected = missing.mapTo(mutableSetOf()) { channel ->
                    val programme = programmes[channel.epgId]?.takeIf { it.start <= now && now < it.end }
                    cache[channel.epgId] = Result(programme, now)
                    channel.epgId
                }
                changed(affected)
            }
        }
    }
}
