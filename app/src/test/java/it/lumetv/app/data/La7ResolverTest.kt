package it.lumetv.app.data

import it.lumetv.core.Channel
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class La7ResolverTest {
    @Test fun `La7 and Cinema resolve their own live configuration and DRM authorization`() = runTest {
        for ((key, slug, contentId) in listOf(Triple("La7", "la7", "1"), Triple("La7c", "la7-cinema", "2"))) {
            val transport = FakeTransport(page(config(contentId)), """{"preAuthToken":"session-token"}""")
            val stream = StreamResolver(transport).resolve(channel(key))
            val drm = requireNotNull(stream.drm)

            assertEquals("https://www.la7play.it/dirette/$slug", transport.requests.first().first)
            assertEquals("https://cdn.example/$contentId/live.mpd?a=1&b=2", stream.url)
            assertEquals("application/dash+xml", stream.mimeType)
            assertEquals("https://license.example/widevine", drm.licenseUrl)
            assertEquals("session-token", drm.headers["preAuthorization"])
            assertEquals("application/octet-stream", drm.headers["Content-Type"])
            assertFalse(stream.headers.containsKey("preAuthorization"))
            assertEquals("https://www.la7play.it", stream.headers["Origin"])
            assertEquals("https://www.la7play.it/", stream.headers["Referer"])
            for ((_, headers) in transport.requests) {
                assertEquals("https://www.la7play.it", headers["Origin"])
                assertEquals("https://www.la7play.it/", headers["Referer"])
                assertFalse(headers.containsKey("preAuthorization"))
            }

            val tokenUrl = transport.requests.last().first.toHttpUrl()
            assertEquals("token.example", tokenUrl.host)
            assertEquals("/tk.php", tokenUrl.encodedPath)
            assertEquals("1", tokenUrl.queryParameter("existing"))
            assertEquals(contentId, tokenUrl.queryParameter("id"))
            assertEquals("LIVE", tokenUrl.queryParameter("contentType"))
            assertTrue(tokenUrl.queryParameter("v")!!.toLong() > 0)
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun `RSC chunks concatenate before parsing escaped nested player settings`() = runTest {
        val settings = config("1").put("nested", JSONObject().put("description", "A } brace and a \"quote\""))
        val flight = "42:[\"$\",\"Player\",null,{\"configurazionePlayer\":$settings}]\n"
        val splitAt = flight.indexOf("configurazionePlayer") + 9
        val first = JSONObject.quote(flight.substring(0, splitAt))
        val second = JSONObject.quote(flight.substring(splitAt)).replace("&", "\\u0026")
        val html = "<html><script>self.__next_f.push([1,$first])</script>" +
            "<script>self.__next_f.push([0])</script>" +
            "<script>self.__next_f.push([1,$second])</script></html>"
        val transport = FakeTransport(html, """{"preAuthToken":"TOKEN"}""")

        val stream = StreamResolver(transport).resolve(channel("La7"))

        assertEquals("https://cdn.example/1/live.mpd?a=1&b=2", stream.url)
        assertEquals("https://license.example/widevine", stream.drm!!.licenseUrl)
    }

    @Test fun `large escaped RSC payload does not overflow while locating the player configuration`() = runTest {
        val padding = JSONObject.quote("x\"y".repeat(25_000))
        val firstChunk = "41:[\"$\",\"Placeholder\",null,{\"padding\":$padding}]\n"
        val secondChunk = "42:[\"$\",\"Player\",null,{\"configurazionePlayer\":${config("1")}}]\n"
        val html = "<html><script>self.__next_f.push([1,${JSONObject.quote(firstChunk)}])</script>" +
            "<script>self.__next_f.push([1,${JSONObject.quote(secondChunk)}])</script></html>"
        assertTrue("Fixture must retain realistic RSC chunk sizes", html.length in 130_000..180_000)
        val transport = FakeTransport(html, """{"preAuthToken":"TOKEN"}""")

        val stream = StreamResolver(transport).resolve(channel("La7"))

        assertEquals("https://cdn.example/1/live.mpd?a=1&b=2", stream.url)
        assertEquals("https://license.example/widevine", stream.drm!!.licenseUrl)
        assertEquals(2, transport.requests.size)
    }

    @Test fun `authorization supports the token field names returned by La7 Play`() = runTest {
        for (field in listOf("preAuthToken", "token", "auth")) {
            val transport = FakeTransport(page(config("1")), JSONObject().put(field, "TOKEN").toString())

            val stream = StreamResolver(transport).resolve(channel("La7"))

            assertEquals("TOKEN", stream.drm!!.headers["preAuthorization"])
        }
    }

    @Test fun `missing blank or non-string authorization fails with a safe source error`() = runTest {
        for (response in listOf("{}", """{"preAuthToken":""}""", """{"preAuthToken":"  "}""",
            """{"preAuthToken":null}""", """{"preAuthToken":123}""")) {
            val transport = FakeTransport(page(config("1")), response)

            val error = sourceFailure { StreamResolver(transport).resolve(channel("La7")) }

            assertFalse(error.message.orEmpty().contains("https://"))
            assertEquals(2, transport.requests.size)
        }
    }

    @Test fun `incomplete live configuration fails before requesting authorization`() = runTest {
        val incomplete = listOf("mainLiveUrl", "drmContentId", "drmLicenseServer", "drmTokenEndpoint").map { field ->
            config("1").apply { remove(field) }
        } + listOf(
            config("1").put("mainLiveUrl", ""),
            config("1").put("drmLicenseServer", "com.microsoft.playready|https://license.example/playready")
        )
        for (settings in incomplete) {
            val transport = FakeTransport(page(settings), """{"preAuthToken":"TOKEN"}""")

            sourceFailure { StreamResolver(transport).resolve(channel("La7")) }

            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun `a changed page produces a source error before playback`() = runTest {
        val transport = FakeTransport("<html><h1>La7 Play</h1></html>", "{}")

        sourceFailure { StreamResolver(transport).resolve(channel("La7")) }

        assertEquals(1, transport.requests.size)
    }

    private fun config(contentId: String) = JSONObject()
        .put("backupLiveUrl", "https://backup.example/wrong.mpd|https://backup.example/wrong.m3u8")
        .put("nested", JSONObject().put("enabled", true))
        .put("mainLiveUrl", "https://cdn.example/$contentId/live.mpd?a=1&b=2|https://cdn.example/$contentId/live.m3u8")
        .put("drmLicenseServer", "com.microsoft.playready|https://license.example/playready;com.widevine.alpha|https://license.example/widevine")
        .put("drmTokenEndpoint", "https://token.example/tk.php?existing=1")
        .put("drmContentId", contentId)

    private fun page(config: JSONObject): String {
        val flight = "42:[\"$\",\"Player\",null,{\"configurazionePlayer\":$config}]\n"
        return "<html><script>self.__next_f.push([1,${JSONObject.quote(flight)}])</script></html>"
    }

    private suspend fun sourceFailure(block: suspend () -> Unit): SourceException {
        try {
            block()
        } catch (error: SourceException) {
            return error
        }
        throw AssertionError("Expected a SourceException")
    }

    private fun channel(key: String) = Channel("la7-$key", key, 7, "", "", "", "", "la7", key)

    private class FakeTransport(private val page: String, private val tokenResponse: String) : Transport {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            requests += url to headers.toMap()
            return when (url.toHttpUrl().host) {
                "www.la7play.it" -> page
                "token.example" -> tokenResponse
                else -> throw AssertionError("Unexpected request host")
            }
        }

        override suspend fun post(url: String, data: JSONObject, headers: Map<String, String>): String =
            throw AssertionError("La7 authorization uses GET")
    }
}
