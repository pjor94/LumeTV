package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PersonalOnDemandTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){for(name in listOf("personal-playlists","personal-guides"))File(context.filesDir,name).deleteRecursively();File(context.filesDir,"personal-watch.sqlite").delete()}
    private val text="#EXTM3U\n#EXTINF:-1 tvg-id=\"live\",Diretta\nhttps://media.example/live.m3u8\n#EXTINF:120 tvg-id=\"film\" group-title=\"Cinema\",Film\nhttps://media.example/film.mp4"
    @Test fun `live and VOD catalog migration preserve IDs favorites and source override`()=runTest {
        val repo=PersonalPlaylistRepository.get(context);val id=repo.save("Mista",repo.prepareFile(text.toByteArray()))
        val rows=repo.local();assertFalse(rows.first().isVod);assertTrue(rows.last().isVod);assertEquals("",rows.last().epgId)
        context.getSharedPreferences("lume",Context.MODE_PRIVATE).edit().putStringSet("favorites",setOf(rows.last().id)).commit()
        val file=File(context.filesDir,"personal-playlists/$id.sqlite")
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            val root=db.rawQuery("SELECT value FROM metadata WHERE key='source'",null).use {it.moveToFirst();JSONObject(it.getString(0))};root.put("version",3)
            db.execSQL("UPDATE metadata SET value=? WHERE key='source'",arrayOf(root.toString()))
            db.execSQL("DROP INDEX content_position");db.execSQL("ALTER TABLE records DROP COLUMN content")
        }
        val restored=PersonalPlaylistRepository(context);assertEquals(rows,restored.local())
        assertEquals(setOf(rows.last().id),context.getSharedPreferences("lume",Context.MODE_PRIVATE).getStringSet("favorites",emptySet()))
        restored.setSettings(id,restored.settings(id).copy(contentOverride=PlaylistContentKind.LIVE))
        assertTrue(restored.local().none {it.isVod});assertEquals(rows.map {it.id},restored.local().map {it.id})
        restored.setSettings(id,restored.settings(id).copy(contentOverride=PlaylistContentKind.VOD));assertTrue(restored.local().all {it.isVod})
    }
    @Test fun `library search group and page offsets stay bounded across sources`()=runTest {
        val repo=PersonalPlaylistRepository.get(context)
        for(source in listOf("A","B")) {
            val list=buildString {append("#EXTM3U\n");repeat(75){append("#EXTINF:120 tvg-id=\"$source-$it\" group-title=\"${if(it%2==0)"Pari" else "Dispari"}\",Film $source ${it.toString().padStart(3,'0')}\nhttps://example.com/$source/$it.mp4\n")}}
            repo.save(source,repo.prepareFile(list.toByteArray()))
        }
        val first=repo.library(null,"",null);val second=repo.library(null,"",null,50);val last=repo.library(null,"",null,100)
        assertEquals(150,first.total);assertEquals(50,first.rows.size);assertEquals(50,second.rows.size);assertEquals(50,last.rows.size)
        assertEquals(150,(first.rows+second.rows+last.rows).map {it.id}.toSet().size)
        assertEquals(38,repo.library(repo.state.value.first().id,"Film A","Pari").total)
        assertEquals(0,repo.library(null,"%",null).total)
        assertTrue(repo.library(null,"",null,150).rows.isEmpty())
    }
    @Test fun `watch position is separate from live and archive and completion starts again`() {
        val id="user_"+"a".repeat(32);val store=PersonalPlaybackStore.get(context);val vod=PlaybackTarget("opaque",PlaybackMode.VOD)
        val archive=vod.copy(mode=PlaybackMode.ARCHIVE,eventId="event")
        store.write(id,vod,45_000,120_000);store.write(id,archive,10_000,60_000)
        assertEquals(45_000L,store.read(id,vod).resume);assertEquals(10_000L,store.read(id,archive).resume)
        store.write(id,vod,120_000,120_000,true);assertEquals(0L,store.read(id,vod).resume)
        store.write(id,vod.copy(mode=PlaybackMode.LIVE),1000,0);assertEquals(WatchPosition(),store.read(id,vod.copy(mode=PlaybackMode.LIVE)))
        store.removeSource(id);assertEquals(WatchPosition(),store.read(id,archive))
    }
    @Test fun `VOD resolver resumes a saved position and archive retains DRM but isolates another host`()=runTest {
        MockWebServer().use {server->server.start()
            val now=System.currentTimeMillis();val fmt=DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneId.of("UTC"))
            fun date(t:Long)=fmt.format(Instant.ofEpochMilli(t))
            val list="#EXTM3U url-tvg=\"${server.url("/epg.xml")}\" catchup=\"default\" catchup-source=\"https://archive.example/replay.mpd?start={utc}&end={utcend}&sig=a%2Bb+c\" catchup-days=\"7\"\n"+
                "#EXTINF:-1 tvg-id=\"live\",Live\n#EXTHTTP:{\"Authorization\":\"private-stream\",\"User-Agent\":\"Fixture\"}\n#KODIPROP:inputstream.adaptive.manifest_type=mpd\n#KODIPROP:inputstream.adaptive.license_type=clearkey\n#KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100\nhttps://media.example/live.mpd\n#EXTINF:120,Film\nhttps://media.example/film.mp4"
            val repo=PersonalPlaylistRepository.get(context);val id=repo.save("Mista",repo.prepareFile(list.toByteArray(),settings=PlaylistSettings(guide=PersonalGuideSettings(allowHttp=true))))
            server.enqueue(MockResponse().setBody("<tv><channel id=\"live\"><display-name>Live</display-name></channel><programme channel=\"live\" start=\"${date(now-3_600_000)}\" stop=\"${date(now-1_800_000)}\"><title>Archivio privato</title></programme></tv>"))
            val guide=PersonalGuideRepository.get(context);guide.refresh(true,id);server.takeRequest()
            val rows=repo.local();val live=rows.first();val vod=rows.last();val watch=PersonalPlaybackStore.get(context)
            watch.write(id,PlaybackTarget(vod.id,PlaybackMode.VOD),33_000,120_000)
            val resolver=PersonalPlaybackResolver(context,repo,OkHttpClient())
            val result=resolver.resolve(vod,PlaybackTarget(vod.id),null)
            assertEquals(PlaybackMode.VOD,result.target.mode);assertEquals(33_000L,result.startPosition)
            val event=guide.window(listOf(live),now-86_400_000,now)[live.epgId]!!.single()
            val replay=resolver.resolve(live,PlaybackTarget(live.id,PlaybackMode.ARCHIVE,event.eventId),60_000)
            assertEquals(PlaybackMode.ARCHIVE,replay.target.mode);assertEquals("Archivio privato",replay.title);assertEquals(60_000L,replay.baseOffset)
            assertTrue(replay.stream.url.contains("sig=a%2Bb+c"));assertFalse(replay.stream.headers.containsKey("Authorization"));assertEquals("Fixture",replay.stream.headers["User-Agent"])
            assertEquals(DrmScheme.CLEARKEY,replay.stream.drm!!.scheme)
            repo.delete(id);assertEquals(WatchPosition(),watch.read(id,PlaybackTarget(vod.id,PlaybackMode.VOD)))
        }
    }
    @Test fun `HTTP archive has an explicit source consent error before any playback request`()=runTest {
        MockWebServer().use {server->server.start()
            val now=System.currentTimeMillis();val format=DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneId.of("UTC"))
            fun date(t:Long)=format.format(Instant.ofEpochMilli(t))
            val list="#EXTM3U url-tvg=\"${server.url("/epg")}\" catchup=\"default\" catchup-days=\"7\" catchup-source=\"http://archive.example/movie.mp4?start={utc}\"\n#EXTINF:-1 tvg-id=\"one\",Uno\nhttps://media.example/live.m3u8"
            val repo=PersonalPlaylistRepository.get(context);val id=repo.save("HTTP archivio",repo.prepareFile(list.toByteArray(),settings=PlaylistSettings(guide=PersonalGuideSettings(allowHttp=true))))
            server.enqueue(MockResponse().setBody("<tv><channel id=\"one\"><display-name>Uno</display-name></channel><programme channel=\"one\" start=\"${date(now-3_600_000)}\" stop=\"${date(now-1_800_000)}\"><title>Replay</title></programme></tv>"))
            val guide=PersonalGuideRepository.get(context);guide.refresh(true,id);server.takeRequest()
            val channel=repo.local().single();val event=guide.window(listOf(channel),now-86_400_000,now)[channel.epgId]!!.single()
            try {PersonalPlaybackResolver(context,repo,OkHttpClient()).resolve(channel,PlaybackTarget(channel.id,PlaybackMode.ARCHIVE,event.eventId),0L);fail("HTTP archive opened without source consent")}
            catch(error:SourceException){assertTrue(error.message.orEmpty().contains("Consenti HTTP"))}
        }
    }
}
