package it.lumetv.app.data

import it.lumetv.core.Channel
import it.lumetv.core.CatalogPolicy
import it.lumetv.core.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface CatalogStore {
    var lastSuccess: Long
    var lastAttempt: Long
    fun read(): String?
    /** Must replace the previous file atomically, keeping it if the write fails. */
    fun write(text: String)
}

internal object CatalogValidator {
    data class Catalog(val text: String, val channels: List<Channel>)

    fun parse(text: String): List<Channel> = validate(text).channels

    fun validate(text: String): Catalog {
        if (text.length > 2 * 1024 * 1024) throw SourceException("Catalogo troppo grande.")
        var pending = false
        text.removePrefix("\uFEFF").lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#EXTINF:") -> {
                    if (pending) throw SourceException("Catalogo incompleto.")
                    pending = true
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (!pending) throw SourceException("Catalogo non valido.")
                    pending = false
                }
            }
        }
        if (pending) throw SourceException("Catalogo incompleto.")
        // Validate the original structure before filtering, so exclusions cannot hide truncation.
        val filtered = CatalogPolicy.filterPlaylist(text)
        val channels = M3uParser.parse(filtered).filter { it.supported }
        if (!text.removePrefix("\uFEFF").trimStart().startsWith("#EXTM3U") ||
            channels.size < 20 || channels.count { it.family == "Rai" } < 3 || channels.count { it.family == "Mediaset" } < 3) {
            throw SourceException("La lista ricevuta è incompleta. Mantengo il catalogo salvato.")
        }
        return Catalog(filtered, channels)
    }
}

/** Data-only refresh. Provider integration code is updated through app releases. */
internal class CatalogSync(
    private val http: Transport,
    private val store: CatalogStore,
    private val bundled: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val SOURCE_URL = "https://tvchannels.worldtvlive.eu/tv/_dtt_kodi/list.m3u8"
        const val REFRESH_INTERVAL = 24 * 60 * 60 * 1000L
        const val RETRY_INTERVAL = 60 * 60 * 1000L
    }
    private val mutex = Mutex()

    suspend fun local(): List<Channel> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cached = runCatching { store.read()?.let(CatalogValidator::parse) }.getOrNull()
            cached ?: run {
                store.lastSuccess = 0
                CatalogValidator.parse(bundled())
            }
        }
    }

    suspend fun refresh(force: Boolean = false): List<Channel>? = mutex.withLock {
        val time = now()
        if (!force && (recent(store.lastSuccess, time, REFRESH_INTERVAL) || recent(store.lastAttempt, time, RETRY_INTERVAL))) return null
        store.lastAttempt = time
        val body = http.get(SOURCE_URL)
        val catalog = withContext(Dispatchers.Default) { CatalogValidator.validate(body) }
        withContext(Dispatchers.IO) {
            store.write(catalog.text)
            store.lastSuccess = now()
        }
        catalog.channels
    }

    // A clock corrected backwards must not freeze refreshes for days.
    private fun recent(saved: Long, time: Long, interval: Long) = saved > 0 && time >= saved && time - saved < interval
}
