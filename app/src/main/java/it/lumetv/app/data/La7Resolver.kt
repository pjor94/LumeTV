// SPDX-License-Identifier: GPL-3.0-or-later
// La7 broadcaster integration originally adapted from WorldLiveTV (WLTV Group).
// Modified by the Lume TV project, 2026-11-07: resolve La7 Play configuration and DRM authorization.
package it.lumetv.app.data

import it.lumetv.core.Channel
import it.lumetv.core.DrmSpec
import it.lumetv.core.ResolvedStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

class La7Resolver(private val http: Transport) {
    private val host = "https://www.la7play.it"

    suspend fun resolve(channel: Channel): ResolvedStream {
        val path = when (channel.key) {
            "La7" -> "/dirette/la7"
            "La7c" -> "/dirette/la7-cinema"
            else -> throw SourceException("Canale La7 non riconosciuto.")
        }
        val headers = HttpClient.browserHeaders(host)
        val config = playerConfig(http.get(host + path, headers))
        val manifest = url(config.string("mainLiveUrl").substringBefore('|').trim(), "La diretta La7 non è disponibile.")
        val license = config.string("drmLicenseServer").split(';').firstNotNullOfOrNull { entry ->
            val scheme = entry.substringBefore('|').trim()
            if (scheme == "com.widevine.alpha" && '|' in entry) entry.substringAfter('|').trim() else null
        } ?: throw SourceException("Configurazione DRM La7 non disponibile.")
        val licenseUrl = url(license, "Configurazione DRM La7 non valida.")
        val contentId = config.string("drmContentId")
        val endpoint = config.string("drmTokenEndpoint")
        if (contentId.isBlank() || endpoint.isBlank()) throw SourceException("Autorizzazione La7 non disponibile.")
        val tokenUrl = host.toHttpUrl().resolve(endpoint)?.newBuilder()
            ?.setQueryParameter("id", contentId)
            ?.setQueryParameter("contentType", "LIVE")
            ?.setQueryParameter("v", System.currentTimeMillis().toString())
            ?.build() ?: throw SourceException("Autorizzazione La7 non valida.")
        val response = try {
            JSONObject(http.get(tokenUrl.toString(), headers))
        } catch (_: JSONException) {
            throw SourceException("Autorizzazione La7 non valida.")
        }
        val token = listOf("preAuthToken", "token", "auth").firstNotNullOfOrNull { name ->
            (response.opt(name) as? String)?.takeIf { it.isNotBlank() }
        } ?: throw SourceException("Autorizzazione La7 non disponibile.")
        return ResolvedStream(manifest.toString(), "application/dash+xml", headers,
            DrmSpec(licenseUrl.toString(), headers + mapOf(
                "Content-Type" to "application/octet-stream", "preAuthorization" to token)))
    }

    private fun playerConfig(page: String): JSONObject {
        try {
            // Next.js serializes the player props in JSON strings; a field can span multiple chunks.
            val chunks = rscChunk.findAll(page).map { match ->
                JSONTokener(match.groupValues[1]).nextValue() as? String
                    ?: throw SourceException("Configurazione La7 non valida.")
            }.joinToString("")
            for (source in listOf(chunks, page)) {
                val match = configuration.find(source) ?: continue
                return JSONTokener(source.substring(match.groups[1]!!.range.first)).nextValue() as? JSONObject
                    ?: throw SourceException("Configurazione La7 non valida.")
            }
        } catch (_: JSONException) {
            throw SourceException("Configurazione La7 non valida.")
        }
        throw SourceException("La diretta La7 non è disponibile.")
    }

    private fun JSONObject.string(name: String) = (opt(name) as? String)?.trim().orEmpty()

    private fun url(value: String, message: String): HttpUrl = value.toHttpUrlOrNull()
        ?: throw SourceException(message)

    private companion object {
        // A possessive repetition avoids Java regex stack overflow on real, large RSC chunks.
        val rscChunk = Regex("""self\.__next_f\.push\(\s*\[\s*1\s*,\s*("(?:\\.|[^"\\])*+")\s*]\s*\)""")
        val configuration = Regex(""""configurazionePlayer"\s*:\s*(\{)""")
    }
}
