package it.lumetv.app.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.data.resolveWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Listener lifetime is precisely the visible TV activity, including catalog and settings. */
internal class CompanionTvController(private val activity:ComponentActivity,private val scope:CoroutineScope,player:TvPlayer,zap:(Int)->Unit,navigate:(String)->Unit,
    repository:it.lumetv.app.data.ChannelRepository,openWatch:(it.lumetv.core.Channel,Long)->Unit) {
    val store=CompanionStore(activity)
    val name=CompanionProtocol.display("TV · "+Build.MODEL,80)
    private val handler=Handler(Looper.getMainLooper())
    private val manager=activity.getSystemService(NsdManager::class.java)
    private val playback=CompanionPlayback(player,zap,navigate)
    private val playlists=CompanionPlaylistReceiver(activity,scope)
    private val watch=CompanionWatchReceiver(scope,{reference->repository.resolveWatch(reference)},
        {action->onMain(action)}, {player.snapshot}, {owner->
            CompanionProtocol.authorizeControl(active && store.enabled,store.peers().any {CompanionProtocol.same(it.tokenHash,owner)})
        },openWatch)
    private var identity:CompanionIdentity?=null
    @Volatile private var pairing:CompanionPairing?=null
    @Volatile private var server:CompanionHttp?=null
    @Volatile var endpoint:CompanionEndpoint?=null;private set
    @Volatile private var active=false
    @Volatile private var listenerGeneration=0L
    private var worker:Job?=null
    private var advertisement:NsdManager.RegistrationListener?=null
    private var multicast:android.net.wifi.WifiManager.MulticastLock?=null
    var onChanged:()->Unit={}
    var onPaired:()->Unit={}
    var message="";private set
    init {activity.lifecycle.addObserver(object:DefaultLifecycleObserver {
        override fun onStart(owner:LifecycleOwner){active=true;worker=scope.launch {while(isActive){reconcile();delay(1500)}}}
        override fun onStop(owner:LifecycleOwner){active=false;worker?.cancel();stop();changed()}
        override fun onDestroy(owner:LifecycleOwner){stop()}
    })}
    private fun changed(){handler.post {onChanged()}}
    fun enabled(value:Boolean){store.enabled=value;if(!value)stop();changed()}
    fun beginPairing(confirmOnTv:Boolean=false):CompanionInvite? {
        val e=endpoint ?: return null;val identity=identity ?: return null;val pairing=pairing ?: return null
        return try {CompanionInvite(e,identity.pin,pairing.begin(confirmOnTv)).also {message="Disponibile mentre Lume è aperta sulla TV."}}
        catch(_:CompanionFailure){message="Attendi che il telefono completi il collegamento prima di abbinarne un altro.";changed();null}
    }
    fun invite():CompanionInvite? {
        val e=endpoint ?: return null;val identity=identity ?: return null
        return pairing?.code()?.let {CompanionInvite(e,identity.pin,it)}
    }
    fun remaining()=pairing?.remaining() ?: 0
    fun pending()=pairing?.pending()
    fun handingOver()=pairing?.handingOver()==true
    fun finishPairing(){pairing?.close(keepResult=true)}
    fun decide(ticket:String,allow:Boolean){
        try {pairing?.decide(ticket,allow)}catch(_:CompanionFailure){pairing?.decide(ticket,false);message="Rimuovi un telefono autorizzato prima di aggiungerne un altro.";changed()}
    }
    fun revoke(id:String){val peer=store.peers().firstOrNull {it.id==id};store.revoke(id);peer?.let {playlists.revoke(it.tokenHash);watch.revoke(it.tokenHash)};changed()}
    private suspend fun reconcile() {
        if(!active || !store.enabled){if(server!=null)stop();return}
        val address=runCatching {CompanionNetwork.address(activity)}.getOrNull()
        if(address==null){stop();message="Collega la TV alla rete Wi-Fi o Ethernet di casa (IPv4).";changed();return}
        if(endpoint?.host==address && server?.isOpen==true)return
        stop();message="Preparo il collegamento…";changed()
        try {
            val identity=withContext(Dispatchers.IO){identity ?: CompanionIdentity(activity)}
            if(!active || !store.enabled)return
            this.identity=identity
            val nextPairing=CompanionPairing(store.id,name,identity.pin,store::remember,::changed)
            pairing=nextPairing
            val serving=listenerGeneration
            val listener=withContext(Dispatchers.IO){CompanionHttp(identity,address,store.port){request->request(serving,request)}.also {
                if(!active || !store.enabled || listenerGeneration!=serving)it.close() else server=it
            }}
            if(!active || !store.enabled || listenerGeneration!=serving){listener.close();return}
            server=listener;store.port=listener.port;endpoint=CompanionEndpoint(address,listener.port,store.id,name)
            advertise(listener.port);message="Disponibile mentre Lume è aperta sulla TV.";changed()
        }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(_:Exception){stop();message="Collegamento non disponibile. Controlla la rete e riprova.";changed()}
    }
    private fun advertise(port:Int) {
        multicast=runCatching {activity.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            .createMulticastLock("lume-companion-tv").apply {setReferenceCounted(false);acquire()}}.getOrNull()
        val registration=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(info:NsdServiceInfo)=Unit
            override fun onServiceUnregistered(info:NsdServiceInfo)=Unit
            override fun onRegistrationFailed(info:NsdServiceInfo,error:Int){handler.post {if(advertisement===this){message="Ricerca automatica non disponibile. Usa il QR o l’indirizzo della TV.";onChanged()}}}
            override fun onUnregistrationFailed(info:NsdServiceInfo,error:Int)=Unit
        }
        advertisement=registration
        runCatching {manager.registerService(NsdServiceInfo().apply {serviceName=name;serviceType=CompanionProtocol.SERVICE;this.port=port;setAttribute("v","1");setAttribute("id",store.id)},NsdManager.PROTOCOL_DNS_SD,registration)}
    }
    private fun stop() {
        listenerGeneration++
        playlists.reset()
        watch.reset()
        server?.close();server=null;endpoint=null
        advertisement?.let {runCatching {manager.unregisterService(it)}};advertisement=null
        multicast?.let {runCatching {it.release()}};multicast=null
        pairing?.close();pairing=null
    }
    private fun request(serving:Long,request:CompanionRequest):CompanionResponse {
        if(!active || !store.enabled || serving!=listenerGeneration)throw CompanionFailure(503,"unavailable")
        val p=pairing ?: throw CompanionFailure(503,"unavailable")
        val data=when(request.method to request.path) {
            "POST" to "/v1/pair/challenge" -> p.challenge(request.body)
            "POST" to "/v1/pair/request" -> p.request(request.body)
            "POST" to "/v1/pair/status" -> p.status(request.body.getString("ticket"))
            "GET" to "/v1/state", "POST" to "/v1/command" -> {
                CompanionProtocol.authorizeControl(active && store.enabled && serving==listenerGeneration,store.authorized(request.token))
                onMain {
                    CompanionProtocol.authorizeControl(active && store.enabled && serving==listenerGeneration,store.authorized(request.token))
                    if(p.acknowledge(request.token))onPaired()
                    if(request.path=="/v1/state")playback.state() else playback.command(CompanionProtocol.digest(request.token.toByteArray()),request.body)
                }
            }
            else -> if(request.method=="POST" && request.path in PLAYLIST_PATHS) {
                CompanionProtocol.authorizeControl(active && store.enabled && serving==listenerGeneration,store.authorized(request.token))
                playlists.request(CompanionProtocol.digest(request.token.toByteArray()),request.path.substringAfterLast('/'),request.body) {
                    CompanionProtocol.authorizeControl(active && store.enabled && serving==listenerGeneration,store.authorized(request.token))
                }
            } else if(request.method=="POST" && request.path in WATCH_PATHS) {
                CompanionProtocol.authorizeControl(active && store.enabled && serving==listenerGeneration,store.authorized(request.token))
                watch.request(CompanionProtocol.digest(request.token.toByteArray()),request.path.substringAfterLast('/'),request.body)
            } else throw CompanionFailure(404,"not_found")
        }
        return CompanionResponse(body=data)
    }
    private fun <T> onMain(action:()->T):T {
        if(Looper.myLooper()==Looper.getMainLooper())return action()
        val task=FutureTask(action);handler.post(task)
        try {return task.get(2,TimeUnit.SECONDS)}
        catch(e:java.util.concurrent.ExecutionException){throw e.cause ?: e}
        catch(_:java.util.concurrent.TimeoutException){throw CompanionFailure(503,"tv_busy")}
        finally {handler.removeCallbacks(task);task.cancel(false)}
    }
    private companion object {
        val PLAYLIST_PATHS=setOf("start","chunk","prepare","status","import","cancel").map {"/v1/playlists/$it"}.toSet()
        val WATCH_PATHS=setOf("resolve","start","status","cancel").map {"/v1/watch/$it"}.toSet()
    }
}
