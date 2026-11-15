package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VideoContinuityTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private val history get()=PersonalPlaybackStore.get(app)
    private fun repo()=PersonalPlaylistRepository(app)
    private fun target(item:VideoItem)=PlaybackTarget(item.id,PlaybackMode.VOD)
    private fun list(vararg names:String)=buildString {
        appendLine("#EXTM3U")
        names.forEachIndexed {i,name->appendLine("#EXTINF:-1 type=\"${if(name.startsWith("Film"))"movie" else "episode"}\",$name");appendLine("https://example.org/item/$i.mp4")}
    }.toByteArray()
    private suspend fun rows(repo:PersonalPlaylistRepository,source:String)=repo.videos(VideoQuery(source=source,collapseSeries=false)).rows
    @Before fun reset(){File(app.filesDir,"personal-playlists").deleteRecursively();File(app.filesDir,"personal-watch.sqlite").delete();app.getSharedPreferences("lume",0).edit().clear().commit()}

    @Test fun `legacy watch records gain visibility without losing VOD or archive progress`() {
        val source="user_"+"a".repeat(32);val file=File(app.filesDir,"personal-watch.sqlite")
        SQLiteDatabase.openOrCreateDatabase(file,null).use {db->
            db.execSQL("CREATE TABLE watch(source TEXT,channel TEXT,mode TEXT,event TEXT,position INTEGER,duration INTEGER,completed INTEGER,updated INTEGER,PRIMARY KEY(source,channel,mode,event))")
            db.execSQL("INSERT INTO watch VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(source,"movie","VOD","",42_000,90_000,0,1000))
            db.execSQL("INSERT INTO watch VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(source,"tv","ARCHIVE","show",60_000,120_000,1,1000))
        }
        assertEquals(WatchPosition(42_000,90_000),history.read(source,PlaybackTarget("movie",PlaybackMode.VOD)))
        assertEquals(WatchPosition(60_000,120_000,true),history.read(source,PlaybackTarget("tv",PlaybackMode.ARCHIVE,"show")))
        history.write(source,PlaybackTarget("movie",PlaybackMode.VOD),43_000,90_000)
        assertEquals(43_000,history.read(source,PlaybackTarget("movie",PlaybackMode.VOD)).resume)
    }
    @Test fun `manual watched state removes Continue and non watched resets progress while preserving saved titles`()=runTest {
        val repo=repo();val source=repo.save("Cinema",repo.prepareFile(list("Film uno")))
        val item=rows(repo,source).single();val prefs=app.getSharedPreferences("lume",0)
        prefs.edit().putStringSet("favorites",setOf(item.id)).putStringSet("video_saved_series",setOf("$source:series")).commit()
        history.write(source,target(item),42_000,180_000)
        assertEquals(1,repo.videos(VideoQuery(shelf=VideoShelf.CONTINUE)).total)
        repo.setVideoCompleted(item,true)
        val seen=repo.videoDetails(item)!!
        assertTrue(seen.completed);assertEquals(0L,seen.resume);assertEquals(0,repo.videos(VideoQuery(shelf=VideoShelf.CONTINUE)).total)
        repo.setVideoCompleted(seen,false)
        val unseen=repo.videoDetails(item)!!
        assertFalse(unseen.completed);assertEquals(0L,unseen.position);assertEquals(180_000L,unseen.watchedDuration)
        assertEquals(setOf(item.id),prefs.getStringSet("favorites",emptySet()));assertEquals(setOf("$source:series"),prefs.getStringSet("video_saved_series",emptySet()))
    }
    @Test fun `hiding Continue preserves resume refreshes cached counts and can be reversed`()=runTest {
        val repo=repo();val source=repo.save("Cinema",repo.prepareFile(list("Film uno","Film due")))
        val items=rows(repo,source);val q=VideoQuery(shelf=VideoShelf.CONTINUE)
        items.forEach {history.write(source,target(it),42_000,180_000)}
        assertEquals(2,repo.videos(q).total);assertEquals(2,repo.cachedVideos(q)!!.total)
        repo.setContinueHidden(items[0],true)
        assertEquals(listOf(items[1].id),repo.videos(q).rows.map {it.id})
        val hidden=repo.videoDetails(items[0])!!
        assertTrue(hidden.hiddenFromContinue);assertEquals(42_000L,hidden.resume);assertFalse(hidden.completed)
        repo.setContinueHidden(hidden,false);assertEquals(2,repo.videos(q).total)
        repo.setContinueHidden(items[0],true)
        val session=history.session(source,target(items[0]));assertEquals(42_000L,history.start(session)!!.position)
        assertFalse(history.read(source,target(items[0])).hidden);assertEquals(2,repo.videos(q).total)
    }
    @Test fun `manual edits defeat late player saves including completion until deliberate playback starts`()=runTest {
        val repo=repo();val source=repo.save("Cinema",repo.prepareFile(list("Film uno","Film due")))
        val items=rows(repo,source);val item=items[0]
        val session=history.session(source,target(item));history.start(session)
        assertTrue(history.record(session,42_000,180_000,false))
        repo.setContinueHidden(item,false);assertTrue(history.record(session,42_000,180_000,false))
        repo.setContinueHidden(item,true)
        assertFalse(history.record(session,90_000,180_000,false));assertFalse(history.record(session,180_000,180_000,true))
        assertEquals(WatchPosition(42_000,180_000,false,true),history.read(source,target(item)))
        val next=history.session(source,target(item));history.start(next)
        repo.setVideoCompleted(items[1],true)
        assertTrue(history.record(next,60_000,180_000,false))
        repo.setVideoCompleted(item,true);assertFalse(history.record(next,70_000,180_000,false))
        repo.setVideoCompleted(item,false);assertFalse(history.record(next,180_000,180_000,true))
        val again=history.session(source,target(item));assertEquals(0L,history.start(again)!!.position)
        assertTrue(history.record(again,6000,180_000,false));assertEquals(6000L,history.read(source,target(item)).resume)
    }
    @Test fun `a pending open cannot erase a later manual decision and deleted sources reject old sessions`()=runTest {
        val repo=repo();val source=repo.save("Cinema",repo.prepareFile(list("Film uno")))
        val item=rows(repo,source).single();val pending=history.session(source,target(item))
        repo.setVideoCompleted(item,true);assertNull(history.start(pending));assertTrue(history.read(source,target(item)).completed)
        val current=history.session(source,target(item));history.start(current);history.record(current,6000,180_000,false)
        repo.delete(source);assertFalse(history.record(current,90_000,180_000,false));assertEquals(WatchPosition(),history.read(source,target(item)))
    }
    @Test fun `episode neighbors cross seasons numerically and never cross series or source`()=runTest {
        val repo=repo();val source=repo.save("Serie",repo.prepareFile(list("Orbita S02E02","Orbita S01E10","Orbita S01E02","Orbita S02E01","Altra S01E03","Film uno")))
        val secondSource=repo.save("Copia",repo.prepareFile(list("Orbita S01E03")))
        val items=rows(repo,source).associateBy {it.title}
        val a=items.getValue("Orbita S01E02");assertTrue(a.seriesId.isNotBlank());assertFalse(a.isSeries)
        assertEquals("Orbita S01E10",repo.nextEpisode(source,a.id)?.title)
        assertEquals("Orbita S02E01",repo.nextEpisode(source,items.getValue("Orbita S01E10").id)?.title)
        assertEquals("Orbita S02E02",repo.nextEpisode(source,items.getValue("Orbita S02E01").id)?.title)
        assertNull(repo.nextEpisode(source,items.getValue("Orbita S02E02").id));assertNull(repo.nextEpisode(source,items.getValue("Film uno").id))
        assertNull(repo.nextEpisode(secondSource,rows(repo,secondSource).single().id))
    }
    @Test fun `alternate copies of an episode are skipped while unknown numbering follows list order`()=runTest {
        val repo=repo()
        val data="""
            #EXTM3U
            #EXTINF:-1 type="episode" series-name="Serie" season="1" episode="1",Prima copia
            https://example.org/1.mp4
            #EXTINF:-1 type="episode" series-name="Serie" season="1" episode="1",Seconda copia
            https://example.org/2.mp4
            #EXTINF:-1 type="episode" series-name="Serie" season="1" episode="2",Prossimo
            https://example.org/3.mp4
            #EXTINF:-1 type="episode" series-name="Senza numeri",Prima
            https://example.org/4.mp4
            #EXTINF:-1 type="episode" series-name="Senza numeri",Seconda
            https://example.org/5.mp4
        """.trimIndent().toByteArray()
        val source=repo.save("Serie",repo.prepareFile(data));val items=rows(repo,source).associateBy {it.title}
        assertEquals("Prossimo",repo.nextEpisode(source,items.getValue("Prima copia").id)?.title)
        assertEquals("Seconda",repo.nextEpisode(source,items.getValue("Prima").id)?.title)
        assertNull(repo.nextEpisode(source,items.getValue("Seconda").id))
    }
    @Test fun `series play selects Continue then the first unseen episode and can rewatch a completed series`()=runTest {
        val repo=repo();val source=repo.save("Serie",repo.prepareFile(list("Orbita S02E01","Orbita S01E02","Orbita S01E01")))
        val series=repo.videos().rows.single();val episodes=rows(repo,source).sortedWith(compareBy<VideoItem>{it.season}.thenBy {it.episode})
        assertEquals(episodes[0].id,repo.seriesPlaybackTarget(series)?.id)
        repo.setVideoCompleted(episodes[0],true);assertEquals(episodes[1].id,repo.seriesPlaybackTarget(series)?.id)
        history.write(source,target(episodes[2]),42_000,180_000);assertEquals(episodes[2].id,repo.seriesPlaybackTarget(series)?.id)
        repo.setVideoCompleted(episodes[2],true);assertEquals(episodes[1].id,repo.seriesPlaybackTarget(series)?.id)
        repo.setVideoCompleted(episodes[1],true);assertEquals(episodes[0].id,repo.seriesPlaybackTarget(series)?.id)
        assertTrue(repo.seriesPlaybackTarget(series)!!.completed)
    }
    @Test fun `neighbor lookup reaches beyond display windows without loading a full series`()=runTest {
        val repo=repo();val source=repo.save("Serie grande",repo.prepareFile(buildString {
            appendLine("#EXTM3U");for(i in 450 downTo 1){appendLine("#EXTINF:-1 type=\"episode\",Orbita S01E${i.toString().padStart(3,'0')}");appendLine("https://example.org/episode/$i.mp4")}
        }.toByteArray()))
        val item=repo.videos(VideoQuery(source=source,text="S01E210",collapseSeries=false,limit=1)).rows.single()
        assertTrue(repo.cached().none {it.id==item.id})
        val next=repo.nextEpisode(source,item.id)!!
        assertEquals("Orbita S01E211",next.title);assertTrue(repo.cached().none {it.id==next.id})
        assertEquals(source,next.source);assertTrue(repo.videoReadCacheState().getValue("rows")<=600)
    }
    @Test fun `refresh preserves watched and hidden state and disabled or live entries reject manual edits`()=runTest {
        val repo=repo();val input=list("Film uno","Film due");val source=repo.save("Cinema",repo.prepareFile(input));val items=rows(repo,source)
        repo.setVideoCompleted(items[0],true);history.write(source,target(items[1]),42_000,180_000);repo.setContinueHidden(items[1],true)
        repo.save("Rinominata",repo.prepareFile(input),source)
        assertTrue(repo.videoDetails(items[0])!!.completed);assertTrue(repo.videoDetails(items[1])!!.hiddenFromContinue)
        repo.setEnabled(source,false)
        try{repo.setVideoCompleted(items[0],false);fail("Disabled source accepted")}catch(_:java.io.IOException){}
        repo.setEnabled(source,true);assertTrue(repo.videoDetails(items[0])!!.completed)
        repo.setSettings(source,PlaylistSettings(contentOverride=PlaylistContentKind.LIVE))
        try{repo.setContinueHidden(items[1],false);fail("Live entry accepted")}catch(_:java.io.IOException){}
        assertTrue(history.read(source,target(items[1])).hidden)
    }
}
