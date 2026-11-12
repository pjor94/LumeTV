package it.lumetv.app.data

import android.content.Context
import android.util.AtomicFile
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import it.lumetv.core.PersonalM3uParser
import it.lumetv.core.PlaylistEntry
import it.lumetv.core.PlaylistIdentity
import it.lumetv.core.PlaylistRecord
import it.lumetv.core.PlaylistReport
import it.lumetv.core.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID

enum class PlaylistLocation { URL, FILE }
data class PersonalPlaylistSource(val id: String, val name: String, val location: PlaylistLocation,
    val enabled: Boolean, val count: Int, val updated: Long, val checked: Long, val revision: Long,
    val defaultKind: MediaKind, val error: String = "", val refreshing: Boolean = false, val nextRetry: Long = 0)

class PlaylistDraft internal constructor(internal val location: PlaylistLocation, internal val address: String,
    internal val defaultKind: MediaKind, val report: PlaylistReport, internal val etag: String? = null, internal val modified: String? = null) {
    override fun toString() = "PlaylistDraft(location=$location, count=${report.entries.size})"
}

/** One shared owner for all personal sources, across both activities and the playback service. */
class PersonalPlaylistRepository(
    context: Context, private val fetcher: PlaylistFetcher = PersonalPlaylistNetwork.fetcher(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_SOURCES = 10
        const val MAX_TOTAL = 20_000
        private val instances = java.util.WeakHashMap<Context, PersonalPlaylistRepository>()
        fun get(context: Context): PersonalPlaylistRepository = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app) { PersonalPlaylistRepository(app) }
        }
    }
    private data class Stored(val source: PersonalPlaylistSource, val address: String, val records: List<PlaylistRecord>,
        val activeIds: Set<String>, val etag: String? = null, val modified: String? = null, val attempted: Long = 0, val retryAt: Long = 0, val failures: Int = 0)
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "personal-playlists")
    private val index = AtomicFile(File(directory, "index.json"))
    private val mutex = Mutex()
    private val downloads = Semaphore(2)
    private val sources = linkedMapOf<String, Stored>()
    private val registered = linkedSetOf<String>()
    private val busy = hashSetOf<String>()
    private val _state = MutableStateFlow<List<PersonalPlaylistSource>>(emptyList())
    val state: StateFlow<List<PersonalPlaylistSource>> = _state.asStateFlow()
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    @Volatile private var loaded = false
    @Volatile private var visibleChannels = emptyList<Channel>()
    @Volatile private var knownChannelIds = emptySet<String>()
    @Volatile var storageError: String? = null
        private set

    suspend fun local(): List<Channel> { load(); return visibleChannels }
    fun cached(): List<Channel> = visibleChannels

    private suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            directory.mkdirs()
            try {
                val root = read(index, 64 * 1024)
                if (root != null) {
                    require(root.getInt("version") == 1)
                    val ids = root.getJSONArray("ids")
                    require(ids.length() <= MAX_SOURCES)
                    for (i in 0 until ids.length()) {
                        val id = ids.getString(i)
                        require(validId(id)); registered += id
                        runCatching { read(sourceFile(id), 32 * 1024 * 1024)?.let(::decode) }
                            .getOrNull()?.takeIf { it.source.id == id }?.let { sources[id] = it }
                    }
                }
            } catch (_: Exception) { storageError = "L’archivio delle liste non è leggibile. I dati sono stati conservati." }
            if (storageError == null) directory.listFiles()?.forEach { file ->
                val id = file.name.removeSuffix(".bak").removeSuffix(".json")
                if (validId(id) && id !in registered) sourceFile(id).delete()
            }
            loaded = true
            publish()
        }
    }

    suspend fun prepareUrl(address: String, defaultKind: MediaKind = MediaKind.TV): PlaylistDraft {
        val url = address.trim()
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || !PersonalPlaylistNetwork.allowed(uri) || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null || url.length > 8192)
            throw IOException("Inserisci l’indirizzo HTTPS della lista, senza credenziali prima del nome del server.")
        val download = downloads.withPermit { fetcher.fetch(url, null, null) }
        val text = download.text ?: throw IOException("Il servizio non ha restituito la lista. Riprova.")
        val report = parse(text, download.finalUrl, defaultKind)
        return PlaylistDraft(PlaylistLocation.URL, url, defaultKind, report, download.etag, download.modified)
    }

    suspend fun prepareFile(bytes: ByteArray, defaultKind: MediaKind = MediaKind.TV): PlaylistDraft =
        PlaylistDraft(PlaylistLocation.FILE, "", defaultKind, parse(decodePlaylistBytes(bytes), null, defaultKind))

    private suspend fun parse(text: String, base: String?, kind: MediaKind) = withContext(Dispatchers.Default) {
        PersonalM3uParser.parse(text, base, kind) { PersonalPlaylistNetwork.allowed(it) }
    }

    suspend fun save(name: String, draft: PlaylistDraft, replacing: String? = null): String {
        load()
        return withContext(Dispatchers.IO) { mutex.withLock {
            writable()
            val title = name.trim().take(100)
            if (title.isBlank()) throw IOException("Assegna un nome alla lista.")
            if (sources.values.any { it.source.id != replacing && it.source.name.equals(title, true) })
                throw IOException("Esiste già una lista con questo nome. Scegli un nome diverso.")
            val old = replacing?.let { sources[it] ?: throw IOException("La lista non è più disponibile.") }
            if (old == null && registered.size >= MAX_SOURCES) throw IOException("Puoi aggiungere fino a 10 liste personali.")
            if (sources.values.filter { it.source.id != replacing }.sumOf { it.activeIds.size } + draft.report.entries.size > MAX_TOTAL)
                throw IOException("Le liste personali superano il limite complessivo di 20.000 canali.")
            val id = replacing ?: "user_" + UUID.randomUUID().toString().replace("-", "")
            val records = PlaylistIdentity.reconcile(draft.report.entries, old?.records.orEmpty(), now())
            val active = records.map { it.id }.toSet()
            val archived = old?.records.orEmpty().filter { it.id !in active && now() - it.seenAt < 30L * 86_400_000 }.takeLast(PersonalM3uParser.MAX_ENTRIES)
            val source = PersonalPlaylistSource(id, title, draft.location, old?.source?.enabled ?: true,
                records.size, now(), now(), (old?.source?.revision ?: 0) + 1, draft.defaultKind)
            val stored = Stored(source, draft.address, records + archived, active, draft.etag, draft.modified)
            write(sourceFile(id), encode(stored))
            if (old == null) {
                try { writeIndex(registered + id) } catch (error: Exception) { sourceFile(id).delete(); throw error }
                registered += id
            }
            sources[id] = stored; publish(); id
        } }
    }

    suspend fun rename(id: String, name: String) = change(id) { old ->
        val title = name.trim().take(100)
        if (title.isBlank()) throw IOException("Assegna un nome alla lista.")
        if (sources.values.any { it.source.id != id && it.source.name.equals(title, true) })
            throw IOException("Esiste già una lista con questo nome. Scegli un nome diverso.")
        old.copy(source = old.source.copy(name = title, revision = old.source.revision + 1))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = change(id) { it.copy(source = it.source.copy(enabled = enabled, revision = it.source.revision + 1)) }

    private suspend fun change(id: String, action: (Stored) -> Stored) {
        load()
        withContext(Dispatchers.IO) { mutex.withLock {
            writable()
            val old = sources[id] ?: throw IOException("La lista non è più disponibile.")
            val next = action(old)
            write(sourceFile(id), encode(next)); sources[id] = next; publish()
        } }
    }

    suspend fun address(id: String): String { load(); return mutex.withLock { sources[id]?.address.orEmpty() } }

    suspend fun delete(id: String) {
        load()
        withContext(Dispatchers.IO) { mutex.withLock {
            writable()
            if (id !in registered) return@withLock
            writeIndex(registered - id)
            val removed = sources.remove(id)?.records.orEmpty().map { it.id }.toSet()
            registered -= id
            sourceFile(id).delete()
            clearReferences(removed)
            publish()
        } }
    }

    suspend fun refreshAll(force: Boolean = false): Boolean {
        load()
        val ids = mutex.withLock { sources.values.filter { it.source.enabled && it.source.location == PlaylistLocation.URL }.map { it.source.id } }
        return coroutineScope { ids.map { id -> async {
            try { refresh(id, force) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        } }.awaitAll().any { it } }
    }

    suspend fun refresh(id: String, force: Boolean = true): Boolean {
        load()
        val previous = withContext(Dispatchers.IO) { mutex.withLock {
            writable()
            val old = sources[id] ?: throw IOException("La lista non è più disponibile.")
            if (id in busy || old.source.location != PlaylistLocation.URL || old.retryAt > now() && old.retryAt - now() <= 86_400_000 ||
                recent(old.attempted, 30_000) || !force && recent(old.source.checked, 86_400_000)) return@withLock null
            val next = old.copy(attempted = now())
            write(sourceFile(id), encode(next)); sources[id] = next; busy += id; publish(false); next
        } } ?: return false
        try {
            var download = downloads.withPermit { fetcher.fetch(previous.address, previous.etag, previous.modified) }
            if (download.text == null && previous.activeIds.isEmpty()) download = downloads.withPermit { fetcher.fetch(previous.address, null, null) }
            val report = download.text?.let { parse(it, download.finalUrl, previous.source.defaultKind) }
            return withContext(Dispatchers.IO) { mutex.withLock {
                val current = sources[id]
                if (current == null || current.source.revision != previous.source.revision) return@withLock false
                val records = report?.let { PlaylistIdentity.reconcile(it.entries, current.records, now()) }
                val active = records?.map { it.id }?.toSet() ?: current.activeIds
                if (sources.values.filter { it.source.id != id }.sumOf { it.activeIds.size } + active.size > MAX_TOTAL)
                    throw IOException("L’aggiornamento supera il limite complessivo di 20.000 canali.")
                val archived = if (records == null) emptyList() else current.records.filter { it.id !in active && now() - it.seenAt < 30L * 86_400_000 }.takeLast(PersonalM3uParser.MAX_ENTRIES)
                val next = current.copy(source = current.source.copy(count = active.size, checked = now(),
                    updated = if (records != null) now() else current.source.updated, error = ""),
                    records = records?.plus(archived) ?: current.records, activeIds = active,
                    etag = download.etag ?: current.etag, modified = download.modified ?: current.modified, retryAt = 0, failures = 0)
                write(sourceFile(id), encode(next)); sources[id] = next; publish(); true
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            withContext(Dispatchers.IO) { mutex.withLock {
                sources[id]?.takeIf { it.source.revision == previous.source.revision }?.let { current ->
                    val message = when (error) {
                        is PlaylistFetchException, is it.lumetv.core.PlaylistFormatException -> error.message.orEmpty()
                        else -> "Aggiornamento non riuscito. La copia salvata resta disponibile."
                    }
                    val serverRetry = (error as? PlaylistFetchException)?.retryAt ?: 0
                    val delay = if ((error as? PlaylistFetchException)?.status == 429) 3_600_000L else (900_000L shl current.failures.coerceAtMost(2))
                    val next = current.copy(source = current.source.copy(error = message), failures = (current.failures + 1).coerceAtMost(10),
                        retryAt = if (serverRetry > now()) serverRetry else now() + delay)
                    write(sourceFile(id), encode(next)); sources[id] = next; publish(false)
                }
            } }
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { busy -= id; publish(false) } }
        }
    }

    suspend fun resolve(id: String, http: okhttp3.OkHttpClient): ResolvedStream {
        load()
        val entry = mutex.withLock {
            sources.values.firstOrNull { it.source.enabled && id in it.activeIds }?.records?.firstOrNull { it.id == id }?.entry
        } ?: throw SourceException("Questo canale personale non è più disponibile. Controlla la sua lista.")
        val mime = entry.mimeType ?: PersonalPlaylistNetwork.detectMime(entry.url, entry.headers, http)
        return ResolvedStream(entry.url, mime, entry.headers, personal = true)
    }

    fun available(sourceId: String): Boolean = state.value.any { it.id == sourceId && it.enabled && it.count > 0 }
    fun knownIds(): Set<String> = knownChannelIds
    private fun recent(time: Long, interval: Long) = time > 0 && now() >= time && now() - time < interval
    private fun writable() { storageError?.let { throw IOException(it) } }
    private fun validId(id: String) = Regex("user_[a-f0-9]{32}").matches(id)
    private fun sourceFile(id: String): AtomicFile { require(validId(id)); return AtomicFile(File(directory, "$id.json")) }

    private fun publish(changed: Boolean = true) {
        knownChannelIds = sources.values.flatMap { it.records }.map { it.id }.toSet()
        visibleChannels = sources.values.filter { it.source.enabled }.flatMap { stored ->
            stored.records.filter { it.id in stored.activeIds }.sortedBy { it.entry.position }.map { record ->
                val e = record.entry
                Channel(record.id, e.name, e.number ?: 9999, "", e.groups.joinToString(" · "), e.logo, "", "personal", record.id,
                    kind = e.kind, sourceId = stored.source.id, position = e.position, groups = e.groups)
            }
        }
        _state.value = registered.map { id -> sources[id]?.let { it.source.copy(refreshing = id in busy, nextRetry = it.retryAt) }
            ?: PersonalPlaylistSource(id, "Lista non leggibile", PlaylistLocation.FILE, false, 0, 0, 0, 0, MediaKind.TV, "Il file salvato non è leggibile. Puoi eliminare questa voce e importare di nuovo la lista.") }
        if (changed) _version.value++
    }

    private fun clearReferences(ids: Set<String>) = synchronized(IntegrationStore.cacheLock) {
        val prefs = app.getSharedPreferences("lume", Context.MODE_PRIVATE)
        val editor = prefs.edit().putStringSet("favorites", prefs.getStringSet("favorites", emptySet()).orEmpty() - ids)
            .putString("recent_channels", prefs.getString("recent_channels", "").orEmpty().split(',').filterNot { it in ids }.joinToString(","))
        if (prefs.getString("last_channel", null) in ids) editor.remove("last_channel")
        editor.apply()
        val discovery = app.getSharedPreferences("discovery", Context.MODE_PRIVATE)
        runCatching {
            val rows = JSONArray(discovery.getString("reminders", "[]"))
            val kept = JSONArray()
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.takeIf { it.optString("channel") !in ids }?.let(kept::put)
            discovery.edit().putString("reminders", kept.toString()).apply()
        }
    }

    private fun writeIndex(ids: Collection<String>) = write(index, JSONObject().put("version", 1).put("ids", JSONArray(ids)))
    private fun write(file: AtomicFile, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 32 * 1024 * 1024) throw IOException("I dati della lista superano lo spazio previsto.")
        directory.mkdirs()
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
    }
    private fun read(file: AtomicFile, limit: Int): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { input ->
            val bytes = input.readBytesLimited(limit)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun encode(stored: Stored): JSONObject {
        val s = stored.source
        return JSONObject().put("version", 1).put("id", s.id).put("name", s.name).put("location", s.location.name)
            .put("address", stored.address).put("enabled", s.enabled).put("kind", s.defaultKind.name)
            .put("updated", s.updated).put("checked", s.checked).put("revision", s.revision).put("error", s.error)
            .put("attempted", stored.attempted).put("retryAt", stored.retryAt).put("failures", stored.failures).put("etag", stored.etag).put("modified", stored.modified)
            .put("records", JSONArray().apply { stored.records.forEach { r ->
                val e = r.entry
                put(JSONObject().put("id", r.id).put("seen", r.seenAt).put("active", r.id in stored.activeIds)
                    .put("name", e.name).put("url", e.url).put("tvgId", e.tvgId).put("logo", e.logo)
                    .put("number", e.number).put("groups", JSONArray(e.groups)).put("kind", e.kind.name)
                    .put("headers", JSONObject(e.headers)).put("mime", e.mimeType).put("position", e.position))
            } })
    }
    private fun decode(root: JSONObject): Stored {
        require(root.getInt("version") == 1)
        val id = root.getString("id"); require(validId(id))
        val rows = root.getJSONArray("records"); require(rows.length() <= PersonalM3uParser.MAX_ENTRIES * 2)
        val active = hashSetOf<String>()
        val records = (0 until rows.length()).map { index ->
            val r = rows.getJSONObject(index); val channelId = r.getString("id"); require(Regex("[a-f0-9]{24}").matches(channelId))
            if (r.optBoolean("active")) active += channelId
            val groups = r.getJSONArray("groups").let { g -> (0 until minOf(20, g.length())).map { g.getString(it).take(100) } }
            val headerObject = r.getJSONObject("headers")
            val headers = headerObject.keys().asSequence().take(12).associateWith { headerObject.getString(it) }
            val entry = PlaylistEntry(r.getString("name").take(300), r.getString("url"), r.optString("tvgId"), r.optString("logo"),
                r.optInt("number").takeIf { it in 1..999_999 && it != 9999 }, groups, MediaKind.valueOf(r.getString("kind")), headers,
                r.optString("mime").takeIf(String::isNotBlank), r.getInt("position"))
            require(PersonalPlaylistNetwork.allowed(URI(entry.url)))
            PlaylistRecord(channelId, entry, r.getLong("seen"))
        }
        require(active.size <= PersonalM3uParser.MAX_ENTRIES && records.map { it.id }.distinct().size == records.size)
        val source = PersonalPlaylistSource(id, root.getString("name").take(100), PlaylistLocation.valueOf(root.getString("location")),
            root.getBoolean("enabled"), active.size, root.getLong("updated"), root.getLong("checked"), root.getLong("revision"),
            MediaKind.valueOf(root.getString("kind")), root.optString("error"))
        return Stored(source, root.optString("address"), records, active, root.optString("etag").takeIf(String::isNotBlank),
            root.optString("modified").takeIf(String::isNotBlank), root.optLong("attempted"), root.optLong("retryAt"), root.optInt("failures").coerceIn(0, 10))
    }
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw IOException("Archivio troppo grande.")
        output.write(buffer, 0, count)
    }
}
