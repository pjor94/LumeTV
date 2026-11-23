package it.lumetv.app.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import it.lumetv.app.data.*
import it.lumetv.core.VodKind
import it.lumetv.core.VodMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VodPreviewProviderTest {
    @Test fun `VTT chapters load and preview child origins do not inherit private headers`()=runTest {
        MockWebServer().use {server->
            val root="http://media.example.com:${server.port}"
            val requests=CopyOnWriteArrayList<RecordedRequest>()
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                requests+=request
                return when(request.path){
                    "/preview.vtt"->MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:20.000\nhttp://other.example.com:${server.port}/frame.png#xywh=0,0,1,1")
                    "/chapters.vtt"->MockResponse().setBody("WEBVTT\n\n00:00.000 --> 00:20.000\nInizio")
                    else->MockResponse().setBody(Buffer().write(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPJkAAAAASUVORK5CYII=")))
                }
            }}
            val client=OkHttpClient.Builder().dns(object:Dns{override fun lookup(hostname:String)=listOf(InetAddress.getLoopbackAddress())}).build()
            val context=PersonalRequestContext(PlaylistSettings(artworkHttp=true),true,root+"/list.m3u")
            var chapters:List<VodChapter> = emptyList();var answered=false
            val provider=VodPreviewProvider(RuntimeEnvironment.getApplication(),backgroundScope,HttpClient(client),context,mapOf("Authorization" to "preview-secret"),
                VodMetadata("Film",VodKind.MOVIE,previewVtt=root+"/preview.vtt",chaptersVtt=root+"/chapters.vtt"),MediaItem.fromUri(root+"/movie.mp4"),
                DefaultMediaSourceFactory(RuntimeEnvironment.getApplication()),false,{true},{chapters=it},{true})
            fun waitFor(test:()->Boolean){repeat(300){testScheduler.runCurrent();if(test())return;Thread.sleep(10)};fail("Preview operation did not finish")}
            try {
                waitFor {chapters.isNotEmpty() && requests.size>=2};repeat(10){Thread.sleep(10);testScheduler.runCurrent()}
                provider.request(5_000){answered=true}
                waitFor {answered && requests.any {it.path=="/frame.png"}}
                assertEquals(listOf(VodChapter(0,"Inizio")),chapters)
                assertEquals("preview-secret",requests.first {it.path=="/preview.vtt"}.getHeader("Authorization"))
                assertNull(requests.first {it.path=="/frame.png"}.getHeader("Authorization"))
            }finally {provider.close()}
        }
    }
    @Test fun `public metadata names resolving to private addresses are rejected`()=runTest {
        MockWebServer().use {server->
            val lookups=AtomicInteger();val root="http://media.example.com:${server.port}"
            val client=OkHttpClient.Builder().dns(object:Dns{override fun lookup(hostname:String)=listOf(InetAddress.getLoopbackAddress()).also {lookups.incrementAndGet()}}).build()
            val provider=VodPreviewProvider(RuntimeEnvironment.getApplication(),backgroundScope,HttpClient(client),PersonalRequestContext(PlaylistSettings(artworkHttp=true),true,root),emptyMap(),
                VodMetadata("Film",VodKind.MOVIE,chaptersVtt=root+"/chapters.vtt"),MediaItem.fromUri(root+"/movie.mp4"),
                DefaultMediaSourceFactory(RuntimeEnvironment.getApplication()),false,{true},{fail("Private metadata was accepted")})
            try {repeat(200){testScheduler.runCurrent();if(lookups.get()>0)return@repeat;Thread.sleep(5)};assertTrue(lookups.get()>0);assertEquals(0,server.requestCount)}finally {provider.close()}
        }
    }
}
