// SPDX-License-Identifier: GPL-3.0-or-later
// Broadcaster flows adapted from WorldLiveTV 1.2.14 + hotfix 121417 (WLTV Group).
// Modified by the Lume TV project, 2026-10-25: Kotlin/Android port and Media3 integration.
// Modified by the Lume TV project, 2026-11-07: delegate La7 to its La7 Play integration.
// Modified by the Lume TV project, 2026-11-11: resolve personal channels through private playlist storage.
package it.lumetv.app.data

import it.lumetv.core.Channel
import it.lumetv.core.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class StreamResolver(private val http: Transport, private val personal: suspend (String) -> ResolvedStream = {
    throw SourceException("Questo canale personale non è disponibile.")
}) {
    private val rai = RaiResolver(http)
    private val mediaset = MediasetResolver(http)
    private val la7 = La7Resolver(http)

    suspend fun resolve(channel: Channel): ResolvedStream = withContext(Dispatchers.Default) { when (channel.broadcaster) {
        "rai", "rainewsrg" -> rai.resolve(channel)
        "mediaset" -> mediaset.resolve(channel)
        "sky" -> {
            val id = mapOf("Sky TG24" to 1, "Cielo" to 2, "TV8" to 7)[channel.key]
                ?: throw SourceException("Canale Sky non riconosciuto.")
            val data = JSONObject(http.get("https://video.sky.it/api/v1/getLivestream?id=$id&isMobile=false"))
            ResolvedStream(requireUrl(data.optString("streaming_url")), "application/x-mpegURL")
        }
        "la7" -> la7.resolve(channel)
        "discovery" -> DiscoveryResolver(http).resolve(channel.key)
        "direct" -> ResolvedStream(channel.source, headers = channel.headers)
        "personal" -> personal(channel.id)
        else -> throw SourceException("Questa emittente non è ancora supportata.")
    } }


}

internal fun requireUrl(value: String): String {
    if (!value.startsWith("https://") && !value.startsWith("http://")) throw SourceException("Il canale non ha uno stream disponibile al momento.")
    return value
}

internal fun parseSmil(xml: String): Pair<String, String> {
    if (Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) throw SourceException("Risposta XML non valida.")
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    val refs = doc.getElementsByTagNameNS("*", "ref")
    for (i in 0 until refs.length) {
        val ref = refs.item(i) as org.w3c.dom.Element
        val url = ref.getAttribute("src")
        if (!url.startsWith("http")) continue
        val params = ref.getElementsByTagNameNS("*", "param")
        val pid = (0 until params.length).firstNotNullOfOrNull { j ->
            val value = (params.item(j) as org.w3c.dom.Element).getAttribute("value")
            Regex("(?:^|\\|)pid=([^|]+)").find(value)?.groupValues?.get(1)
        }.orEmpty()
        if (ref.getAttribute("security") == "commonEncryption" && pid.isBlank()) throw SourceException("Autorizzazione DRM Mediaset incompleta.")
        return url to if (ref.getAttribute("security") == "commonEncryption") pid else ""
    }
    throw SourceException("L’emittente non ha autorizzato questa diretta.")
}
