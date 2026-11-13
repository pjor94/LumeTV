package it.lumetv.core

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale

enum class PlaylistFormat { EXTENDED, PLAIN }
enum class PlaylistEncoding(val charset: String) { UTF8("UTF-8"), WINDOWS_1252("windows-1252"), LATIN1("ISO-8859-1") }
enum class PlaylistGroupProfile { CURRENT, KODI, OTT }
enum class PlaylistContentKind { LIVE, VOD, PLAYLIST }

data class PlaylistReadOptions(
    val format: PlaylistFormat = PlaylistFormat.EXTENDED,
    val encoding: PlaylistEncoding = PlaylistEncoding.UTF8,
    val groups: PlaylistGroupProfile = PlaylistGroupProfile.CURRENT,
)

/** Values may contain credentials. Only the bounded diagnostics belong in presentation state. */
class PlaylistMetadata(
    val attributes: Map<String, String> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),
    val duration: Double? = null,
    val document: String = "root",
    val seekable: Boolean? = null,
    val contentKind: PlaylistContentKind = PlaylistContentKind.LIVE,
    val baseUrl: String? = null,
) {
    operator fun get(key: String): String? = attributes[key] ?: defaults[key]
    val tvgName get() = get("tvg-name").orEmpty()
    val languages get() = values("tvg-language")
    val countries get() = values("tvg-country")
    val restricted get() = get("adult")?.lowercase(Locale.ROOT) in setOf("true", "1", "yes") || !get("parent-code").isNullOrBlank()
    val audioTrack get() = get("audio-track")
    val videoTrack get() = get("video-track")
    val epgShiftHours get() = get("tvg-shift")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -24.0..24.0 }
    val catchup get() = CatchupMetadata(get("catchup") ?: get("catchup-type"), get("catchup-source"),
        get("catchup-days")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..365.0 },
        get("catchup-correction")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -24.0..24.0 },
        get("catchup-time"), get("timeshift"), get("tvg-rec"))
    private fun values(key: String) = get(key).orEmpty().split(',', ';').map(String::trim).filter(String::isNotBlank).distinct().take(20)
    override fun toString() = "PlaylistMetadata(kind=$contentKind)"
}

class CatchupMetadata(val mode: String?, val source: String?, val days: Double?, val correctionHours: Double?,
    val legacyTime: String?, val timeshift: String?, val recording: String?) {
    override fun toString() = "CatchupMetadata(configured=${!mode.isNullOrBlank()})"
}

/** Defaults are stored once per document, rather than copied into every channel payload. */
class PlaylistDescriptor(val documents: Map<String, Map<String, String>> = emptyMap(), val bases:Map<String,String> = emptyMap()) {
    val attributes get() = documents["root"].orEmpty()
    val epgUrls get() = (attributes["url-tvg"] ?: attributes["x-tvg-url"]).orEmpty()
        .split(',').map(String::trim).filter(String::isNotBlank).take(16).map {raw ->
            runCatching {val uri=URI(raw);if(uri.isAbsolute)raw else bases["root"]?.let {PlaylistUris.resolve(URI(it),uri).toString()} ?: raw}.getOrDefault(raw)
        }
    val refreshMillis: Long? get() {
        val raw = attributes["refresh"]?.trim() ?: return null
        val duration = runCatching { Duration.parse(raw.uppercase(Locale.ROOT)).toMillis() }.getOrNull()
            ?: Regex("([0-9]+)\\s*([smhd])", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
                val multiplier = when (it.groupValues[2].lowercase(Locale.ROOT)) { "s" -> 1000L; "m" -> 60_000L; "h" -> 3_600_000L; else -> 86_400_000L }
                it.groupValues[1].toLongOrNull()?.takeIf { n -> n <= Long.MAX_VALUE / multiplier }?.times(multiplier)
            }
        return duration?.takeIf { it > 0 }?.coerceIn(15 * 60_000L, 7 * 86_400_000L)
    }
    val refreshAt: Long? get() = attributes["refresh_at"]?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    override fun toString() = "PlaylistDescriptor(documents=${documents.size})"
}

enum class PlaylistDiagnosticCode(val message: String) {
    INVALID_ENTRY("indirizzo o nome non valido"), HTTP_BLOCKED("stream HTTP escluso; abilita HTTP per questa lista"),
    INVALID_HEADERS("header o protezione non supportati"), CATALOG_EXCLUDED("canale escluso dal catalogo Lume"),
    UNSUPPORTED_PLAYBACK("configurazione di riproduzione non supportata"),
    STORED_METADATA("metadati aggiuntivi conservati; alcune preferenze dipendono dal player"),
    UNKNOWN_ATTRIBUTE("attributi aggiuntivi conservati"), UNKNOWN_PROPERTY("direttiva aggiuntiva conservata ma non applicata"),
    INVALID_REFRESH("aggiornamento della lista non interpretabile; usa un intervallo nelle impostazioni"),
    PLAYER_OPTION("opzione del player non supportata"),
    SERVICE_INFO("informazioni del servizio disponibili nello stato della lista"),
}

data class PlaylistDiagnostic(val code: PlaylistDiagnosticCode, val line: Int, val entry: Int, val excluded: Boolean = false) {
    val message get() = (if (entry > 0) "Voce $entry" else "Lista") + " · riga $line: " + code.message
}

/** Shared across includes: duplicates, rejected entries and playlist references also spend the budget. */
class PlaylistScanBudget(private val rememberFingerprint: ((String) -> Boolean)? = null) {
    var entries: Int = 0
        internal set
    var documents: Int = 0
        internal set
    private val fingerprints = hashSetOf<String>()
    internal fun firstFingerprint(value: String): Boolean = rememberFingerprint?.invoke(value) ?: fingerprints.add(value)
    internal val path = hashSetOf<String>()
}

class PlaylistDocument(val reader: java.io.Reader, val baseUrl: String, val release: () -> Unit) : AutoCloseable {
    override fun close() { try { reader.close() } finally { release() } }
    override fun toString() = "PlaylistDocument()"
}

object PlaylistCapabilities {
    val activeAttributes = setOf("tvg-id", "tvg-name", "tvg-logo", "group-title", "tvg-chno", "ch-number", "radio", "http-user-agent", "http-referrer", "type", "content-type", "media")
    val storedAttributes = setOf("url-tvg", "x-tvg-url", "url-logo", "tvg-shift", "catchup", "catchup-type", "catchup-source", "catchup-days", "catchup-correction", "catchup-time", "timeshift", "tvg-rec", "tvg-language", "tvg-country", "parent-code", "adult", "audio-track", "video-track", "m3uautoload")
    val informativeAttributes = setOf("max-conn","billed-till","billed-msg")
    val sourceAttributes = setOf("refresh", "refresh_at")
    val activeProperties = setOf("inputstream", "inputstreamaddon", "mimetype", "inputstream.adaptive.manifest_type", "inputstream.adaptive.common_headers", "inputstream.adaptive.manifest_headers", "inputstream.adaptive.stream_headers", "inputstream.adaptive.license_type", "inputstream.adaptive.license_key", "inputstream.adaptive.drm_legacy", "inputstream.adaptive.drm", "exthttp", "lumetv.pipe_headers")
    val unsupportedDrmProperties = setOf("inputstream.adaptive.server_certificate", "inputstream.adaptive.pre_init_data", "inputstream.adaptive.license_data", "inputstream.adaptive.license_flags", "inputstream.adaptive.drm_pre_init_data")

    fun logo(raw: String, defaults: Map<String, String>, base: String?): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val uri = URI(raw)
            if (uri.isAbsolute) raw else {
                val logoBase = defaults["url-logo"]?.let { value -> URI(value).let { if (it.isAbsolute) it else base?.let { b -> PlaylistUris.resolve(URI(b),it) } } }
                (logoBase ?: base?.let(::URI))?.let {PlaylistUris.resolve(it,uri)}?.toString().orEmpty()
            }
        }.getOrDefault("").take(8192)
    }
}

/** HTTP relative references retain raw query bytes, including signatures and literal plus signs. */
object PlaylistUris {
    fun resolve(base:URI,reference:URI):URI {
        if(reference.isAbsolute)return reference
        val result=if(reference.rawAuthority==null && reference.rawPath.orEmpty().isEmpty()) {
            URI(base.scheme+"://"+base.rawAuthority+base.rawPath.orEmpty()+
                (reference.rawQuery ?: base.rawQuery)?.let {"?$it"}.orEmpty()+reference.rawFragment?.let {"#$it"}.orEmpty())
        } else base.resolve(reference)
        var path=result.rawPath.orEmpty()
        while(path.startsWith("/../"))path=path.removePrefix("/..")
        if(path=="/..")path="/"
        if(path==result.rawPath)return result
        return URI(result.scheme+"://"+result.rawAuthority+path+result.rawQuery?.let {"?$it"}.orEmpty()+result.rawFragment?.let {"#$it"}.orEmpty())
    }
}
