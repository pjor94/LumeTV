package it.lumetv.app.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodMediaCacheTest {
    private fun awaitCleanup(session:VodMediaCache.Session)=runBlocking {withTimeout(5_000){session.cleared.await()}}
    private fun fill(store:VodMediaCache,session:VodMediaCache.Session,uri:Uri,name:String="movie"):androidx.media3.datasource.DataSource {
        val upstream=androidx.media3.datasource.DataSource.Factory {androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096){7})}
        val factory=session.track(store.factory(upstream,session.namespace(name)){true})
        return factory.createDataSource().also {it.open(DataSpec(uri));assertEquals(4096,it.read(ByteArray(4096),0,4096))}
    }
    @Test fun `retiring a session waits for the writer and prevents late writes from recreating its cache`() {
        val store=VodMediaCache(RuntimeEnvironment.getApplication());val session=store.session()
        try {
            val uri=Uri.parse("https://video.test/one.mp4");val source=fill(store,session,uri)
            session.close();assertFalse(session.cleared.isCompleted)
            try {source.read(ByteArray(1),0,1);fail("Retired reader remained usable")}catch(_:java.io.IOException){}
            source.close();awaitCleanup(session)
            assertEquals(0,store.cache.cacheSpace);assertTrue(store.cache.keys.isEmpty())
            session.close();assertTrue(session.cleared.isCompleted)
        }finally {session.close();awaitCleanup(session);store.cache.release()}
    }
    @Test fun `old cleanup cannot delete the new content even at an identical URL`() {
        val store=VodMediaCache(RuntimeEnvironment.getApplication());val old=store.session();val next=store.session()
        try {
            val uri=Uri.parse("https://video.test/movie.mp4")
            val oldSource=fill(store,old,uri);old.close()
            fill(store,next,uri).close()
            val nextKey=store.key(next.namespace("movie"),uri)
            assertTrue(store.cache.isCached(nextKey,0,4096))
            oldSource.close();awaitCleanup(old)
            assertFalse(store.cache.keys.any {it.startsWith(old.namespace(""))})
            assertTrue(store.cache.isCached(nextKey,0,4096))
            next.close();awaitCleanup(next);assertEquals(0,store.cache.cacheSpace)
        }finally {old.close();next.close();awaitCleanup(old);awaitCleanup(next);store.cache.release()}
    }
    @Test fun `pause and reopening a source within a content session preserve its bytes`() {
        val store=VodMediaCache(RuntimeEnvironment.getApplication());val session=store.session()
        try {
            val uri=Uri.parse("https://video.test/movie.mp4");fill(store,session,uri).close()
            val upstream=androidx.media3.datasource.DataSource.Factory {androidx.media3.datasource.ByteArrayDataSource(byteArrayOf(0))}
            val replay=session.track(store.factory(upstream,session.namespace("movie")){true}).createDataSource()
            replay.open(DataSpec(uri));assertEquals(4096,replay.read(ByteArray(4096),0,4096));replay.close()
            assertEquals(4096,store.cache.cacheSpace)
            session.close();awaitCleanup(session);assertEquals(0,store.cache.cacheSpace)
        }finally {session.close();awaitCleanup(session);store.cache.release()}
    }
    @Test fun `startup removes abandoned media while preserving the new session`() {
        val context=RuntimeEnvironment.getApplication();val old=VodMediaCache(context)
        val uri=Uri.parse("https://video.test/movie.mp4")
        val upstream=androidx.media3.datasource.DataSource.Factory {androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096))}
        val data=old.factory(upstream,"abandoned"){true}.createDataSource();data.open(DataSpec(uri));data.read(ByteArray(4096),0,4096);data.close()
        assertTrue(old.cache.cacheSpace>0);runBlocking {old.startupCleanup.join()};old.cache.release()
        val restored=VodMediaCache(context);val session=restored.session()
        try {
            fill(restored,session,uri).close();runBlocking {withTimeout(5_000){restored.startupCleanup.join()}}
            assertFalse(restored.cache.keys.contains(restored.key("abandoned",uri)))
            assertTrue(restored.cache.isCached(restored.key(session.namespace("movie"),uri),0,4096))
        }finally {session.close();awaitCleanup(session);restored.cache.release()}
    }
    @Test fun `optional transfer cleanup waits until cancellation has closed its writer`() {
        val store=VodMediaCache(RuntimeEnvironment.getApplication());val session=store.session()
        try {runBlocking {
            val entered=CompletableDeferred<Unit>();val leave=CompletableDeferred<Unit>()
            val transfer=launch {session.access {entered.complete(Unit);leave.await()}}
            entered.await();session.close();assertFalse(session.cleared.isCompleted)
            transfer.cancelAndJoin();withTimeout(5_000){session.cleared.await()}
            try {session.access {fail("Retired transfer was restarted")};fail("Retired lease accepted work")}catch(_:java.io.IOException){}
            assertEquals(0,store.cache.cacheSpace)
        }}finally {session.close();awaitCleanup(session);store.cache.release()}
    }
    @Test fun `abandoned downloads are removed even when free space disables caching`() {
        val context=RuntimeEnvironment.getApplication();val old=VodMediaCache(context)
        runBlocking {old.startupCleanup.join()}
        val uri=Uri.parse("https://video.test/movie.mp4")
        val factory=old.factory({androidx.media3.datasource.ByteArrayDataSource(ByteArray(4096))},"abandoned"){true}
        val data=factory.createDataSource();data.open(DataSpec(uri));data.read(ByteArray(4096),0,4096);data.close()
        assertTrue(old.cache.cacheSpace>0)
        val files=old.cache.getCachedSpans(old.key("abandoned",uri)).map {requireNotNull(it.file)}
        assertTrue(files.isNotEmpty());old.cache.release()
        val full=object:android.content.ContextWrapper(context){
            override fun getCacheDir()=object:java.io.File(context.cacheDir.path){override fun getUsableSpace()=0L}
        }
        try {VodMediaCache(full);fail("Caching should remain disabled on a full device")}catch(_:IllegalArgumentException){}
        assertTrue(files.none {it.exists()})
    }
    @Test fun `servers ignoring byte ranges fall back to one continuous transfer`() {
        val bytes=ByteArray(3*1024*1024+71){(it%193).toByte()}
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody(Buffer().write(bytes))}
            val store=VodMediaCache(RuntimeEnvironment.getApplication())
            try {
                val uri=Uri.parse(server.url("/movie.mp4").toString())
                val factory=store.factory(PersonalMediaSources.http(OkHttpClient(),emptyMap()),"continuous"){true}
                val source=VodChunkDataSource(factory,store,"continuous");source.open(DataSpec(uri))
                val output=ByteArrayOutputStream();val buffer=ByteArray(64*1024)
                while(true){val count=source.read(buffer,0,buffer.size);if(count==C.RESULT_END_OF_INPUT)break;output.write(buffer,0,count)}
                source.close();assertArrayEquals(bytes,output.toByteArray());assertEquals(2,server.requestCount)
            }finally {store.cache.release()}
        }
    }
    @Test fun `bounded playback reads allow look-ahead then reuse the same cached bytes`() {
        val bytes=ByteArray(6*1024*1024+321){(it%251).toByte()}
        MockWebServer().use {server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                val range=request.getHeader("Range")?.removePrefix("bytes=")?.split('-')
                val start=range?.firstOrNull()?.toIntOrNull() ?: 0
                if(start>=bytes.size)return MockResponse().setResponseCode(416).setHeader("Content-Range","bytes */${bytes.size}")
                val end=minOf(range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex,bytes.lastIndex)
                return MockResponse().setResponseCode(206).setHeader("Content-Range","bytes $start-$end/${bytes.size}")
                    .setHeader("Accept-Ranges","bytes").setBody(Buffer().write(bytes,start,end-start+1))
            }}
            val store=VodMediaCache(RuntimeEnvironment.getApplication())
            try {
                val uri=Uri.parse(server.url("/movie.mp4").toString())
                val factory=store.factory(PersonalMediaSources.http(OkHttpClient(),emptyMap()),"movie"){true}
                val source=VodChunkDataSource(factory,store,"movie")
                assertEquals(bytes.size.toLong(),source.open(DataSpec(uri)))
                val output=ByteArrayOutputStream();val buffer=ByteArray(64*1024)
                val first=source.read(buffer,0,buffer.size);output.write(buffer,0,first)
                // Playback still owns the first hole. A disjoint range must be writable now.
                CacheWriter(factory.createDataSource(),DataSpec.Builder().setUri(uri).setPosition(4L*1024*1024).setLength(2L*1024*1024).build(),null,null).cache()
                assertTrue(store.cache.isCached(store.key("movie",uri),4L*1024*1024,2L*1024*1024))
                while(true){val count=source.read(buffer,0,buffer.size);if(count==C.RESULT_END_OF_INPUT)break;output.write(buffer,0,count)}
                source.close();assertArrayEquals(bytes,output.toByteArray())
                val requests=server.requestCount
                val replay=VodChunkDataSource(factory,store,"movie");replay.open(DataSpec(uri))
                while(replay.read(buffer,0,buffer.size)!=C.RESULT_END_OF_INPUT)Unit
                replay.close();assertEquals(requests,server.requestCount)
            }finally {store.cache.release()}
        }
    }
    @Test fun `revoked access is checked even when the requested bytes are cached`() {
        val store=VodMediaCache(RuntimeEnvironment.getApplication())
        try {
            var valid=true
            val upstream=androidx.media3.datasource.ByteArrayDataSource(byteArrayOf(1,2,3))
            val factory=store.factory({upstream},"private"){valid}
            val uri=Uri.parse("https://video.test/private.mp4")
            val first=store.guarded(factory){valid}.createDataSource();first.open(DataSpec(uri));first.read(ByteArray(3),0,3);first.close()
            valid=false
            val cached=store.guarded(factory){valid}.createDataSource()
            try {cached.open(DataSpec(uri));fail("Revoked source served bytes")}catch(_:java.io.IOException){}finally {cached.close()}
        }finally {store.cache.release()}
    }
}
