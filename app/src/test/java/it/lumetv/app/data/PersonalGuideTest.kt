package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import org.tukaani.xz.XZOutputStream
import org.tukaani.xz.LZMA2Options

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PersonalGuideTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val now=System.currentTimeMillis()
    private val format=DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneId.of("UTC"))
    private fun date(time:Long)=format.format(Instant.ofEpochMilli(time))
    private fun xml(id:String="shared",name:String="Nome guida",title:String="Programma personale",stop:Boolean=true)=
        "<?xml version=\"1.0\"?><!DOCTYPE tv SYSTEM \"https://must-not-be-loaded.invalid/xmltv.dtd\"><tv><channel id=\"$id\"><display-name>$name</display-name><icon src=\"logo.png\"/></channel>"+
        "<programme channel=\"$id\" start=\"${date(now-3_600_000)}\" ${if(stop)"stop=\"${date(now-1_800_000)}\"" else ""} catchup-id=\"private-catchup\"><title lang=\"en\">English</title><title lang=\"it\">$title</title><desc lang=\"it\">Descrizione privata</desc></programme>"+
        "<programme channel=\"$id\" start=\"${date(now-1_800_000)}\" stop=\"${date(now+1_800_000)}\"><title>Ora</title></programme></tv>"
    @Before fun reset(){for(dir in listOf("personal-playlists","personal-guides"))File(context.filesDir,dir).deleteRecursively();File(context.filesDir,"personal-watch.sqlite").delete()}
    private suspend fun source(repo:PersonalPlaylistRepository,url:String,name:String="Nome",tvg:String="shared",settings:PlaylistSettings=PlaylistSettings(guide=PersonalGuideSettings(allowHttp=true))):String {
        val text="#EXTM3U url-tvg=\"$url\" catchup=\"append\" catchup-source=\"&utc={utc}&end={utcend}\" catchup-days=\"7\"\n#EXTINF:-1 tvg-id=\"$tvg\" tvg-name=\"Nome guida\",$name\nhttps://media.example/live.m3u8"
        return repo.save(name,repo.prepareFile(text.toByteArray(),settings=settings))
    }
    @Test fun `SAX streams multilingual descriptions infers missing ends and accepts partial dates`() {
        val events=mutableListOf<XmlProgramme>();val channels=mutableListOf<XmlChannel>()
        PersonalXmlTv.parse(ByteArrayInputStream(xml(stop=false).toByteArray()),"it",ZoneId.of("UTC"),channel=channels::add,programme=events::add)
        assertEquals("Programma personale",events.first().title);assertEquals(0L,events.first().end)
        assertEquals("private-catchup",events.first().catchupId);assertEquals(listOf("Nome guida"),channels.single().names)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),PersonalXmlTv.timestamp("2026",ZoneId.of("UTC")))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),PersonalXmlTv.timestamp("20261025023000 +0200"))
        assertEquals(0L,PersonalXmlTv.timestamp("20261301000000 +0000"))
    }
    @Test fun `UTF16 is supported and internal DTDs oversized attributes nesting and HTML are refused`() {
        for(charset in listOf(Charsets.UTF_8,Charsets.UTF_16LE,Charsets.UTF_16BE)) {
            var count=0
            val good=xml().replace("version=\"1.0\"","version=\"1.0\" encoding=\"${charset.name()}\"")
            PersonalXmlTv.parse(ByteArrayInputStream(good.toByteArray(charset)),"it",ZoneId.of("UTC"),channel={},programme={count++});assertEquals(2,count)
            val bad="<?xml version=\"1.0\" encoding=\"${charset.name()}\"?><!DOCTYPE tv [<!ENTITY x \"boom\">]><tv/>"
            try{PersonalXmlTv.parse(ByteArrayInputStream(bad.toByteArray(charset)),"it",ZoneId.of("UTC"),channel={},programme={});fail("Internal DTD accepted")}catch(_:Exception){}
        }
        for(bad in listOf("<html>Login</html>","<tv><channel id=\"${"x".repeat(70_000)}\"/></tv>","<tv>"+"<x>".repeat(40)+"</x>".repeat(40)+"</tv>")) {
            var rejected=false
            try{PersonalXmlTv.parse(ByteArrayInputStream(bad.toByteArray()),"it",ZoneId.of("UTC"),channel={},programme={})}catch(_:Exception){rejected=true}
            assertTrue(rejected)
        }
    }
    @Test fun `fragmented UTF16 prefix cannot bypass the internal DTD subset guard`() {
        for(charset in listOf(Charsets.UTF_16LE,Charsets.UTF_16BE)) {
            val text="<?xml version=\"1.0\" encoding=\"${charset.name()}\"?><!DOCTYPE tv [<!ELEMENT tv ANY>]><tv/>"
            val input=object:java.io.FilterInputStream(ByteArrayInputStream(text.toByteArray(charset))) {
                override fun read(b:ByteArray,offset:Int,length:Int)=`in`.read(b,offset,minOf(1,length))
            }
            var rejected=false
            try{PersonalXmlTv.parse(input,"it",ZoneId.of("UTC"),channel={},programme={})}catch(_:SourceException){rejected=true}
            assertTrue(rejected)
        }
    }
    @Test fun `same XML IDs remain source scoped and guide only edits keep playback context`()=runTest {
        MockWebServer().use {server->server.start()
            val repo=PersonalPlaylistRepository.get(context);val url=server.url("/guide.xml").toString()
            val a=source(repo,url,"A");val b=source(repo,url,"B")
            repeat(2){server.enqueue(MockResponse().setBody(xml()))}
            val guide=PersonalGuideRepository.get(context);guide.refresh(true)
            assertTrue(guide.state.value.toString()+org.robolectric.shadows.ShadowLog.getLogsForTag("LumePersonalGuide"),guide.state.value.all {it.error.isBlank()})
            val channels=repo.local();assertNotEquals(channels[0].epgId,channels[1].epgId)
            val map=guide.window(channels,now-86_400_000,now+86_400_000)
            assertEquals(guide.state.value.toString(),2,map.size);assertTrue(map.values.all {it.size==2 && it.first().replayAvailable})
            assertTrue(map.values.flatten().all {it.sourceId==a || it.sourceId==b})
            val token=repo.requestContext(a)!!;val epoch=token.guideToken
            val config=repo.settings(a);repo.setSettings(a,config.copy(guide=config.guide.copy(language="en",allowHttp=false)))
            assertSame(token,repo.requestContext(a));assertTrue(token.valid());assertNotEquals(epoch,token.guideToken)
            assertFalse(token.allowed(java.net.URI(url),PlaylistResource.EPG));assertTrue(token.allowed(java.net.URI("https://media.example/live"),PlaylistResource.MEDIA))
            assertFalse(File(context.filesDir,"guide.json").exists())
            repo.delete(a);assertFalse(File(context.filesDir,"personal-guides/$a.sqlite").exists());assertEquals(1,guide.window(channels,now-86_400_000,now+86_400_000).size)
        }
    }
    @Test fun `gzip xz conditional refresh and failure keep last valid guide with role headers`()=runTest {
        MockWebServer().use {server->server.start()
            val repo=PersonalPlaylistRepository.get(context)
            val settings=PlaylistSettings(guide=PersonalGuideSettings(allowHttp=true),access=mapOf(PlaylistResource.EPG to PlaylistAccessRule(mapOf("X-Guide" to "private-guide")),PlaylistResource.MEDIA to PlaylistAccessRule(mapOf("Authorization" to "must-not-leak"))))
            val id=source(repo,server.url("/epg").toString(),settings=settings)
            val guide=PersonalGuideRepository.get(context)
            for(kind in listOf("gzip","xz")) {
                val bytes=ByteArrayOutputStream().also {out->if(kind=="gzip")GZIPOutputStream(out).use {it.write(xml(stop=false).toByteArray())}else XZOutputStream(out,LZMA2Options()).use {it.write(xml(stop=false).toByteArray())}}.toByteArray()
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)).addHeader("ETag","fixture-tag"));guide.refresh(true,id)
                val request=server.takeRequest();assertEquals("private-guide",request.getHeader("X-Guide"));assertNull(request.getHeader("Authorization"))
                assertEquals(2,guide.window(repo.local(),now-86_400_000,now+86_400_000).values.single().size)
            }
            server.enqueue(MockResponse().setResponseCode(304));guide.refresh(true,id)
            assertEquals("fixture-tag",server.takeRequest().getHeader("If-None-Match"))
            val before=guide.window(repo.local(),now-86_400_000,now+86_400_000)
            server.enqueue(MockResponse().setBody("<html>Login</html>"));guide.refresh(true,id)
            server.takeRequest();assertEquals(before,guide.window(repo.local(),now-86_400_000,now+86_400_000));assertTrue(guide.state.value.single().error.isNotBlank())
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After","120"));guide.refresh(true,id);server.takeRequest()
            assertTrue(guide.state.value.single().nextRetry>=now+120_000)
            val requests=server.requestCount;guide.refresh(true,id);assertEquals(requests,server.requestCount)
        }
    }
    @Test fun `exact unique name ambiguity and manual association use deterministic source bindings`()=runTest {
        MockWebServer().use {server->server.start()
            val repo=PersonalPlaylistRepository.get(context);val id=source(repo,server.url("/epg").toString(),tvg="wrong-id")
            val guide=PersonalGuideRepository.get(context)
            server.enqueue(MockResponse().setBody(xml(id="actual")));guide.refresh(true,id);server.takeRequest()
            val channel=repo.local().single();assertEquals(2,guide.window(listOf(channel),now-86_400_000,now+86_400_000)[channel.epgId]!!.size)
            val searched=guide.search(channel,"personale");assertEquals(1,searched.second);assertTrue(searched.first.single().replayAvailable)
            assertEquals(0,guide.search(channel,"%private%").second)
            guide.map(id,channel.id,-1)
            assertTrue(guide.window(listOf(channel),now-86_400_000,now+86_400_000).isEmpty())
            val ambiguous=xml(id="actual").replace("</channel>","</channel><channel id=\"other\"><display-name>Nome guida</display-name></channel>")
            server.enqueue(MockResponse().setBody(ambiguous));guide.refresh(true,id);server.takeRequest()
            assertEquals(2,guide.choices(id,0,"Nome").size)
            guide.map(id,channel.id,0,"actual")
            server.enqueue(MockResponse().setBody(ambiguous));guide.refresh(true,id);server.takeRequest()
            assertEquals(2,guide.window(listOf(channel),now-86_400_000,now+86_400_000)[channel.epgId]!!.size)
            guide.clear(id);assertTrue(guide.window(listOf(channel),now-86_400_000,now+86_400_000).isEmpty())
            server.enqueue(MockResponse().setBody(ambiguous));guide.refresh(true,id);server.takeRequest()
            assertEquals(2,guide.window(listOf(channel),now-86_400_000,now+86_400_000)[channel.epgId]!!.size)
            guide.map(id,channel.id,null)
            server.enqueue(MockResponse().setBody(ambiguous));guide.refresh(true,id);server.takeRequest()
            // Ambiguous automatic names never pick an arbitrary XMLTV channel.
            assertTrue(guide.window(listOf(channel),now-86_400_000,now+86_400_000).isEmpty())
        }
    }
    @Test fun `replacing a feed URL never merges its predecessor history or event identity`()=runTest {
        MockWebServer().use {server->server.start()
            val repo=PersonalPlaylistRepository.get(context);val old=server.url("/first.xml").toString();val next=server.url("/second.xml").toString()
            val id=source(repo,old);val channel=repo.local().single();val guide=PersonalGuideRepository.get(context)
            server.enqueue(MockResponse().setBody(xml(title="Primo fornitore")));guide.refresh(true,id);server.takeRequest()
            val original=guide.window(listOf(channel),now-86_400_000,now+86_400_000)[channel.epgId]!!.first()
            val settings=repo.settings(id);repo.setSettings(id,settings.copy(guide=settings.guide.copy(urls=listOf(next))))
            server.enqueue(MockResponse().setResponseCode(503));guide.refresh(true,id);server.takeRequest()
            assertEquals(original,guide.window(listOf(channel),now-86_400_000,now+86_400_000)[channel.epgId]!!.first())
            // A failed new endpoint still shows the old copy, but the next successful import replaces its origin.
            val changed=xml(title="Secondo fornitore").replace(date(now-3_600_000),date(now-3_500_000))
            server.enqueue(MockResponse().setBody(changed));guide.refresh(true,id);assertNull(server.takeRequest().getHeader("If-None-Match"))
            val shows=guide.window(listOf(channel),now-86_400_000,now+86_400_000)[channel.epgId]!!
            assertEquals(2,shows.size);assertTrue(shows.none {it.title=="Primo fornitore"});assertNotEquals(original.eventId,shows.first().eventId)
        }
    }
}
