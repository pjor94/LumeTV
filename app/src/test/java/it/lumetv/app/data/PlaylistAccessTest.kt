package it.lumetv.app.data

import it.lumetv.core.PlaylistFormatException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class PlaylistAccessTest {
    @Test fun `settings validate origins transport headers and private serialization`() {
        val settings=PlaylistSettings(access=mapOf(PlaylistResource.PLAYLIST to PlaylistAccessRule(mapOf("authorization" to "private-fixture"),setOf("https://CDN.example.com/"))),sessionCookies=true)
            .validated()
        assertEquals(setOf("https://cdn.example.com:443"),settings.rule(PlaylistResource.PLAYLIST).origins)
        assertEquals(settings,PlaylistSettings.read(settings.json()))
        assertFalse(settings.toString().contains("private-fixture"));assertFalse(settings.access.toString().contains("private-fixture"))
        for(server in listOf("https://user:pass@example.com","https://example.com/path","https://example.com?token=x","file:///tmp/file")) {
            try {PlaylistSettings.canonicalOrigin(server);fail("Invalid origin accepted")}catch(_:PlaylistFormatException){}
        }
        try {PlaylistSettings.parseHeaders("Host: example.com");fail("Transport override accepted")}catch(_:PlaylistFormatException){}
        try {PlaylistSettings.parseHeaders("User-Agent: Èsempio");fail("Header rejected by HTTP transport accepted at validation")}catch(_:PlaylistFormatException){}
    }

    @Test fun `explicitly trusted CDN receives scoped headers while another role does not`() {
        MockWebServer().use {first->MockWebServer().use {second->
            repeat(2){first.enqueue(MockResponse().setResponseCode(302).addHeader("Location",second.url("/child")));second.enqueue(MockResponse().setBody("ok"))}
            val settings=PlaylistSettings(access=mapOf(PlaylistResource.MEDIA to PlaylistAccessRule(origins=setOf(second.url("/").toString())))).validated()
            val context=PersonalRequestContext(settings,true,first.url("/root").toString())
            val request=Request.Builder().url(first.url("/root")).header("Authorization","synthetic").header("X-Token","synthetic-token").build()
            context.client(OkHttpClient(),request.url.toString(),PlaylistResource.MEDIA,setOf("Authorization","X-Token")).newCall(request).execute().close()
            assertEquals("synthetic",second.takeRequest().getHeader("Authorization"));first.takeRequest()
            context.client(OkHttpClient(),request.url.toString(),PlaylistResource.MANIFEST,setOf("Authorization","X-Token")).newCall(request).execute().close()
            val other=second.takeRequest();assertNull(other.getHeader("Authorization"));assertNull(other.getHeader("X-Token"))
        }}
    }

    @Test fun `cookies are isolated by source role and explicit header wins`() {
        MockWebServer().use {server->
            server.enqueue(MockResponse().addHeader("Set-Cookie","session=source-a; Path=/").setBody("ok"))
            repeat(5){server.enqueue(MockResponse().setBody("ok"))}
            val settings=PlaylistSettings(sessionCookies=true)
            val root=server.url("/root").toString()
            val a=PersonalRequestContext(settings,true,root);val b=PersonalRequestContext(settings,true,root)
            fun call(context:PersonalRequestContext,role:PlaylistResource,cookie:String?=null) {
                val request=Request.Builder().url(root).apply {cookie?.let {header("Cookie",it)}}.build()
                context.client(OkHttpClient(),root,role,setOf("Cookie")).newCall(request).execute().close()
            }
            call(a,PlaylistResource.PLAYLIST);server.takeRequest()
            call(a,PlaylistResource.PLAYLIST);assertEquals("session=source-a",server.takeRequest().getHeader("Cookie"))
            call(a,PlaylistResource.MEDIA);assertNull(server.takeRequest().getHeader("Cookie"))
            call(b,PlaylistResource.PLAYLIST);assertNull(server.takeRequest().getHeader("Cookie"))
            call(a,PlaylistResource.PLAYLIST,"explicit=chosen");assertEquals("explicit=chosen",server.takeRequest().getHeader("Cookie"))
            call(PersonalRequestContext(PlaylistSettings(),true,root),PlaylistResource.PLAYLIST);assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun `a retained POST body requires explicit trust before crossing an origin`() {
        MockWebServer().use {first->MockWebServer().use {second->
            val root=first.url("/license").toString()
            first.enqueue(MockResponse().setResponseCode(307).addHeader("Location",second.url("/license")))
            first.enqueue(MockResponse().setResponseCode(307).addHeader("Location",second.url("/license")))
            val request=Request.Builder().url(root).post("private-fixture-body".toRequestBody()).build()
            try {PersonalPlaylistNetwork.client(OkHttpClient(),root,policy={true}).newCall(request).execute().close();fail("Body crossed untrusted origin")}
            catch(_:java.io.IOException){assertEquals(0,second.requestCount)}
            second.enqueue(MockResponse().setBody("ok"))
            val trusted=setOf(PlaylistSettings.canonicalOrigin(second.url("/").toString()))
            PersonalPlaylistNetwork.client(OkHttpClient(),root,trustedOrigins=trusted,policy={true}).newCall(request).execute().close()
            assertEquals("private-fixture-body",second.takeRequest().body.readUtf8())
        }}
    }

    @Test fun `invalidating a source cancels an active response and future requests`() {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("x".repeat(4096)).throttleBody(1,100,TimeUnit.MILLISECONDS))
            val root=server.url("/video").toString();val context=PersonalRequestContext(PlaylistSettings(),true,root)
            val client=context.client(OkHttpClient(),root,PlaylistResource.MEDIA,emptySet())
            val response=client.newCall(Request.Builder().url(root).build()).execute()
            context.invalidate()
            try {response.use {it.body!!.bytes()};fail("Active stream survived invalidation")}catch(_:java.io.IOException){}
            try {client.newCall(Request.Builder().url(root).build()).execute().close();fail("Invalid context reused")}catch(_:java.io.IOException){}
            assertEquals(1,server.requestCount)
        }
    }

    @Test fun `root download headers remain scoped and HTTP artwork is a separate permission`() = runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1,Uno\nhttps://example.com/live"))
            val root=server.url("/list").toString()
            val settings=PlaylistSettings(access=mapOf(PlaylistResource.PLAYLIST to PlaylistAccessRule(mapOf("Authorization" to "synthetic","User-Agent" to "Fixture/ABC"))))
            val context=PersonalRequestContext(settings,true,root)
            PersonalPlaylistNetwork.fetcher(OkHttpClient(),context=context).fetch(root,null,null).use {it.reader().readText()}
            val request=server.takeRequest();assertEquals("synthetic",request.getHeader("Authorization"));assertEquals(listOf("Fixture/ABC"),request.headers.values("User-Agent"))
            assertNull(PersonalLogoPolicy.url("http://artwork.example.com:8080/logo.png",false))
            assertNotNull(PersonalLogoPolicy.url("http://artwork.example.com:8080/logo.png",true))
            assertNull(PersonalLogoPolicy.url("https://127.0.0.1/logo.png",true));assertNull(PersonalLogoPolicy.url("http://10.0.2.2/logo.png",true))
        }
    }

    @Test fun `personal artwork uses its own header context and keeps the DNS guard`() = runBlocking {
        MockWebServer().use {server->
            val body=okio.Buffer().write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10,0,0,0,0))
            server.enqueue(MockResponse().setBody(body))
            val url=server.url("/logo.png").newBuilder().host("artwork.example.com").build()
            val base=OkHttpClient.Builder().dns(object:Dns {override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1"))}).build()
            val context=PersonalRequestContext(PlaylistSettings(artworkHttp=true),false,"https://playlist.example.com")
            val client=context.client(base,url.toString(),PlaylistResource.LOGO,setOf("X-Logo"))
            try {LogoFetcher(client,allowed={true}).fetch(url);fail("Artwork DNS guard bypassed")}catch(_:java.io.IOException){}
            assertEquals(0,server.requestCount)
            LogoFetcher(client,allowed={true},addressAllowed={true}).fetch(url,headers=mapOf("X-Logo" to "synthetic-logo"))
            assertEquals("synthetic-logo",server.takeRequest().getHeader("X-Logo"))
        }
    }
}
