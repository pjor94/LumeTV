package it.lumetv.core

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.io.Reader
import java.io.StringReader
import java.security.MessageDigest

class PlaylistFormatException(message: String) : IllegalArgumentException(message)

/** Private playback data. Do not publish these objects to UI state, logs or integrations. */
class PlaylistEntry(
    val name: String, val url: String, val tvgId: String = "", val logo: String = "",
    val number: Int? = null, val groups: List<String> = emptyList(), val kind: MediaKind = MediaKind.TV,
    val headers: Map<String, String> = emptyMap(), val mimeType: String? = null, val position: Int = 0,
    val properties: Map<String, String> = emptyMap(),
    val metadata: PlaylistMetadata = PlaylistMetadata(),
) {
    override fun toString() = "PlaylistEntry(name=$name, kind=$kind)"
}

data class PlaylistReport(val entries: List<PlaylistEntry>, val excluded: Int, val duplicates: Int, val warnings: List<String>,
    val descriptor: PlaylistDescriptor = PlaylistDescriptor(), val diagnostics: List<PlaylistDiagnostic> = emptyList())
data class PlaylistScanReport(val imported: Int, val scanned: Int, val excluded: Int, val duplicates: Int,
    val warnings: List<String>, val httpEntries: Int, val descriptor: PlaylistDescriptor = PlaylistDescriptor(),
    val diagnostics: List<PlaylistDiagnostic> = emptyList(), val diagnosticCounts: Map<PlaylistDiagnosticCode, Int> = emptyMap())
data class PlaylistRecord(val id: String, val entry: PlaylistEntry, val seenAt: Long)

/** The WLTV parser and its historical identity policy remain unchanged. */
object PersonalM3uParser {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRIES = 50_000
    const val MAX_LINE = 64 * 1024
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|'([^']*)'|([^\\s,]+))")
    private val headerNames = mapOf("user-agent" to "User-Agent", "referer" to "Referer", "referrer" to "Referer",
        "origin" to "Origin", "authorization" to "Authorization", "cookie" to "Cookie")

    fun parse(text: String, baseUrl: String? = null, defaultKind: MediaKind = MediaKind.TV,
              allowHttp: (URI) -> Boolean = { false }, options: PlaylistReadOptions = PlaylistReadOptions()): PlaylistReport {
        // This convenience overload is for small callers/tests. Production imports use scan(Reader).
        var bytes = 0L
        var previousHigh = false
        text.forEach { char ->
            bytes += when { char.code < 128 -> 1; char.code < 2048 -> 2
                char.isLowSurrogate() && previousHigh -> 1; else -> 3 }
            previousHigh = char.isHighSurrogate()
            if (bytes > MAX_BYTES) throw PlaylistFormatException("La lista supera il limite di 64 MiB.")
        }
        val entries = mutableListOf<PlaylistEntry>()
        val result = scan(StringReader(text), baseUrl, defaultKind, allowHttp, options = options, accept = { entries += it; null })
        return PlaylistReport(entries, result.excluded, result.duplicates, result.warnings, result.descriptor, result.diagnostics)
    }

    /** Reads bounded lines and emits one private entry at a time, without retaining the input document. */
    fun scan(reader: Reader, baseUrl: String? = null, defaultKind: MediaKind = MediaKind.TV,
             allowHttp: (URI) -> Boolean = { false }, checkActive: () -> Unit = {},
             progress: (Int) -> Unit = {}, options: PlaylistReadOptions = PlaylistReadOptions(),
             budget: PlaylistScanBudget = PlaylistScanBudget(), include: ((String) -> PlaylistDocument)? = null,
             depth: Int = 0, document: String = "root", inheritedDefaults: Map<String, String> = emptyMap(),
             accept: (PlaylistEntry) -> String?): PlaylistScanReport {
        if (depth > 3 || ++budget.documents > 16) throw PlaylistFormatException("Le liste incluse superano il limite di 3 livelli o 16 documenti.")
        val pathKey = baseUrl?.let { URI(it).normalize().toString() }
        if (pathKey != null && !budget.path.add(pathKey)) throw PlaylistFormatException("La lista contiene un ciclo fra documenti inclusi.")
        var first = true
        var pending = false
        var name = ""
        var metadata = emptyMap<String, String>()
        var inheritedGroups = emptyList<String>()
        val headers = linkedMapOf<String, String>()
        val properties = linkedMapOf<String, String>()
        var unsupported = false
        var mime: String? = null
        var duration: Double? = null
        var seekable: Boolean? = null
        var vodHint = false
        var defaults = inheritedDefaults
        var lineNumber = 0
        val documents = linkedMapOf(document to defaults)
        val documentBases = linkedMapOf<String,String>().apply {baseUrl?.let {put(document,it)}}
        var count = 0
        var excluded = 0
        var duplicates = 0
        val warnings = mutableListOf<String>()
        var imported = 0
        var httpEntries = 0
        val diagnostics = mutableListOf<PlaylistDiagnostic>()
        val diagnosticCounts = linkedMapOf<PlaylistDiagnosticCode, Int>()
        fun diagnostic(code: PlaylistDiagnosticCode, rejected: Boolean = false, reason: String? = null) {
            diagnosticCounts[code] = (diagnosticCounts[code] ?: 0) + 1
            val value = PlaylistDiagnostic(code, lineNumber, count, rejected)
            if (rejected) {
                diagnostics.add(0,value);if(diagnostics.size>20)diagnostics.removeAt(20)
                warnings.add(0,reason?.let { "Voce $count: $it" } ?: value.message);if(warnings.size>20)warnings.removeAt(20)
            } else {
                if (diagnostics.size < 20) diagnostics += value
                if (warnings.size < 20) warnings += reason?.let { "Voce $count: $it" } ?: value.message
            }
            if (rejected) excluded++
        }
        fun nextEntry() {
            count = ++budget.entries
            if (count > MAX_ENTRIES) throw PlaylistFormatException("La lista supera il limite complessivo di 50.000 voci, comprese le liste incluse.")
            if (count % 256 == 0) progress(count)
        }
        fun header(key: String, value: String) {
            try {
                val canonical = headerNames[key.lowercase(Locale.ROOT)] ?: PlaylistHeaders.name(key)
                headers.putAll(PlaylistHeaders.merge(headers, mapOf(canonical to value)))
            } catch (_: PlaylistFormatException) { unsupported = true }
        }
        fun property(key: String, value: String) {
            if ((key !in properties && properties.size >= 32) || value.length > 32 * 1024 ||
                properties.filterKeys { it != key }.values.sumOf { it.length } + value.length > 128 * 1024) unsupported = true
            else properties[key] = value
        }
        try { boundedLines(reader) { raw ->
            lineNumber++
            checkActive()
            val line = raw.trim().let { if (first) it.removePrefix("\uFEFF").trimStart() else it }
            if (line.isEmpty()) return@boundedLines
            if (first) {
                first = false
                if (line.startsWith("#EXTM3U", true)) {
                    defaults = inheritedDefaults + attributes(line.substring(7))
                    documents[document] = defaults
                    val descriptor = PlaylistDescriptor(mapOf("root" to defaults))
                    if (defaults["refresh"] != null && descriptor.refreshMillis == null || defaults["refresh_at"] != null && descriptor.refreshAt == null)
                        diagnostic(PlaylistDiagnosticCode.INVALID_REFRESH)
                    if (defaults.keys.any { it in PlaylistCapabilities.storedAttributes && it != "url-logo" }) diagnostic(PlaylistDiagnosticCode.STORED_METADATA)
                    if(defaults.keys.any {it in PlaylistCapabilities.informativeAttributes})diagnostic(PlaylistDiagnosticCode.SERVICE_INFO)
                    if (defaults.keys.any { it !in PlaylistCapabilities.activeAttributes && it !in PlaylistCapabilities.storedAttributes && it !in PlaylistCapabilities.sourceAttributes && it !in PlaylistCapabilities.informativeAttributes })diagnostic(PlaylistDiagnosticCode.UNKNOWN_ATTRIBUTE)
                    return@boundedLines
                }
                if (options.format != PlaylistFormat.PLAIN) throw PlaylistFormatException("Seleziona una lista M3U estesa oppure scegli M3U semplice nelle impostazioni avanzate.")
            }
            when {
                line.equals("#EXT-X-PLAYLIST-TYPE:VOD", true) && options.groups == PlaylistGroupProfile.KODI -> vodHint = true
                line.startsWith("#EXT-X-", true) -> throw PlaylistFormatException("Questo indirizzo contiene un singolo flusso HLS. Seleziona una lista di canali.")
                line.startsWith("#EXTINF:", true) -> {
                    if (pending) throw PlaylistFormatException("La lista è incompleta: manca l’indirizzo di un canale.")
                    pending = true; nextEntry()
                    var quote: Char? = null; var escaped = false
                    val separator = line.indexOfFirst { char ->
                        if (escaped) { escaped = false; false }
                        else if (char == '\\' && quote != null) { escaped = true; false }
                        else if (char == '"' || char == '\'') { if (quote == char) quote = null else if (quote == null) quote = char; false }
                        else char == ',' && quote == null
                    }
                    if (quote != null || separator < 0) throw PlaylistFormatException("La lista contiene metadati incompleti.")
                    metadata = attributes(line.substring(0, separator))
                    duration = line.substringAfter(':').substringBefore(' ').substringBefore(',').toDoubleOrNull()?.takeIf(Double::isFinite)
                    name = line.substring(separator + 1).trim().ifBlank { metadata["tvg-name"].orEmpty().trim() }.take(300)
                    headers.clear(); properties.clear(); unsupported = false; mime = null; seekable = null
                    (defaults + metadata)["http-user-agent"]?.let { header("user-agent", it) }
                    (defaults + metadata)["http-referrer"]?.let { header("referer", it) }
                }
                line.startsWith("#EXTGRP:", true) -> inheritedGroups = groups(line.substringAfter(':'))
                pending && line.startsWith("#EXTVLCOPT:http-", true) -> {
                    val option = line.substringAfter(':').substringBefore('=').lowercase(Locale.ROOT).removePrefix("http-")
                    if (option in setOf("user-agent", "referrer", "referer", "origin", "cookie")) header(option, line.substringAfter('=', ""))
                    else { property("vlc.$option", line.substringAfter('=', "")); diagnostic(PlaylistDiagnosticCode.PLAYER_OPTION) }
                }
                pending && line.startsWith("#EXTVLCOPT:", true) -> { property("vlc." + line.substringAfter(':').substringBefore('=').lowercase(Locale.ROOT), line.substringAfter('=', "")); diagnostic(PlaylistDiagnosticCode.PLAYER_OPTION) }
                pending && line.startsWith("#EXTHTTP:", true) -> property("exthttp", line.substringAfter(':'))
                pending && line.startsWith("#KODIPROP:", true) -> {
                    val key = line.substringAfter(':').substringBefore('=').trim().lowercase(Locale.ROOT)
                    val value = line.substringAfter('=', "").trim()
                    property(key, value)
                    if (key !in PlaylistCapabilities.activeProperties && key !in PlaylistCapabilities.unsupportedDrmProperties && "license" !in key && "drm" !in key)
                        diagnostic(PlaylistDiagnosticCode.UNKNOWN_PROPERTY)
                    if (key=="inputstream.adaptive.manifest_type") mime = when (value.lowercase(Locale.ROOT)) {
                        "hls" -> "application/x-mpegURL"; "mpd", "dash" -> "application/dash+xml"; else -> null
                    }
                }
                !line.startsWith('#') -> {
                    if (!pending) {
                        if (options.format != PlaylistFormat.PLAIN) throw PlaylistFormatException("La lista contiene un indirizzo privo dei dati del canale.")
                        nextEntry(); metadata = emptyMap(); duration = null; seekable = null
                        name = runCatching { URI(line.substringBefore('|')).path.orEmpty().substringAfterLast('/').takeIf(String::isNotBlank) }.getOrNull()
                            ?: "Canale $count"
                    }
                    pending = false
                    if ('|' in line) line.substringAfter('|').split('&').forEach { pair ->
                        if ('=' in pair) {
                            val key = decode(pair.substringBefore('=')).removePrefix("!")
                            if (key.equals("seekable", true)) {
                                seekable = when (decode(pair.substringAfter('='))) { "0" -> false; "1" -> true; else -> null }
                                if (seekable == null) diagnostic(PlaylistDiagnosticCode.PLAYER_OPTION)
                            } else header(key, decode(pair.substringAfter('=')))
                        }
                    }
                    if ('|' in line) property("lumetv.pipe_headers", line.substringAfter('|').split('&').filterNot { decode(it.substringBefore('=')).removePrefix("!").equals("seekable", true) }.joinToString("&"))
                    val uri = runCatching {
                        val original = URI(line.substringBefore('|'))
                        if (original.isAbsolute) original else baseUrl?.let { PlaylistUris.resolve(URI(it),original) }
                    }.getOrNull()
                    val protocol = uri?.scheme?.lowercase(Locale.ROOT)
                    if (protocol == "http") httpEntries++
                    val valid = uri != null && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
                        (protocol == "https" || protocol == "http" && allowHttp(uri)) && uri.toString().length <= 8192
                    when {
                        name.isBlank() -> diagnostic(PlaylistDiagnosticCode.INVALID_ENTRY, true)
                        unsupported -> diagnostic(PlaylistDiagnosticCode.INVALID_HEADERS, true)
                        !valid -> diagnostic(if (protocol == "http") PlaylistDiagnosticCode.HTTP_BLOCKED else PlaylistDiagnosticCode.INVALID_ENTRY, true)
                        else -> {
                            val effective = defaults + metadata
                            val kind = when (effective["radio"]?.lowercase(Locale.ROOT)) { "true", "1" -> MediaKind.RADIO; "false", "0" -> MediaKind.TV; else -> defaultKind }
                            val entryGroups = metadata["group-title"]?.let(::groups) ?: inheritedGroups
                            val content = when {
                                (metadata["type"] ?: metadata["content-type"]).equals("playlist", true) -> PlaylistContentKind.PLAYLIST
                                vodHint || metadata["media"]?.lowercase(Locale.ROOT) in setOf("true", "1") || (duration ?: 0.0) > 0 -> PlaylistContentKind.VOD
                                else -> PlaylistContentKind.LIVE
                            }
                            if (metadata.keys.any { it in PlaylistCapabilities.storedAttributes && it != "url-logo" } || content == PlaylistContentKind.VOD)
                                diagnostic(PlaylistDiagnosticCode.STORED_METADATA)
                            if (metadata.keys.any { it !in PlaylistCapabilities.activeAttributes && it !in PlaylistCapabilities.storedAttributes && it !in PlaylistCapabilities.sourceAttributes && it !in PlaylistCapabilities.informativeAttributes })
                                diagnostic(PlaylistDiagnosticCode.UNKNOWN_ATTRIBUTE)
                            val entry = PlaylistEntry(name, uri.toString(), effective["tvg-id"].orEmpty().take(300),
                                PlaylistCapabilities.logo(effective["tvg-logo"].orEmpty(), effective, baseUrl),
                                (metadata["tvg-chno"] ?: metadata["ch-number"])?.toIntOrNull()?.takeIf { it in 1..999_999 }, entryGroups,
                                kind, headers.toMap(), mime ?: properties["mimetype"], count - 1, properties.toMap(),
                                PlaylistMetadata(metadata, defaults, duration, document, seekable, content,baseUrl))
                            val policyChannel = Channel("", entry.name, entry.number ?: 9999, entry.tvgId, entry.groups.joinToString(";"), entry.logo, entry.url, "direct", "", numberKnown = entry.number != null)
                            if (content == PlaylistContentKind.PLAYLIST) {
                                val loader = include ?: throw PlaylistFormatException("La lista contiene documenti inclusi; importa da URL per caricarli.")
                                checkActive()
                                loader(entry.url).use { child ->
                                    val key = MessageDigest.getInstance("SHA-256").digest(entry.url.toByteArray()).hex()
                                    val inherited=defaults.toMutableMap()
                                    listOf("url-tvg","x-tvg-url").forEach {attr->inherited[attr]?.let {refs->baseUrl?.let {parent->
                                        inherited[attr]=refs.split(',').joinToString(",") {raw->runCatching {PlaylistUris.resolve(URI(parent),URI(raw.trim())).toString()}.getOrDefault(raw.trim())}
                                    }}}
                                    inherited["url-logo"]?.let {logoBase ->baseUrl?.let {parentBase ->
                                        runCatching {PlaylistUris.resolve(URI(parentBase),URI(logoBase)).toString()}.getOrNull()?.let {inherited["url-logo"]=it}
                                    }}
                                    val report = scan(child.reader, child.baseUrl, kind, allowHttp, checkActive, progress, options, budget, include, depth + 1, key, inherited, accept)
                                    imported += report.imported; excluded += report.excluded; duplicates += report.duplicates; httpEntries += report.httpEntries
                                    documents.putAll(report.descriptor.documents)
                                    documentBases.putAll(report.descriptor.bases)
                                    warnings += report.warnings.take(20 - warnings.size)
                                    diagnostics += report.diagnostics.take(20 - diagnostics.size)
                                    report.diagnosticCounts.forEach { (code, n) -> diagnosticCounts[code] = (diagnosticCounts[code] ?: 0) + n }
                                }
                            } else if (CatalogPolicy.isExcluded(policyChannel)) diagnostic(PlaylistDiagnosticCode.CATALOG_EXCLUDED, true)
                            else {
                                val digest = MessageDigest.getInstance("SHA-256")
                                fun field(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
                                listOf(entry.name, entry.url, entry.tvgId, entry.kind.name, entry.number.toString(), entry.mimeType.orEmpty()).forEach(::field)
                                entry.groups.forEach(::field)
                                entry.metadata.attributes.toSortedMap().forEach { (k, v) -> field(k); field(v) }
                                defaults.filterKeys {it in PlaylistCapabilities.storedAttributes}.toSortedMap().forEach { (k,v) ->field(k);field(v)}
                                field(entry.metadata.duration.toString()); field(entry.metadata.seekable.toString()); field(content.name)
                                (entry.headers.toSortedMap() + entry.properties.toSortedMap()).forEach { (k, v) -> field(k); field(v) }
                                val fingerprint = digest.digest().hex()
                                if (budget.fingerprints.add(fingerprint)) {
                                    val reason = accept(entry)
                                    if (reason == null) imported++ else diagnostic(PlaylistDiagnosticCode.UNSUPPORTED_PLAYBACK, true, reason)
                                } else duplicates++
                            }
                        }
                    }
                    if (options.groups == PlaylistGroupProfile.OTT || options.groups == PlaylistGroupProfile.KODI && "group-title" in metadata) inheritedGroups = emptyList()
                    headers.clear(); properties.clear(); metadata = emptyMap(); name = ""
                }
            }
        } } finally { if (pathKey != null) budget.path.remove(pathKey) }
        if (pending) throw PlaylistFormatException("La lista è incompleta: manca l’indirizzo dell’ultimo canale.")
        if (imported == 0 && depth == 0) throw PlaylistFormatException("La lista non contiene canali importabili. Controlla il formato, il consenso HTTP e le direttive DRM." + warnings.firstOrNull()?.let { " $it" }.orEmpty())
        progress(budget.entries)
        return PlaylistScanReport(imported, budget.entries, excluded, duplicates, warnings, httpEntries, PlaylistDescriptor(documents,documentBases), diagnostics, diagnosticCounts)
    }

    private fun attributes(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        attribute.findAll(value).forEach { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            if (result.size >= 64 && key !in result) throw PlaylistFormatException("La lista contiene troppi attributi per voce o intestazione (massimo 64).")
            result[key] = match.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty().replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return result
    }

    private fun boundedLines(reader: Reader, block: (String) -> Unit) {
        val buffer = CharArray(8192)
        val line = StringBuilder()
        var afterCr = false
        while (true) {
            val size = reader.read(buffer)
            if (size < 0) break
            for (i in 0 until size) {
                val char = buffer[i]
                if (char == '\r' || char == '\n') {
                    if (!(char == '\n' && afterCr)) { block(line.toString()); line.setLength(0) }
                    afterCr = char == '\r'
                } else {
                    afterCr = false
                    if (line.length >= MAX_LINE) throw PlaylistFormatException("La lista contiene una riga troppo lunga (massimo 64 KiB).")
                    line.append(char)
                }
            }
        }
        if (line.isNotEmpty()) block(line.toString())
    }

    private fun groups(value: String) = value.split(';').map { it.trim().take(100) }.filter(String::isNotBlank).distinct().take(20)
    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}

object PlaylistIdentity {
    private val random = SecureRandom()
    fun keys(entry: PlaylistEntry): List<String?> = listOf(
        entry.tvgId.takeIf(String::isNotBlank)?.let { "${entry.kind}|$it" },
        "${entry.kind}|${entry.url}",
        "${entry.kind}|${entry.name.trim().lowercase(Locale.ROOT)}|${entry.groups.sorted()}",
    )
    fun newId(): String = ByteArray(12).also(random::nextBytes).hex()
    fun reconcile(entries: List<PlaylistEntry>, previous: List<PlaylistRecord>, now: Long): List<PlaylistRecord> {
        val assigned = mutableMapOf<Int, String>()
        val used = hashSetOf<String>()
        val strategies: List<(PlaylistEntry) -> String?> = listOf(
            { e -> keys(e)[0] }, { e -> keys(e)[1] }, { e -> keys(e)[2] },
        )
        strategies.forEach { key ->
            val old = previous.filter { it.id !in used }.groupBy { key(it.entry) }.filterKeys { it != null }.filterValues { it.size == 1 }
            val fresh = entries.indices.filter { it !in assigned }.groupBy { key(entries[it]) }.filterKeys { it != null }.filterValues { it.size == 1 }
            fresh.forEach { (identity, indexes) -> old[identity]?.single()?.let { assigned[indexes.single()] = it.id; used += it.id } }
        }
        val existing = previous.map { it.id }.toMutableSet()
        return entries.mapIndexed { index, entry ->
            val id = assigned[index] ?: generateSequence { newId() }
                .first { existing.add(it) }
            PlaylistRecord(id, entry, now)
        }
    }
}

private fun ByteArray.hex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) { for (byte in this@hex) { val n = byte.toInt() and 255; append(alphabet[n ushr 4]); append(alphabet[n and 15]) } }
}
