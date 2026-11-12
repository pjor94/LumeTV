package it.lumetv.core

import java.security.MessageDigest
import java.net.URLDecoder

enum class MediaKind { TV, RADIO }

data class Channel(
    val id: String,
    val name: String,
    val number: Int,
    val epgId: String,
    val group: String,
    val logo: String,
    val source: String,
    val broadcaster: String,
    val key: String,
    val headers: Map<String, String> = emptyMap(),
    val kind: MediaKind = MediaKind.TV,
    val sourceId: String = BUILTIN_TV_SOURCE,
    val position: Int = 0,
    val groups: List<String> = emptyList(),
    val numberKnown: Boolean = number != 9999,
    val contentKind: PlaylistContentKind = PlaylistContentKind.LIVE,
) {
    val isVod get() = contentKind==PlaylistContentKind.VOD
    val displayNumber: Int? get() = number.takeIf { numberKnown }
    val isPersonal: Boolean get() = sourceId.startsWith("user_")
    val isRadio: Boolean get() = kind == MediaKind.RADIO
    val family: String get() = if (isRadio) "Radio" else when (broadcaster) {
        "rai", "rainewsrg" -> "Rai"
        "mediaset" -> "Mediaset"
        else -> "Altri canali"
    }
    val supported: Boolean get() = broadcaster in setOf("rai", "rainewsrg", "mediaset", "sky", "la7", "discovery", "direct", "personal")
}

const val BUILTIN_TV_SOURCE = "builtin_tv"
const val BUILTIN_RADIO_SOURCE = "builtin_radio"

object M3uParser {
    private val attribute = Regex("([\\w-]+)=\"([^\"]*)\"")
    fun parse(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var metadata: Map<String, String> = emptyMap()
        var name = ""
        val headers = linkedMapOf<String, String>()
        text.removePrefix("\uFEFF").lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#EXTINF:") -> {
                    metadata = attribute.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                    var quoted = false
                    val separator = line.indexOfFirst { char ->
                        if (char == '"') quoted = !quoted
                        char == ',' && !quoted
                    }
                    name = if (separator >= 0) line.substring(separator + 1).trim() else metadata["tvg-name"].orEmpty()
                    headers.clear()
                    metadata["http-user-agent"]?.let { headers["User-Agent"] = it }
                    metadata["http-referrer"]?.let { headers["Referer"] = it }
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=") -> headers["User-Agent"] = line.substringAfter('=')
                line.startsWith("#EXTVLCOPT:http-referrer=") -> headers["Referer"] = line.substringAfter('=')
                !line.startsWith("#") && line.isNotBlank() && name.isNotBlank() -> {
                    val url = line.substringBefore('|')
                    if ('|' in line) line.substringAfter('|').split('&').forEach { entry ->
                        if ('=' in entry) headers[decode(entry.substringBefore('='))] = decode(entry.substringAfter('='))
                    }
                    val route = parseRoute(url)
                    if (route != null) {
                        val (broadcaster, key) = route
                        val identity = if (broadcaster == "direct") metadata["tvg-id"].orEmpty() + "|" + name + "|" + metadata["group-title"].orEmpty() else "$broadcaster|$key"
                        val id = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                        channels += Channel(id, name, metadata["tvg-chno"]?.toIntOrNull() ?: 9999,
                            metadata["tvg-id"].orEmpty(), metadata["group-title"].orEmpty(), metadata["tvg-logo"].orEmpty(),
                            url, broadcaster, key, headers.toMap())
                    }
                    name = ""
                }
            }
        }
        return channels.distinctBy { it.id }.sortedBy { it.number }
    }

    private fun parseRoute(url: String): Pair<String, String>? {
        if (url.startsWith("https://") || url.startsWith("http://")) return "direct" to url
        if (!url.startsWith("plugin://plugin.video.wltvhelper/")) return null
        val route = url.substringAfter("plugin://plugin.video.wltvhelper/").removePrefix("?").removePrefix("/")
        if (!route.startsWith("play/")) return null
        val parts = route.removePrefix("play/").split('/', limit = 2)
        return if (parts.size == 2) parts[0] to decode(parts[1]) else null
    }
    private fun decode(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}

enum class DrmScheme { WIDEVINE, CLEARKEY }
enum class LicenseChallengeEncoding { RAW, BASE64, BASE64_URL }
enum class LicenseResponseEncoding { RAW, BASE64, JSON, JSON_BASE64, BASE64_JSON }
data class LicenseRequestSpec(val method: String = "POST", val challenge: LicenseChallengeEncoding = LicenseChallengeEncoding.RAW,
    val response: LicenseResponseEncoding = LicenseResponseEncoding.RAW, val responsePath: List<String> = emptyList(),
    val template: String? = null, val wrappers: List<String> = emptyList(), val unwrappers: List<String> = emptyList()) {
    override fun toString() = "LicenseRequestSpec(method=$method, response=$response)"
}
data class HlsKeySpec(val parameters: String = "", val headers: Map<String,String> = emptyMap()) {
    override fun toString() = "HlsKeySpec()"
}
data class DrmSpec(val licenseUrl: String, val headers: Map<String, String> = emptyMap(),
    val scheme: DrmScheme = DrmScheme.WIDEVINE, val clearKeyResponse: String? = null,
    val multiSession: Boolean = false, val request: LicenseRequestSpec = LicenseRequestSpec()) {
    override fun toString() = "DrmSpec(scheme=$scheme, local=${clearKeyResponse != null})"
}
data class ResolvedStream(
    val url: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drm: DrmSpec? = null,
    val personal: Boolean = false,
    val personalAllowHttp: Boolean = false,
    val manifestHeaders: Map<String, String>? = null,
    val personalNetworkToken: String = "",
    val seekable: Boolean? = null,
    val hlsKey: HlsKeySpec? = null,
) {
    override fun toString() = "ResolvedStream(mimeType=$mimeType, personal=$personal, drm=$drm)"
}

object ChannelNavigation {
    fun next(channels: List<Channel>, currentId: String?, direction: Int, favoriteIds: Set<String>? = null): Channel? {
        val current = channels.firstOrNull { it.id == currentId }
        val available = channels.filter { (current == null || it.kind == current.kind && it.sourceId == current.sourceId) && (favoriteIds == null || it.id in favoriteIds) }
        if (available.isEmpty()) return null
        val index = available.indexOfFirst { it.id == currentId }
        val next = if (index < 0) { if (direction < 0) available.lastIndex else 0 }
            else Math.floorMod(index + direction, available.size)
        return available[next]
    }
    fun byNumber(channels: List<Channel>, number: Int) = channels.firstOrNull { !it.isRadio && it.displayNumber == number }
}
