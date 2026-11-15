package it.lumetv.app.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

internal object CompanionNetwork {
    fun address(context:Context):String? {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.asSequence().filter {network->manager.getNetworkCapabilities(network)?.let {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }==true}.flatMap {manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence()}
            .mapNotNull {it.address.hostAddress}.firstOrNull(CompanionProtocol::localHost)
    }
}

/** Discovery exists only while the connection screen is visible; old Android needs a multicast lock. */
@Suppress("DEPRECATION")
internal class CompanionDiscovery(context:Context,private val changed:(List<CompanionEndpoint>)->Unit,private val failed:()->Unit) {
    private val manager=context.getSystemService(NsdManager::class.java)
    private val wifi=context.applicationContext.getSystemService(WifiManager::class.java)
    private val handler=Handler(Looper.getMainLooper())
    private var multicast:WifiManager.MulticastLock?=null
    private val services=linkedMapOf<String,CompanionEndpoint>()
    private val waiting=ArrayDeque<NsdServiceInfo>()
    private val present=mutableSetOf<String>()
    private var resolving=false
    private var generation=0
    private var listener:NsdManager.DiscoveryListener?=null
    fun start() {
        if(listener!=null)return
        val request=++generation
        multicast=runCatching {wifi.createMulticastLock("lume-companion-discovery").apply {setReferenceCounted(false);acquire()}}.getOrNull()
        val discovery=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String)=Unit
            override fun onDiscoveryStopped(type:String)=Unit
            override fun onStartDiscoveryFailed(type:String,error:Int){handler.post {if(generation==request){stop();failed()}}}
            override fun onStopDiscoveryFailed(type:String,error:Int)=Unit
            override fun onServiceFound(info:NsdServiceInfo){handler.post {if(generation==request && present.size<32 && present.add(info.serviceName) && info.serviceType.trimEnd('.')==CompanionProtocol.SERVICE.trimEnd('.')){waiting.add(info);resolve(request)}}}
            override fun onServiceLost(info:NsdServiceInfo){handler.post {if(generation==request){present.remove(info.serviceName);services.remove(info.serviceName);waiting.removeAll {it.serviceName==info.serviceName};changed(services.values.toList())}}}
        }
        listener=discovery
        try {manager.discoverServices(CompanionProtocol.SERVICE,NsdManager.PROTOCOL_DNS_SD,discovery)}catch(_:Exception){stop();failed()}
    }
    private fun resolve(request:Int) {
        if(resolving || waiting.isEmpty() || generation!=request)return
        val info=waiting.removeFirst();resolving=true
        try {manager.resolveService(info,object:NsdManager.ResolveListener {
            override fun onResolveFailed(info:NsdServiceInfo,error:Int){handler.post {if(generation==request){resolving=false;resolve(request)}}}
            override fun onServiceResolved(info:NsdServiceInfo){handler.post {
                if(generation!=request)return@post
                resolving=false
                val id=info.attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
                val version=info.attributes["v"]?.toString(Charsets.UTF_8)
                val host=info.host?.hostAddress.orEmpty()
                if(info.serviceName in present && version=="1" && CompanionProtocol.uuid(id) && CompanionProtocol.localHost(host)) {
                    services[info.serviceName]=CompanionEndpoint(host,info.port,id,CompanionProtocol.display(info.serviceName,80))
                    changed(services.values.toList())
                }
                resolve(request)
            }}
        })}catch(_:Exception){resolving=false;resolve(request)}
    }
    fun stop() {
        generation++;listener?.let {runCatching {manager.stopServiceDiscovery(it)}};listener=null
        multicast?.let {runCatching {it.release()}};multicast=null
        waiting.clear();present.clear();services.clear();resolving=false
    }
}
