package it.lumetv.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class PersonalPlaylistNetworkTest {
    @Test fun `retry after supports both seconds and HTTP dates`() {
        val now = java.time.Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()
        assertEquals(now + 60_000, retryAfter("60", now))
        assertEquals(now + 60_000, retryAfter("Wed, 11 Nov 2026 10:01:00 GMT", now))
        assertEquals(0, retryAfter("invalid", now))
        assertEquals(0, retryAfter("-1", now))
    }
    @Test fun `cross origin redirects and child requests cannot forward authentication`() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            second.enqueue(MockResponse().setBody("ok")); second.enqueue(MockResponse().setBody("child"))
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/final")))
            val root = first.url("/master").toString()
            val client = PersonalPlaylistNetwork.client(OkHttpClient(), root, setOf("Authorization", "Cookie", "Origin")) { true }
            val request = Request.Builder().url(root).header("Authorization", "secret").header("Cookie", "secret-cookie")
                .header("Origin", "https://private.example").build()
            client.newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
            assertEquals("secret", first.takeRequest().getHeader("Authorization"))
            val redirected = second.takeRequest()
            assertNull(redirected.getHeader("Authorization")); assertNull(redirected.getHeader("Cookie")); assertNull(redirected.getHeader("Origin"))
            client.newCall(request.newBuilder().url(second.url("/child")).build()).execute().close()
            assertNull(second.takeRequest().getHeader("Authorization"))
        } }
    }

    @Test fun `the HTTPS policy blocks downgrade redirects before their target is requested`() {
        assertTrue(PersonalPlaylistNetwork.allowed(URI("https://example.com/live")))
        assertFalse(PersonalPlaylistNetwork.allowed(URI("http://example.com/live")))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://blocked.invalid/secret"))
            val root = server.url("/list").toString()
            val client = PersonalPlaylistNetwork.client(OkHttpClient(), root) { it.host == "localhost" }
            try { client.newCall(Request.Builder().url(root).build()).execute().close(); fail("Downgrade accepted") }
            catch (expected: java.io.IOException) { assertFalse(expected.message.orEmpty().contains("secret")) }
            assertEquals(1, server.requestCount)
        }
    }
}
