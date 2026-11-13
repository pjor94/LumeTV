package it.lumetv.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

    @Test fun `license redirects honor see other and temporary redirect request semantics`() {
        for (code in listOf(303,307)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).addHeader("Location",server.url("/license")))
            server.enqueue(MockResponse().setBody("synthetic response"))
            val root=server.url("/start").toString()
            val client=PersonalPlaylistNetwork.client(OkHttpClient(),root,policy={true})
            val request=Request.Builder().url(root).header("Content-Type","application/json").post("synthetic challenge".toRequestBody()).build()
            client.newCall(request).execute().use { assertTrue(it.isSuccessful) }
            assertEquals("POST",server.takeRequest().method)
            val next=server.takeRequest()
            assertEquals(if(code==303)"GET" else "POST",next.method)
            assertEquals(if(code==303)"" else "synthetic challenge",next.body.readUtf8())
            if(code==303)assertNull(next.getHeader("Content-Type"))
        }
    }

    @Test fun `HTTP consent bypasses only the built in gate while HTTPS remains the default`() {
        assertFalse(PersonalPlaylistNetwork.allowed(URI("http://example.com/list")))
        assertTrue(PersonalPlaylistNetwork.allowed(URI("http://example.com/list"),true))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val base=OkHttpClient.Builder().addNetworkInterceptor(BuiltinNetworkPolicy).build()
            val root=server.url("/list").toString()
            try { PersonalPlaylistNetwork.client(base,root).newCall(Request.Builder().url(root).build()).execute().close();fail("HTTP allowed") }
            catch(_:java.io.IOException) { assertEquals(0,server.requestCount) }
            PersonalPlaylistNetwork.client(base,root,policy={ PersonalPlaylistNetwork.allowed(it,true) }).newCall(Request.Builder().url(root).build()).execute().use { assertEquals("ok",it.body!!.string()) }
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun `revoked consent blocks an HTTP playlist redirect already waiting on the server`() = kotlinx.coroutines.test.runTest {
        MockWebServer().use { server ->
            val allowed=java.util.concurrent.atomic.AtomicBoolean(true)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    allowed.set(false)
                    return MockResponse().setResponseCode(302).addHeader("Location",server.url("/after-revoke"))
                }
            }
            val fetcher=PersonalPlaylistNetwork.fetcher(OkHttpClient(),allowHttp=true,httpAllowed=allowed::get)
            try { fetcher.fetch(server.url("/list").toString(),null,null).close();fail("Redirect fetched after consent revocation") }
            catch(_:java.io.IOException) { assertEquals(1,server.requestCount) }
        }
    }
    @Test fun `built in HTTP exceptions do not expand with the source consent feature`() {
        fun url(value:String)=okhttp3.HttpUrl.Builder().scheme("http").host(value).build()
        assertTrue(BuiltinNetworkPolicy.allowed(url("api.epg-guide.com")))
        assertTrue(BuiltinNetworkPolicy.allowed(url("live.streaming.bitonlive.net")))
        assertFalse(BuiltinNetworkPolicy.allowed(url("example.com")))
        assertFalse(BuiltinNetworkPolicy.allowed(url("api.epg-guide.com.evil.example")))
    }
    @Test fun `the byte limit applies to the stream without allocating a document`() {
        var remaining=it.lumetv.core.PersonalM3uParser.MAX_BYTES.toLong()+1
        val input=object:java.io.InputStream() {
            override fun read():Int=if(remaining-->0)65 else -1
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                if(remaining==0L)return -1
                val n=minOf(remaining,len.toLong()).toInt();remaining-=n;return n
            }
        }
        try { LimitedPlaylistInput(input).copyTo(java.io.OutputStream.nullOutputStream());fail("Size limit ignored") }
        catch(error:java.io.IOException) { assertTrue(error.message.orEmpty().contains("256 MiB")) }
    }
}
