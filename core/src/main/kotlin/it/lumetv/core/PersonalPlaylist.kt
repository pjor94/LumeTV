package it.lumetv.core

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.io.Reader
import java.io.StringReader
import java.security.MessageDigest

class PlaylistFormatException(message: String, val line: Int? = null, val document: String = "root") : IllegalArgumentException(message)

/** Private playback data. Do not publish these objects to UI state, logs or integrations. */
class PlaylistEntry(
    val name: String, val url: String, val tvgId: String = "", val logo: String = "",
    val number: Int? = null, val groups: List<String> = emptyList(), val kind: MediaKind = MediaKind.TV,
    val headers: Map<String, String> = emptyMap(), val mimeType: String? = null, val position: Int = 0,
    val properties: Map<String, String> = emptyMap(),
    val metadata: PlaylistMetadata = PlaylistMetadata(), val localplay: PlaylistLocalplay? = null,
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
    const val VERSION = 1
    const val MAX_BYTES = 256 * 1024 * 1024
    const val MAX_ENTRIES = 500_000
    const val MAX_LINE = 64 * 1024
    private val attribute = Regex("([\\w-]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|'([^']*)'|([^\\s,]+))")
    private val headerNames = mapOf("user-agent" to "User-Agent", "referer" to "Referer", "referrer" to "Referer",
        "origin" to "Origin", "authorization" to "Authorization", "cookie" to "Cookie")
    private val networkAddress = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
    private val decoration = Regex("[-_=*─━═]{3,}")
    private val diagnosticSecret = Regex("[a-fA-F0-9]{24,}")
    private val diagnosticProperty = Regex("[a-z0-9_.-]{1,100}")

    private fun addressCandidate(line: String, relative: Boolean): Boolean {
        val address = line.substringBefore('|')
        if (networkAddress.containsMatchIn(address) || address.startsWith("http:", true) || address.startsWith("https:", true)) return true
        if (!relative || address.isBlank()) return false
        if (address.none { it in "/:.?" } && (decoration.containsMatchIn(address) || address.all { it in "-_=*─━═• " })) return false
        return runCatching { URI(address); true }.getOrDefault(false)
    }

    fun parse(text: String, baseUrl: String? = null, defaultKind: MediaKind = MediaKind.TV,
              allowHttp: (URI) -> Boolean = { false }, options: PlaylistReadOptions = PlaylistReadOptions()): PlaylistReport {
        // This convenience overload is for small callers/tests. Production imports use scan(Reader).
        var bytes = 0L
        var previousHigh = false
        text.forEach { char ->
            bytes += when { char.code < 128 -> 1; char.code < 2048 -> 2
                char.isLowSurrogate() && previousHigh -> 1; else -> 3 }
            previousHigh = char.isHighSurrogate()
            if (bytes > MAX_BYTES) throw PlaylistFormatException("La lista supera il limite di 256 MiB.")
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
        var brokenEntry = false
        var entryLine = 0
        var recoveredEntry = false
        var preludeLine = 0
        var name = ""
        var metadata = emptyMap<String, String>()
        var inheritedGroups = emptyList<String>()
        val headers = linkedMapOf<String, String>()
        val properties = linkedMapOf<String, String>()
        var unsupported = false
        var mime: String? = null
        var duration: Double? = null
        var seekable: Boolean? = null
        var documentVod = false
        var entryVod = false
        val classifier=PlaylistContentClassifier(options)
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
        fun retain(value:PlaylistDiagnostic) {
            val examples=diagnostics.count {it.code==value.code && it.property==value.property && it.document==value.document}
            if(!value.excluded && examples>=if(value.priority==3)1 else 3)return
            val index=diagnostics.indexOfFirst {it.priority>=value.priority}.let {if(it<0)diagnostics.size else it}
            if(index<20){diagnostics.add(index,value);if(diagnostics.size>20)diagnostics.removeAt(20)}
            warnings.clear();warnings.addAll(diagnostics.map {it.message})
        }
        fun diagnostic(code: PlaylistDiagnosticCode, rejected: Boolean = false, reason: String? = null,
                       atLine: Int = lineNumber, entryNumber: Int = count, propertyName:String?=null) {
            diagnosticCounts[code] = (diagnosticCounts[code] ?: 0) + 1
            val label=name.take(100).takeUnless {"://" in it || it.any(Char::isISOControl) || diagnosticSecret.containsMatchIn(it)}.orEmpty()
            retain(PlaylistDiagnostic(code,atLine,entryNumber,rejected,document,reason?.take(300),if(entryNumber>0)label else "",propertyName?.takeIf {diagnosticProperty.matches(it)}))
            if (rejected) excluded++
        }
        fun resetEntry() {
            pending=false;brokenEntry=false;recoveredEntry=false;name="";metadata=emptyMap()
            headers.clear();properties.clear();unsupported=false;mime=null;duration=null;seekable=null;entryVod=false
            preludeLine=0
        }
        fun attributeHeaders(values: Map<String,String>) {
            values["http-user-agent"]?.let { headerValue ->
                try { headers.putAll(PlaylistHeaders.merge(headers,mapOf("User-Agent" to headerValue))) }
                catch (_:PlaylistFormatException) { unsupported=true }
            }
            values["http-referrer"]?.let { headerValue ->
                try { headers.putAll(PlaylistHeaders.merge(headers,mapOf("Referer" to headerValue))) }
                catch (_:PlaylistFormatException) { unsupported=true }
            }
        }
        fun nextEntry() {
            count = ++budget.entries
            if (count > MAX_ENTRIES) throw PlaylistFormatException("La lista supera il limite complessivo di 500.000 voci lette, comprese le liste incluse, le escluse e le duplicate.")
            if (count % 256 == 0) progress(count)
        }
        fun header(key: String, value: String) {
            if(!pending && preludeLine==0)preludeLine=lineNumber
            try {
                val canonical = headerNames[key.lowercase(Locale.ROOT)] ?: PlaylistHeaders.name(key)
                headers.putAll(PlaylistHeaders.merge(headers, mapOf(canonical to value)))
            } catch (_: PlaylistFormatException) { unsupported = true }
        }
        fun property(key: String, value: String) {
            if(!pending && preludeLine==0)preludeLine=lineNumber
            if ((key !in properties && properties.size >= 32) || value.length > 32 * 1024 ||
                properties.filterKeys { it != key }.values.sumOf { it.length } + value.length > 128 * 1024) unsupported = true
            else properties[key] = value
        }
        try { boundedLines(reader) { raw ->
            lineNumber++
            checkActive()
            val line = raw.trim().removePrefix("\uFEFF").trimStart()
            if (line.isEmpty()) return@boundedLines
            if (line.startsWith("<html",true) || line.startsWith("<!doctype html",true))
                throw PlaylistFormatException("Il documento ricevuto è una pagina HTML, non una lista M3U.")
            if (first) {
                if (line.startsWith('#') && !line.startsWith("#EXTM3U",true) && !line.startsWith("#EXTINF",true) &&
                    !line.startsWith("#EXT-X-",true) && !line.startsWith("#KODIPROP:",true) && !line.startsWith("#EXTVLCOPT:",true) && !line.startsWith("#EXTHTTP:",true))return@boundedLines
                first = false
                if (line.startsWith("#EXTM3U", true)) {
                    defaults = inheritedDefaults + attributes(line.substring(7))
                    documents[document] = defaults
                    val descriptor = PlaylistDescriptor(mapOf("root" to defaults))
                    if (defaults["refresh"] != null && descriptor.refreshMillis == null || defaults["refresh_at"] != null && descriptor.refreshAt == null)
                        diagnostic(PlaylistDiagnosticCode.INVALID_REFRESH)
                    if (defaults.keys.any { it in PlaylistCapabilities.storedAttributes && it != "url-logo" && it != "group-logo" }) diagnostic(PlaylistDiagnosticCode.STORED_METADATA)
                    if("group-logo" in defaults)diagnostic(PlaylistDiagnosticCode.GROUP_ARTWORK,propertyName="group-logo")
                    if(defaults.keys.any {it in PlaylistCapabilities.informativeAttributes})diagnostic(PlaylistDiagnosticCode.SERVICE_INFO)
                    if (defaults.keys.any { it !in PlaylistCapabilities.activeAttributes && it !in PlaylistCapabilities.storedAttributes && it !in PlaylistCapabilities.sourceAttributes && it !in PlaylistCapabilities.informativeAttributes })diagnostic(PlaylistDiagnosticCode.UNKNOWN_ATTRIBUTE)
                    return@boundedLines
                }
                if (options.format != PlaylistFormat.PLAIN) throw PlaylistFormatException("Seleziona una lista M3U estesa oppure scegli M3U semplice nelle impostazioni avanzate.")
            }
            when {
                line.equals("#EXT-X-PLAYLIST-TYPE:VOD", true) && options.groups == PlaylistGroupProfile.KODI -> {
                    if(!pending && count==0)documentVod=true else entryVod=true
                }
                line.startsWith("#EXT-X-", true) -> throw PlaylistFormatException("Questo indirizzo contiene un singolo flusso HLS. Seleziona una lista di canali.")
                line.startsWith("#EXTINF:", true) -> {
                    if (pending) {
                        diagnostic(PlaylistDiagnosticCode.MISSING_STREAM,true,atLine=entryLine)
                        resetEntry()
                    }
                    if(brokenEntry)resetEntry()
                    pending = true; entryLine=lineNumber;nextEntry()
                    var quote: Char? = null; var escaped = false
                    val separator = line.indexOfFirst { char ->
                        if (escaped) { escaped = false; false }
                        else if (char == '\\' && quote != null) { escaped = true; false }
                        else if (char == '"' || char == '\'') { if (quote == char) quote = null else if (quote == null) quote = char; false }
                        else char == ',' && quote == null
                    }
                    if (quote != null) {
                        diagnostic(PlaylistDiagnosticCode.MALFORMED_METADATA,true);resetEntry();brokenEntry=true;return@boundedLines
                    }
                    metadata = try { attributes(if(separator<0)line else line.substring(0,separator)) }
                    catch (_:PlaylistFormatException) {
                        diagnostic(PlaylistDiagnosticCode.MALFORMED_METADATA,true);resetEntry();brokenEntry=true;return@boundedLines
                    }
                    if(separator<0 && metadata["tvg-name"].isNullOrBlank()) {
                        diagnostic(PlaylistDiagnosticCode.MALFORMED_METADATA,true);resetEntry();brokenEntry=true;return@boundedLines
                    }
                    duration = line.substringAfter(':').substringBefore(' ').substringBefore(',').toDoubleOrNull()?.takeIf(Double::isFinite)
                    name = (if(separator>=0)line.substring(separator+1).trim() else "").ifBlank { metadata["tvg-name"].orEmpty().trim() }.take(300)
                    recoveredEntry=separator<0 || name.isBlank()
                    if(name.isBlank())name="Canale $count"
                    val prelude=headers.toMap();headers.clear();attributeHeaders(defaults);headers.putAll(prelude);attributeHeaders(metadata)
                }
                line.startsWith("#EXTGRP:", true) -> inheritedGroups = groups(line.substringAfter(':'))
                !brokenEntry && line.startsWith("#EXTVLCOPT:http-", true) -> {
                    val option = line.substringAfter(':').substringBefore('=').lowercase(Locale.ROOT).removePrefix("http-")
                    if (option in setOf("user-agent", "referrer", "referer", "origin", "cookie")) header(option, line.substringAfter('=', ""))
                    else { property("vlc.$option", line.substringAfter('=', "")); diagnostic(PlaylistDiagnosticCode.PLAYER_OPTION) }
                }
                !brokenEntry && line.startsWith("#EXTVLCOPT:", true) -> { property("vlc." + line.substringAfter(':').substringBefore('=').lowercase(Locale.ROOT), line.substringAfter('=', "")); diagnostic(PlaylistDiagnosticCode.PLAYER_OPTION) }
                !brokenEntry && line.startsWith("#EXTHTTP:", true) -> property("exthttp", line.substringAfter(':'))
                !brokenEntry && line.startsWith("#KODIPROP:", true) -> {
                    val key = line.substringAfter(':').substringBefore('=').trim().lowercase(Locale.ROOT)
                    val value = line.substringAfter('=', "").trim()
                    property(key, value)
                    if (key !in PlaylistCapabilities.activeProperties && key !in PlaylistCapabilities.unsupportedDrmProperties && "license" !in key && "drm" !in key)
                        diagnostic(PlaylistDiagnosticCode.UNKNOWN_PROPERTY,entryNumber=if(pending)count else 0,propertyName=key)
                    if(key=="inputstream.adaptive.manifest_type")mime=PlaylistManifestTypes.mime(value)
                    if(key=="inputstream.adaptive.file_type" && PlaylistManifestTypes.mime(value)==null)diagnostic(PlaylistDiagnosticCode.PLAYER_OPTION,entryNumber=if(pending)count else 0,propertyName=key)
                }
                !line.startsWith('#') -> {
                    if(brokenEntry){resetEntry();return@boundedLines}
                    val command=PlaylistLocalplay.isCommand(line)
                    val localplay=if(command)PlaylistLocalplay.read(line) else null
                    if(command && localplay==null) {
                        if(!pending)nextEntry()
                        diagnostic(PlaylistDiagnosticCode.UNKNOWN_LOCALPLAY,true);resetEntry();return@boundedLines
                    }
                    if(!command && !addressCandidate(line,pending || options.format==PlaylistFormat.PLAIN && baseUrl!=null)) {
                        diagnostic(PlaylistDiagnosticCode.IGNORED_TEXT,entryNumber=if(pending)count else 0);return@boundedLines
                    }
                    if (!pending) {
                        nextEntry();entryLine=lineNumber;recoveredEntry=options.format!=PlaylistFormat.PLAIN
                        name = if(localplay!=null)localplay.resolverKey else if(options.format==PlaylistFormat.PLAIN)runCatching { URI(line.substringBefore('|')).path.orEmpty().substringAfterLast('/').takeIf(String::isNotBlank) }.getOrNull() ?: "Canale $count" else "Canale $count"
                        val prelude=headers.toMap();headers.clear();attributeHeaders(defaults);headers.putAll(prelude)
                    }
                    pending = false
                    if (localplay==null && '|' in line) line.substringAfter('|').split('&').forEach { pair ->
                        if ('=' in pair) {
                            val key = decode(pair.substringBefore('=')).removePrefix("!")
                            if (key.equals("seekable", true)) {
                                seekable = when (decode(pair.substringAfter('='))) { "0" -> false; "1" -> true; else -> null }
                                if (seekable == null) diagnostic(PlaylistDiagnosticCode.PLAYER_OPTION)
                            } else header(key, decode(pair.substringAfter('=')))
                        }
                    }
                    if (localplay==null && '|' in line) property("lumetv.pipe_headers", line.substringAfter('|').split('&').filterNot { decode(it.substringBefore('=')).removePrefix("!").equals("seekable", true) }.joinToString("&"))
                    val uri = runCatching {
                        val original = URI(localplay?.address ?: line.substringBefore('|'))
                        if (localplay!=null || original.isAbsolute) original else baseUrl?.let { PlaylistUris.resolve(URI(it),original) }
                    }.getOrNull()
                    val protocol = uri?.scheme?.lowercase(Locale.ROOT)
                    if (protocol == "http") httpEntries++
                    val valid = localplay!=null || uri != null && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
                        (protocol == "https" || protocol == "http" && allowHttp(uri)) && uri.toString().length <= 8192
                    when {
                        name.isBlank() -> diagnostic(PlaylistDiagnosticCode.INVALID_ENTRY, true)
                        unsupported -> diagnostic(PlaylistDiagnosticCode.INVALID_HEADERS, true)
                        !valid -> diagnostic(if (protocol == "http") PlaylistDiagnosticCode.HTTP_BLOCKED else PlaylistDiagnosticCode.INVALID_ENTRY, true)
                        else -> {
                            val effective = defaults + metadata
                            val kind = when (effective["radio"]?.lowercase(Locale.ROOT)) { "true", "1" -> MediaKind.RADIO; "false", "0" -> MediaKind.TV; else -> defaultKind }
                            val entryGroups = metadata["group-title"]?.let(::groups) ?: inheritedGroups
                            val marker=when {entryVod->PlaylistVodMarker.ENTRY;documentVod->PlaylistVodMarker.DOCUMENT;else->PlaylistVodMarker.NONE}
                            val classification=classifier.classify(metadata,defaults,duration,requireNotNull(uri),entryGroups,marker)
                            val content=classification.kind
                            if(classification.conflicting)diagnostic(PlaylistDiagnosticCode.CONTENT_CONFLICT)
                            if (metadata.keys.any { it in PlaylistCapabilities.storedAttributes && it != "url-logo" && it != "group-logo" } || content == PlaylistContentKind.VOD)
                                diagnostic(PlaylistDiagnosticCode.STORED_METADATA)
                            if (metadata.keys.any { it !in PlaylistCapabilities.activeAttributes && it !in PlaylistCapabilities.storedAttributes && it !in PlaylistCapabilities.sourceAttributes && it !in PlaylistCapabilities.informativeAttributes })
                                diagnostic(PlaylistDiagnosticCode.UNKNOWN_ATTRIBUTE)
                            if("group-logo" in metadata)diagnostic(PlaylistDiagnosticCode.GROUP_ARTWORK,propertyName="group-logo")
                            val entry = PlaylistEntry(name, uri.toString(), effective["tvg-id"].orEmpty().take(300),
                                PlaylistCapabilities.logo(effective["tvg-logo"].orEmpty(), effective, baseUrl),
                                (metadata["tvg-chno"] ?: metadata["ch-number"])?.toIntOrNull()?.takeIf { it in 1..999_999 }, entryGroups,
                                kind, headers.toMap(), properties["mimetype"]?.takeIf(String::isNotBlank) ?: mime ?: PlaylistManifestTypes.mime(properties["inputstream.adaptive.file_type"]), count - 1, properties.toMap(),
                                PlaylistMetadata(metadata, defaults, duration, document, seekable, content,baseUrl,marker,classification.reason,PlaylistContentClassifier.VERSION),localplay)
                            if (content == PlaylistContentKind.PLAYLIST && localplay!=null)diagnostic(PlaylistDiagnosticCode.INVALID_ENTRY,true)
                            else if (content == PlaylistContentKind.PLAYLIST) {
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
                                    report.diagnostics.forEach(::retain)
                                    report.diagnosticCounts.forEach { (code, n) -> diagnosticCounts[code] = (diagnosticCounts[code] ?: 0) + n }
                                }
                            } else {
                                val digest = MessageDigest.getInstance("SHA-256")
                                fun field(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
                                listOf(entry.name, entry.url, entry.tvgId, entry.kind.name, entry.number.toString(), entry.mimeType.orEmpty()).forEach(::field)
                                entry.groups.forEach(::field)
                                entry.metadata.attributes.toSortedMap().forEach { (k, v) -> field(k); field(v) }
                                defaults.filterKeys {it in PlaylistCapabilities.storedAttributes}.toSortedMap().forEach { (k,v) ->field(k);field(v)}
                                field(entry.metadata.duration.toString()); field(entry.metadata.seekable.toString()); field(content.name)
                                (entry.headers.toSortedMap() + entry.properties.toSortedMap()).forEach { (k, v) -> field(k); field(v) }
                                val fingerprint = digest.digest().hex()
                                if (budget.firstFingerprint(fingerprint)) {
                                    val reason = accept(entry)
                                    if (reason == null) {
                                        imported++
                                        if(recoveredEntry)diagnostic(PlaylistDiagnosticCode.RECOVERED_ENTRY,atLine=entryLine)
                                    } else diagnostic(PlaylistDiagnosticCode.UNSUPPORTED_PLAYBACK, true, reason,atLine=entryLine)
                                } else duplicates++
                            }
                        }
                    }
                    if (options.groups == PlaylistGroupProfile.OTT || options.groups == PlaylistGroupProfile.KODI && "group-title" in metadata) inheritedGroups = emptyList()
                    resetEntry()
                }
            }
        } } catch(error:PlaylistFormatException) {
            if(error.line!=null)throw error
            throw PlaylistFormatException(error.message ?: "Lista non valida.",lineNumber,document)
        } finally { if (pathKey != null) budget.path.remove(pathKey) }
        if (pending) diagnostic(PlaylistDiagnosticCode.MISSING_STREAM,true,atLine=entryLine)
        else if(preludeLine>0)diagnostic(PlaylistDiagnosticCode.TRAILING_PROPERTIES,atLine=preludeLine,entryNumber=0)
        if (imported == 0 && depth == 0) throw PlaylistFormatException("La lista non contiene canali importabili. Controlla il formato, il consenso HTTP e le direttive DRM." + warnings.firstOrNull()?.let { " $it" }.orEmpty(),lineNumber,document)
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
