package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VideoMetadataRepositoryTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val opened=mutableListOf<VideoMetadataRepository>()
    private class Api:TmdbService {
        override val configured=true
        var candidates=listOf(VodTitleCandidate(42,VodMediaType.MOVIE,"Orizzonte",year=2023))
        val searches=AtomicInteger();val downloads=AtomicInteger()
        val started=CompletableDeferred<Unit>();var gate:CompletableDeferred<Unit>?=null
        var fail=false
        var trailers=listOf(TmdbTrailer("AAAAAAAAAAA","YouTube","it",official=true))
        override suspend fun search(query:VodLookupQuery):List<VodTitleCandidate>{searches.incrementAndGet();if(fail)throw TmdbException();return candidates}
        override suspend fun details(type:VodMediaType,id:Int):TmdbMetadata {
            downloads.incrementAndGet();started.complete(Unit);gate?.await();if(fail)throw TmdbException()
            return TmdbMetadata(id,type,"Orizzonte",2023,"Una storia italiana",cast=listOf("Anna Rossi"),runtimeMinutes=120,posterPath="/poster.jpg",trailers=trailers)
        }
    }
    @Before fun reset() {
        context.getSharedPreferences("video-metadata",0).edit().clear().commit()
        context.getSharedPreferences("lume",0).edit().clear().commit()
        File(context.filesDir,"personal-playlists").deleteRecursively();context.deleteDatabase("video-metadata.sqlite")
    }
    @After fun close(){opened.forEach {it.close()};VideoArtworkRepository.clearForTests()}
    private val playlist="#EXTM3U\n#EXTINF:-1 type=\"movie\" year=\"2023\",Orizzonte (2023)\nhttps://example.org/movie.mp4\n"
    private suspend fun fixture():Pair<PersonalPlaylistRepository,VideoItem> {
        val repo=PersonalPlaylistRepository(context)
        repo.prepareFile(playlist.toByteArray()).use {repo.save("Cinema",it)}
        return repo to repo.videos().rows.single()
    }
    private fun metadata(repo:PersonalPlaylistRepository,api:Api,clock:AtomicLong=AtomicLong(System.currentTimeMillis()))=
        VideoMetadataRepository(context,repo,api,VideoMetadataStore(context),clock::get).also {opened+=it}
    private suspend fun resolved(meta:VideoMetadataRepository,input:VideoLookup)=withTimeout(5000) {meta.observe(input).first {it.status!=VideoMetadataStatus.LOADING}}

    @Test fun `metadata is reused across repository restarts and playback duration stays local`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val api=Api()
        val one=metadata(repo,api);assertEquals(VideoMetadataStatus.READY,resolved(one,input).status)
        assertEquals(1,api.searches.get());assertEquals(1,api.downloads.get())
        assertEquals(0L,repo.videoDetails(item)!!.duration)
        one.close();opened.remove(one)
        val offline=Api().apply {fail=true};val two=metadata(repo,offline)
        assertEquals("Una storia italiana",resolved(two,input).details!!.overview)
        assertEquals(0,offline.searches.get());assertEquals(0,offline.downloads.get())
    }
    @Test fun `playlist replacement preserves the mapping for the same reconciled title`()=runBlocking {
        val (repo,item)=fixture();val api=Api();val meta=metadata(repo,api)
        resolved(meta,repo.videoLookup(item)!!)
        repo.prepareFile(playlist.toByteArray()).use {repo.save("Cinema",it,item.source)}
        val next=repo.videos().rows.single();assertEquals(item.id,next.id)
        assertEquals(VideoMetadataStatus.READY,resolved(meta,repo.videoLookup(next)!!).status)
        assertEquals(1,api.searches.get());assertEquals(1,api.downloads.get())
    }
    @Test fun `old cache refreshes trailers without losing a manual association or redownloading on the next open`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val api=Api();val clock=System.currentTimeMillis()
        VideoMetadataStore(context).use {store->
            store.save(input,VideoStoredMatch(42,emptyList(),true,false,Long.MAX_VALUE),
                TmdbMetadata(42,VodMediaType.MOVIE,"Orizzonte",2023,"Trama salvata",trailersLoaded=false),clock){true}
            val db=store.writableDatabase
            val payload=db.rawQuery("SELECT payload FROM details",null).use {it.moveToFirst();org.json.JSONObject(it.getString(0))}
            payload.remove("trailers");payload.remove("trailersLoaded")
            db.execSQL("UPDATE details SET payload=?",arrayOf(payload.toString()))
        }
        val meta=metadata(repo,api)
        repeat(2){assertEquals(api.trailers,resolved(meta,input).details!!.trailers)}
        assertEquals(0,api.searches.get());assertEquals(1,api.downloads.get())
        VideoMetadataStore(context).use {assertTrue(it.match(input)!!.manual)}
    }
    @Test fun `a checked empty trailer list remains cached`()=runBlocking {
        val (repo,item)=fixture();val api=Api().apply {trailers=emptyList()};val meta=metadata(repo,api)
        repeat(2){assertTrue(resolved(meta,repo.videoLookup(item)!!).details!!.trailers.isEmpty())}
        assertEquals(1,api.downloads.get())
    }
    @Test fun `ambiguous matches support persistent manual selection and explicit exclusion`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!
        val api=Api().apply {candidates=candidates+VodTitleCandidate(43,VodMediaType.MOVIE,"Orizzonte",year=2023)}
        val meta=metadata(repo,api)
        assertEquals(VideoMetadataStatus.AMBIGUOUS,resolved(meta,input).status);assertEquals(0,api.downloads.get())
        meta.choose(input,api.candidates.last())
        assertEquals(43,resolved(meta,input).details!!.id)
        meta.choose(input,null)
        assertEquals(VideoMetadataStatus.EXCLUDED,resolved(meta,input).status)
        assertEquals(1,api.downloads.get())
        VideoMetadataStore(context).use {store->assertTrue(store.manualChoices().getJSONObject(0).getBoolean("excluded"))}
    }
    @Test fun `expired metadata refreshes by id and a network failure keeps the saved details`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val api=Api();val clock=AtomicLong(System.currentTimeMillis())
        val meta=metadata(repo,api,clock);resolved(meta,input)
        clock.addAndGet(31*VideoMetadataStore.DAY);api.fail=true
        val state=resolved(meta,input)
        assertEquals(VideoMetadataStatus.ERROR,state.status);assertEquals("Una storia italiana",state.details!!.overview)
        assertEquals(1,api.searches.get());assertEquals(2,api.downloads.get())
    }
    @Test fun `negative results are cached but transient failures are not saved as missing titles`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val api=Api().apply {candidates=emptyList()}
        val clock=AtomicLong(System.currentTimeMillis());val meta=metadata(repo,api,clock)
        repeat(2){assertEquals(VideoMetadataStatus.NOT_FOUND,resolved(meta,input).status)}
        assertEquals(1,api.searches.get());clock.addAndGet(2*VideoMetadataStore.DAY);api.fail=true
        assertEquals(VideoMetadataStatus.ERROR,resolved(meta,input).status)
        VideoMetadataStore(context).use {store->assertTrue(store.match(input)!!.expires<clock.get())}
    }
    @Test fun `disabling online data hides saved enrichment without issuing requests`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val api=Api();val meta=metadata(repo,api)
        resolved(meta,input);meta.setEnabled(false)
        assertEquals(VideoMetadataStatus.DISABLED,resolved(meta,input).status);assertNull(meta.cached(input))
        assertEquals(1,api.searches.get());assertEquals(1,api.downloads.get())
    }
    @Test fun `disabling during manual search debounce prevents the request`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val api=Api();val meta=metadata(repo,api)
        val search=async {meta.suggestions(input,"Altro titolo")};delay(100);meta.setEnabled(false)
        assertTrue(search.await().isEmpty());assertEquals(0,api.searches.get())
    }
    @Test fun `removing a source during a request cannot recreate its mapping`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val gate=CompletableDeferred<Unit>()
        val api=Api().apply {this.gate=gate};val meta=metadata(repo,api)
        val observer=launch {meta.observe(input).collect()}
        withTimeout(3000){api.started.await()};repo.delete(item.source);gate.complete(Unit)
        delay(100);observer.cancelAndJoin()
        VideoMetadataStore(context).use {store->assertNull(store.match(input))}
    }
    @Test fun `cancelling one observer preserves the shared request for the other`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val gate=CompletableDeferred<Unit>()
        val api=Api().apply {this.gate=gate};val meta=metadata(repo,api)
        val first=launch {meta.observe(input).collect()}
        withTimeout(3000){api.started.await()}
        val second=async {resolved(meta,input)};delay(100);first.cancelAndJoin();gate.complete(Unit)
        assertEquals(VideoMetadataStatus.READY,second.await().status)
        assertEquals(1,api.searches.get());assertEquals(1,api.downloads.get())
    }
    @Test fun `manual backup choices roll back together with a failed restore`()=runBlocking {
        val (repo,item)=fixture();val input=repo.videoLookup(item)!!;val meta=metadata(repo,Api())
        meta.choose(input,VodTitleCandidate(42,VodMediaType.MOVIE,"Orizzonte",year=2023))
        VideoMetadataStore(context).use {store->
            val choices=store.manualChoices();store.writableDatabase.execSQL("DELETE FROM matches")
            assertTrue(runCatching {store.restoreWithChoices(choices,setOf(item.source)){error("failure")}}.isFailure)
            assertNull(store.match(input));store.restoreChoices(choices,setOf(item.source));assertEquals(42,store.match(input)!!.id)
        }
    }
}
