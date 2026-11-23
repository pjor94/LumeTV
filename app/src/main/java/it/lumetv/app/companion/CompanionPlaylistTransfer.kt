package it.lumetv.app.companion

import android.content.Context
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PersonalPlaylistTransfer
import it.lumetv.app.data.PlaylistDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

internal object CompanionPlaylistWire {
    const val CHUNK=16*1024
    fun hash(file:File,check:()->Unit={}):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(32*1024)
            while(true){check();val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}

/** One bounded, owner-bound staging area. Preparing never changes the TV's saved playlists. */
internal class CompanionPlaylistReceiver(context:Context,private val scope:CoroutineScope,
    private val repository:PersonalPlaylistRepository=PersonalPlaylistRepository.get(context),
    private val now:()->Long=android.os.SystemClock::elapsedRealtime) {
    private val directory=File(context.cacheDir,"playlist-transfers-in")
    private class Transfer(val owner:String,val id:String,val fingerprint:String,val name:String,val size:Long,val hash:String,val file:File,var touched:Long) {
        var received=0L
        var status="uploading"
        var draft:PlaylistDraft?=null
        var job:Job?=null
        var error=""
        var source=""
    }
    private var current:Transfer?=null
    private val completed=linkedMapOf<String,Pair<String,JSONObject>>()

    @Synchronized fun request(owner:String,action:String,body:JSONObject,authorize:()->Unit={}):JSONObject {
        authorize()
        expire()
        val id=body.getString("id");require(CompanionProtocol.uuid(id))
        if(action=="start")return start(owner,id,body)
        completed["$owner:$id"]?.let {return JSONObject(it.second.toString())}
        val transfer=current?.takeIf {it.owner==owner && it.id==id} ?: throw CompanionFailure(404,"playlist_missing")
        transfer.touched=now()
        when(action) {
            "chunk"->chunk(transfer,body)
            "prepare"->prepare(transfer)
            "status"->Unit
            "import"->commit(transfer,body)
            "cancel"->{
                if(transfer.status=="importing")throw CompanionFailure(409,"playlist_importing")
                transfer.status="cancelled";remember(transfer);current=null;dispose(transfer)
            }
            else->throw CompanionFailure(404,"not_found")
        }
        return snapshot(transfer)
    }
    private fun start(owner:String,id:String,body:JSONObject):JSONObject {
        val name=body.getString("name").trim();require(name.isNotEmpty() && name.length<=100 && name.none(Char::isISOControl))
        val size=body.getLong("size");require(size in 1..PersonalPlaylistTransfer.MAX_BYTES)
        val hash=body.getString("sha256");require(CompanionProtocol.hex(hash))
        val fingerprint=JSONObject().put("name",name).put("size",size).put("sha256",hash).toString()
        completed["$owner:$id"]?.let {
            if(it.first!=fingerprint)throw CompanionFailure(409,"playlist_conflict")
            return JSONObject(it.second.toString())
        }
        current?.let {
            if(it.owner==owner && it.id==id) {
                if(it.fingerprint!=fingerprint)throw CompanionFailure(409,"playlist_conflict")
                it.touched=now();return snapshot(it)
            }
            if(it.owner!=owner || it.status=="importing")throw CompanionFailure(409,"playlist_busy")
            it.status="cancelled";remember(it);current=null;dispose(it)
        }
        directory.mkdirs()
        directory.listFiles().orEmpty().filter {System.currentTimeMillis()-it.lastModified()>3_600_000}.forEach(File::delete)
        if(directory.usableSpace<size+16L*1024*1024)throw CompanionFailure(507,"playlist_space")
        val file=File.createTempFile("incoming-",".gz",directory)
        val transfer=Transfer(owner,id,fingerprint,name,size,hash,file,now());current=transfer
        return snapshot(transfer)
    }
    private fun chunk(transfer:Transfer,body:JSONObject) {
        val offset=body.getLong("offset");val encoded=body.getString("data")
        require(encoded.length<=((CompanionPlaylistWire.CHUNK+2)/3)*4)
        val bytes=Base64.getDecoder().decode(encoded)
        require(bytes.isNotEmpty() && bytes.size<=CompanionPlaylistWire.CHUNK && offset>=0 && offset<=transfer.size-bytes.size)
        if(transfer.status!="uploading")throw CompanionFailure(409,"playlist_state")
        if(offset<transfer.received) {
            if(offset+bytes.size>transfer.received)throw CompanionFailure(409,"playlist_offset")
            val previous=ByteArray(bytes.size)
            RandomAccessFile(transfer.file,"r").use {it.seek(offset);it.readFully(previous)}
            if(!MessageDigest.isEqual(previous,bytes))throw CompanionFailure(409,"playlist_conflict")
            return
        }
        if(offset!=transfer.received)throw CompanionFailure(409,"playlist_offset")
        RandomAccessFile(transfer.file,"rw").use {it.seek(offset);it.write(bytes)}
        transfer.received+=bytes.size
    }
    private fun prepare(transfer:Transfer) {
        if(transfer.status!="uploading")return
        if(transfer.received!=transfer.size)throw CompanionFailure(409,"playlist_incomplete")
        transfer.status="preparing"
        transfer.job=scope.launch(Dispatchers.IO) {
            var draft:PlaylistDraft?=null
            try {
                val task=kotlinx.coroutines.currentCoroutineContext()
                if(CompanionPlaylistWire.hash(transfer.file){task.ensureActive()}!=transfer.hash)throw CompanionFailure(400,"playlist_integrity")
                draft=repository.prepareTransferredPlaylist(transfer.file)
                synchronized(this@CompanionPlaylistReceiver) {
                    if(current===transfer && transfer.status=="preparing") {
                        transfer.draft=draft;draft=null;transfer.status="ready";transfer.touched=now()
                        transfer.file.delete()
                    }
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){synchronized(this@CompanionPlaylistReceiver) {
                if(current===transfer){transfer.status="failed";transfer.error=if(error is CompanionFailure)error.code else "playlist_invalid";transfer.file.delete()}
            }}finally {draft?.close()}
        }
    }
    private fun commit(transfer:Transfer,body:JSONObject) {
        if(transfer.status=="importing")return
        if(transfer.status!="ready")throw CompanionFailure(409,"playlist_state")
        val name=body.getString("name").trim();require(name.isNotEmpty() && name.length<=100 && name.none(Char::isISOControl))
        if(repository.state.value.any {it.name.equals(name,true)})throw CompanionFailure(409,"playlist_name_exists")
        if(repository.state.value.size>=PersonalPlaylistRepository.MAX_SOURCES)throw CompanionFailure(409,"playlist_limit")
        val draft=transfer.draft ?: throw CompanionFailure(409,"playlist_state")
        if(repository.state.value.sumOf {it.count.toLong()}+draft.report.count>PersonalPlaylistRepository.MAX_TOTAL)throw CompanionFailure(409,"playlist_total")
        transfer.error="";transfer.status="importing"
        transfer.job=scope.launch(Dispatchers.IO) {
            try {
                val source=repository.save(name,draft)
                synchronized(this@CompanionPlaylistReceiver) {
                    transfer.source=source;transfer.status="imported";transfer.draft=null;transfer.touched=now()
                    remember(transfer);if(current===transfer)current=null
                }
                draft.close();transfer.file.delete()
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){synchronized(this@CompanionPlaylistReceiver) {
                if(current===transfer){
                    transfer.status=if(draft.file.isFile)"ready" else "failed"
                    if(transfer.status=="failed"){transfer.draft=null;draft.close()}
                    transfer.error="playlist_save_failed";transfer.touched=now()
                }
                else draft.close()
            }}
        }
    }
    private fun snapshot(transfer:Transfer):JSONObject = JSONObject().put("version",1).put("id",transfer.id)
        .put("name",CompanionProtocol.display(transfer.name,100)).put("status",transfer.status)
        .put("received",transfer.received).put("size",transfer.size).put("error",transfer.error)
        .apply {
            transfer.draft?.let {draft->put("preview",JSONObject().put("count",draft.report.count)
                .put("sample",JSONArray(draft.report.sample.map {CompanionProtocol.display(it,300)}))
                .put("location",draft.location.name).put("allowHttp",draft.allowHttp))}
            if(transfer.source.isNotBlank())put("source",transfer.source)
        }
    private fun remember(transfer:Transfer) {
        completed["${transfer.owner}:${transfer.id}"]=transfer.fingerprint to snapshot(transfer)
        while(completed.size>16)completed.remove(completed.keys.first())
    }
    private fun expire() {current?.takeIf {it.status!="importing" && now()-it.touched>10*60_000}?.let {
        current=null;dispose(it)
    }}
    private fun dispose(transfer:Transfer) {
        transfer.job?.cancel()
        val draft=transfer.draft;transfer.draft=null
        cleanup.launch {draft?.close();transfer.file.delete()}
    }
    @Synchronized fun revoke(owner:String) {
        current?.takeIf {it.owner==owner && it.status!="importing"}?.let {current=null;dispose(it)}
        completed.keys.filter {it.startsWith("$owner:")}.forEach(completed::remove)
    }
    @Synchronized fun reset() {
        current?.let {if(it.status!="importing"){current=null;dispose(it)}}
    }
    private companion object {
        val cleanup=CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
    }
}
