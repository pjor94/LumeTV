package it.lumetv.app.data

import it.lumetv.core.Channel
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/** Public artwork only. These rules do not apply to stream/session URLs. */
internal object LogoPolicy {
    const val EPG_IMAGE_HOST = "116.202.210.205"
    const val FRESH_FOR = 7 * 24 * 60 * 60_000L
    const val MAX_BYTES = 1024 * 1024
    private val epgHosts = setOf("epg-guide.com", "www.epg-guide.com", EPG_IMAGE_HOST)

    fun url(value: String): HttpUrl? {
        if (value.length > 2048) return null
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        if (url.port != if (url.isHttps) 443 else 80) return null
        val host = url.host
        if (host in epgHosts) return url.takeIf { it.encodedPath.startsWith("/icn/icons/") && it.encodedPath.endsWith(".png") && it.query == null }
        if (!publicHostname(host)) return null
        if (!url.isHttps && host != "www.man-ga.it") return null
        return url
    }

    fun redirect(from: HttpUrl, to: HttpUrl): Boolean {
        if (url(to.toString()) == null) return false
        if (from.host in epgHosts) return to.host in epgHosts
        return from.host.removePrefix("www.") == to.host.removePrefix("www.") && (!from.isHttps || to.isHttps)
    }

    private fun publicHostname(host: String): Boolean = host.contains('.') && ':' !in host &&
        !host.matches(Regex("[0-9.]+")) && !host.endsWith(".local") && !host.endsWith(".localhost") &&
        !host.endsWith(".internal") && !host.endsWith(".test") && !host.endsWith(".invalid")

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return false
        if (bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64) return false
        return true
    }

    fun fresh(checkedAt: Long, now: Long) = checkedAt > 0 && now >= checkedAt && now - checkedAt < FRESH_FOR

    fun candidates(channel: Channel, epg: Map<String, String>): List<String> = buildList {
        add(channel.logo)
        epg[channel.epgId]?.let(::add)
        // Artwork can be shared across regional editions; programmes must remain separate.
        if (channel.epgId.startsWith("rai3tgr")) epg["rai3"]?.let(::add)
        if (channel.epgId == "rairadio2") epg["rairadio2visualradio"]?.let(::add)
    }.mapNotNull { url(it)?.toString() }.distinct()
}
