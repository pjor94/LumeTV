package it.lumetv.app.data

import it.lumetv.core.M3uParser
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Opt-in check of the actual La7 resolver. Never requests licenses or records session tokens. */
class La7LiveResolverTest {
    @Test fun `La7 and La7 Cinema resolve with reachable DASH manifests`() = runBlocking {
        assumeTrue(System.getProperty("lume.live") == "true")
        val http = HttpClient()
        val resolver = StreamResolver(http)
        val channels = M3uParser.parse(javaClass.getResource("/channels.m3u")!!.readText())
            .filter { it.broadcaster == "la7" }
        assertEquals(setOf("La7", "La7c"), channels.map { it.key }.toSet())
        val results = channels.map { channel ->
            val result = JSONObject().put("channel", channel.name)
            try {
                val stream = resolver.resolve(channel)
                result.put("resolved", true).put("mime", stream.mimeType)
                    .put("drm", stream.drm != null)
                    .put("has_authorization", !stream.drm?.headers?.get("preAuthorization").isNullOrBlank())
                val request = http.request(stream.url, stream.headers).newBuilder()
                    .header("Range", "bytes=0-8191").build()
                http.read(request) { response ->
                    val text = response.body!!.byteStream().readNBytes(8192).toString(Charsets.UTF_8)
                    result.put("manifest_http", response.code).put("dash", text.contains("<MPD"))
                }
            } catch (error: Exception) {
                result.put("resolved", false).put("error_type", error.javaClass.simpleName)
                if (error is SourceException) result.put("message", error.message)
            }
            result
        }
        val report = JSONObject().put("checked_at_utc", Instant.now().toString())
            .put("scope", "APK resolver, token retrieval and bounded manifest reads; no DRM license or playback test")
            .put("channels", JSONArray(results))
        File(requireNotNull(System.getProperty("lume.liveReport"))).resolveSibling("live-la7-resolvers.json")
            .apply { parentFile?.mkdirs(); writeText(report.toString(2) + "\n") }
        for (result in results) {
            assertTrue(result.toString(), result.optBoolean("resolved"))
            assertEquals(result.toString(), "application/dash+xml", result.optString("mime"))
            assertTrue(result.toString(), result.optBoolean("drm"))
            assertTrue(result.toString(), result.optBoolean("has_authorization"))
            assertTrue(result.toString(), result.optBoolean("dash"))
        }
    }
}
