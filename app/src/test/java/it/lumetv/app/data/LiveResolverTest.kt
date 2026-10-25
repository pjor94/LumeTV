package it.lumetv.app.data

import it.lumetv.core.M3uParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Explicit opt-in integration check. No DRM license requests and no tokens in its report. */
class LiveResolverTest {
    @Test fun `all bundled Rai and Mediaset channels resolve or report their actual availability`() = runBlocking {
        assumeTrue(System.getProperty("lume.live") == "true")
        val http = HttpClient()
        val resolver = StreamResolver(http)
        val channels = M3uParser.parse(javaClass.getResource("/channels.m3u")!!.readText()).filter { it.family in setOf("Rai", "Mediaset") }
        assertTrue(channels.size >= 6)
        val permits = Semaphore(3)
        val reports = channels.map { channel -> async {
            permits.withPermit {
                val row = JSONObject().put("channel", channel.name).put("broadcaster", channel.broadcaster)
                try {
                    val stream = resolver.resolve(channel)
                    row.put("resolved", true).put("drm", stream.drm != null).put("mime", stream.mimeType)
                    val request = http.request(stream.url, stream.headers).newBuilder().header("Range", "bytes=0-8191").build()
                    val info = http.read(request) { response ->
                        val bytes = response.body!!.byteStream().readNBytes(8192)
                        val text = bytes.toString(Charsets.UTF_8)
                        response.code to when {
                            text.trimStart().startsWith("#EXTM3U") -> "HLS"
                            text.contains("<MPD") -> "DASH"
                            else -> "other"
                        }
                    }
                    row.put("manifest_http", info.first).put("manifest_format", info.second)
                } catch (error: Exception) {
                    row.put("resolved", false).put("error_type", error.javaClass.simpleName)
                    // Only explicit user-facing exceptions are safe to persist; HTTP URLs can contain tokens.
                    if (error is SourceException) row.put("message", error.message)
                }
                row
            }
        } }.awaitAll()
        val report = JSONObject().put("checked_at_utc", Instant.now().toString())
            .put("scope", "Same Kotlin resolvers as the APK; URL resolution and bounded manifest reads only, no license or playback test")
            .put("channels", JSONArray(reports))
        File(requireNotNull(System.getProperty("lume.liveReport")) { "Set lume.liveReport to the report path" }).apply {
            parentFile?.mkdirs()
            writeText(report.toString(2) + "\n")
        }
        // Inactive event channels are a valid observation; the main channels must resolve.
        for (name in listOf("Rai 1", "Rai 2", "Rai 3", "Canale 5", "Rete 4", "Italia 1")) {
            val result = reports.single { it.getString("channel") == name }
            assertTrue("$name: $result", result.optBoolean("resolved"))
            assertTrue("$name: $result", result.optString("manifest_format") in setOf("HLS", "DASH"))
        }
    }
}
