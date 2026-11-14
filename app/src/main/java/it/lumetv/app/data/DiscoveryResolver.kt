// SPDX-License-Identifier: GPL-3.0-or-later
// Extracted from the WLTV-derived StreamResolver; provider behavior is shared with the built-in catalog.
package it.lumetv.app.data

import it.lumetv.core.DrmSpec
import it.lumetv.core.ResolvedStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

internal class DiscoveryResolver(private val http:Transport) {
    suspend fun resolve(key:String): ResolvedStream {
        val configs = mapOf(
            "Nove" to Triple("nove", "nove.tv", "3"), "RealTime" to Triple("realtime", "realtime.it", "2"),
            "DMAX" to Triple("dmaxit", "dmax.it", "4"), "Giallo" to Triple("giallo", "giallotv.it", "27"),
            "Turbo" to Triple("motortrend", "discoveryturbo.it", "11"), "HGTV" to Triple("hgtvit", "homegardentv.it", "13"),
            "FoodNetwork" to Triple("foodnetwork", "foodnetwork.it", "6"), "K2" to Triple("k2", "k2tv.it", "24"),
            "Frisbee" to Triple("frisbee", "frisbeetv.it", "26"), "Discovery" to Triple("discoverychannelit", "discovery.it", "12"))
        val config = configs[key] ?: throw SourceException("Canale WBD non riconosciuto.")
        var endpoint = "https://public.aurora.enhanced.live"
        var realm = "it"
        var channelId = config.third
        val headers = HttpClient.browserHeaders("https://${config.second}").toMutableMap()
        if (key !in setOf("K2", "Frisbee")) {
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
