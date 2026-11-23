package it.lumetv.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import it.lumetv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kxml2.io.KXmlParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CatalogGuideFallbackTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private val now=System.currentTimeMillis()
    private val date=DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)
    private val store get()=CatalogGuideStore(app.filesDir)
    private fun channel(id:String,name:String=id,logo:String="https://public.example.com/$id.png")=
        """<channel id="$id"><display-name>$name</display-name><icon src="$logo"/></channel>"""
    private fun show(id:String,title:String="Programma Lume",start:Long=now-1_800_000,end:Long=now+1_800_000)=
        """<programme channel="$id" start="${date.format(Instant.ofEpochMilli(start))}" stop="${date.format(Instant.ofEpochMilli(end))}"><title lang="it">$title</title><desc>Descrizione Lume</desc></programme>"""
    private fun feed(id:String="Extra.it",name:String="Canale Extra",title:String="Programma Lume")=
        "<tv>"+channel("rai1","Rai 1")+channel(id,name)+show("rai1")+show(id,title)+"</tv>"
    private fun install(xml:String=feed()) {
        store.stage(xml.byteInputStream(),setOf("rai1"),now,KXmlParser()).use {store.commit(it)}
    }
    private suspend fun source(text:String="""#EXTM3U
#EXTINF:-1 tvg-id="Extra.it",Canale Extra
https://media.example.com/live.m3u8""",settings:PlaylistSettings=PlaylistSettings()):String {
        val repo=PersonalPlaylistRepository.get(app)
        repo.local()
        return repo.prepareFile(text.toByteArray(),settings=settings).use {repo.save("Personale ${repo.state.value.size+1}",it)}
    }
    private suspend fun item(id:String)=PersonalPlaylistRepository.get(app).local().first {it.sourceId==id}
    @Before fun reset() {
        listOf("personal-playlists","personal-guides","channel-artwork").forEach {File(app.filesDir,it).deleteRecursively()}
        listOf("catalog-guide.sqlite","catalog-guide.sqlite.bak","guide.json").forEach {File(app.filesDir,it).delete()}
        app.getSharedPreferences("guide",Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `the complete public feed covers a personal channel beyond both catalog previews and the old logo cap`()=runTest {
        val count=720
        val xml="<tv>"+channel("rai1","Rai 1")+(0 until count).joinToString(""){channel("extra$it.it","Extra $it")}+
            show("rai1")+(0 until count).joinToString(""){show("extra$it.it","Extra programma $it")}+"</tv>"
        val zipped=ByteArrayOutputStream().also {out->XZOutputStream(out,LZMA2Options()).use {it.write(xml.toByteArray())}}.toByteArray()
        var requests=0
        val client=OkHttpClient.Builder().addInterceptor {chain->
            assertEquals("api.epg-guide.com",chain.request().url.host);requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(zipped.toResponseBody()).build()
        }.build()
        val id=source("#EXTM3U\n"+(0 until count).joinToString("\n") {"#EXTINF:-1 tvg-id=\"extra$it.it\",Extra $it\nhttps://media.example.com/$it.m3u8"})
        val repo=PersonalPlaylistRepository.get(app)
        val target=repo.catalog(PersonalCatalogQuery(source=id,text="Extra 719")).rows.single()
        val builtin=Channel("builtin","Rai 1",1,"rai1","","","","rai","rai1")
        val guide=GuideRepository(app,HttpClient(client),supplementRefresh={})
        guide.refresh(listOf(builtin,target),true)
        val result=guide.window(listOf(target),now,now+3_600_000)
        assertEquals("Extra programma 719",result[target.epgId]?.single()?.title)
        assertEquals("https://public.example.com/extra719.it.png",store.logo(repo.playbackEntry(target.id).entry))
        assertEquals(1,requests)
        assertEquals(listOf("Extra programma 719"),guide.personal.search(target,"programma").first.map {it.title})
        assertEquals(0,guide.personal.search(target,"%programma%").second)
        val exported=File(app.filesDir,"guide.json").readText()
        assertFalse(exported.contains("extra719"));assertFalse(exported.contains(id));assertFalse(exported.contains(target.id))
        assertTrue(File(app.filesDir,"catalog-guide.sqlite").length()>0)
    }

    @Test fun `a supplied guide retains priority and its last valid schedule during failure`()=runTest {
        install()
        MockWebServer().use {server->server.start()
            val id=source("#EXTM3U url-tvg=\"${server.url("/private.xml")}\"\n#EXTINF:-1 tvg-id=\"Extra.it\",Canale Extra\nhttps://media.example.com/live.m3u8",
                PlaylistSettings(guide=PersonalGuideSettings(allowHttp=true)))
            val guide=PersonalGuideRepository.get(app);val channel=item(id)
            server.enqueue(MockResponse().setBody(feed(title="Programma del fornitore")));guide.refresh(true,id);server.takeRequest()
            assertEquals("Programma del fornitore",guide.window(listOf(channel),now,now+3_600_000)[channel.epgId]?.single()?.title)
            server.enqueue(MockResponse().setResponseCode(503));guide.refresh(true,id);server.takeRequest()
            assertEquals("Programma del fornitore",guide.window(listOf(channel),now,now+3_600_000)[channel.epgId]?.single()?.title)
            assertTrue(guide.state.value.single().error.isNotBlank())
        }
    }

    @Test fun `an explicitly configured or entry level guide is never silently replaced by the public guide`()=runTest {
        install()
        val configured=source(settings=PlaylistSettings(guide=PersonalGuideSettings(urls=listOf("https://provider.example.com/guide.xml"))))
        val individual=source("#EXTM3U\n#EXTINF:-1 tvg-id=\"Extra.it\" url-tvg=\"https://provider.example.com/guide.xml\",Canale Extra\nhttps://media.example.com/other.m3u8")
        val guide=PersonalGuideRepository.get(app)
        for(id in listOf(configured,individual))assertTrue(guide.window(listOf(item(id)),now,now+3_600_000).isEmpty())
    }

    @Test fun `disabled guides manual exclusions VOD and removed lists do not regain schedules from fallback`()=runTest {
        install()
        val repo=PersonalPlaylistRepository.get(app);val id=source();val channel=item(id)
        val guide=PersonalGuideRepository.get(app)
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isNotEmpty())
        guide.map(id,channel.id,-1)
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty());assertEquals(0,guide.search(channel,"").second)
        guide.map(id,channel.id,null)
        repo.setSettings(id,PlaylistSettings(guide=PersonalGuideSettings(enabled=false)))
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty())
        repo.setSettings(id,PlaylistSettings(contentOverride=PlaylistContentKind.VOD))
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty());assertNull(repo.fallbackArtwork(channel))
        repo.setSettings(id,PlaylistSettings());repo.setEnabled(id,false)
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty())
        repo.setEnabled(id,true);repo.delete(id)
        assertTrue(guide.window(listOf(channel),now,now+3_600_000).isEmpty())
    }

    @Test fun `matching uses exact ids or unique full names while preserving regional and timeshift distinctions`() {
        install("<tv>"+channel("rai1","Rai 1")+channel("one.it","Nome comune")+channel("two.it","Nome comune")+
            channel("regional.it","Rai 3 TGR Lazio")+channel("plus.it","Extra +1")+
            show("rai1")+show("one.it","Uno")+show("two.it","Due")+show("regional.it","Regione")+show("plus.it","Differita")+"</tv>")
        fun entry(name:String,tvg:String="",tvgName:String="")=PlaylistEntry(name,"https://media.example.com/live",tvgId=tvg,metadata=PlaylistMetadata(attributes=mapOf("tvg-name" to tvgName)))
        assertEquals("Uno",store.window(entry("Nome comune","ONE.IT"),now,now+3_600_000).single().title)
        assertTrue(store.window(entry("Nome comune"),now,now+3_600_000).isEmpty())
        assertEquals("Regione",store.window(entry("Nome lista",tvgName="Rai_3 TGR Lazio"),now,now+3_600_000).single().title)
        assertTrue(store.window(entry("Rai 3 TGR Veneto"),now,now+3_600_000).isEmpty())
        assertTrue(store.window(entry("Extra"),now,now+3_600_000).isEmpty())
        assertEquals("Differita",store.window(entry("Extra +1"),now,now+3_600_000).single().title)
    }

    @Test fun `malformed or cancelled imports keep the public cache and leave no draft files`() {
        install()
        val entry=PlaylistEntry("Canale Extra","https://media.example.com/live",tvgId="Extra.it")
        val before=store.window(entry,now,now+3_600_000)
        for(xml in listOf("<html>Login</html>",feed().removeSuffix("</tv>"))) {
            var failed=false
            try{store.stage(xml.byteInputStream(),setOf("rai1"),now,KXmlParser()).close()}catch(_:Exception){failed=true}
            assertTrue(failed);assertEquals(before,store.window(entry,now,now+3_600_000))
        }
        try{store.stage(feed().byteInputStream(),setOf("rai1"),now,KXmlParser(),active={throw CancellationException()});fail()}catch(_:CancellationException){}
        assertEquals(before,store.window(entry,now,now+3_600_000))
        assertTrue(app.filesDir.listFiles().orEmpty().none {it.name.startsWith(".catalog-guide-")})
        assertTrue(store.window(entry,now+CatalogGuideStore.FUTURE,now+CatalogGuideStore.FUTURE+3_600_000).isEmpty())
    }

    @Test fun `public logos fill only absent artwork and never receive private access headers`()=runBlocking {
        install()
        val requests=Collections.synchronizedList(mutableListOf<Request>())
        val image=ByteArrayOutputStream().also {Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        val http=HttpClient(OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request();requests+=request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if(request.url.host=="private.example.com")404 else 200).message("Fixture")
                .body(image.toResponseBody("image/png".toMediaType())).build()
        }.build())
        val settings=PlaylistSettings(access=mapOf(PlaylistResource.LOGO to PlaylistAccessRule(mapOf("Authorization" to "private-secret","X-Logo" to "private-logo"))))
        val missing=source(settings=settings)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val logos=ChannelLogoRepository(app,http,scope)
            var color=0
            withTimeout(10_000){logos.request(item(missing)){color=it?.bitmap?.getPixel(0,0) ?: 0}.join()}
            assertEquals(Color.BLUE,color)
            assertEquals("public.example.com",requests.single().url.host)
            assertNull(requests.single().header("Authorization"));assertNull(requests.single().header("X-Logo"));assertNull(requests.single().header("Cookie"))
            requests.clear()
            val own=source("#EXTM3U\n#EXTINF:-1 tvg-id=\"Extra.it\" tvg-logo=\"https://private.example.com/logo.png\",Canale Extra\nhttps://media.example.com/own.m3u8",settings)
            withTimeout(10_000){logos.request(item(own)){color=it?.bitmap?.getPixel(0,0) ?: 0}.join()}
            assertEquals(0,color)
            assertEquals("private.example.com",requests.single().url.host)
            assertEquals("private-secret",requests.single().header("Authorization"))
        }finally {scope.cancel()}
    }
}
