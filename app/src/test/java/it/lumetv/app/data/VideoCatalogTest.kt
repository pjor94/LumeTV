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
class VideoCatalogTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private var clock=1000L
    private fun repo()=PersonalPlaylistRepository(context,now={clock})
    @Before fun reset(){File(context.filesDir,"personal-playlists").deleteRecursively();File(context.filesDir,"personal-watch.sqlite").delete();context.getSharedPreferences("lume",0).edit().clear().commit()}
    private fun list(vararg rows:Triple<String,String,String>)=buildString {appendLine("#EXTM3U");rows.forEach {(name,attrs,path)->appendLine("#EXTINF:-1 $attrs,$name");appendLine("https://example.org/$path")}}.toByteArray()
    private val mixed get()=list(Triple("Zeta","type=\"movie\" group-title=\"Film\" year=\"2022\" plot=\"Una storia\"","movie/u/p/1.mp4"),
        Triple("Orbita S02E02","type=\"series\" group-title=\"Serie\"","series/u/p/2.mp4"),
        Triple("Orbita S01E01","type=\"series\" group-title=\"Serie\"","series/u/p/3.mp4"),
        Triple("Orbita S02E01","type=\"series\" group-title=\"Serie\"","series/u/p/4.mp4"),
        Triple("Concerto","type=\"vod\" group-title=\"Musica\"","concert.mp4"),Triple("Diretta","type=\"live\"","live/u/p/1.mp4"))
    @Test fun `series collapse in browsing while episodes remain numerically ordered and playable`()=runTest {
        val repo=repo();val id=repo.save("Mista",repo.prepareFile(mixed));val page=repo.videos()
        assertEquals(3,page.total);val series=page.rows.single {it.isSeries};assertEquals("Orbita",series.displayTitle);assertEquals(3,series.episodes)
        val episodes=repo.videos(VideoQuery(source=id,series=series.series,collapseSeries=false)).rows
        assertEquals(listOf("Orbita S01E01","Orbita S02E01","Orbita S02E02"),episodes.map {it.title})
        assertEquals(listOf(1,2),repo.videoSeasons(series).first)
        assertEquals(2,repo.videos(VideoQuery(source=id,series=series.series,season=2,collapseSeries=false)).total)
        assertTrue(episodes.all {!it.isSeries && repo.channel(it.id,id)?.isVod==true})
    }
    @Test fun `unknown VOD stays accessible and films and series filters remain separate`()=runTest {
        val repo=repo();repo.save("Mista",repo.prepareFile(mixed))
        assertEquals(listOf("Zeta"),repo.videos(VideoQuery(kind=VodKind.MOVIE)).rows.map {it.title})
        assertEquals(listOf("Orbita"),repo.videos(VideoQuery(kind=VodKind.EPISODE)).rows.map {it.displayTitle})
        assertEquals(listOf("Concerto"),repo.videos(VideoQuery(group="Musica")).rows.map {it.title})
        assertEquals("Una storia",repo.videos(VideoQuery(kind=VodKind.MOVIE)).rows.single().description)
    }
    @Test fun `continue watching is recent first ignores completed content and preserves episode identity`()=runTest {
        val repo=repo();val id=repo.save("Mista",repo.prepareFile(mixed))
        val all=repo.videos(VideoQuery(collapseSeries=false)).rows;val movie=all.single {it.kind==VodKind.MOVIE};val episode=all.first {it.kind==VodKind.EPISODE}
        val history=PersonalPlaybackStore.get(context)
        history.write(id,PlaybackTarget(movie.id,PlaybackMode.VOD),14_000,180_000)
        history.write(id,PlaybackTarget(episode.id,PlaybackMode.VOD),29_000,90_000)
        val page=repo.videos(VideoQuery(shelf=VideoShelf.CONTINUE))
        assertEquals(setOf(movie.id,episode.id),page.rows.map {it.id}.toSet());assertTrue(page.rows.none {it.isSeries})
        history.write(id,PlaybackTarget(movie.id,PlaybackMode.VOD),180_000,180_000,true)
        assertEquals(episode.id,repo.videos(VideoQuery(shelf=VideoShelf.CONTINUE)).rows.single().id)
        repo.setEnabled(id,false);assertEquals(0,repo.videos(VideoQuery(shelf=VideoShelf.CONTINUE)).total)
    }
    @Test fun `saved series survives representative reordering and remains isolated by source`()=runTest {
        val repo=repo();val id=repo.save("Prima",repo.prepareFile(mixed));repo.save("Seconda",repo.prepareFile(mixed))
        val series=repo.videos(VideoQuery(source=id,kind=VodKind.EPISODE)).rows.single();repo.toggleSavedSeries(series)
        assertEquals(id,repo.videos(VideoQuery(shelf=VideoShelf.SAVED)).rows.single().source)
        val reorder=list(Triple("Orbita S01E01","type=\"series\"","series/u/p/3.mp4"),Triple("Orbita S02E02","type=\"series\"","series/u/p/2.mp4"))
        repo.save("Prima",repo.prepareFile(reorder),id)
        val saved=repo.videos(VideoQuery(shelf=VideoShelf.SAVED)).rows.single();assertEquals(series.series,saved.series);assertTrue(saved.saved)
        repo.toggleSavedSeries(saved);assertEquals(0,repo.videos(VideoQuery(shelf=VideoShelf.SAVED)).total)
    }
    @Test fun `legacy favorites are reused and removing a saved series clears its episode favorites`()=runTest {
        val repo=repo();repo.save("Mista",repo.prepareFile(mixed))
        val all=repo.videos(VideoQuery(collapseSeries=false)).rows;val ids=all.filter {it.kind==VodKind.EPISODE || it.kind==VodKind.MOVIE}.map {it.id}.toSet()
        context.getSharedPreferences("lume",0).edit().putStringSet("favorites",ids).commit()
        val saved=repo.videos(VideoQuery(shelf=VideoShelf.SAVED));assertEquals(2,saved.total)
        repo.toggleSavedSeries(saved.rows.single {it.isSeries})
        assertEquals(listOf("Zeta"),repo.videos(VideoQuery(shelf=VideoShelf.SAVED)).rows.map {it.title})
    }
    @Test fun `global title order and paging work across every source without duplicate or missing rows`()=runTest {
        val repo=repo()
        for(i in 0..9)repo.save("Lista $i",repo.prepareFile(list(Triple("Titolo ${9-i}","type=\"movie\"","$i.mp4"))))
        val a=repo.videos(VideoQuery(order=VideoOrder.TITLE,limit=4));val b=repo.videos(VideoQuery(order=VideoOrder.TITLE,offset=4,limit=6))
        assertEquals(10,a.total);assertEquals((0..9).map {"Titolo $it"},(a.rows+b.rows).map {it.title})
    }
    @Test fun `added dates survive refresh and existing archives are not marked newly added`()=runTest {
        val repo=repo();val id=repo.save("Mista",repo.prepareFile(mixed));val old=repo.videos().rows.associate {it.id to it.added}
        clock=2000;val next=mixed+"#EXTINF:-1 type=\"movie\",Nuovo\nhttps://example.org/100.mp4\n".toByteArray()
        repo.save("Mista",repo.prepareFile(next),id)
        val rows=repo.videos(VideoQuery(order=VideoOrder.ADDED)).rows
        assertEquals("Nuovo",rows.first().title);assertEquals(2000L,rows.first().added);rows.filter {it.id in old}.forEach {assertEquals(old[it.id],it.added)}
        val file=File(context.filesDir,"personal-playlists/$id.sqlite")
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("DROP TABLE video_added");it.execSQL("DELETE FROM metadata WHERE key='video-index'")}
        assertTrue(repo.videos().rows.all {it.added==0L})
    }
    @Test fun `manual live override hides VOD while forcing VOD indexes previously live rows`()=runTest {
        val repo=repo();val id=repo.save("Mista",repo.prepareFile(mixed));repo.videos()
        repo.setSettings(id,PlaylistSettings(contentOverride=PlaylistContentKind.LIVE));assertEquals(0,repo.videos().total)
        repo.setSettings(id,PlaylistSettings(contentOverride=PlaylistContentKind.VOD));assertTrue(repo.videos().rows.any {it.title=="Diretta"})
        repo.setSettings(id,PlaylistSettings());assertFalse(repo.videos().rows.any {it.title=="Diretta"})
    }
    @Test fun `an interrupted index build rolls back without changing original records and can retry`()=runTest {
        val repo=repo();val id=repo.save("Mista",repo.prepareFile(mixed));val store=PersonalPlaylistStore(File(context.filesDir,"personal-playlists"));val stored=store.read(id)!!
        val original=repo.catalog(PersonalCatalogQuery(source=id,content=null)).rows.map {it.id};var checks=0
        try {VideoCatalogIndex.ensure(store,stored,{if(++checks==4)throw kotlinx.coroutines.CancellationException("stop")}){_,_,_->};fail("Cancellation ignored")}catch(_:kotlinx.coroutines.CancellationException){}
        SQLiteDatabase.openDatabase(store.file(id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            assertFalse(db.rawQuery("SELECT value FROM metadata WHERE key='video-index'",null).use {it.moveToFirst()})
        }
        assertEquals(original,repo.catalog(PersonalCatalogQuery(source=id,content=null)).rows.map {it.id})
        assertEquals(3,repo.videos().total)
    }
    @Test fun `index storage budget failure preserves the source and is recoverable`()=runTest {
        val repo=repo();val id=repo.save("Mista",repo.prepareFile(mixed));val occupied=File(context.filesDir,"personal-playlists/occupied.sqlite")
        try {
            java.io.RandomAccessFile(occupied,"rw").use {it.setLength(PersonalPlaylistStore.MAX_DISK)}
            try {repo.videos();fail("Storage budget ignored")}catch(_:java.io.IOException){}
            assertTrue(repo.state.value.single().enabled);assertEquals(6,repo.catalog(PersonalCatalogQuery(source=id,content=null)).total)
            occupied.delete();assertEquals(3,repo.videos().total)
        }finally {occupied.delete()}
    }
    @Test fun `artwork keys survive reopening isolate access policy and never inherit stream secrets`()=runTest {
        val repo=repo();val data=list(Triple("Film","type=\"movie\" poster=\"http://images.example.org/cover.png\" http-user-agent=\"Player\"","one.mp4"))
        val initial=PlaylistSettings(artworkHttp=true,access=mapOf(PlaylistResource.LOGO to PlaylistAccessRule(mapOf("X-Image" to "first"))))
        val id=repo.save("Arte",repo.prepareFile(data,settings=initial));val item=repo.videos().rows.single();val first=repo.videoArtwork(item,false)!!
        assertEquals("first",first.headers["X-Image"]);assertEquals("Player",first.headers["User-Agent"]);assertNull(first.headers["Cookie"])
        val reopened=repo();reopened.local();reopened.videos();assertEquals(first.cacheKey,reopened.videoArtwork(item,false)!!.cacheKey)
        reopened.setSettings(id,initial.copy(access=mapOf(PlaylistResource.LOGO to PlaylistAccessRule(mapOf("X-Image" to "second")))))
        val second=reopened.videoArtwork(item,false)!!;assertNotEquals(first.cacheKey,second.cacheKey);assertEquals("second",second.headers["X-Image"])
        reopened.setSettings(id,initial.copy(artworkHttp=false));assertNull(reopened.videoArtwork(item,false))
    }
    @Test fun `episode collections without season numbers remain selectable`()=runTest {
        val repo=repo();repo.save("Serie",repo.prepareFile(list(Triple("Puntata","type=\"episode\" series-name=\"Orbita\"","episode.mp4"))))
        val series=repo.videos().rows.single();assertEquals(listOf(0),repo.videoSeasons(series).first)
        assertEquals(1,repo.videos(VideoQuery(source=series.source,series=series.series,season=0,collapseSeries=false)).total)
    }
}
