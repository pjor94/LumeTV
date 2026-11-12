package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.ArchiveEvent
import it.lumetv.core.CatchupUrls
import it.lumetv.core.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

data class PersonalGuideStatus(val sourceId:String,val updated:Long=0,val matched:Int=0,val programmes:Int=0,
    val refreshing:Boolean=false,val error:String="",val nextRetry:Long=0)

/** Personal XMLTV is never put in guide.json or exposed to launcher integrations. */
class PersonalGuideRepository private constructor(private val app:Context) {
    companion object {
        private val instances=java.util.WeakHashMap<Context,PersonalGuideRepository>()
        fun get(context:Context):PersonalGuideRepository=synchronized(instances) {
            val app=context.applicationContext;instances.getOrPut(app){PersonalGuideRepository(app)}
        }
        internal fun removeFiles(context:Context,source:String) {
            if(!PersonalPlaylistStore.validId(source))return
            val dir=File(context.filesDir,"personal-guides")
            listOf("","-journal","-wal","-shm").forEach {File(dir,"$source.sqlite$it").delete()}
            synchronized(instances){instances[context.applicationContext]?._state?.let {s->s.value=s.value.filter {it.sourceId!=source}}}
        }
        internal fun icon(context:Context,source:String,channel:String):String? =
            runCatching {PersonalGuideStore(File(context.filesDir,"personal-guides")).icon(source,channel)}.getOrNull()
    }
    private val personal=PersonalPlaylistRepository.get(app)
    private val store=PersonalGuideStore(File(app.filesDir,"personal-guides"))
    private val mutex=Mutex()
    private val http=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()
    private val _state=MutableStateFlow<List<PersonalGuideStatus>>(emptyList())
    val state=_state.asStateFlow()
    private fun status(s:PersonalGuideStatus){_state.value=_state.value.filter {it.sourceId!=s.sourceId}+s}

    suspend fun refresh(force:Boolean=false,sourceId:String?=null)=withContext(Dispatchers.IO) {
        mutex.withLock {
            val inputs=personal.guideInputs(sourceId)
            if(sourceId==null)_state.value=_state.value.filter {s->inputs.any {it.source.id==s.sourceId}}
            for(input in inputs) {
                val id=input.source.id;val epoch=input.context.guideToken;val now=System.currentTimeMillis()
                val job=currentCoroutineContext()
                fun active(){job.ensureActive();if(!input.context.valid() || input.context.guideToken!=epoch)throw CancellationException("Guide policy changed")}
                val urls=input.candidates.flatMap {it.feeds}.distinct().take(16)
                val before=store.feeds(id).associateBy {it.id}
                val count=store.count(id)
                status(PersonalGuideStatus(id,before.values.maxOfOrNull {it.updated} ?: 0,count.first,count.second,true))
                try {
                    active()
                    store.prune(id,urls.size,now-input.settings.guide.historyDays*86_400_000L,now+input.settings.guide.futureHours*3_600_000L)
                    for((feed,url) in urls.withIndex()) {
                        active()
                        val eligible=input.candidates.filter {url in it.feeds}
                        val urlHash=hash(url)
                        val manual=store.mappings(id)
                        val signature=hash(url+"|"+input.settings.guide.json()+"|"+input.settings.rule(PlaylistResource.EPG).headers.toSortedMap()+"|"+
                            eligible.joinToString {it.id+it.tvgId+it.tvgName+it.name+it.shift}+"|"+manual.toSortedMap())
                        val old=before[feed] ?: GuideFeedState(feed)
                        if(old.signature==signature && now<old.serverRetry)continue
                        if(!force && old.signature==signature && (now<old.retry || now-old.attempted<30_000 || old.error.isEmpty() && now-old.updated<6*3_600_000L))continue
                        try {
                            if(!input.context.allowed(java.net.URI(url),PlaylistResource.EPG))throw SourceException("La guida richiede HTTP. Autorizzalo nelle impostazioni della guida di questa lista.")
                            val headers=input.settings.rule(PlaylistResource.EPG).headers
                            val request=Request.Builder().url(url).header("Accept-Encoding","identity").apply {
                                headers.forEach { (k,v)->header(k,v) }
                                if(old.signature==signature && old.rows>0 && old.urlHash==urlHash){old.etag?.let {header("If-None-Match",it)};old.modified?.let {header("If-Modified-Since",it)}}
                            }.build()
                            val client=input.context.client(http,url,PlaylistResource.EPG,headers.keys)
                            // The source context cancels this call when disabled, removed or reconfigured.
                            client.newCall(request).guideResponse().use {response->
                                active()
                                if(response.code==304 && old.signature==signature) {
                                    input.context.whileValid {active();store.state(id,old.copy(updated=now,attempted=now,retry=0,error="",serverRetry=0))}
                                } else {
                                    if(!response.isSuccessful)throw GuideDownloadFailure(response.code,retryDelay(response.header("Retry-After"),now))
                                    val body=response.body ?: throw SourceException("La guida ricevuta è vuota.")
                                    if(body.contentLength()>32L*1024*1024)throw SourceException("La guida compressa supera il limite di 32 MiB.")
                                    decoded(body.byteStream()).use {xml->store.stage(xml,input,feed,url,eligible,now,::active,old.updated>0 && old.urlHash!=urlHash).use {draft->
                                        active()
                                        input.context.whileValid {active();store.commit(id,feed,draft,GuideFeedState(feed,signature,now,now,0,response.header("ETag"),response.header("Last-Modified"),urlHash=urlHash),now,input.settings.guide.historyDays,input.settings.guide.futureHours)}
                                    }}
                                }
                            }
                        }catch(cancelled:CancellationException){throw cancelled}
                        catch(error:Exception) {
                            active()
                            android.util.Log.w("LumePersonalGuide","refresh_failed type=${error.javaClass.simpleName} at=${error.stackTrace.firstOrNull {it.className.startsWith("it.lumetv.")}?.let {it.fileName+":"+it.lineNumber}}")
                            val retry=now+maxOf(60_000,(error as? GuideDownloadFailure)?.retry ?: 15*60_000)
                            val message=when(error){is SourceException->error.message.orEmpty();is GuideDownloadFailure->"Il server della guida ha risposto con HTTP ${error.code}.";else->"Guida non aggiornata. La copia precedente resta disponibile."}
                            input.context.whileValid {active();store.state(id,old.copy(signature=signature,attempted=now,retry=retry,error=message.take(240),serverRetry=if((error as? GuideDownloadFailure)?.code==429)retry else 0,
                                etag=old.etag.takeIf {old.signature==signature},modified=old.modified.takeIf {old.signature==signature}))}
                        }
                    }
                }finally {
                    if(input.context.valid() && input.context.guideToken==epoch) {
                        val feeds=store.feeds(id);val counts=store.count(id)
                        status(PersonalGuideStatus(id,feeds.maxOfOrNull {it.updated} ?: 0,counts.first,counts.second,false,
                            feeds.firstOrNull {it.error.isNotEmpty()}?.error.orEmpty(),feeds.maxOfOrNull {it.retry} ?: 0))
                    }else _state.value=_state.value.filter {it.sourceId!=id}
                }
            }
        }
    }

    suspend fun window(channels:List<Channel>,from:Long,until:Long):Map<String,List<Programme>> = withContext(Dispatchers.IO) {
        val result=linkedMapOf<String,List<Programme>>();val now=System.currentTimeMillis()
        for((source,items) in channels.filter {it.isPersonal && !it.isVod}.take(200).groupBy {it.sourceId}) {
            val context=personal.requestContext(source) ?: continue
            if(!context.settings.guide.enabled)continue
            val epoch=context.guideToken
            val guide=context.settings.guide
            val data=store.window(source,items,from.coerceAtLeast(now-guide.historyDays*86_400_000L),until.coerceAtMost(now+guide.futureHours*3_600_000L))
            for(channel in items) {
                val list=data[channel.epgId] ?: continue
                val entry=runCatching {personal.playbackEntry(channel.id)}.getOrNull() ?: continue
                val spec=runCatching {CatchupUrls.spec(entry.entry.metadata,guide.archiveDays)}.getOrNull()
                result[channel.epgId]=list.map {p->
                    val event=if(spec?.mode==it.lumetv.core.CatchupMode.VOD)store.event(source,channel.id,p.eventId) else null
                    p.copy(replayAvailable=CatchupUrls.available(spec,ArchiveEvent(p.start,p.end,event?.catchupId.orEmpty()),now,guide.ongoing))
                }
            }
            if(!context.valid() || epoch!=context.guideToken)items.forEach {result.remove(it.epgId)}
        }
        result
    }
    internal suspend fun event(channel:Channel,event:String):PrivateGuideEvent?=withContext(Dispatchers.IO) {
        val context=personal.requestContext(channel.sourceId) ?: return@withContext null
        if(!context.settings.guide.enabled)return@withContext null
        store.event(channel.sourceId,channel.id,event).takeIf {context.valid()}
    }
    suspend fun choices(source:String,feed:Int,query:String,offset:Int=0)=withContext(Dispatchers.IO){store.choices(source,feed,query,offset,50)}
    suspend fun search(channel:Channel,query:String,offset:Int=0):Pair<List<Programme>,Int> = withContext(Dispatchers.IO) {
        val entry=runCatching {personal.playbackEntry(channel.id)}.getOrNull() ?: return@withContext emptyList<Programme>() to 0
        val settings=entry.settings.guide
        if(!settings.enabled || channel.isVod)return@withContext emptyList<Programme>() to 0
        val epoch=entry.context.guideToken;val now=System.currentTimeMillis()
        val spec=runCatching {CatchupUrls.spec(entry.entry.metadata,settings.archiveDays)}.getOrNull()
        val (events,total)=store.searchPage(channel.sourceId,channel.id,query,now-settings.historyDays*86_400_000L,now+settings.futureHours*3_600_000L,offset)
        if(!entry.context.valid() || epoch!=entry.context.guideToken)return@withContext emptyList<Programme>() to 0
        events.map {e->e.programme.copy(replayAvailable=CatchupUrls.available(spec,ArchiveEvent(e.programme.start,e.programme.end,e.catchupId),now,settings.ongoing))} to total
    }
    suspend fun map(source:String,channel:String,feed:Int?,xml:String="")=withContext(Dispatchers.IO) {
        val context=personal.requestContext(source) ?: throw SourceException("La lista non è disponibile.")
        context.invalidateGuide();mutex.withLock {context.whileValid {store.mapping(source,channel,feed,xml)}}
    }
    suspend fun clear(source:String)=withContext(Dispatchers.IO) {
        val context=personal.requestContext(source) ?: return@withContext
        context.invalidateGuide();mutex.withLock {context.whileValid {store.clear(source);_state.value=_state.value.filter {it.sourceId!=source}}}
    }
    internal fun storedFeedCount(source:String)=store.feeds(source).size
    internal fun storedCounts(source:String)=store.count(source)
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private class GuideDownloadFailure(val code:Int,val retry:Long?):java.io.IOException()
    private fun retryDelay(raw:String?,now:Long):Long?=raw?.toLongOrNull()?.coerceIn(0,86_400)?.times(1000) ?: raw?.let {
        runCatching {(java.time.ZonedDateTime.parse(it,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now).coerceIn(0,86_400_000L)}.getOrNull()
    }
    private suspend fun okhttp3.Call.guideResponse():okhttp3.Response=suspendCancellableCoroutine {continuation->
        continuation.invokeOnCancellation {cancel()}
        enqueue(object:okhttp3.Callback {
            override fun onFailure(call:okhttp3.Call,error:java.io.IOException){if(continuation.isActive)continuation.resumeWithException(error)}
            override fun onResponse(call:okhttp3.Call,response:okhttp3.Response) {
                continuation.resume(response,onCancellation={_,value,_->value.close()})
            }
        })
    }
    private fun decoded(input:InputStream):InputStream {
        val bounded=object:FilterInputStream(input){var bytes=0L
            fun count(n:Int){if(n>0){bytes+=n;if(bytes>32L*1024*1024)throw SourceException("La guida compressa supera il limite di 32 MiB.")}}
            override fun read()=`in`.read().also {if(it>=0)count(1)}
            override fun read(b:ByteArray,o:Int,l:Int)=`in`.read(b,o,l).also(::count)
        }
        val push=PushbackInputStream(bounded,6);val first=ByteArray(6);var n=0
        while(n<first.size){val read=push.read(first,n,first.size-n);if(read<0)break;if(read>0)n+=read}
        if(n>0)push.unread(first,0,n)
        return when {
            n>=2 && first[0]==0x1f.toByte() && first[1]==0x8b.toByte()->GZIPInputStream(push)
            n>=6 && first.contentEquals(byteArrayOf(0xfd.toByte(),0x37,0x7a,0x58,0x5a,0))->XZInputStream(push,64*1024)
            else->push
        }
    }
}
