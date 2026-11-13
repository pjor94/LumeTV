package it.lumetv.app.data

import android.content.SharedPreferences
import android.os.CancellationSignal
import it.lumetv.core.PlaybackMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read snapshots are protected per source; an import never queues all Video reads behind its global state mutex. */
internal class VideoLibraryData(private val store:PersonalPlaylistStore,private val history:PersonalPlaybackStore,
    private val prefs:SharedPreferences,private val progress:(String,Int,Int)->Unit) {
    private data class Source(val stored:PlaylistStored,val epoch:Long)
    private data class Ready(val epoch:Long,val bytes:Long,val modified:Long,val info:VideoIndexInfo)
    private data class Request(val sources:List<Pair<String,Long>>,val files:List<Pair<Long,Long>>,val query:VideoQuery,
        val favorites:Set<String>,val series:Set<String>,val history:Long,val preview:Boolean)
    private val locks=ConcurrentHashMap<String,ReentrantReadWriteLock>()
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val preparing=mutableMapOf<Pair<String,Long>,Deferred<VideoIndexInfo>>()
    private val ready=ConcurrentHashMap<String,Ready>()
    @Volatile private var sources=emptyMap<String,Source>()
    private val _version=MutableStateFlow(0L)
    val version=_version.asStateFlow()
    private val _favorites=MutableStateFlow(0L)
    val favoritesVersion=_favorites.asStateFlow()
    private var epoch=0L
    private val cacheLock=Any()
    private val pages=object:LinkedHashMap<Request,VideoPage>(32,.75f,true){}
    private val counts=object:LinkedHashMap<Request,Int>(64,.75f,true){}
    private val listener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key=="favorites" || key=="video_saved_series")_favorites.value++}
    init {prefs.registerOnSharedPreferenceChangeListener(listener)}
    fun update(all:Collection<PlaylistStored>,available:Set<String>,changed:Set<String>) {
        val old=sources
        val next=all.filter {it.source.id in available}.associate {stored->
            val previous=old[stored.source.id]
            val same=previous!=null && stored.source.id !in changed && previous.stored.source.revision==stored.source.revision &&
                previous.stored.source.updated==stored.source.updated && previous.stored.source.count==stored.source.count && previous.stored.settings==stored.settings
            stored.source.id to Source(stored,if(same)previous.epoch else ++epoch)
        }
        sources=next
        if(old.mapValues {it.value.epoch}!=next.mapValues {it.value.epoch}) {
            synchronized(cacheLock){pages.clear();counts.clear()};_version.value++
        }
        ready.keys.retainAll(next.keys)
    }
    fun epoch(source:String):Long?=sources[source]?.epoch
    internal fun clearReadCache()=synchronized(cacheLock){pages.clear();counts.clear()}
    internal fun cacheState()=synchronized(cacheLock){mapOf("pages" to pages.size,"rows" to pages.values.sumOf {it.rows.size},"counts" to counts.size)}
    fun key(query:VideoQuery):Any=listOf(_version.value,if(query.shelf==VideoShelf.SAVED)_favorites.value else 0L,if(query.shelf==VideoShelf.CONTINUE)history.version.value else 0L)
    private fun selected(q:VideoQuery)=sources.values.filter {q.source==null || q.source==it.stored.source.id}
    private fun valid(snapshot:List<Source>)=snapshot.all {sources[it.stored.source.id]?.epoch==it.epoch}
    private fun request(snapshot:List<Source>,q:VideoQuery,preview:Boolean,files:Boolean=true):Request = Request(
        snapshot.map {it.stored.source.id to it.epoch},if(files)snapshot.map {store.file(it.stored.source.id).let {f->f.length() to f.lastModified()}} else emptyList(),q,
        prefs.getStringSet("favorites",emptySet()).orEmpty().toSet(),prefs.getStringSet("video_saved_series",emptySet()).orEmpty().toSet(),
        if(q.shelf==VideoShelf.CONTINUE)history.version.value else 0L,preview)
    private fun countKey(r:Request)=r.copy(query=r.query.copy(offset=0,limit=0,details=false,order=VideoOrder.PLAYLIST),favorites=if(r.query.shelf==VideoShelf.SAVED)r.favorites else emptySet(),series=if(r.query.shelf==VideoShelf.SAVED)r.series else emptySet(),preview=false)
    fun cached(q:VideoQuery,preview:Boolean=false):VideoPage? {
        val r=request(selected(q),q,preview,false)
        // File metadata is deliberately not touched by the UI thread. The read path validates it before publishing.
        val page=synchronized(cacheLock){pages.entries.lastOrNull {it.key.copy(files=emptyList())==r}?.value} ?: return null
        val positions=history.cachedPositions() ?: return page
        return decorate(page,positions)
    }
    private fun remember(r:Request,page:VideoPage) = synchronized(cacheLock) {
        pages[r]=page
        while(pages.size>40 || pages.values.sumOf {it.rows.size}>600)pages.remove(pages.keys.first())
        if(page.countKnown){counts[countKey(r)]=page.total;while(counts.size>64)counts.remove(counts.keys.first())}
    }
    private fun lock(id:String)=locks.getOrPut(id){ReentrantReadWriteLock(true)}
    private fun <T> readLocked(ids:List<String>,signal:CancellationSignal,action:()->T):T {
        val acquired=mutableListOf<java.util.concurrent.locks.Lock>()
        try {
            for(id in ids.sorted()) {
                val lock=lock(id).readLock()
                VideoPerformance.measure("read_wait"){while(!lock.tryLock(25,TimeUnit.MILLISECONDS))signal.throwIfCanceled()}
                acquired+=lock;signal.throwIfCanceled()
            }
            return action()
        }finally {acquired.asReversed().forEach {it.unlock()}}
    }
    fun <T> write(id:String,action:()->T):T {
        synchronized(preparing){preparing.filterKeys {it.first==id}.values.forEach {it.cancel()};preparing.keys.removeAll {it.first==id}}
        val sourceLock=lock(id);val lock=sourceLock.writeLock();lock.lock()
        try {ready.remove(id);return action()}finally {lock.unlock();if(id !in sources)locks.remove(id,sourceLock)}
    }
    private suspend fun prepare(source:Source):VideoIndexInfo {
        val id=source.stored.source.id;val file=store.file(id)
        ready[id]?.takeIf {it.epoch==source.epoch && it.bytes==file.length() && it.modified==file.lastModified()}?.let {return it.info}
        val key=id to source.epoch
        val task=synchronized(preparing) {preparing[key]?.takeIf {it.isActive} ?: work.async(start=CoroutineStart.LAZY) {
            val operation=currentCoroutineContext();val lock=lock(id).writeLock()
            while(!lock.tryLock(25,TimeUnit.MILLISECONDS))operation.ensureActive()
            try {
                operation.ensureActive();if(!valid(listOf(source)))throw CancellationException("Video source changed")
                ready[id]?.takeIf {it.epoch==source.epoch && it.bytes==file.length() && it.modified==file.lastModified()}?.let {return@async it.info}
                val info=VideoPerformance.measure("prepare") {VideoCatalogIndex.ensure(store,source.stored,{operation.ensureActive()},progress)}
                ready[id]=Ready(source.epoch,file.length(),file.lastModified(),info);info
            }finally {lock.unlock()}
        }.also {preparing[key]=it;it.start()} }
        try {return task.await()}finally {if(task.isCompleted)synchronized(preparing){if(preparing[key]===task)preparing.remove(key)}}
    }
    private suspend fun <T> sql(action:(CancellationSignal)->T):T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine {continuation->
            val signal=CancellationSignal();continuation.invokeOnCancellation {signal.cancel()}
            try {signal.throwIfCanceled();val value=action(signal);if(continuation.isActive)continuation.resume(value)}
            catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
        }
    }
    suspend fun page(q:VideoQuery,preview:Boolean=false):VideoPage = withContext(Dispatchers.IO) {
        for(attempt in 0..2) {
            val snapshot=selected(q)
            if(snapshot.isEmpty() || q.group=="" || q.shelf==VideoShelf.SAVED && prefs.getStringSet("favorites",emptySet()).isNullOrEmpty() && prefs.getStringSet("video_saved_series",emptySet()).isNullOrEmpty() ||
                q.shelf==VideoShelf.CONTINUE && history.snapshot().keys.none {key->snapshot.any {it.stored.source.id==key.first}})return@withContext VideoPage(emptyList(),0,!preview)
            val indexed=snapshot.map {VideoSource(it.stored,prepare(it))}
            val value=sql {signal->readLocked(snapshot.map {it.stored.source.id},signal) {
                if(!valid(snapshot))return@readLocked null
                val request=request(snapshot,q,preview)
                val cached=synchronized(cacheLock){pages[request]}
                val raw=if(cached!=null){VideoPerformance.count("page_cache_hit");cached} else {
                    val known=synchronized(cacheLock){counts[countKey(request)]}
                    if(known!=null)VideoPerformance.count("count_cache_hit")
                    val result=store.videoPage(indexed,q,history.database(),request.favorites,request.series,signal,known,preview)
                    if(valid(snapshot))remember(request,result);result
                }
                if(!valid(snapshot))return@readLocked null
                val positions=history.snapshot()
                decorate(raw,positions)
            }}
            if(value!=null)return@withContext value
        }
        throw java.io.IOException("La lista è cambiata. Riapri i video.")
    }
    private fun decorate(page:VideoPage,positions:Map<Pair<String,String>,WatchPosition>)=page.copy(rows=page.rows.map {item->
        positions[item.source to item.id]?.let {p->item.copy(position=p.position,watchedDuration=p.duration,completed=p.completed)} ?: item.copy(position=0,watchedDuration=0,completed=false)
    })
    fun positions(page:VideoPage)=history.cachedPositions()?.let {decorate(page,it)} ?: page
    suspend fun <T> readSource(id:String,action:(PlaylistStored,CancellationSignal)->T):T? = withContext(Dispatchers.IO) {
        val snapshot=sources[id] ?: return@withContext null;prepare(snapshot)
        sql {signal->readLocked(listOf(id),signal){if(valid(listOf(snapshot)))action(snapshot.stored,signal) else null}}
    }
    suspend fun groups(q:VideoQuery,limit:Int=6):List<String> = withContext(Dispatchers.IO) {
        val snapshot=selected(q);val indexed=snapshot.map {VideoSource(it.stored,prepare(it))}
        sql {signal->readLocked(snapshot.map {it.stored.source.id},signal){
            if(!valid(snapshot))return@readLocked emptyList()
            indexed.flatMap {source->android.database.sqlite.SQLiteDatabase.openDatabase(store.file(source.id).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {db->
                db.rawQuery("SELECT g.name FROM video_groups g WHERE g.id<>0 AND EXISTS(SELECT 1 FROM video_titles t WHERE t.scope=? AND t.group_id=g.id) ORDER BY g.name COLLATE NOCASE,g.name LIMIT ?",arrayOf(source.scope.toString(),limit.toString()),signal).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
            }}.distinct().sortedWith(::groupOrder).take(limit)
        }}
    }
    /** SQLite NOCASE folds ASCII only; compare code points to preserve its UTF-8 ordering across sources. */
    private fun groupOrder(a:String,b:String):Int {
        var i=0;var j=0
        while(i<a.length && j<b.length) {
            val left=a.codePointAt(i);val right=b.codePointAt(j)
            val x=if(left in 65..90)left+32 else left;val y=if(right in 65..90)right+32 else right
            if(x!=y)return x.compareTo(y)
            i+=Character.charCount(left);j+=Character.charCount(right)
        }
        return if(i<a.length)1 else if(j<b.length)-1 else a.compareTo(b)
    }
}
