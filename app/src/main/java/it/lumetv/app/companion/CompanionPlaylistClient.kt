package it.lumetv.app.companion

import it.lumetv.app.data.ExportedPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.RandomAccessFile
import java.util.Base64

internal class CompanionPlaylistClient(private val connection:CompanionClient) {
    suspend fun upload(id:String,playlist:ExportedPlaylist,progress:(Long,Long)->Unit):JSONObject = withContext(Dispatchers.IO) {
        val task=kotlinx.coroutines.currentCoroutineContext()
        val size=playlist.file.length()
        var state=call("start",id,JSONObject().put("name",playlist.name).put("size",size)
            .put("sha256",CompanionPlaylistWire.hash(playlist.file){task.ensureActive()}))
        if(state.getString("status")!="uploading")return@withContext state
        RandomAccessFile(playlist.file,"r").use {input->
            var offset=state.getLong("received");require(offset in 0..size);input.seek(offset)
            val buffer=ByteArray(CompanionPlaylistWire.CHUNK)
            while(offset<size) {
                task.ensureActive()
                val count=input.read(buffer);check(count>0)
                state=call("chunk",id,JSONObject().put("offset",offset)
                    .put("data",Base64.getEncoder().encodeToString(buffer.copyOf(count))))
                offset+=count;check(state.getLong("received")==offset);progress(offset,size)
            }
        }
        call("prepare",id)
    }
    suspend fun await(id:String,state:JSONObject):JSONObject {
        var latest=state
        while(latest.getString("status") in setOf("preparing","importing")) {delay(500);latest=status(id)}
        return latest
    }
    suspend fun status(id:String)=call("status",id)
    suspend fun commit(id:String,name:String)=call("import",id,JSONObject().put("name",name))
    suspend fun cancel(id:String)=call("cancel",id)
    private suspend fun call(action:String,id:String,args:JSONObject=JSONObject()):JSONObject =
        connection.exchange("/v1/playlists/$action",JSONObject(args.toString()).put("id",id),true)
}
