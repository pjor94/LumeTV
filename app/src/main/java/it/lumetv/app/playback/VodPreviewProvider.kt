package it.lumetv.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import it.lumetv.app.data.HttpClient
import it.lumetv.app.data.PersonalRequestContext
import it.lumetv.app.data.PlaylistResource
import it.lumetv.core.VodMetadata
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A single, cancellable preview decoder, separate from the playing video. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VodPreviewProvider(
    private val context:Context,parent:CoroutineScope,http:HttpClient,
    private val requests:PersonalRequestContext?,private val headers:Map<String,String>,metadata:VodMetadata?,
    private val item:MediaItem,private val mediaFactory:MediaSource.Factory,
    private val extractAllowed:Boolean,private val canLoad:()->Boolean,
    private val onChapters:(List<VodChapter>)->Unit,
    private val addressAllowed:(java.net.InetAddress)->Boolean=it.lumetv.app.data.LogoPolicy::publicAddress,
) {
    private val scope=CoroutineScope(parent.coroutineContext+SupervisorJob(parent.coroutineContext[Job]))
    private val base=http.client.newBuilder().connectionPool(okhttp3.ConnectionPool())
        .dns(object:okhttp3.Dns {override fun lookup(hostname:String)=http.client.dns.lookup(hostname).also {addresses->
            if(addresses.isEmpty() || addresses.any {address->!addressAllowed(address)})
                throw java.net.UnknownHostException("Anteprima non disponibile")
        }}).callTimeout(5,java.util.concurrent.TimeUnit.SECONDS).build()
    private val calls=ConcurrentHashMap.newKeySet<Call>()
    private val frames=object:LruCache<Long,Bitmap>(4*1024*1024){override fun sizeOf(key:Long,value:Bitmap)=value.allocationByteCount}
    private var cues:List<VodPreviewCue> = emptyList()
    private var extractor:FrameExtractor?=null
    private var pending:Job?=null
    @Volatile private var closed=false
    private var extractionFailed=false
    private val previewRoot=metadata?.previewVtt.orEmpty()
    private suspend fun awaitReserve(){while(valid() && !canLoad())delay(250);check(valid())}
    init {
        metadata?.previewVtt?.takeIf(String::isNotBlank)?.let {url->scope.launch {
            try {awaitReserve();cues=VodSidecars.previews(bytes(url,512*1024).decodeToString(),url)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
        }}
        metadata?.chaptersVtt?.takeIf(String::isNotBlank)?.let {url->scope.launch {
            try {awaitReserve();val result=VodSidecars.chapters(bytes(url,512*1024).decodeToString());if(!closed && valid())onChapters(result)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
        }}
    }
    private fun valid()=!closed && requests?.valid()!=false
    fun request(position:Long,answer:(Bitmap?)->Unit) {
        pending?.cancel()
        if(!valid()){answer(null);return}
        val key=position.coerceAtLeast(0)/10_000
        frames.get(key)?.let {answer(it);return}
        if(!canLoad()){answer(null);return}
        pending=scope.launch {
            val bitmap=try {
                val cue=cues.firstOrNull {position>=it.start && position<it.end}
                if(cue!=null)decode(bytes(cue.url,4*1024*1024,previewRoot),cue.crop)
                else if(extractAllowed && !extractionFailed) {
                    val decoder=extractor ?: FrameExtractor.Builder(context,item).setMediaSourceFactory(mediaFactory)
                        .setMediaCodecSelector {mime,secure,tunneled->
                            if(secure || tunneled)emptyList() else androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime,false,false).filter {it.softwareOnly}
                        }
                        .setSeekParameters(SeekParameters.CLOSEST_SYNC).setEffects(listOf(Presentation.createForHeight(180)))
                        .build().also {extractor=it}
                    withTimeout(5_000){decoder.getFrame(position).await().bitmap}
                }else null
            }catch(cancelled:CancellationException){
                extractor?.close();extractor=null
                if(cancelled is TimeoutCancellationException){extractionFailed=true;null}else throw cancelled
            }catch(_:Exception){extractionFailed=true;extractor?.close();extractor=null;null}
            if(valid() && isActive){if(bitmap!=null)frames.put(key,bitmap);answer(bitmap)}
        }
    }
    fun cancel(){pending?.cancel();pending=null;extractor?.close();extractor=null}
    fun close(){closed=true;pending?.cancel();scope.cancel();calls.forEach(Call::cancel);base.connectionPool.evictAll();extractor?.close();extractor=null;frames.evictAll()}
    private suspend fun bytes(url:String,limit:Int,requestRoot:String=url):ByteArray=runInterruptible(Dispatchers.IO) {
        check(valid());val uri=URI(url)
        require(uri.scheme in setOf("https","http") && uri.host!=null && uri.rawUserInfo==null && uri.fragment==null)
        if(requests!=null)require(requests.allowed(uri,PlaylistResource.LOGO)) else require(uri.scheme=="https")
        val client=requests?.client(base,requestRoot,PlaylistResource.LOGO,headers.keys) ?: base
        val call=client.newCall(Request.Builder().url(url).apply {headers.forEach { (key,value)->header(key,value)}}.build())
        calls.add(call)
        try {call.execute().use {response->
            check(response.isSuccessful);val body=requireNotNull(response.body);require(body.contentLength()<=limit)
            body.byteStream().use {input->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16*1024)
                while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=limit);output.write(buffer,0,count);check(valid())}
                output.toByteArray()
            }
        }}finally {calls.remove(call);call.cancel()}
    }
    private suspend fun decode(bytes:ByteArray,crop:List<Int>):Bitmap?=withContext(Dispatchers.Default) {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768)
        val rect=if(crop.size==4)Rect(crop[0],crop[1],crop[0]+crop[2],crop[1]+crop[3]) else Rect(0,0,bounds.outWidth,bounds.outHeight)
        require(rect.left>=0 && rect.top>=0 && rect.right<=bounds.outWidth && rect.bottom<=bounds.outHeight)
        val options=BitmapFactory.Options().apply {inPreferredConfig=Bitmap.Config.ARGB_8888;inSampleSize=1
            while(rect.width()/(inSampleSize*2)>=320 || rect.height()/(inSampleSize*2)>=180)inSampleSize*=2}
        @Suppress("DEPRECATION") val decoder=BitmapRegionDecoder.newInstance(bytes,0,bytes.size,false)
        try {decoder?.decodeRegion(rect,options)}finally {decoder?.recycle()}
    }
    private suspend fun <T> ListenableFuture<T>.await():T=suspendCancellableCoroutine {continuation->
        addListener({if(continuation.isActive)try {continuation.resume(get())}catch(error:Exception){continuation.resumeWithException(error)}},Executor {it.run()})
        continuation.invokeOnCancellation {cancel(false)}
    }
}
