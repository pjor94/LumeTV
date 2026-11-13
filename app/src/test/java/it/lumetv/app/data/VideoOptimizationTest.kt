package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VideoOptimizationTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val prefs get()=context.getSharedPreferences("lume",0)
    private fun fixture(n:Int)=buildString {
        appendLine("#EXTM3U")
        repeat(n){i->val series=i%5>=3
            appendLine("#EXTINF:-1 type=\"${if(series)"series" else "movie"}\" group-title=\"Gruppo ${i%4}\" plot=\"Una trama\",${if(series)"Orbita ${i/20} S01E${(i%20+1).toString().padStart(2,'0')}" else "Film $i"}")
            appendLine("https://example.org/video/$i.mp4")
        }
    }.toByteArray()
    @Before fun reset(){File(context.filesDir,"personal-playlists").deleteRecursively();File(context.filesDir,"personal-watch.sqlite").delete();prefs.edit().clear().commit();VideoArtworkRepository.clearForTests()}
    private fun count(name:String)=VideoPerformance.snapshot()[name] ?: 0L
    @Test fun `previews never count and later windows reuse the exact count`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);repo.save("Prova",repo.prepareFile(fixture(300)))
        val before=count("count")
        val preview=repo.videoPreview(VideoQuery(details=false));assertFalse(preview.countKnown);assertEquals(16,preview.rows.size)
        assertEquals(before,count("count"));assertTrue(preview.rows.none {it.detailsLoaded});assertTrue(preview.rows.all {it.description.isBlank()})
        val first=repo.videos(VideoQuery(kind=VodKind.MOVIE));val after=count("count")
        val second=repo.videos(VideoQuery(kind=VodKind.MOVIE,offset=50))
        assertEquals(before+1,after);assertEquals(after,count("count"));assertEquals(first.total,second.total)
        repo.videos(VideoQuery(kind=VodKind.MOVIE,order=VideoOrder.TITLE));repo.videos(VideoQuery(kind=VodKind.MOVIE,order=VideoOrder.ADDED))
        assertEquals(after,count("count"))
        assertTrue(first.rows.map {it.id}.intersect(second.rows.map {it.id}.toSet()).isEmpty())
        val reads=count("page");repo.videos(VideoQuery(kind=VodKind.MOVIE));assertEquals(reads,count("page"))
    }
    @Test fun `watch changes decorate cached films without rerunning their SQL`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);val source=repo.save("Prova",repo.prepareFile(fixture(40)))
        val q=VideoQuery(kind=VodKind.MOVIE);val movie=repo.videos(q).rows.first();val key=repo.videoCacheKey(q)
        val queries=count("page");val counts=count("count")
        PersonalPlaybackStore.get(context).write(source,PlaybackTarget(movie.id,PlaybackMode.VOD),21_000,90_000)
        val current=repo.videos(q).rows.first {it.id==movie.id}
        assertEquals(21_000L,current.position);assertEquals(queries,count("page"));assertEquals(counts,count("count"));assertEquals(key,repo.videoCacheKey(q))
        assertEquals(21_000L,repo.cachedVideos(q)!!.rows.first {it.id==movie.id}.position)
    }
    @Test fun `empty personal shelves do not prepare or scan a large new catalog`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);repo.save("Prova",repo.prepareFile(fixture(300)))
        val before=count("prepare");val pages=count("page")
        assertEquals(0,repo.videos(VideoQuery(shelf=VideoShelf.SAVED)).total)
        assertTrue(repo.videoPreview(VideoQuery(shelf=VideoShelf.CONTINUE)).rows.isEmpty())
        assertEquals(before,count("prepare"));assertEquals(pages,count("page"))
        assertTrue(repo.videos().rows.isNotEmpty());assertEquals(before+1,count("prepare"))
    }
    @Test fun `concurrent readers share one index preparation and later calls use the ready index`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);repo.save("Prova",repo.prepareFile(fixture(400)))
        val before=count("prepare")
        val pages=coroutineScope {(0..3).map {async(Dispatchers.IO){repo.videos(VideoQuery(offset=it*10,limit=10))}}.awaitAll()}
        assertTrue(pages.all {it.total==pages[0].total});assertEquals(before+1,count("prepare"))
        repo.videoPreview(VideoQuery(kind=VodKind.MOVIE));assertEquals(before+1,count("prepare"))
    }
    @Test fun `concurrent first access observes the fully published source snapshot`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);repo.save("Salvata",repo.prepareFile(fixture(120)))
        val total=repo.videos().total
        repeat(4){
            val reopened=PersonalPlaylistRepository(context)
            val pages=coroutineScope {(0..3).map {async(Dispatchers.IO){reopened.videos(VideoQuery(limit=1))}}.awaitAll()}
            assertTrue(pages.all {it.total==total && it.rows.size==1})
        }
    }
    @Test fun `database cancellation releases a long running read promptly`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);val id=repo.save("Prova",repo.prepareFile(fixture(400)))
        repo.videos();val directory=File(context.filesDir,"personal-playlists");val store=PersonalPlaylistStore(directory)
        val data=VideoLibraryData(store,PersonalPlaybackStore.get(context),prefs){_,_,_->}
        data.update(listOf(store.read(id)!!),setOf(id),emptySet());data.page(VideoQuery(limit=1))
        val entered=CountDownLatch(1)
        val query=async(Dispatchers.IO){data.readSource(id){_,signal->SQLiteDatabase.openDatabase(store.file(id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            db.rawQuery("SELECT COUNT(*) FROM video_items a,video_items b,video_items c,video_items d",null,signal).use {c->entered.countDown();c.moveToFirst()}
        }}}
        assertTrue(entered.await(5,TimeUnit.SECONDS));delay(30)
        withTimeout(1500){query.cancelAndJoin()}
        assertEquals(1,data.page(VideoQuery(limit=1)).rows.size)
    }
    @Test fun `a source writer does not serialize reads of another source`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);val a=repo.save("Prima",repo.prepareFile(fixture(30)));val b=repo.save("Seconda",repo.prepareFile(fixture(30)))
        val store=PersonalPlaylistStore(File(context.filesDir,"personal-playlists"));val data=VideoLibraryData(store,PersonalPlaybackStore.get(context),prefs){_,_,_->}
        data.update(listOf(store.read(a)!!,store.read(b)!!),setOf(a,b),emptySet());data.page(VideoQuery())
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val writer=async(Dispatchers.IO){data.write(a){entered.countDown();release.await(5,TimeUnit.SECONDS)}}
        try {assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(withTimeout(1500){data.page(VideoQuery(source=b)).rows.isNotEmpty()})}
        finally {release.countDown();writer.await()}
    }
    @Test fun `classification changes rebuild derived contents without replacing identifiers`()=runBlocking {
        val repo=PersonalPlaylistRepository(context)
        val bytes="#EXTM3U\n#EXTINF:-1 group-title=\"Film\",Titolo\nhttps://example.org/test.mp4\n".toByteArray()
        val id=repo.save("Regole",repo.prepareFile(bytes,settings=PlaylistSettings(contentOverride=PlaylistContentKind.VOD)))
        val original=repo.videos().rows.single().id
        repo.setSettings(id,PlaylistSettings(reading=PlaylistReadOptions(vodDetection=PlaylistVodDetection.EXTENDED)))
        assertEquals(original,repo.videos().rows.single().id)
        repo.setSettings(id,PlaylistSettings());assertEquals(0,repo.videos().total)
    }
    @Test fun `an older index survives an interrupted upgrade and then rebuilds with stable personal data`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);val id=repo.save("Precedente",repo.prepareFile(fixture(30)))
        val channel=repo.catalog(PersonalCatalogQuery(source=id,content=null)).rows.first()
        prefs.edit().putStringSet("favorites",setOf(channel.id)).commit()
        PersonalPlaybackStore.get(context).write(id,PlaybackTarget(channel.id,PlaybackMode.VOD),21_000,180_000)
        val store=PersonalPlaylistStore(File(context.filesDir,"personal-playlists"));val stored=store.read(id)!!
        SQLiteDatabase.openDatabase(store.file(id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("CREATE TABLE video_metadata(id TEXT PRIMARY KEY,title TEXT NOT NULL,kind TEXT NOT NULL,series_key TEXT NOT NULL,series_title TEXT NOT NULL,season INTEGER,episode INTEGER,year INTEGER,duration INTEGER NOT NULL,description TEXT NOT NULL,genres TEXT NOT NULL,poster TEXT NOT NULL,backdrop TEXT NOT NULL)")
            db.execSQL("INSERT INTO metadata VALUES('video-index','1')")
        }
        var checks=0
        try {VideoCatalogIndex.ensure(store,stored,{if(++checks==4)throw CancellationException("stop")}){_,_,_->};fail("Upgrade ignored cancellation")}
        catch(_:CancellationException){}
        SQLiteDatabase.openDatabase(store.file(id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            assertEquals("1",db.rawQuery("SELECT value FROM metadata WHERE key='video-index'",null).use {it.moveToFirst();it.getString(0)})
            assertEquals(13,db.rawQuery("PRAGMA table_info(video_metadata)",null).use {it.count})
        }
        val movie=repo.videos(VideoQuery(id=channel.id)).rows.single()
        assertEquals(channel.id,movie.id);assertTrue(movie.saved);assertEquals(21_000L,movie.position)
        assertEquals(VodKind.MOVIE,movie.kind)
    }
}
