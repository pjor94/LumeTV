// SPDX-License-Identifier: GPL-3.0-or-later
// Mediaset broadcaster flow adapted from WorldLiveTV (WLTV Group).
// Modified by the Lume TV project, 2026-10-25: Kotlin/Android port and Media3 integration.
package it.lumetv.app.data

import it.lumetv.core.Channel
import it.lumetv.core.DrmSpec
import it.lumetv.core.ResolvedStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.UUID

class MediasetResolver(private val http: Transport) {
    private data class Session(val appName: String, val sid: String, val token: String, val createdAt: Long)
    private val sessionLock = Mutex()
    private var session: Session? = null
    private val host = "https://mediasetinfinity.mediaset.it"
    private val api = "https://api-ott-prod-fe.mediaset.net"
    private val headers get() = HttpClient.browserHeaders(host)

    suspend fun resolve(channel: Channel): ResolvedStream {
        try { return resolveWithSession(channel, getSession()) }
        catch (error: SourceException) {
            if (error.status !in setOf(401, 403)) throw error
            sessionLock.withLock { session = null }
            return resolveWithSession(channel, getSession())
        }
    }

    private suspend fun getSession(): Session = sessionLock.withLock {
        session?.takeIf { System.currentTimeMillis() - it.createdAt < 15 * 60_000 }?.let { return@withLock it }
        val page = http.get(host, headers)
        val appName = Regex("[\"']app-name[\"']\\s+content=[\"']([^\"']+)").find(page)?.groupValues?.get(1)
            ?: throw SourceException("Il servizio di accesso Mediaset è cambiato. Serve un aggiornamento.")
        val response = JSONObject(http.post("$api/PROD/play/idm/anonymous/login/v2.0",
            JSONObject().put("appName", appName).put("client_id", UUID.randomUUID().toString()), headers)).optJSONObject("response")
            ?: throw SourceException("Accesso anonimo Mediaset non disponibile.")
        val sid = response.optString("sid")
        val token = response.optString("beToken")
        if (sid.isBlank() || token.isBlank()) throw SourceException("Accesso anonimo Mediaset non disponibile.")
        Session(appName, sid, token, System.currentTimeMillis()).also { session = it }
    }

    private suspend fun resolveWithSession(channel: Channel, session: Session): ResolvedStream {
        val code = if (channel.key.startsWith('$')) channel.key.removePrefix("$") else {
            val stations = JSONObject(http.get("https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-stations-v2?fields=callSign,title,tuningInstruction&form=cjson&httpError=true", headers))
            stations.optJSONArray("entries")?.objects()?.firstOrNull { it.optString("title").equals(channel.key, true) }?.optString("callSign")
                ?: throw SourceException("${channel.name} non è presente nel catalogo Mediaset attuale.")
        }
        val request = JSONObject().put("channelCode", code).put("streamType", "LIVE").put("delivery", "Streaming")
            .put("createDevice", "true").put("overrideAppName", session.appName)
        val checkUrl = "$api/PROD/play/playback/check/v2.0".toHttpUrl().newBuilder().addQueryParameter("sid", session.sid).build()
        val response = JSONObject(http.post(checkUrl.toString(), request, headers + ("Authorization" to "Bearer ${session.token}")))
        val selector = response.optJSONObject("response")?.optJSONObject("mediaSelector")
            ?: throw SourceException("${channel.name} non è disponibile al momento o richiede un’autorizzazione diversa.")
        val base = requireUrl(selector.optString("url")).toHttpUrl().newBuilder()
        selector.keys().asSequence().filter { it != "url" && it != "auth" }.forEach { base.addQueryParameter(it, selector.get(it).toString()) }
        base.addQueryParameter("auth", session.token)
        val (url, pid) = parseSmil(http.get(base.build().toString(), headers))
        val drm = if (pid.isNotBlank()) {
            val licenseUrl = "https://widevine.entitlement.theplatform.eu/wv/web/ModularDrm/getRawWidevineLicense".toHttpUrl().newBuilder()
                .addQueryParameter("releasePid", pid).addQueryParameter("account", "http://access.auth.theplatform.com/data/Account/2702976343")
                .addQueryParameter("schema", "1.0").addQueryParameter("token", session.token).build()
            DrmSpec(licenseUrl.toString(), headers + ("Content-Type" to "application/octet-stream"))
        } else null
        return ResolvedStream(requireUrl(url), if (url.contains(".mpd")) "application/dash+xml" else "application/x-mpegURL", headers, drm)
    }
}
