package it.lumetv.app.companion

import it.lumetv.app.data.WatchMatchException
import it.lumetv.app.data.WatchReference
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.core.Channel
import it.lumetv.core.PlaybackMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Lookup is read-only. Start is owner-bound, idempotent, and tied to the TV state shown at lookup. */
internal class CompanionWatchReceiver(private val scope:CoroutineScope,
    private val resolve:suspend (WatchReference)->Channel,
    private val onMain:(()->Unit)->Unit,
    private val snapshot:()->PlaybackSnapshot,
    private val authorize:(String)->Unit,
    private val open:(Channel,Long)->Unit,
    private val now:()->Long=android.os.SystemClock::elapsedRealtime) {
    private class Operation(val owner:String,val id:String,val reference:WatchReference,val created:Long) {
        var status="resolving"
        var channel:Channel?=null
        var baseline=0L
        var revision:Long?=null
        var position:Long?=null
        var startedAt=0L
        var error=""
        var job:Job?=null
    }
    private val operations=linkedMapOf<String,Operation>()
    fun request(owner:String,action:String,body:JSONObject):JSONObject {
        authorize(owner)
        val id=body.getString("id");require(CompanionProtocol.uuid(id))
        // Never wait for the main thread while holding the ledger lock.
        if(action=="status") {
            var value:JSONObject?=null
            onMain {synchronized(this) {
                authorize(owner);expire()
                val operation=operations["$owner:$id"] ?: throw CompanionFailure(404,"watch_missing_request")
                refresh(operation,snapshot());value=result(operation)
            }}
            return requireNotNull(value)
        }
        return synchronized(this) {
            expire()
            val key="$owner:$id"
            if(action=="resolve") {
                val reference=WatchReference.read(body.getJSONObject("reference"))
                operations[key]?.let {
                    if(it.reference!=reference)throw CompanionFailure(409,"watch_conflict")
                    return@synchronized result(it)
                }
                if(operations.size>=32)throw CompanionFailure(409,"watch_busy")
                val operation=Operation(owner,id,reference,now());operations[key]=operation
                operation.job=scope.launch(Dispatchers.IO) {
                    try {
                        val channel=resolve(reference)
                        val task=kotlinx.coroutines.currentCoroutineContext();task.ensureActive()
                        onMain {synchronized(this@CompanionWatchReceiver) {
                            authorize(owner);task.ensureActive()
                            if(operation.status!="resolving")return@synchronized
                            operation.channel=channel;operation.baseline=snapshot().playbackRevision;operation.status="ready"
                        }}
                    }catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){fail(operation,code(error))}
                }
                return@synchronized result(operation)
            }
            val operation=operations[key] ?: throw CompanionFailure(404,"watch_missing_request")
            when(action) {
                "start"->start(operation,body)
                "cancel"->if(operation.status in setOf("resolving","ready")) {operation.status="cancelled";operation.job?.cancel()}
                else->throw CompanionFailure(404,"not_found")
            }
            result(operation)
        }
    }
    private fun start(operation:Operation,body:JSONObject) {
        val raw=body.get("position");require(raw is Int || raw is Long)
        val position=(raw as Number).toLong();require(position in 0..MAX_POSITION && (operation.reference.vod || position==0L))
        operation.position?.let {if(it!=position)throw CompanionFailure(409,"watch_conflict");return}
        if(operation.status!="ready")throw CompanionFailure(409,"watch_not_ready")
        operation.position=position;operation.startedAt=now();operation.status="starting"
        operation.job=scope.launch(Dispatchers.IO) {
            try {
                // Recheck source availability, hidden channels and content identity just before opening.
                val channel=resolve(operation.reference)
                val task=kotlinx.coroutines.currentCoroutineContext();task.ensureActive()
                onMain {synchronized(this@CompanionWatchReceiver) {
                    authorize(operation.owner);task.ensureActive()
                    if(operation.status!="starting")return@synchronized
                    if(now()-operation.startedAt>45_000)throw CompanionFailure(409,"watch_timeout")
                    if(snapshot().playbackRevision!=operation.baseline)throw CompanionFailure(409,"watch_tv_changed")
                    if(channel.id!=operation.channel?.id || channel.sourceId!=operation.channel?.sourceId)throw CompanionFailure(409,"watch_missing")
                    open(channel,position)
                    val opened=snapshot()
                    if(opened.playbackRevision==operation.baseline || opened.channel?.id!=channel.id || opened.channel.sourceId!=channel.sourceId)
                        throw CompanionFailure(409,"watch_failed")
                    operation.revision=opened.playbackRevision
                }}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){fail(operation,code(error))}
        }
    }
    private fun refresh(operation:Operation,state:PlaybackSnapshot) {
        if(operation.status !in setOf("starting","started"))return
        val revision=operation.revision
        if(revision==null){if(now()-operation.startedAt>45_000)fail(operation,"watch_timeout");return}
        val selected=state.channel
        if(state.playbackRevision!=revision || selected?.id!=operation.channel?.id || selected?.sourceId!=operation.channel?.sourceId) {
            fail(operation,"watch_tv_changed");return
        }
        if(state.error!=null){fail(operation,"watch_playback_failed");return}
        if(state.mode!=if(operation.reference.vod)PlaybackMode.VOD else PlaybackMode.LIVE){fail(operation,"watch_missing");return}
        val position=operation.position ?: 0L
        if(state.ready && position>0 && !state.seekable){fail(operation,"watch_not_seekable");return}
        val expected=if(state.duration>0)minOf(position,(state.duration-1).coerceAtLeast(0)) else position
        val elapsed=(now()-operation.startedAt).coerceAtLeast(0)
        val positioned=!operation.reference.vod || state.position in (expected-2000).coerceAtLeast(0)..(expected+elapsed+5000)
        if(state.ready && state.playing && positioned)operation.status="started"
        else if(now()-operation.startedAt>45_000)fail(operation,"watch_timeout")
        else operation.status="starting"
    }
    private fun fail(operation:Operation,code:String)=synchronized(this) {
        if(operation.status !in setOf("cancelled","failed")){operation.status="failed";operation.error=code}
    }
    private fun result(operation:Operation)=JSONObject().put("version",1).put("id",operation.id).put("status",operation.status)
        .put("title",CompanionProtocol.display(operation.channel?.name.orEmpty())).put("vod",operation.reference.vod)
        .put("error",operation.error)
    private fun code(error:Exception)=when(error) {
        is WatchMatchException->error.code
        is CompanionFailure->error.code
        else->"watch_failed"
    }
    private fun expire() {
        operations.entries.removeAll {(_,op)->
            if(now()-op.created>10*60_000){op.job?.cancel();true}else false
        }
    }
    @Synchronized fun revoke(owner:String) {operations.values.filter {it.owner==owner && it.status !in setOf("started","failed")}.forEach {it.job?.cancel();it.status="failed";it.error="not_authorized"}}
    @Synchronized fun reset() {operations.values.filter {it.status !in setOf("started","failed")}.forEach {it.job?.cancel();it.status="failed";it.error="unavailable"}}
    companion object {const val MAX_POSITION=30L*24*60*60*1000}
}
