package it.lumetv.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Programme(val title: String, val start: Long, val end: Long, val description: String = "")

internal interface GuideStore {
    var updated: Long
    var attempted: Long
    var retryAfter: Long
    var failure: String
    fun read(): Map<String, List<Programme>>
    fun write(programmes: Map<String, List<Programme>>)
}

internal fun interface GuideSource {
    suspend fun fetch(wanted: Set<String>, now: Long): Map<String, List<Programme>>
}

internal class GuideSync(
    private val store: GuideStore,
    private val source: GuideSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val FRESH_FOR = 6 * 60 * 60_000L
        const val ERROR_WAIT = 15 * 60_000L
        const val RATE_LIMIT_WAIT = 60 * 60_000L
        const val MINIMUM_WAIT = 30_000L
    }
    private val mutex = Mutex()

    suspend fun local(): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val programmes = runCatching { valid(store.read(), now()) }.getOrDefault(emptyMap())
            if (programmes.isEmpty()) store.updated = 0
            programmes
        }
    }

    suspend fun refresh(wanted: Set<String>, force: Boolean = false): Map<String, List<Programme>>? = mutex.withLock {
        if (wanted.isEmpty()) return null
        val time = now()
        if (recent(store.attempted, time, MINIMUM_WAIT)) return null
        val waiting = store.retryAfter > time && store.retryAfter - time <= RATE_LIMIT_WAIT
        if (waiting && (!force || store.failure == "rate_limit")) return null
        if (!force && recent(store.updated, time, FRESH_FOR)) return null
        store.attempted = time
        store.retryAfter = time + 60_000 // Also bounds attempts interrupted by Home/standby.
        var saving = false
        try {
            val programmes = valid(source.fetch(wanted, time), time).filterKeys { it in wanted }
            if (programmes.isEmpty()) throw SourceException("La guida ricevuta non contiene programmi aggiornati.")
            saving = true
            withContext(Dispatchers.IO) { store.write(programmes) }
            store.updated = now(); store.retryAfter = 0; store.failure = ""
            programmes
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) {
            val limited = error is SourceException && error.status == 429
            store.failure = when { limited -> "rate_limit"; saving -> "storage"; else -> "download" }
            store.retryAfter = now() + if (limited) RATE_LIMIT_WAIT else ERROR_WAIT
            throw error
        }
    }

    private fun recent(saved: Long, time: Long, interval: Long) = saved > 0 && time >= saved && time - saved < interval
    private fun valid(programmes: Map<String, List<Programme>>, time: Long) = programmes.mapValues { (_, shows) ->
        shows.filter { it.title.isNotBlank() && it.start > 0 && it.end > it.start && it.end > time }
            .distinctBy { Triple(it.start, it.end, it.title) }
            .sortedBy { it.start }.take(XmlTvGuideParser.MAX_PER_CHANNEL)
    }.filterValues { it.isNotEmpty() }
}
