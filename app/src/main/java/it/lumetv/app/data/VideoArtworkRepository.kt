package it.lumetv.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.UUID

/** Each view owns a subscription; the bounded cache and shared requests belong to the application repository. */
internal class VideoArtworkRepository(context:Context,private val repository:PersonalPlaylistRepository,
    http:HttpClient,scope:CoroutineScope,addressAllowed:(InetAddress)->Boolean=LogoPolicy::publicAddress) {
    private val jobs=SupervisorJob(scope.coroutineContext[Job])
    private val subscriptions=CoroutineScope(scope.coroutineContext+jobs)
    private val engine=synchronized(shared) {
        shared[repository] ?: Engine(context.applicationContext,repository,http,addressAllowed).also {
            shared[repository]=it
            while(shared.size>2){val first=shared.keys.first();shared.remove(first)?.close()}
        }
    }
    fun request(item:VideoItem,backdrop:Boolean=false,targetPx:Int=if(backdrop)1280 else 768,display:(Bitmap?)->Unit):Job=subscriptions.launch {
        val epoch=repository.videoArtworkEpoch(item.source) ?: run {display(null);return@launch}
        val key="${item.source}|$epoch|${item.id}|$backdrop|${targetPx.coerceIn(64,1280)}"
        fun valid()=repository.videoArtworkEpoch(item.source)==epoch
        val hit=engine.memory.get(key)
        if(hit!=null && valid()) {
            VideoPerformance.count("art_memory_hit");display(hit.bitmap)
            if(hit.until>System.currentTimeMillis())return@launch
        }
        val flight=engine.acquire(key,item,epoch,backdrop,targetPx.coerceIn(64,1280))
        var last=hit?.bitmap
        val observer=launch {flight.bitmap.filterNotNull().collect {bitmap->if(valid() && bitmap!==last){last=bitmap;display(bitmap)}}}
        try {
            flight.done.await()
            val bitmap=flight.bitmap.value
            if(valid() && bitmap!==last){last=bitmap;display(bitmap)}
            else if(valid() && bitmap==null && last==null)display(null)
        }finally {observer.cancel();engine.release(flight)}
    }
    fun close(){jobs.cancel()}
    fun bindingKey(item:VideoItem,backdrop:Boolean,targetPx:Int)="${item.key}|${item.id}|${repository.videoArtworkEpoch(item.source)}|$backdrop|$targetPx"
    private data class Cached(val bitmap:Bitmap,val until:Long)
    private class Transfer(val task:Deferred<ByteArray>,var readers:Int=0)
    private class Lookup(val task:Deferred<PersonalArtwork?>,var readers:Int=0)
    private class Flight(val key:String) {
        val bitmap=MutableStateFlow<Bitmap?>(null)
        val done=CompletableDeferred<Unit>()
        var readers=0
        lateinit var job:Job
    }
    private class Engine(context:Context,private val repository:PersonalPlaylistRepository,private val http:HttpClient,
        private val addressAllowed:(InetAddress)->Boolean) {
        val memory=object:LruCache<String,Cached>(24*1024*1024){override fun sizeOf(key:String,value:Cached)=value.bitmap.allocationByteCount}
        private val directory=File(context.filesDir,"video-artwork")
        private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val downloads=Semaphore(3)
        private val decodes=Semaphore(2)
        private val gate=Any()
        private val active=mutableMapOf<String,Flight>()
        private val transfers=mutableMapOf<String,Transfer>()
        private val lookups=mutableMapOf<String,Lookup>()
        private val references=object:LinkedHashMap<String,PersonalArtwork?>(128,.75f,true){override fun removeEldestEntry(entry:MutableMap.MutableEntry<String,PersonalArtwork?>?)=size>1024}
        private val retry=object:LinkedHashMap<String,Long>(128,.75f,true){override fun removeEldestEntry(entry:MutableMap.MutableEntry<String,Long>?)=size>1024}
        private val diskGate=Any()
        private var disk:LinkedHashMap<String,Long>?=null
        fun acquire(key:String,item:VideoItem,epoch:String,backdrop:Boolean,target:Int):Flight = synchronized(gate) {
            active[key]?.let {it.readers++;VideoPerformance.count("art_shared_request");return@synchronized it}
            Flight(key).also {flight->
                active[key]=flight;flight.readers=1
                flight.job=work.launch {
                    try {load(flight,item,epoch,backdrop,target)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
                    finally {flight.done.complete(Unit)}
                }
            }
        }
        fun release(flight:Flight)=synchronized(gate) {
            flight.readers--
            if(flight.readers==0 && active[flight.key]===flight){active.remove(flight.key);flight.job.cancel()}
        }
        private suspend fun load(flight:Flight,item:VideoItem,epoch:String,backdrop:Boolean,target:Int) {
            fun valid()=repository.videoArtworkEpoch(item.source)==epoch
            val reference="${item.source}|$epoch|${item.id}|$backdrop"
            val resolved=synchronized(gate){if(references.containsKey(reference))true to references[reference] else false to null}
            val art=if(resolved.first)resolved.second else {
                val lookup=synchronized(gate){
                    (lookups[reference] ?: Lookup(work.async(start=CoroutineStart.LAZY){repository.videoArtwork(item,backdrop)}).also {lookups[reference]=it;it.task.start()}).also {it.readers++}
                }
                try {lookup.task.await().also {if(valid())synchronized(gate){references[reference]=it}}}
                finally {synchronized(gate){lookup.readers--;if(lookup.readers==0 && lookups[reference]===lookup){lookups.remove(reference);lookup.task.cancel()}}}
            }
            if(art==null || !valid() || !art.context.valid())return
            val diskKey=item.source+"-"+art.cacheKey+if(backdrop)"-b" else "-p"
            val file=File(directory,"$diskKey.image")
            val cached=memory.get(flight.key)
            if(cached!=null){flight.bitmap.value=cached.bitmap;if(cached.until>System.currentTimeMillis())return}
            else {
                val fromDisk=runCatching {if(file.exists() && file.length()<=LogoPolicy.MAX_BYTES)decodes.withPermit {VideoPerformance.measure("art_disk_decode"){decode(file.readBytes(),target)}} else null}.getOrNull()
                if(fromDisk!=null && valid()) {
                    VideoPerformance.count("art_disk_hit")
                    val until=file.lastModified()+FRESH
                    memory.put(flight.key,Cached(fromDisk,until));flight.bitmap.value=fromDisk
                    if(until>System.currentTimeMillis())return
                }
            }
            if(synchronized(gate){(retry[diskKey] ?: 0)>System.currentTimeMillis()})return
            // Network permits never block a disk hit or decoding a different cached image.
            currentCoroutineContext().ensureActive();if(!valid() || !art.context.valid())return
            try {
                val bytes=transfer(diskKey,art,::valid)
                val bitmap=decodes.withPermit {VideoPerformance.measure("art_decode"){decode(bytes,target)}}
                currentCoroutineContext().ensureActive();if(!valid() || !art.context.valid())return
                write(file,bytes,valid={valid() && art.context.valid()})
                memory.put(flight.key,Cached(bitmap,System.currentTimeMillis()+FRESH));flight.bitmap.value=bitmap
            }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){synchronized(gate){retry[diskKey]=System.currentTimeMillis()+60_000}}
        }
        private suspend fun transfer(key:String,art:PersonalArtwork,valid:()->Boolean):ByteArray {
            val transfer=synchronized(gate) {
                (transfers[key] ?: Transfer(work.async(start=CoroutineStart.LAZY){downloads.withPermit {
                    check(valid() && art.context.valid());VideoPerformance.count("art_network")
                    val context=art.context
                    val client=context.client(http.client,art.url,PlaylistResource.LOGO,art.headers.keys)
                    val fetcher=LogoFetcher(client,allowed={context.allowed(URI(it.toString()),PlaylistResource.LOGO)},
                        redirectAllowed={_,to->context.allowed(URI(to.toString()),PlaylistResource.LOGO)},addressAllowed=addressAllowed)
                    fetcher.fetch(art.url.toHttpUrl(),headers=art.headers).bytes ?: throw java.io.IOException("Artwork unavailable")
                }}).also {transfers[key]=it;it.task.start()}).also {it.readers++}
            }
            try {return transfer.task.await()}finally {synchronized(gate){
                transfer.readers--;if(transfer.readers==0 && transfers[key]===transfer){transfers.remove(key);transfer.task.cancel()}
            }}
        }
        private fun decode(bytes:ByteArray,target:Int):Bitmap {
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong()*bounds.outHeight<=24_000_000)
            val options=BitmapFactory.Options().apply {inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/(inSampleSize*2)>=target)inSampleSize*=2}
            val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size,options))
            val longest=maxOf(bitmap.width,bitmap.height)
            if(longest<=target)return bitmap
            val resized=Bitmap.createScaledBitmap(bitmap,(bitmap.width.toLong()*target/longest).toInt().coerceAtLeast(1),(bitmap.height.toLong()*target/longest).toInt().coerceAtLeast(1),true)
            if(resized!==bitmap)bitmap.recycle();return resized
        }
        private fun write(file:File,bytes:ByteArray,valid:()->Boolean)=synchronized(diskGate) {
            directory.mkdirs()
            val entries=disk ?: LinkedHashMap<String,Long>().also {index->
                directory.listFiles().orEmpty().filter {it.name.endsWith(".image")}.sortedBy(File::lastModified).forEach {index[it.name]=it.length()}
                directory.listFiles().orEmpty().filter {it.name.endsWith(".tmp") && System.currentTimeMillis()-it.lastModified()>3_600_000}.forEach(File::delete)
                disk=index
            }
            val temporary=File(directory,".${file.name}-${UUID.randomUUID()}.tmp")
            try {
                temporary.writeBytes(bytes)
                if(valid() && temporary.renameTo(file)){entries.remove(file.name);entries[file.name]=bytes.size.toLong()}
            }finally {temporary.delete()}
            var total=entries.values.sum()
            while(total>64L*1024*1024 || entries.size>2048) {
                val oldest=entries.entries.firstOrNull() ?: break
                val candidate=File(directory,oldest.key)
                if(candidate.delete() || !candidate.exists()){total-=oldest.value;entries.remove(oldest.key)}else break
            }
        }
        fun invalidate(source:String)=synchronized(gate) {
            active.filterKeys {it.startsWith("$source|")}.values.forEach {it.job.cancel()}
            lookups.filterKeys {it.startsWith("$source|")}.values.toList().forEach {it.task.cancel()}
            references.keys.removeAll {it.startsWith("$source|")}
            memory.snapshot().keys.filter {it.startsWith("$source|")}.forEach(memory::remove)
        }
        fun close(){work.cancel();memory.evictAll()}
    }
    companion object {
        private const val FRESH=6*60*60_000L
        private val shared=LinkedHashMap<PersonalPlaylistRepository,Engine>(4,.75f,true)
        internal fun invalidateSource(repository:PersonalPlaylistRepository,source:String)=synchronized(shared){shared[repository]?.invalidate(source)}
        internal fun clearForTests()=synchronized(shared){shared.values.forEach(Engine::close);shared.clear()}
    }
}
