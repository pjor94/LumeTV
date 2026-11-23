package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kxml2.io.KXmlParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EpgShareTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private val now=System.currentTimeMillis()
    private val date=DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
    private fun channel(id:String,name:String,logo:String="")="""<channel id="$id"><display-name>$name</display-name><icon src="$logo"/></channel>"""
    private fun show(id:String,title:String,start:Long=now-60_000,end:Long=now+3_600_000)=
        """<programme channel="$id" start="${date.format(Instant.ofEpochMilli(start))}" stop="${date.format(Instant.ofEpochMilli(end))}"><title>$title</title></programme>"""
    private fun xml(time:Long=now)="<tv>"+channel("Sky.Uno.it","Sky Uno","https://guidatv.sky.it/logo/uno.png")+show("Sky.Uno.it","Programma Sky",time-60_000,time+3_600_000)+"</tv>"
    private fun gzip(xml:String)=ByteArrayOutputStream().also {out->GZIPOutputStream(out).use {it.write(xml.toByteArray())}}.toByteArray()
    private fun install(feed:PublicGuideFeed,xml:String) {
        val store=CatalogGuideStore(app.filesDir,feed)
        store.stage(xml.byteInputStream(),emptySet(),now,KXmlParser()).use {store.commit(it)}
    }
    private fun entry(id:String="SkyUno.it",name:String="Sky Uno")=PlaylistEntry(name,"https://media.example.com/live.m3u8",tvgId=id)
    private fun valid(time:Long=now)=MockResponse().setBody(Buffer().write(gzip(xml(time)))).addHeader("ETag","\"public-v1\"")
        .addHeader("Last-Modified","Wed, 18 Nov 2026 05:38:00 GMT")
    @Before fun reset() {
        listOf("personal-playlists","personal-guides").forEach {File(app.filesDir,it).deleteRecursively()}
        for(feed in PublicGuideFeed.entries)for(suffix in listOf("",".bak","-journal"))File(app.filesDir,feed.filename+suffix).delete()
        app.getSharedPreferences("epgshare_guide",Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `secondary schedules fill only channels without primary programmes across the retained horizon`() {
        install(PublicGuideFeed.LUME,"<tv>"+channel("SkyUno.it","Sky Uno")+show("SkyUno.it","Lume",now+7_200_000,now+10_800_000)+"</tv>")
        install(PublicGuideFeed.EPGSHARE,"<tv>"+channel("Sky.Uno.it","Sky Uno","https://guidatv.sky.it/logo/uno.png")+
            show("Sky.Uno.it","Solo supplemento")+show("Sky.Uno.it","Domani supplemento",now+86_400_000,now+90_000_000)+"</tv>")
        val fallback=PublicGuideFallback(app.filesDir);val entry=entry()
        assertTrue(fallback.window(entry,now,now+3_600_000,now).isEmpty())
        assertTrue(fallback.onAir(mapOf("sky" to entry), now).isEmpty())
        assertTrue(fallback.window(entry,now+86_400_000,now+90_000_000,now).isEmpty())
        assertEquals(0,fallback.search(entry,"supplemento",now,now+CatalogGuideStore.FUTURE,0,now).second)
        assertEquals("lume",fallback.window(entry,now,now+CatalogGuideStore.FUTURE,now).single().guideOrigin)
        assertEquals(listOf("https://guidatv.sky.it/logo/uno.png"),fallback.logos(entry,emptyList(),now))
        assertEquals(listOf("https://primary.example.com/logo.png"),fallback.logos(entry,listOf("https://primary.example.com/logo.png"),now))
        install(PublicGuideFeed.LUME,"<tv>"+channel("unknown","Canale diverso")+show("unknown","Altro")+"</tv>")
        val shows=fallback.window(entry,now,now+3_600_000,now)
        assertEquals("Solo supplemento",shows.single().title);assertEquals("epgshare",shows.single().guideOrigin)
        val live = fallback.onAir(mapOf("sky" to entry), now).getValue("sky")
        assertEquals("Solo supplemento", live.title)
        assertEquals("epgshare", live.guideOrigin)
        assertTrue(live.description.isBlank())
        assertFalse(shows.single().replayAvailable);assertTrue(shows.single().sourceId.isBlank());assertTrue(shows.single().eventId.isBlank())
    }

    @Test fun `conflicting primary identities do not fall through to a weaker secondary match`() {
        install(PublicGuideFeed.LUME,"<tv>"+channel("first","Sky Uno")+channel("second","Sky Uno")+show("first","Uno")+show("second","Due")+"</tv>")
        install(PublicGuideFeed.EPGSHARE,xml())
        val fallback=PublicGuideFallback(app.filesDir)
        assertTrue(fallback.window(entry(),now,now+3_600_000,now).isEmpty())
        assertTrue(fallback.onAir(mapOf("sky" to entry()), now).isEmpty())
        assertTrue(fallback.logos(entry(),emptyList(),now).isEmpty())
        install(PublicGuideFeed.LUME,"<tv>"+channel("rai3.it","Rai 3")+show("rai3.it","Nazionale")+"</tv>")
        val regional=entry("rai3.it","Rai 3 TGR Puglia")
        assertTrue(fallback.window(regional,now,now+3_600_000,now).isEmpty())
    }

    @Test fun `verified desktop aliases require the identifier and expected name and preserve shifts`() {
        val channels=listOf("Eurosport.Italia.it" to "Eurosport Italia","Eurosport.2.Italia.it" to "Eurosport 2 Italia",
            "Solocalcio.it.it" to "Solocalcio","Sky.Uno.+..it" to "Sky Uno +","Sky.Uno.+.it" to "Sky Uno +")
        install(PublicGuideFeed.EPGSHARE,"<tv>"+channels.joinToString(""){channel(it.first,it.second)}+channels.joinToString(""){show(it.first,it.first)}+"</tv>")
        val fallback=PublicGuideFallback(app.filesDir)
        val expected=listOf(Triple("Eurosport1.it","Eurosport 1 HD","Eurosport.Italia.it"),Triple("DaznEurosport2.it","DAZN Eurosport 2","Eurosport.2.Italia.it"),
            Triple("SoloCalcioSportitaliaFonte1.it","Solo Calcio | Sportitalia ( Fonte 1 )","Solocalcio.it.it"),
            Triple("SkyUnoPlus.it","Sky Uno Plus","Sky.Uno.+..it"),Triple("Sky.Uno.+.it","Sky Uno +","Sky.Uno.+.it"))
        for((id,name,title) in expected)assertEquals(title,fallback.window(entry(id,name),now,now+3_600_000,now).single().title)
        for((id,name) in listOf("Eurosport1.fr" to "Eurosport 1","Eurosport1.it" to "Eurosport 360 1","SkyUnoPlus.it" to "Sky Uno +1","" to "Sky Uno +"))
            assertTrue("$id $name",fallback.window(entry(id,name),now,now+3_600_000,now).isEmpty())
    }

    @Test fun `provider configuration manual exclusions disabled guides and VOD suppress the secondary fallback`()=runTest {
        install(PublicGuideFeed.EPGSHARE,xml())
        val repo=PersonalPlaylistRepository.get(app)
        val source=repo.prepareFile("#EXTM3U\n#EXTINF:-1 tvg-id=\"SkyUno.it\",Sky Uno\nhttps://media.example.com/live.m3u8".toByteArray()).use {repo.save("Sky",it)}
        val channel=repo.local().single();val guide=PersonalGuideRepository.get(app)
        assertEquals("epgshare",guide.window(listOf(channel),now,now+3_600_000)[channel.epgId]?.single()?.guideOrigin)
        guide.map(source,channel.id,-1);assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty())
        guide.map(source,channel.id,null)
        repo.setSettings(source,PlaylistSettings(guide=PersonalGuideSettings(urls=listOf("https://provider.example.com/guide.xml"))))
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty())
        repo.setSettings(source,PlaylistSettings(guide=PersonalGuideSettings(enabled=false)))
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty())
        repo.setSettings(source,PlaylistSettings(contentOverride=PlaylistContentKind.VOD))
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty())
    }

    @Test fun `gzip conditional requests exclude cookies and refresh the retained window after twelve hours`()=runBlocking {
        MockWebServer().use {server->server.start()
            var time=now
            val cookie=Cookie.Builder().name("private").value("secret").hostOnlyDomain(server.hostName).build()
            val client=OkHttpClient.Builder().cookieJar(object:CookieJar {
                override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>){}
                override fun loadForRequest(url:HttpUrl)=listOf(cookie)
            }).build()
            val repo=EpgShareRepository(app,client,server.url("/guide.xml.gz").toString()){time}
            server.enqueue(valid(time));repo.refresh(true)
            val request=server.takeRequest(5,TimeUnit.SECONDS)!!
            for(header in listOf("Cookie","Authorization","Referer","X-Guide","X-Logo"))assertNull(request.getHeader(header))
            assertEquals("identity",request.getHeader("Accept-Encoding"));assertTrue(request.getHeader("User-Agent")!!.startsWith("LumeTV/"))
            assertEquals(time,repo.updated)
            time+=31_000;server.enqueue(MockResponse().setResponseCode(304));repo.refresh(true)
            val conditional=server.takeRequest(5,TimeUnit.SECONDS)!!
            assertEquals("\"public-v1\"",conditional.getHeader("If-None-Match"));assertNotNull(conditional.getHeader("If-Modified-Since"))
            assertEquals(now,repo.updated)
            time=now+EpgShareRepository.REINDEX_AFTER+1;server.enqueue(valid(time));repo.refresh(true)
            assertNull(server.takeRequest(5,TimeUnit.SECONDS)!!.getHeader("If-None-Match"));assertEquals(time,repo.updated)
            assertTrue(CatalogGuideStore(app.filesDir,PublicGuideFeed.EPGSHARE).ready(time))
        }
    }

    @Test fun `rate limits survive repository restart and invalid gzip or XML never replace valid data`()=runBlocking {
        MockWebServer().use {server->server.start()
            var time=now;val endpoint=server.url("/guide.xml.gz").toString()
            var repo=EpgShareRepository(app,OkHttpClient(),endpoint){time}
            server.enqueue(valid());repo.refresh(true);server.takeRequest()
            val store=CatalogGuideStore(app.filesDir,PublicGuideFeed.EPGSHARE)
            val before=store.window(entry(),now,now+3_600_000)
            time+=31_000;server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After","120"));repo.refresh(true);server.takeRequest()
            assertEquals("rate_limit",repo.failure);assertEquals(time+120_000,repo.nextRetry)
            repo=EpgShareRepository(app,OkHttpClient(),endpoint){time};repo.refresh(true);assertEquals(2,server.requestCount)
            time+=121_000;server.enqueue(MockResponse().setBody("not gzip"));repo.refresh(true);server.takeRequest()
            assertEquals(before,store.window(entry(),now,now+3_600_000));assertEquals("unavailable",repo.failure)
            time+=301_000;server.enqueue(MockResponse().setBody(Buffer().write(gzip("<tv><channel id=\"broken\">"))));repo.refresh(true);server.takeRequest()
            assertEquals(before,store.window(entry(),now,now+3_600_000))
            time+=301_000;server.enqueue(MockResponse().setBody(Buffer().write(gzip(xml()).dropLast(12).toByteArray())));repo.refresh(true);server.takeRequest()
            assertEquals(before,store.window(entry(),now,now+3_600_000))
            assertTrue(app.filesDir.listFiles().orEmpty().none {it.name.startsWith(".catalog-guide-")})
        }
    }

    @Test fun `cross origin redirects and unsolicited 304 responses are rejected`()=runBlocking {
        MockWebServer().use {server->server.start()
            var time=now
            val repo=EpgShareRepository(app,OkHttpClient(),server.url("/guide.xml.gz").toString()){time}
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","https://other.example.com/guide.xml.gz"));repo.refresh(true)
            assertEquals(1,server.requestCount);assertEquals(0L,repo.updated)
            time+=301_000;server.enqueue(MockResponse().setResponseCode(304));repo.refresh(true)
            assertEquals(2,server.requestCount);assertEquals(0L,repo.updated);assertEquals("unavailable",repo.failure)
        }
    }

    @Test fun `cancelled downloads leave the prior public index and stop promptly`()=runBlocking {
        MockWebServer().use {server->server.start()
            var time=now
            val repo=EpgShareRepository(app,OkHttpClient(),server.url("/guide.xml.gz").toString()){time}
            server.enqueue(valid());repo.refresh(true);server.takeRequest()
            time+=31_000;server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val task=launch(Dispatchers.IO){repo.refresh(true)}
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS))
            withTimeout(2_000){task.cancelAndJoin()}
            assertEquals(now,repo.updated);assertTrue(CatalogGuideStore(app.filesDir,PublicGuideFeed.EPGSHARE).ready(now))
        }
    }

    @Test fun `primary download failure does not prevent the independent supplement from refreshing`()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(valid())
            val repo=PersonalPlaylistRepository.get(app)
            repo.prepareFile("#EXTM3U\n#EXTINF:-1 tvg-id=\"SkyUno.it\",Sky Uno\nhttps://media.example.com/live.m3u8".toByteArray()).use {repo.save("Sky",it)}
            val primary=OkHttpClient.Builder().addInterceptor {chain->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("Rate limit")
                    .body(ByteArray(0).toResponseBody()).build()
            }.build()
            val extra=EpgShareRepository(app,OkHttpClient(),server.url("/guide.xml.gz").toString())
            val guide=GuideRepository(app,HttpClient(primary),supplementRefresh={extra.refresh(it)})
            val builtin=Channel("rai1","Rai 1",1,"rai1","","","","rai","rai1")
            val personal=repo.local().single()
            val result=guide.refresh(listOf(builtin,personal),true)!!
            assertEquals("epgshare",result[personal.epgId]?.single()?.guideOrigin)
            assertEquals("rate_limit",guide.failure);assertTrue(extra.updated>0)
        }
    }

    @Test fun `optional replay imports the downloaded public feed through the Android index`() {
        val path=System.getenv("LUME_EPGSHARE_REPLAY").orEmpty()
        org.junit.Assume.assumeTrue(path.isNotBlank())
        val store=CatalogGuideStore(app.filesDir,PublicGuideFeed.EPGSHARE)
        java.util.zip.GZIPInputStream(File(path).inputStream()).use {input->
            store.stage(input,emptySet(),now,KXmlParser()).use {store.commit(it)}
        }
        assertTrue(store.ready(now))
        assertTrue(store.probe(entry(),now).hasProgrammes)
        assertTrue(store.probe(entry("SkyCinemaUno.it","Sky Cinema Uno"),now).hasProgrammes)
    }
}
