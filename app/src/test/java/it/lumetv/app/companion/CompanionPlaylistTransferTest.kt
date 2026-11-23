package it.lumetv.app.companion

import android.content.Context
import it.lumetv.app.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class CompanionPlaylistTransferTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private val owner="ab".repeat(32)
    private var elapsed=1000L
    @Before fun reset() {
        File(app.filesDir,"personal-playlists").deleteRecursively()
        File(app.cacheDir,"playlist-transfers-in").deleteRecursively()
        app.getSharedPreferences("lume",0).edit().clear().commit()
    }
    private suspend fun exported(repo:PersonalPlaylistRepository):ExportedPlaylist {
        val input="#EXTM3U\n#EXTINF:-1,Uno\nhttps://example.org/live.m3u8?secret=private-stream-fixture\n"
        val source=repo.prepareFile(input.toByteArray()).use {repo.save("Casa",it)}
        return repo.exportPlaylist(source)
    }
    private fun receiver(scope:CoroutineScope,repo:PersonalPlaylistRepository)=CompanionPlaylistReceiver(app,scope,repo){elapsed}
    private fun body(id:String)=JSONObject().put("id",id)
    private fun start(id:String,file:ExportedPlaylist,hash:String=CompanionPlaylistWire.hash(file.file))=body(id)
        .put("name",file.name).put("size",file.file.length()).put("sha256",hash)
    private fun upload(receiver:CompanionPlaylistReceiver,id:String,file:ExportedPlaylist) {
        val input=file.file.readBytes()
        input.asList().chunked(CompanionPlaylistWire.CHUNK).forEachIndexed {index,chunk->
            val data=body(id).put("offset",index.toLong()*CompanionPlaylistWire.CHUNK)
                .put("data",Base64.getEncoder().encodeToString(chunk.toByteArray()))
            val result=receiver.request(owner,"chunk",data)
            assertEquals(result.toString(),receiver.request(owner,"chunk",data).toString())
        }
    }
    private suspend fun settled(receiver:CompanionPlaylistReceiver,id:String):JSONObject=withTimeout(20_000) {
        var state=receiver.request(owner,"status",body(id))
        while(state.getString("status") in setOf("preparing","importing")){delay(10);state=receiver.request(owner,"status",body(id))}
        state
    }
    @Test fun `preview is non destructive and repeated confirmation imports exactly once`()=runBlocking {
        val repo=PersonalPlaylistRepository(app);val receiver=receiver(this,repo)
        exported(repo).use {file->
            val id=UUID.randomUUID().toString();val offer=start(id,file)
            assertEquals(receiver.request(owner,"start",offer).toString(),receiver.request(owner,"start",offer).toString())
            upload(receiver,id,file)
            receiver.request(owner,"prepare",body(id))
            val ready=settled(receiver,id)
            assertEquals("ready",ready.getString("status"));assertEquals(1,ready.getJSONObject("preview").getInt("count"))
            assertFalse(ready.toString().contains("secret"));assertFalse(ready.toString().contains("https://"))
            assertEquals(1,repo.state.value.size)
            assertEquals("playlist_name_exists",assertThrows(CompanionFailure::class.java){receiver.request(owner,"import",body(id).put("name","Casa"))}.code)
            assertEquals("ready",receiver.request(owner,"status",body(id)).getString("status"))
            val confirm=body(id).put("name","Casa sulla TV")
            receiver.request(owner,"import",confirm);receiver.request(owner,"import",confirm)
            val done=settled(receiver,id);assertEquals("imported",done.getString("status"))
            assertEquals(done.toString(),receiver.request(owner,"import",confirm).toString())
            assertEquals(done.toString(),receiver.request(owner,"start",offer).toString())
            assertEquals(2,repo.state.value.size)
            assertTrue(File(app.cacheDir,"playlist-transfers-in").listFiles().orEmpty().isEmpty())
        }
        receiver.reset()
    }
    @Test fun `owner isolation cancellation expiry and declared size limits protect the staging area`()=runBlocking {
        val repo=PersonalPlaylistRepository(app);val receiver=receiver(this,repo)
        exported(repo).use {file->
            val id=UUID.randomUUID().toString();val offer=start(id,file)
            receiver.request(owner,"start",offer)
            assertEquals(404,assertThrows(CompanionFailure::class.java){receiver.request("cd".repeat(32),"status",body(id))}.status)
            assertEquals("playlist_busy",assertThrows(CompanionFailure::class.java){receiver.request("cd".repeat(32),"start",start(UUID.randomUUID().toString(),file))}.code)
            assertEquals("playlist_incomplete",assertThrows(CompanionFailure::class.java){receiver.request(owner,"prepare",body(id))}.code)
            assertEquals("playlist_conflict",assertThrows(CompanionFailure::class.java){receiver.request(owner,"start",start(id,file).put("name","Diversa"))}.code)
            assertEquals("cancelled",receiver.request(owner,"cancel",body(id)).getString("status"))
            val next=UUID.randomUUID().toString();receiver.request(owner,"start",start(next,file))
            elapsed+=10*60_000+1
            assertEquals("playlist_missing",assertThrows(CompanionFailure::class.java){receiver.request(owner,"status",body(next))}.code)
            assertThrows(IllegalArgumentException::class.java){receiver.request(owner,"start",start(UUID.randomUUID().toString(),file).put("size",Long.MAX_VALUE))}
            assertEquals(1,repo.state.value.size)
        }
        receiver.reset()
    }
    @Test fun `changed chunks gaps and failed integrity cannot change saved data`()=runBlocking {
        val repo=PersonalPlaylistRepository(app);val receiver=receiver(this,repo)
        exported(repo).use {file->
            val id=UUID.randomUUID().toString();receiver.request(owner,"start",start(id,file,"00".repeat(32)))
            val bytes=file.file.readBytes()
            assertEquals("playlist_offset",assertThrows(CompanionFailure::class.java){receiver.request(owner,"chunk",body(id).put("offset",1).put("data","AA=="))}.code)
            upload(receiver,id,file)
            assertEquals("playlist_conflict",assertThrows(CompanionFailure::class.java){receiver.request(owner,"chunk",body(id).put("offset",0).put("data",Base64.getEncoder().encodeToString(byteArrayOf((bytes[0].toInt() xor 1).toByte()))))}.code)
            receiver.request(owner,"prepare",body(id))
            val failed=settled(receiver,id)
            assertEquals("failed",failed.getString("status"));assertEquals("playlist_integrity",failed.getString("error"))
            assertEquals("playlist_state",assertThrows(CompanionFailure::class.java){receiver.request(owner,"import",body(id).put("name","TV"))}.code)
            assertEquals(1,repo.state.value.size)
            receiver.request(owner,"cancel",body(id))
        }
        receiver.reset()
    }
    @Test fun `index write failure after moving the draft allows a fresh upload and imports only once`()=runBlocking {
        val repo=PersonalPlaylistRepository(app);val receiver=receiver(this,repo)
        exported(repo).use {file->
            val id=UUID.randomUUID().toString();receiver.request(owner,"start",start(id,file));upload(receiver,id,file)
            receiver.request(owner,"prepare",body(id));assertEquals("ready",settled(receiver,id).getString("status"))
            val index=File(app.filesDir,"personal-playlists/index.json");val original=index.readBytes()
            val blocked=File(index.path+".new").apply {mkdir();resolve("block").writeText("fail index creation")}
            try {
                receiver.request(owner,"import",body(id).put("name","TV"))
                val failed=settled(receiver,id)
                assertEquals("failed",failed.getString("status"));assertEquals("playlist_save_failed",failed.getString("error"))
                assertFalse(failed.has("preview"));assertArrayEquals(original,index.readBytes())
                assertEquals(1,repo.state.value.size)
            }finally {blocked.deleteRecursively()}
            receiver.request(owner,"cancel",body(id))
            val retry=UUID.randomUUID().toString();receiver.request(owner,"start",start(retry,file));upload(receiver,retry,file)
            receiver.request(owner,"prepare",body(retry));assertEquals("ready",settled(receiver,retry).getString("status"))
            val confirm=body(retry).put("name","TV")
            receiver.request(owner,"import",confirm);assertEquals("imported",settled(receiver,retry).getString("status"))
            receiver.request(owner,"import",confirm)
            assertEquals(2,repo.state.value.size);assertEquals(1,repo.state.value.count {it.name=="TV"})
        }
        receiver.reset()
    }
}
