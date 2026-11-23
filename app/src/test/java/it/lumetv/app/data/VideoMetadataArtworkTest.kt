package it.lumetv.app.data

import android.content.Context
import android.graphics.Bitmap
import it.lumetv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoMetadataArtworkTest {
    @Test fun `arriving metadata invalidates a cached absent image and never forwards playlist credentials`()=runBlocking {
        val context:Context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("video-metadata.sqlite");context.getSharedPreferences("video-metadata",0).edit().clear().commit()
        File(context.filesDir,"personal-playlists").deleteRecursively();File(context.filesDir,"video-artwork").deleteRecursively()
        VideoArtworkRepository.clearForTests()
        val repo=PersonalPlaylistRepository(context)
        repo.configureVideoMetadataForTests(object:TmdbService {
            override val configured=true
            override suspend fun search(query:VodLookupQuery)=listOf(VodTitleCandidate(42,VodMediaType.MOVIE,"Orizzonte",year=2023))
            override suspend fun details(type:VodMediaType,id:Int)=TmdbMetadata(id,type,"Orizzonte",2023,"Una storia",posterPath="/poster.jpg")
        })
        repo.prepareFile("#EXTM3U\n#EXTINF:-1 type=\"movie\" year=\"2023\",Orizzonte\nhttps://example.org/video.mp4\n".toByteArray(),
            settings=PlaylistSettings(access=mapOf(PlaylistResource.LOGO to PlaylistAccessRule(mapOf("Authorization" to "private-fixture","X-Art" to "private-fixture"))))).use {repo.save("Cinema",it)}
        val item=repo.videos().rows.single();val online=repo.videoMetadata
        val bytes=ByteArrayOutputStream().also {out->Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE);compress(Bitmap.CompressFormat.PNG,100,out);recycle()
        }}.toByteArray()
        val request=AtomicReference<Request>()
        val client=OkHttpClient.Builder().addInterceptor {chain->
            request.set(chain.request());Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val loader=VideoArtworkRepository(context,repo,HttpClient(client),scope,addressAllowed={true})
        try {
            val before=loader.bindingKey(item,false,256)
            withTimeout(3000){loader.request(item,targetPx=256){}.join()}
            assertNull(request.get())
            withTimeout(3000){online.observe(repo.videoLookup(item)!!).first {it.status==VideoMetadataStatus.READY}}
            assertNotEquals(before,loader.bindingKey(item,false,256))
            val image=CompletableDeferred<Bitmap>();val task=loader.request(item,targetPx=256){it?.let(image::complete)}
            try {assertTrue(withTimeout(3000){image.await()}.height>0)}finally {task.cancelAndJoin()}
            assertEquals("image.tmdb.org",request.get().url.host)
            assertNull(request.get().header("Authorization"));assertNull(request.get().header("X-Art"))
        }finally {loader.close();scope.cancel();online.close();VideoArtworkRepository.clearForTests()}
    }
}
