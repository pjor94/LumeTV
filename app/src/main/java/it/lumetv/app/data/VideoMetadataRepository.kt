package it.lumetv.app.data

import android.content.Context
import android.content.SharedPreferences
import it.lumetv.app.BuildConfig
import it.lumetv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal enum class VideoMetadataStatus { DISABLED, UNAVAILABLE, LOADING, READY, AMBIGUOUS, NOT_FOUND, EXCLUDED, ERROR }
internal data class VideoMetadataState(val status:VideoMetadataStatus,val details:TmdbMetadata?=null,
    val candidates:List<VodTitleCandidate> = emptyList(),val message:String="")

/** Network work is observed only by title details. Catalog queries and artwork only read the cache. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VideoMetadataRepository(context:Context,private val playlists:PersonalPlaylistRepository,
    private val service:TmdbService=TmdbClient(BuildConfig.TMDB_READ_ACCESS_TOKEN),
    private val store:VideoMetadataStore=VideoMetadataStore(context),private val now:()->Long=System::currentTimeMillis) {
    private val prefs=context.applicationContext.getSharedPreferences("video-metadata",Context.MODE_PRIVATE)
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val generation=AtomicLong()
    private val artworkGeneration=AtomicLong()
    private val serial=AtomicLong()
    private val revisions=ConcurrentHashMap<String,Long>()
    private val failures=ConcurrentHashMap<String,Pair<Long,String>>()
    private val gate=Any()
    private val requests=mutableMapOf<String,Flight>()
    private val _updates=MutableStateFlow(0L)
    val updates:StateFlow<Long> = _updates.asStateFlow()
    val configured get()=service.configured
    val enabled get()=prefs.getBoolean("enabled",true)
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key=="enabled")invalidate()}
    init {prefs.registerOnSharedPreferenceChangeListener(listener)}
    private class Flight(val task:Deferred<Result>,var readers:Int=0)
    private data class Result(val id:Int?,val candidates:List<VodTitleCandidate>,val details:TmdbMetadata?,val downloaded:Boolean=false)

    fun setEnabled(value:Boolean){prefs.edit().putBoolean("enabled",value).apply()}
    fun artworkRevision(item:VideoItem)="${generation.get()}:${artworkGeneration.get()}:${revisions[item.source+"|"+localKey(item)] ?: 0L}"
    private fun touch(input:VideoLookup) {
        if(revisions.size>=2048){artworkGeneration.incrementAndGet();revisions.clear()}
        revisions[input.source+"|"+input.key]=serial.incrementAndGet()
    }
    private fun invalidate() {
        generation.incrementAndGet()
        synchronized(gate){requests.values.forEach {it.task.cancel()};requests.clear()}
        failures.clear();_updates.update {it+1}
    }
    private fun valid(input:VideoLookup,epoch:Long)=enabled && generation.get()==epoch && playlists.validVideoLookup(input)

    fun observe(input:VideoLookup):Flow<VideoMetadataState> = updates.transformLatest {
        if(!enabled){emit(VideoMetadataState(VideoMetadataStatus.DISABLED));return@transformLatest}
        val epoch=generation.get()
        val match=store.match(input)
        if(match?.excluded==true){emit(VideoMetadataState(VideoMetadataStatus.EXCLUDED));return@transformLatest}
        val cached=match?.id?.let {store.details(input.query.type,it)}
        val candidates=match?.candidates.orEmpty()
        if(!configured){emit(VideoMetadataState(VideoMetadataStatus.UNAVAILABLE,cached?.value,candidates));return@transformLatest}
        val fresh=if(match?.id!=null)cached!=null && cached.expires>now() && cached.value.trailersLoaded else match!=null && match.expires>now()
        if(fresh){emit(state(match?.id,candidates,cached?.value));return@transformLatest}
        val failure=failures[input.query.fingerprint]?.takeIf {it.first>now()}
        if(failure!=null){emit(VideoMetadataState(VideoMetadataStatus.ERROR,cached?.value,candidates,failure.second));return@transformLatest}
        emit(VideoMetadataState(VideoMetadataStatus.LOADING,cached?.value,candidates))
        try {
            val result=shared(input.query,match?.id ?: input.query.tmdbId)
            if(!valid(input,epoch))return@transformLatest
            val selected=VideoStoredMatch(result.id,result.candidates,match?.manual==true,false,
                if(result.id!=null)Long.MAX_VALUE else now()+VideoMetadataStore.DAY)
            store.save(input,selected,result.details.takeIf {result.downloaded},now()){valid(input,epoch)}
            if(valid(input,epoch)){touch(input);emit(state(result.id,result.candidates,result.details))}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception) {
            if(!valid(input,epoch))return@transformLatest
            val status=(error as? TmdbException)?.status
            val message=when(status) {
                401,403->"Le informazioni online non sono disponibili in questa versione."
                429->"Il servizio richiede una pausa. Riprova più tardi."
                else->"Informazioni online non disponibili. Puoi continuare a guardare il video."
            }
            if(failures.size>=256)failures.clear()
            failures[input.query.fingerprint]=(if(status==401 || status==403)Long.MAX_VALUE else maxOf(now()+60_000,(error as? TmdbException)?.retryAt ?: 0)) to message
            emit(VideoMetadataState(VideoMetadataStatus.ERROR,cached?.value,candidates,message))
        }
    }.flowOn(Dispatchers.IO)

    private fun state(id:Int?,candidates:List<VodTitleCandidate>,details:TmdbMetadata?)=VideoMetadataState(
        if(id!=null && details!=null)VideoMetadataStatus.READY else if(candidates.isNotEmpty())VideoMetadataStatus.AMBIGUOUS else VideoMetadataStatus.NOT_FOUND,details,candidates)

    private suspend fun shared(query:VodLookupQuery,known:Int?):Result {
        val key=query.fingerprint+":"+known
        val flight=synchronized(gate) {
            (requests[key] ?: Flight(work.async(start=CoroutineStart.LAZY) {
                val candidates=if(known!=null)emptyList() else VodTitleMatcher.ranked(query,service.search(query))
                val id=known ?: (if(query.imdbId.isNotBlank())candidates.singleOrNull() else VodTitleMatcher.automatic(query,candidates))?.id
                if(id==null)return@async Result(null,candidates,null)
                val cached=store.details(query.type,id)
                if(cached!=null && cached.expires>now() && cached.value.trailersLoaded)Result(id,candidates,cached.value)
                else Result(id,candidates,service.details(query.type,id),true)
            }).also {requests[key]=it;it.task.start()}).also {it.readers++}
        }
        return try {flight.task.await()}finally {synchronized(gate) {
            flight.readers--
            if(flight.readers==0 && requests[key]===flight){requests.remove(key);if(flight.task.isActive)flight.task.cancel()}
        }}
    }

    suspend fun suggestions(input:VideoLookup,text:String):List<VodTitleCandidate> = withContext(Dispatchers.IO) {
        val epoch=generation.get()
        if(!enabled || !configured || !playlists.validVideoLookup(input))return@withContext emptyList()
        delay(300)
        if(!valid(input,epoch))return@withContext emptyList()
        if(text.isBlank())store.match(input)?.candidates?.takeIf {it.isNotEmpty()}?.let {return@withContext it}
        val query=input.query.copy(title=if(text.isBlank())input.query.title else VodTitleMatcher.clean(text),
            year=if(text.isBlank())input.query.year else null,tmdbId=null,imdbId="")
        if(query.title.isBlank())return@withContext emptyList()
        val result=VodTitleMatcher.ranked(query,service.search(query))
        if(valid(input,epoch))result else emptyList()
    }
    suspend fun choose(input:VideoLookup,candidate:VodTitleCandidate?)=withContext(Dispatchers.IO) {
        val epoch=generation.get();check(valid(input,epoch))
        require(candidate==null || candidate.id>0 && candidate.type==input.query.type)
        store.save(input,VideoStoredMatch(candidate?.id,listOfNotNull(candidate),true,candidate==null,Long.MAX_VALUE),null,now()){valid(input,epoch)}
        failures.remove(input.query.fingerprint);touch(input);_updates.update {it+1}
    }
    suspend fun cached(input:VideoLookup):TmdbMetadata?=withContext(Dispatchers.IO) {
        if(!enabled || !playlists.validVideoLookup(input))return@withContext null
        val match=store.match(input)?.takeUnless {it.excluded} ?: return@withContext null
        match.id?.let {store.details(input.query.type,it)?.value}
    }
    suspend fun clearCache()=withContext(Dispatchers.IO) {
        invalidate();store.clear();playlists.clearOnlineArtwork();_updates.update {it+1}
    }
    fun removeSource(source:String) {store.removeSource(source);revisions.keys.removeAll {it.startsWith("$source|")};_updates.update {it+1}}
    internal fun refreshFromStore()=invalidate()
    internal fun close(){prefs.unregisterOnSharedPreferenceChangeListener(listener);work.cancel();store.close()}

    companion object {
        internal fun localKey(item:VideoItem)=if(item.seriesId.isNotBlank())"s:${item.seriesId}" else "i:${item.id}"
    }
}
