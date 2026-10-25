// SPDX-License-Identifier: GPL-3.0-or-later
// Rai broadcaster flow adapted from WorldLiveTV (WLTV Group).
// Modified by the Lume TV project, 2026-10-25: Kotlin/Android port and Media3 integration.
package it.lumetv.app.data

import it.lumetv.core.Channel
import it.lumetv.core.DrmSpec
import it.lumetv.core.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class RaiResolver(private val http: Transport) {
    private val lock = Mutex()
    private var services: JSONObject? = null
    private var cachedAt = 0L
    private val host = "https://www.raiplay.it"

    suspend fun resolve(channel: Channel): ResolvedStream {
        val serviceUrl = try {
            RaiServices.find(catalog(), channel)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
        val contentUrl = serviceUrl ?: run {
            if (channel.broadcaster == "rainewsrg") throw SourceException("Questa edizione regionale non è disponibile al momento.")
            val direct = JSONObject(http.get("$host/dirette.json"))
            val service = direct.getJSONArray("contents").objects().firstOrNull {
                RaiServices.normalize(it.optString("channel")) == RaiServices.normalize(channel.key)
            } ?: throw SourceException("${channel.name} non è disponibile nelle dirette Rai al momento.")
            service.optJSONObject("video")?.optString("content_url").orEmpty()
        }
        val relinker = requireUrl(contentUrl).toHttpUrl().newBuilder()
            .setQueryParameter("output", "63").setQueryParameter("forceUserAgent", "raiplayappletv").build()
        val response = JSONObject(http.get(relinker.toString(), HttpClient.browserHeaders(host)))
        val url = requireUrl(response.optJSONArray("video")?.optString(0).orEmpty())
        val licenses = response.optJSONObject("licence_server_map")?.optJSONArray("drmLicenseUrlValues")?.objects().orEmpty()
        val widevine = licenses.firstOrNull { it.optString("drm").equals("WIDEVINE", true) }
        if (licenses.isNotEmpty() && widevine == null) throw SourceException("Questa diretta richiede un sistema DRM non disponibile in questa versione.")
        val drm = widevine?.let {
            val licenseUrl = requireUrl(it.optString("licenceUrl")).toHttpUrl()
            val token = licenseUrl.queryParameter("Authorization")
            val headers = HttpClient.browserHeaders(host).toMutableMap()
            headers["Content-Type"] = "application/octet-stream"
            if (token != null) headers["nv-authorizations"] = token
            DrmSpec(licenseUrl.newBuilder().query(null).build().toString(), headers)
        }
        return ResolvedStream(url, if (url.contains(".mpd")) "application/dash+xml" else "application/x-mpegURL", HttpClient.browserHeaders(host), drm)
    }

    private suspend fun catalog(): JSONObject = lock.withLock {
        val now = System.currentTimeMillis()
        if (services == null || now - cachedAt > 600_000) {
            services = JSONObject(http.get("https://www.replaytvmhp.rai.it/hbbtv/launcher/RemoteControl/resources/srvConfig.json"))
            cachedAt = now
        }
        services!!
    }
}

internal object RaiServices {
    private val regionalTriplets = mapOf("vda" to 301, "piemonte" to 302, "liguria" to 303, "lombardia" to 304,
        "veneto" to 305, "bolzano" to 306, "trento" to 307, "fvg" to 309, "fjk" to 310,
        "emiliaromagna" to 311, "toscana" to 312, "marche" to 313, "umbria" to 314,
        "lazio" to 315, "abruzzo" to 316, "molise" to 317, "campania" to 318,
        "puglia" to 319, "basilicata" to 320, "calabria" to 321, "sardegna" to 322, "sicilia" to 323)
    fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")
    fun find(catalog: JSONObject, channel: Channel): String? {
        val editors = catalog.getJSONObject("Configuration").getJSONObject("Editors").getJSONArray("Editor").objects()
        val all = editors.flatMap { editor ->
            editor.optJSONArray("Services")?.objects().orEmpty().filter { it.optString("delivery") == "3" }
                .flatMap { it.optJSONArray("Service")?.objects().orEmpty() }
        }
        val triplet = if (channel.broadcaster == "rainewsrg") regionalTriplets[channel.key.substringBefore('$')]?.let { "0.0.$it" } else null
        val name = if (channel.key == "Rai Radio 2") "Rai Radio 2 Visual" else channel.key
        val service = if (channel.broadcaster == "rainewsrg") all.firstOrNull { triplet != null && it.optString("dvbTriplet") == triplet }
            else all.firstOrNull { normalize(it.optString("name")) == normalize(name) }
        return service?.optString("url")?.takeIf { it.isNotBlank() }
    }
}
