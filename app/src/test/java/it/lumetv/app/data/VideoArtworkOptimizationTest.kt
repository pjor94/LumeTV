package it.lumetv.app.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoArtworkOptimizationTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val png:ByteArray get()=ByteArrayOutputStream().also {out->Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE);compress(Bitmap.CompressFormat.PNG,100,out);recycle()}}.toByteArray()
    @Before fun reset(){VideoArtworkRepository.clearForTests();File(context.filesDir,"personal-playlists").deleteRecursively();File(context.filesDir,"personal-watch.sqlite").delete();File(context.filesDir,"video-artwork").deleteRecursively();context.getSharedPreferences("lume",0).edit().clear().commit()}
    @After fun close(){work.cancel();VideoArtworkRepository.clearForTests()}
    private fun http(started:(Call)->Unit={})=HttpClient(OkHttpClient.Builder().dns(object:Dns {override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1"))}).addInterceptor {chain->started(chain.call());chain.proceed(chain.request())}.build())
    private suspend fun fixture(server:MockWebServer,n:Int=1):Pair<PersonalPlaylistRepository,List<VideoItem>> {
        val repo=PersonalPlaylistRepository(context)
        val data=buildString {appendLine("#EXTM3U");repeat(n){i->appendLine("#EXTINF:-1 type=\"movie\" poster=\"http://images.example.org:${server.port}/$i.png\",Film $i");appendLine("https://example.org/$i.mp4")}}
        repo.save("Arte",repo.prepareFile(data.toByteArray(),settings=PlaylistSettings(artworkHttp=true,access=mapOf(PlaylistResource.LOGO to PlaylistAccessRule(mapOf("X-Art" to "test"))))))
        return repo to repo.videos().rows
    }
    private fun art(repo:PersonalPlaylistRepository,http:HttpClient)=VideoArtworkRepository(context,repo,http,work,addressAllowed={true})
    private suspend fun image(loader:VideoArtworkRepository,item:VideoItem,size:Int=256):Bitmap {
        val result=CompletableDeferred<Bitmap>();val task=loader.request(item,targetPx=size){if(it!=null)result.complete(it)}
        return try {withTimeout(3000){result.await()}}finally {task.cancelAndJoin()}
    }
    @Test fun `reopening a screen hits shared memory before resolving its private artwork`()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(Buffer().write(png)))
            val (repo,items)=fixture(server);val client=http();val first=art(repo,client);val bitmap=image(first,items.single());first.close()
            val lookups=VideoPerformance.snapshot()["art_lookup"]
            val second=art(repo,client);assertSame(bitmap,image(second,items.single()));assertEquals(lookups,VideoPerformance.snapshot()["art_lookup"])
            assertEquals(1,server.requestCount);assertTrue(maxOf(bitmap.width,bitmap.height)<=256)
            assertEquals("test",server.takeRequest(1,TimeUnit.SECONDS)!!.getHeader("X-Art"))
        }
    }
    @Test fun `different poster sizes share one lookup and one transfer`()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(Buffer().write(png)).setBodyDelay(250,TimeUnit.MILLISECONDS))
            val (repo,items)=fixture(server);val loader=art(repo,http());val before=VideoPerformance.snapshot()["art_lookup"] ?: 0L
            val images=coroutineScope {listOf(async{image(loader,items.single(),128)},async{image(loader,items.single(),384)}).awaitAll()}
            assertEquals(listOf(128,384),images.map {maxOf(it.width,it.height)});assertEquals(1,server.requestCount)
            assertEquals(before+1,VideoPerformance.snapshot()["art_lookup"])
        }
    }
    @Test fun `disk hits do not wait behind the network download queue`()=runBlocking {
        MockWebServer().use {server->server.start();repeat(3){server.enqueue(MockResponse().setBody(Buffer().write(png)).setBodyDelay(2,TimeUnit.SECONDS))}
            val (repo,items)=fixture(server,4);val reference=repo.videoArtwork(items.last(),false)!!
            val file=File(context.filesDir,"video-artwork/${items.last().source}-${reference.cacheKey}-p.image").apply {parentFile!!.mkdirs();writeBytes(png)}
            // Stale artwork is shown immediately, even though its refresh must wait for a permit.
            file.setLastModified(System.currentTimeMillis()-7*60*60_000)
            val loader=art(repo,http());val slow=items.take(3).map {loader.request(it){}}
            repeat(3){assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))}
            val before=System.nanoTime();val bitmap=image(loader,items.last())
            assertTrue("Cached bitmap waited for downloads",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<1000)
            assertEquals(256,maxOf(bitmap.width,bitmap.height));slow.forEach {it.cancelAndJoin()}
        }
    }
    @Test fun `leaving the last subscriber cancels a slow response body`()=runBlocking {
        val call=AtomicReference<Call>()
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(Buffer().write(png)).setBodyDelay(2,TimeUnit.SECONDS))
            val (repo,items)=fixture(server);val client=http {call.set(it)}
            val loader=art(repo,client);val job=loader.request(items.single()){}
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));delay(100);job.cancelAndJoin()
            withTimeout(1000){while(call.get()?.isCanceled()!=true)delay(10)}
        }
    }
    @Test fun `cancelling one observer preserves the other observer transfer`()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(Buffer().write(png)).setBodyDelay(250,TimeUnit.MILLISECONDS))
            val (repo,items)=fixture(server);val loader=art(repo,http());val one=loader.request(items.single(),targetPx=256){}
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));val two=async {image(loader,items.single())};delay(50);one.cancelAndJoin()
            assertNotNull(two.await());assertEquals(1,server.requestCount)
        }
    }
    @Test fun `an access policy change revokes cached private artwork`()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setBody(Buffer().write(png)))
            val (repo,items)=fixture(server);val loader=art(repo,http());image(loader,items.single())
            repo.setSettings(items.single().source,PlaylistSettings(artworkHttp=false))
            var displayed:Bitmap?=null
            withTimeout(3000){loader.request(items.single(),targetPx=256){displayed=it}.join()}
            assertNull(displayed);assertEquals(1,server.requestCount)
        }
    }
    @Test fun `missing artwork is backed off across screen reopenings`()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setResponseCode(404))
            val (repo,items)=fixture(server);val client=http();val first=art(repo,client)
            withTimeout(3000){first.request(items.single()){assertNull(it)}.join()};first.close()
            val lookups=VideoPerformance.snapshot()["art_lookup"]
            withTimeout(3000){art(repo,client).request(items.single()){assertNull(it)}.join()}
            assertEquals(1,server.requestCount);assertEquals(lookups,VideoPerformance.snapshot()["art_lookup"])
        }
    }
}
