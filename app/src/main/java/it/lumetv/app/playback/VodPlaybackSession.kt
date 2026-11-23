package it.lumetv.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.hls.HlsManifest
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

internal data class VodPreloadRange(val uri:Uri,val offset:Long=0,val length:Long=C.LENGTH_UNSET.toLong())

/** One foreground VOD transfer. Extra reads stop whenever playback needs its reserve. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VodPlaybackSession(
    private val scope:CoroutineScope,private val store:VodMediaCache,private val namespace:String,
    private val upstream:DataSource.Factory,private val uri:Uri,private val ownership:VodMediaCache.Session,private val valid:()->Boolean,
) {
    @Volatile private var active=true
    @Volatile private var permitted=false
    @Volatile private var plans:List<VodPreloadRange> = emptyList()
    private var job:Job?=null
    @Volatile private var retryAfter=0L
    @Volatile private var disabled=false
    @Volatile var preloadedBytes=0L
        private set
    private val readHead=AtomicLong()
    private val cacheFactory=store.factory(upstream,namespace){active && valid()}
    val previewFactory:DataSource.Factory=ownership.track(store.guarded(cacheFactory){active && valid()})
    val playbackFactory:DataSource.Factory=ownership.track(DataSource.Factory {
        val normal=store.guarded(cacheFactory){active && valid()}
        var data:DataSource=normal.createDataSource()
        object:DataSource {
            private var tracksHead=false
            private var offset=0L
            override fun open(dataSpec:DataSpec):Long {
                tracksHead=dataSpec.uri==uri;offset=dataSpec.position
                data=if(tracksHead)VodChunkDataSource(normal,store,namespace) else normal.createDataSource()
                listeners.forEach(data::addTransferListener)
                return data.open(dataSpec).also {if(tracksHead)readHead.set(offset)}
            }
            private val listeners=mutableListOf<TransferListener>()
            override fun addTransferListener(listener:TransferListener){listeners+=listener}
            override fun getUri()=data.uri
            override fun getResponseHeaders()=data.responseHeaders
            override fun close()=data.close()
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int=data.read(buffer,offset,length).also {
                if(it>0 && tracksHead){this.offset+=it;readHead.set(this.offset)}
            }
        }
    })
    fun update(allowed:Boolean,manifest:Any?,position:Long,video:Format?,audio:Format?,period:Int) {
        permitted=allowed && !disabled && active && valid() && android.os.SystemClock.elapsedRealtime()>=retryAfter
        if(!permitted){job?.cancel();return}
        val rate=(video?.bitrate?.takeIf {it>0} ?: 0)+(audio?.bitrate?.takeIf {it>0} ?: 0)
        val horizon=if(rate>0)(store.quota/3*8_000/rate).coerceIn(30_000,600_000) else 120_000
        plans=when(manifest) {
            is HlsManifest -> hls(manifest,position,horizon)
            is DashManifest -> dash(manifest,position,video,audio,period,horizon)
            null -> progressive()
            else -> emptyList()
        }
        if(job?.isCompleted==false || plans.isEmpty())return
        job=scope.launch(Dispatchers.IO) {
            try {ownership.access {
                var transferred=0L
                for(range in plans) {
                    ensureActive();if(!permitted || !active || !valid())break
                    if(transferred>=store.quota/3)break
                    val key=store.key(namespace,range.uri)
                    val known=ContentMetadata.getContentLength(store.cache.getContentMetadata(key))
                    val size=if(range.length>0)range.length else if(known>=0)(known-range.offset).coerceAtLeast(0) else 4L*1024*1024
                    if(size==0L)continue
                    if(store.cache.isCached(key,range.offset,size))continue
                    val spec=DataSpec.Builder().setUri(range.uri).setPosition(range.offset).setLength(range.length).build()
                    runInterruptible {
                        val writer=CacheWriter(cacheFactory.createDataSource(),spec,ByteArray(32*1024)) {_,_,newBytes->
                            transferred+=newBytes
                            preloadedBytes+=newBytes
                            if(!permitted || !active || !valid() || transferred>=store.quota/3 || Thread.currentThread().isInterrupted)
                                throw java.io.InterruptedIOException("Precaricamento sospeso")
                        }
                        try {writer.cache()}finally {writer.cancel()}
                    }
                    delay(150)
                }
            }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){permitted=false;retryAfter=android.os.SystemClock.elapsedRealtime()+30_000} // Optional reads cannot restart playback.
        }
    }
    fun suspendPreload(){permitted=false;job?.cancel()}
    fun protectPlayback(){disabled=true;suspendPreload()}
    fun close(){active=false;suspendPreload()}
    private fun progressive():List<VodPreloadRange> {
        val chunk=VodChunkDataSource.CHUNK_BYTES
        if(store.cache.getContentMetadata(store.key(namespace,uri)).get(VodChunkDataSource.RANGE_SUPPORT,-1L)==0L)return emptyList()
        val head=((readHead.get().coerceAtLeast(0)+chunk-1)/chunk)*chunk
        val length=ContentMetadata.getContentLength(store.cache.getContentMetadata(store.key(namespace,uri)))
        val end=minOf(if(length>0)length else Long.MAX_VALUE,head+store.quota/3)
        return generateSequence(head){it+chunk}.takeWhile {it<end}.take(180)
            .map {VodPreloadRange(uri,it,minOf(chunk,end-it))}.toList()
    }
    fun fullyCached():Boolean {
        val key=store.key(namespace,uri)
        val total=ContentMetadata.getContentLength(store.cache.getContentMetadata(key))
        return total>0 && store.cache.isCached(key,0,total)
    }
    private fun hls(manifest:HlsManifest,position:Long,horizon:Long):List<VodPreloadRange> {
        val playlist=manifest.mediaPlaylist
        if(!playlist.hasEndTag)return emptyList()
        return playlist.segments.asSequence().filter {it.relativeStartTimeUs/1000 in position..position+horizon}.take(180).flatMap {segment->
            listOfNotNull(segment.initializationSegment,segment).asSequence().map {
                VodPreloadRange(Uri.parse(java.net.URI(playlist.baseUri).resolve(it.url).toString()),it.byteRangeOffset,it.byteRangeLength)
            }
        }.distinct().toList()
    }
    private fun dash(manifest:DashManifest,position:Long,video:Format?,audio:Format?,period:Int,horizon:Long):List<VodPreloadRange> {
        if(manifest.dynamic || period !in 0 until manifest.periodCount)return emptyList()
        val duration=manifest.getPeriodDurationUs(period)
        return manifest.getPeriod(period).adaptationSets.flatMap {set->
            val selected=when(set.type){C.TRACK_TYPE_VIDEO->video;C.TRACK_TYPE_AUDIO->audio;else->null} ?: return@flatMap emptyList()
            val representation=set.representations.firstOrNull {it.format.id==selected.id && it.format.id!=null}
                ?: set.representations.firstOrNull {it.format.bitrate==selected.bitrate && it.format.height==selected.height}
                ?: return@flatMap emptyList()
            val index=representation.index ?: return@flatMap emptyList()
            val count=index.getSegmentCount(duration);if(count<=0 || representation.baseUrls.isEmpty())return@flatMap emptyList()
            val base=representation.baseUrls.first().url
            val start=index.getSegmentNum(position*1000,duration)
            val end=minOf(index.firstSegmentNum+count,start+180,index.getSegmentNum((position+horizon)*1000,duration)+1)
            val ranges=(start until end).map {index.getSegmentUrl(it)}
            (listOfNotNull(representation.initializationUri)+ranges).map {VodPreloadRange(it.resolveUri(base),it.start,it.length)}
        }.distinct()
    }
}
