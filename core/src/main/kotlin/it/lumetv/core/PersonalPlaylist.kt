package it.lumetv.core

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale

class PlaylistFormatException(message: String) : IllegalArgumentException(message)

/** Private playback data. Do not publish these objects to UI state, logs or integrations. */
class PlaylistEntry(
    val name: String, val url: String, val tvgId: String = "", val logo: String = "",
    val number: Int? = null, val groups: List<String> = emptyList(), val kind: MediaKind = MediaKind.TV,
    val headers: Map<String, String> = emptyMap(), val mimeType: String? = null, val position: Int = 0,
) {
    override fun toString() = "PlaylistEntry(name=$name, kind=$kind)"
}

data class PlaylistReport(val entries: List<PlaylistEntry>, val excluded: Int, val duplicates: Int, val warnings: List<String>)
data class PlaylistRecord(val id: String, val entry: PlaylistEntry, val seenAt: Long)

/** The WLTV parser and its historical identity policy remain unchanged. */
object PersonalM3uParser {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_ENTRIES = 5_000
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|'([^']*)'|([^\\s,]+))")
    private val headerNames = mapOf("user-agent" to "User-Agent", "referer" to "Referer", "referrer" to "Referer",
        "origin" to "Origin", "authorization" to "Authorization", "cookie" to "Cookie")

    fun parse(text: String, baseUrl: String? = null, defaultKind: MediaKind = MediaKind.TV,
              allowHttp: (URI) -> Boolean = { false }): PlaylistReport {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw PlaylistFormatException("La lista supera il limite di 8 MiB.")
        val lines = text.removePrefix("\uFEFF").trimStart().lineSequence()
        var first = true
        var pending = false
        var name = ""
        var metadata = emptyMap<String, String>()
        var inheritedGroups = emptyList<String>()
        val headers = linkedMapOf<String, String>()
        var unsupported = false
        var mime: String? = null
        var count = 0
        var excluded = 0
        var duplicates = 0
        val warnings = mutableListOf<String>()
        val entries = mutableListOf<PlaylistEntry>()
        val fingerprints = hashSetOf<List<Any?>>()
        fun warning(reason: String) { excluded++; if (warnings.size < 20) warnings += "Voce $count: $reason" }
        fun header(key: String, value: String) {
            val canonical = headerNames[key.lowercase(Locale.ROOT)] ?: return
            if (value.length > 4096 || value.any { it == '\r' || it == '\n' || it == '\u0000' }) unsupported = true
            else headers[canonical] = value
        }
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.length > 32 * 1024) throw PlaylistFormatException("La lista contiene una riga troppo lunga.")
            if (line.isEmpty()) return@forEach
            if (first) {
                first = false
                if (!line.startsWith("#EXTM3U", true)) throw PlaylistFormatException("Seleziona una lista M3U estesa, con intestazione #EXTM3U.")
                return@forEach
            }
            when {
                line.startsWith("#EXT-X-", true) -> throw PlaylistFormatException("Questo indirizzo contiene un singolo flusso HLS. Seleziona una lista di canali.")
                line.startsWith("#EXTINF:", true) -> {
                    if (pending) throw PlaylistFormatException("La lista è incompleta: manca l’indirizzo di un canale.")
                    pending = true; count++
                    if (count > MAX_ENTRIES) throw PlaylistFormatException("La lista supera il limite di 5.000 voci.")
                    var quote: Char? = null; var escaped = false
                    val separator = line.indexOfFirst { char ->
                        if (escaped) { escaped = false; false }
                        else if (char == '\\' && quote != null) { escaped = true; false }
                        else if (char == '"' || char == '\'') { if (quote == char) quote = null else if (quote == null) quote = char; false }
                        else char == ',' && quote == null
                    }
                    if (quote != null || separator < 0) throw PlaylistFormatException("La lista contiene metadati incompleti.")
                    metadata = attribute.findAll(line.substring(0, separator)).associate {
                        it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
                            .replace("\\\"", "\"").replace("\\\\", "\\")
                    }
                    name = line.substring(separator + 1).trim().ifBlank { metadata["tvg-name"].orEmpty().trim() }.take(300)
                    headers.clear(); unsupported = false; mime = null
                    metadata["http-user-agent"]?.let { header("user-agent", it) }
                    metadata["http-referrer"]?.let { header("referer", it) }
                }
                line.startsWith("#EXTGRP:", true) -> inheritedGroups = groups(line.substringAfter(':'))
                pending && line.startsWith("#EXTVLCOPT:http-", true) -> {
                    val option = line.substringAfter(':').substringBefore('=').removePrefix("http-")
                    header(option, line.substringAfter('=', ""))
                }
                pending && line.startsWith("#KODIPROP:", true) -> {
                    val property = line.substringAfter(':').substringBefore('=').lowercase(Locale.ROOT)
                    if ("license" in property || "drm" in property) unsupported = true
                    if (property.endsWith("manifest_type")) mime = when (line.substringAfter('=').lowercase(Locale.ROOT)) {
                        "hls" -> "application/x-mpegURL"; "mpd", "dash" -> "application/dash+xml"; else -> null
                    }
                }
                !line.startsWith('#') -> {
                    if (!pending) throw PlaylistFormatException("La lista contiene un indirizzo privo dei dati del canale.")
                    pending = false
                    if ('|' in line) line.substringAfter('|').split('&').forEach { pair ->
                        if ('=' in pair) header(decode(pair.substringBefore('=')), decode(pair.substringAfter('=')))
                    }
                    val uri = runCatching {
                        val original = URI(line.substringBefore('|'))
                        if (original.isAbsolute) original else baseUrl?.let { URI(it).resolve(original) }
                    }.getOrNull()
                    val protocol = uri?.scheme?.lowercase(Locale.ROOT)
                    val valid = uri != null && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
                        (protocol == "https" || protocol == "http" && allowHttp(uri)) && uri.toString().length <= 8192
                    when {
                        name.isBlank() -> warning("nome assente")
                        unsupported -> warning("header o protezione non supportati")
                        !valid -> warning("indirizzo non supportato; usa uno stream HTTPS")
                        else -> {
                            val kind = when (metadata["radio"]?.lowercase(Locale.ROOT)) { "true" -> MediaKind.RADIO; "false" -> MediaKind.TV; else -> defaultKind }
                            val entryGroups = metadata["group-title"]?.let(::groups) ?: inheritedGroups
                            val entry = PlaylistEntry(name, uri.toString(), metadata["tvg-id"].orEmpty().take(300), metadata["tvg-logo"].orEmpty().take(2048),
                                metadata["tvg-chno"]?.toIntOrNull()?.takeIf { it in 1..999_999 && it != 9999 }, entryGroups,
                                kind, headers.toMap(), mime, count - 1)
                            val policyChannel = Channel("", entry.name, entry.number ?: 9999, entry.tvgId, entry.groups.joinToString(";"), entry.logo, entry.url, "direct", "")
                            if (CatalogPolicy.isExcluded(policyChannel)) warning("canale escluso dal catalogo Lume")
                            else {
                                val fingerprint = listOf(entry.name, entry.url, entry.tvgId, entry.groups, entry.kind, entry.number, entry.headers, entry.mimeType)
                                if (fingerprints.add(fingerprint)) entries += entry else duplicates++
                            }
                        }
                    }
                    headers.clear(); metadata = emptyMap(); name = ""
                }
            }
        }
        if (pending) throw PlaylistFormatException("La lista è incompleta: manca l’indirizzo dell’ultimo canale.")
        if (entries.isEmpty()) throw PlaylistFormatException("La lista non contiene canali importabili. Sono supportati stream HTTPS senza direttive DRM aggiuntive.")
        return PlaylistReport(entries, excluded, duplicates, warnings)
    }

    private fun groups(value: String) = value.split(';').map { it.trim().take(100) }.filter(String::isNotBlank).distinct().take(20)
    private fun decode(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}

object PlaylistIdentity {
    private val random = SecureRandom()
    fun reconcile(entries: List<PlaylistEntry>, previous: List<PlaylistRecord>, now: Long): List<PlaylistRecord> {
        val assigned = mutableMapOf<Int, String>()
        val used = hashSetOf<String>()
        val strategies: List<(PlaylistEntry) -> String?> = listOf(
            { e -> e.tvgId.takeIf(String::isNotBlank)?.let { "${e.kind}|$it" } },
            { e -> "${e.kind}|${e.url}" },
            { e -> "${e.kind}|${e.name.trim().lowercase(Locale.ROOT)}|${e.groups.sorted()}" },
        )
        strategies.forEach { key ->
            val old = previous.filter { it.id !in used }.groupBy { key(it.entry) }.filterKeys { it != null }.filterValues { it.size == 1 }
            val fresh = entries.indices.filter { it !in assigned }.groupBy { key(entries[it]) }.filterKeys { it != null }.filterValues { it.size == 1 }
            fresh.forEach { (identity, indexes) -> old[identity]?.single()?.let { assigned[indexes.single()] = it.id; used += it.id } }
        }
        val existing = previous.map { it.id }.toMutableSet()
        return entries.mapIndexed { index, entry ->
            val id = assigned[index] ?: generateSequence { ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) } }
                .first { existing.add(it) }
            PlaylistRecord(id, entry, now)
        }
    }
}
