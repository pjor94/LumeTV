package it.lumetv.core

import java.net.URI
import java.security.MessageDigest
import java.net.URLDecoder

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
) {
    val family: String get() = when (broadcaster) {
        "rai", "rainewsrg" -> "Rai"
        "mediaset" -> "Mediaset"
        else -> "Altri canali"
    }
    val supported: Boolean get() = broadcaster in setOf("rai", "rainewsrg", "mediaset", "sky", "la7", "discovery", "direct")
}

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

data class DrmSpec(val licenseUrl: String, val headers: Map<String, String> = emptyMap())
data class ResolvedStream(
    val url: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drm: DrmSpec? = null,
)

object ChannelNavigation {
    fun next(channels: List<Channel>, currentId: String?, direction: Int): Channel? {
        if (channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.id == currentId }
        val next = if (index < 0) 0 else Math.floorMod(index + direction, channels.size)
        return channels[next]
    }
    fun byNumber(channels: List<Channel>, number: Int) = channels.firstOrNull { it.number == number }
}
