// SPDX-License-Identifier: GPL-3.0-or-later
// Broadcaster flows adapted from WorldLiveTV 1.2.14 + hotfix 121417 (WLTV Group).
// Modified by the Lume TV project, 2026-10-25: Kotlin/Android port and Media3 integration.
package it.lumetv.app.data

import it.lumetv.core.Channel
import it.lumetv.core.DrmSpec
import it.lumetv.core.ResolvedStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class StreamResolver(private val http: Transport) {
    private val rai = RaiResolver(http)
    private val mediaset = MediasetResolver(http)

    suspend fun resolve(channel: Channel): ResolvedStream = withContext(Dispatchers.Default) { when (channel.broadcaster) {
        "rai", "rainewsrg" -> rai.resolve(channel)
        "mediaset" -> mediaset.resolve(channel)
        "sky" -> {
            val id = mapOf("Sky TG24" to 1, "Cielo" to 2, "TV8" to 7)[channel.key]
                ?: throw SourceException("Canale Sky non riconosciuto.")
            val data = JSONObject(http.get("https://video.sky.it/api/v1/getLivestream?id=$id&isMobile=false"))
            ResolvedStream(requireUrl(data.optString("streaming_url")), "application/x-mpegURL")
        }
        "la7" -> resolveLa7(channel)
        "discovery" -> resolveDiscovery(channel)
        "direct" -> ResolvedStream(channel.source, headers = channel.headers)
        else -> throw SourceException("Questa emittente non è ancora supportata.")
    } }

    private suspend fun resolveLa7(channel: Channel): ResolvedStream {
        val host = "https://www.la7.it"
        val headers = HttpClient.browserHeaders(host)
        val path = if (channel.key == "La7c") "/live-la7cinema" else "/dirette-tv"
        val page = http.get(host + path, headers)
        val url = Regex("[\"]?dash[\"]?\\s*:\\s*[\"']([^\"']+)").find(page)?.groupValues?.get(1)
            ?: throw SourceException("La diretta La7 non è disponibile.")
        val tokenPath = Regex("preTokenUrl\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: throw SourceException("Autorizzazione La7 non disponibile.")
        val tokenUrl = host.toHttpUrl().resolve(tokenPath)?.toString() ?: throw SourceException("Autorizzazione La7 non valida.")
        val token = JSONObject(http.get(tokenUrl, headers)).getString("preAuthToken")
        return ResolvedStream(requireUrl(url), "application/dash+xml", headers,
            DrmSpec("https://la7.prod.conax.cloud/widevine/license?d=${System.currentTimeMillis() / 1000}",
                headers + mapOf("Content-Type" to "application/octet-stream", "preAuthorization" to token)))
    }

    private suspend fun resolveDiscovery(channel: Channel): ResolvedStream {
        val configs = mapOf(
            "Nove" to Triple("nove", "nove.tv", "3"), "RealTime" to Triple("realtime", "realtime.it", "2"),
            "DMAX" to Triple("dmaxit", "dmax.it", "4"), "Giallo" to Triple("giallo", "giallotv.it", "27"),
            "Turbo" to Triple("motortrend", "discoveryturbo.it", "11"), "HGTV" to Triple("hgtvit", "homegardentv.it", "13"),
            "FoodNetwork" to Triple("foodnetwork", "foodnetwork.it", "6"), "K2" to Triple("k2", "k2tv.it", "24"),
            "Frisbee" to Triple("frisbee", "frisbeetv.it", "26"), "Discovery" to Triple("discoverychannelit", "discovery.it", "12"))
        val config = configs[channel.key] ?: throw SourceException("Canale WBD non riconosciuto.")
        var endpoint = "https://public.aurora.enhanced.live"
        var realm = "it"
        var channelId = config.third
        val headers = HttpClient.browserHeaders("https://${config.second}").toMutableMap()
        if (channel.key !in setOf("K2", "Frisbee")) {
            val url = "$endpoint/site/configurations/?include=default&filter[environment]=${config.first}&v=2"
            val data = JSONObject(http.get(url, headers)).getJSONObject("settings").getJSONObject("site").getJSONObject("simulcast")
            endpoint = data.getString("sonicEndpoint").trimEnd('/')
            realm = data.getString("sonicRealm")
            channelId = data.get("channelId").toString()
        }
        headers += mapOf("X-Device-Info" to "STONEJS/1 (Unknown/Unknown; Windows/NT 10.0; Unknown)",
            "X-disco-params" to "realm=$realm", "X-disco-client" to "WEB:UNKNOWN:wbdatv:2.1.9")
        val tokenUrl = endpoint.toHttpUrl().newBuilder().addPathSegment("token").addQueryParameter("realm", realm).build()
        val token = JSONObject(http.get(tokenUrl.toString(), headers)).getJSONObject("data").getJSONObject("attributes").getString("token")
        headers["Authorization"] = "Bearer $token"
        val request = JSONObject().put("channelId", channelId)
            .put("deviceInfo", JSONObject().put("adBlocker", false).put("drmSupported", true)
                .put("hdrCapabilities", JSONArray().put("SDR")).put("hwDecodingCapabilities", JSONArray()).put("soundCapabilities", JSONArray().put("STEREO")))
            .put("wisteriaProperties", JSONObject().put("platform", "desktop").put("device", JSONObject().put("type", "desktop")
                .put("browser", JSONObject().put("name", "chrome").put("version", "146.0.0.0"))))
        val response = JSONObject(http.post("$endpoint/playback/v3/channelPlaybackInfo", request, headers))
        val streams = response.optJSONObject("data")?.optJSONObject("attributes")?.optJSONArray("streaming")?.objects().orEmpty()
        val stream = streams.firstOrNull { it.optString("type") == "hls" } ?: streams.firstOrNull()
            ?: throw SourceException("Questa diretta WBD non è disponibile al momento.")
        val protection = stream.optJSONObject("protection")
        val drm = if (protection?.optBoolean("drmEnabled") == true) {
            val license = protection.optJSONObject("schemes")?.optJSONObject("widevine")?.optString("licenseUrl").orEmpty()
            DrmSpec(requireUrl(license), HttpClient.browserHeaders("https://www.discoveryplus.com"))
        } else null
        return ResolvedStream(requireUrl(stream.optString("url")), if (stream.optString("type") == "dash") "application/dash+xml" else "application/x-mpegURL",
            HttpClient.browserHeaders("https://www.discoveryplus.com"), drm)
    }
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
