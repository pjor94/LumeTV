package it.lumetv.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class LogoFetcherTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==")
    private fun fetcher(server: MockWebServer) = LogoFetcher(OkHttpClient(),
        allowed = { it.host == server.hostName && it.port == server.port },
        redirectAllowed = { _, to -> to.host == server.hostName && to.port == server.port },
        addressAllowed = { it.isLoopbackAddress })

    @Test fun `cache validators reach the image behind an approved redirect`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual.png"))
            server.enqueue(MockResponse().setResponseCode(304))
            assertNull(fetcher(server).fetch(server.url("/logo.png"), "logo-v1").bytes)
            server.takeRequest()
            assertEquals("logo-v1", server.takeRequest().getHeader("If-None-Match"))
        }
    }

    @Test fun `download preserves original image bytes and cache validators`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(png)).setHeader("ETag", "logo-v1").setHeader("Last-Modified", "Tue, 27 Oct 2026 12:00:00 GMT"))
            val result = fetcher(server).fetch(server.url("/logo.png"))
            assertArrayEquals(png, result.bytes)
            assertEquals("logo-v1", result.etag)
            assertTrue(server.takeRequest().getHeader("User-Agent")!!.startsWith("WLTV "))
        }
    }

    @Test fun `not modified retains cache metadata without downloading the image`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(304))
            val result = fetcher(server).fetch(server.url("/logo.png"), "logo-v1", "last-date")
            assertNull(result.bytes)
            assertEquals("logo-v1", result.etag)
            val request = server.takeRequest()
            assertEquals("logo-v1", request.getHeader("If-None-Match"))
            assertEquals("last-date", request.getHeader("If-Modified-Since"))
        }
    }

    @Test fun `redirect target is checked before any request reaches another server`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                other.start()
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/logo.png")))
                try { fetcher(server).fetch(server.url("/logo.png")); fail("Unexpected redirect accepted") }
                catch (_: LogoDownloadException) { assertEquals(0, other.requestCount) }
            }
        }
    }

    @Test fun `HTML masquerading as a PNG is rejected`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("<html>not artwork</html>"))
            try { fetcher(server).fetch(server.url("/logo.png")); fail("HTML accepted") }
            catch (_: LogoDownloadException) { }
        }
    }

    @Test fun `oversized responses are rejected before decoding`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(LogoPolicy.MAX_BYTES + 1))))
            try { fetcher(server).fetch(server.url("/logo.png")); fail("Oversized image accepted") }
            catch (_: LogoDownloadException) { }
        }
    }

    @Test fun `redirect loops have a bounded request count`() = runBlocking {
        MockWebServer().use { server ->
            repeat(4) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/logo.png")) }
            try { fetcher(server).fetch(server.url("/logo.png")); fail("Redirect loop accepted") }
            catch (_: LogoDownloadException) { assertEquals(4, server.requestCount) }
        }
    }
}
