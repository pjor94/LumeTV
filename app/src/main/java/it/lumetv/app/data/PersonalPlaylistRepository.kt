package it.lumetv.app.data

import android.content.Context
import android.util.AtomicFile
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import it.lumetv.core.ResolvedStream
import it.lumetv.core.PlaylistDescriptor
import it.lumetv.core.PlaylistDiagnostic
import it.lumetv.core.PlaylistDiagnosticCode
import it.lumetv.core.PlaylistDocument
import it.lumetv.core.PlaylistEntry
import it.lumetv.core.PlaylistContentKind
import it.lumetv.core.PlaylistContentClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlin.coroutines.resumeWithException

enum class PlaylistLocation { URL, FILE }
data class PersonalPlaylistSource(val id: String, val name: String, val location: PlaylistLocation,
    val enabled: Boolean, val count: Int, val updated: Long, val checked: Long, val revision: Long,
    val defaultKind: MediaKind, val error: String = "", val refreshing: Boolean = false, val nextRetry: Long = 0,
    val allowHttp: Boolean = false, val metadataComplete: Boolean = true)
data class PlaylistPreview(val count: Int, val sample: List<String>, val excluded: Int, val duplicates: Int,
    val warnings: List<String>, val httpEntries: Int, val drmEntries: Int,
    val diagnostics: List<PlaylistDiagnostic> = emptyList(), val diagnosticCounts: Map<PlaylistDiagnosticCode,Int> = emptyMap())
data class PlaylistImportProgress(val bytes: Long, val entries: Int)
data class PlaylistClassificationProgress(val name:String,val processed:Int,val total:Int)
enum class PlaylistSavePhase { PREPARING, SAVING }

class PlaylistDraft internal constructor(internal val location: PlaylistLocation, internal val address: String,
    internal val defaultKind: MediaKind, val report: PlaylistPreview, internal val file: File,
    val allowHttp: Boolean, internal val etag: String? = null, internal val modified: String? = null,
    internal val settings: PlaylistSettings = PlaylistSettings(), internal val descriptor: PlaylistDescriptor = PlaylistDescriptor(),
    internal var context: PersonalRequestContext? = null) : Closeable {
    override fun close() { file.delete(); File(file.path + "-journal").delete(); context?.invalidate(); context=null }
    override fun toString() = "PlaylistDraft(location=$location, count=${report.count})"
}

/** Private URLs, headers and keys stay on disk. Catalog state contains only channel display metadata. */
class PersonalPlaylistRepository(context: Context, private val fetcher: PlaylistFetcher? = null,
    private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_SOURCES = 10
        const val MAX_TOTAL = 1_000_000
        const val CATALOG_WINDOW = 200
        private val instances = java.util.WeakHashMap<Context, PersonalPlaylistRepository>()
        fun get(context: Context): PersonalPlaylistRepository = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app) { PersonalPlaylistRepository(app) }
        }
    }
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "personal-playlists")
    private val index = AtomicFile(File(directory, "index.json"))
    private val store = PersonalPlaylistStore(directory)
    private val mutex = Mutex()
    private val imports = Semaphore(1)
    private val sources = linkedMapOf<String, PlaylistStored>()
    private val registered = linkedSetOf<String>()
    private val catalogs = linkedMapOf<String,List<Channel>>()
    private val contentKinds=mutableMapOf<String,Set<PlaylistContentKind>>()
    private val busy = hashSetOf<String>()
    private val _state = MutableStateFlow<List<PersonalPlaylistSource>>(emptyList())
    val state: StateFlow<List<PersonalPlaylistSource>> = _state.asStateFlow()
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    private val _videoSources=MutableStateFlow<Set<String>>(emptySet())
    val videoSources:StateFlow<Set<String>> = _videoSources.asStateFlow()
    private val _classification=MutableStateFlow<PlaylistClassificationProgress?>(null)
    val classification:StateFlow<PlaylistClassificationProgress?> = _classification.asStateFlow()
    private val _videoProgress=MutableStateFlow<PlaylistClassificationProgress?>(null)
    val videoProgress=_videoProgress.asStateFlow()
    private val videoData=VideoLibraryData(store,PersonalPlaybackStore.get(app),app.getSharedPreferences("lume",Context.MODE_PRIVATE)) {name,done,total->_videoProgress.value=PlaylistClassificationProgress(name,done,total)}
    val videoCatalogVersion=videoData.version
    val videoFavoritesVersion=videoData.favoritesVersion
    fun videoCacheKey(query:VideoQuery):Any=videoData.key(query)
    internal fun clearVideoReadCache()=videoData.clearReadCache()
    internal fun videoReadCacheState()=videoData.cacheState()
    fun cachedVideos(query:VideoQuery,preview:Boolean=false):VideoPage?=videoData.cached(if(preview)query.copy(offset=0,limit=16) else query,preview)
    internal fun videoPositions(page:VideoPage)=videoData.positions(page)
    internal fun videoArtworkEpoch(source:String):String?=videoData.epoch(source)?.let {epoch->requestContext(source)?.let {"$epoch:${it.token}"}}

    private fun classificationProgress(name:String,processed:Int,total:Int){_classification.value=PlaylistClassificationProgress(name,processed,total)}
    @Volatile private var loaded = false
    @Volatile private var visibleChannels = emptyList<Channel>()
    @Volatile private var contexts = emptyMap<String,PersonalRequestContext>()
    @Volatile var storageError: String? = null
        private set

    suspend fun local(): List<Channel> { load(); return visibleChannels }
    fun cached(): List<Channel> = visibleChannels
    fun available(sourceId: String) = state.value.any { it.id==sourceId && it.enabled && it.count>0 }
    fun allowsHttp(sourceId: String) = state.value.firstOrNull { it.id==sourceId }?.allowHttp == true
    fun requestContext(sourceId:String):PersonalRequestContext? = contexts[sourceId]?.takeIf { it.valid() }
    suspend fun settings(id:String):PlaylistSettings { load();return mutex.withLock { sources[id]?.settings ?: throw IOException("La lista non è più disponibile.") } }
    suspend fun descriptor(id:String):PlaylistDescriptor { load();return mutex.withLock { sources[id]?.descriptor ?: PlaylistDescriptor() } }
    suspend fun importReport(id:String):PlaylistPreview? { load();return mutex.withLock { sources[id]?.report } }
    suspend fun classificationNeedsRefresh(id:String):Boolean {load();return mutex.withLock {sources[id]?.classificationNeedsRefresh==true}}
    suspend fun classificationConflicts(id:String):Int {load();return mutex.withLock {sources[id]?.classificationConflicts ?: 0}}
    suspend fun library(source:String?,query:String,group:String?,offset:Int=0):PersonalLibraryPage {
        return catalog(PersonalCatalogQuery(source=source,content=PlaylistContentKind.VOD,text=query,group=group,offset=offset,limit=50))
    }
    suspend fun videos(query:VideoQuery=VideoQuery()):VideoPage {load();return try {videoData.page(query)}finally {_videoProgress.value=null}}
    suspend fun videoPreview(query:VideoQuery):VideoPage {load();return try {videoData.page(query.copy(offset=0,limit=16),true)}finally {_videoProgress.value=null}}
    suspend fun videoGroups(query:VideoQuery):List<String> {load();return videoData.groups(query)}
    suspend fun videoDetails(item:VideoItem):VideoItem? = videos(if(item.isSeries)VideoQuery(source=item.source,series=item.series,limit=1) else VideoQuery(source=item.source,id=item.id,collapseSeries=false,limit=1)).rows.firstOrNull()
    suspend fun videoSeasons(item:VideoItem,query:String="",offset:Int=0):Pair<List<Int>,Int> {
        load();return videoData.readSource(item.source){stored,signal->
            android.database.sqlite.SQLiteDatabase.openDatabase(store.file(item.source).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {db->
                val filter="series_key=?"+if(stored.settings.contentOverride==null)" AND content='VOD'" else ""
                val sql="SELECT DISTINCT COALESCE(season,0) AS season FROM video_items WHERE $filter"
                val all=db.rawQuery("SELECT season FROM ($sql) WHERE CAST(season AS TEXT) LIKE ? ORDER BY season",arrayOf(item.series,"%${query.take(20)}%"),signal).use {c->buildList {while(c.moveToNext())add(c.getInt(0))}}
                all.drop(offset.coerceAtLeast(0)).take(50) to all.size
            }
        } ?: (emptyList<Int>() to 0)
    }
    suspend fun toggleSavedSeries(item:VideoItem) {
        require(item.isSeries);load();withContext(Dispatchers.IO){mutex.withLock {
            val prefs=app.getSharedPreferences("lume",Context.MODE_PRIVATE)
            val values=prefs.getStringSet("video_saved_series",emptySet()).orEmpty().toMutableSet()
            val favorites=prefs.getStringSet("favorites",emptySet()).orEmpty().toMutableSet()
            if(item.saved) {
                values.remove(item.key)
                android.database.sqlite.SQLiteDatabase.openDatabase(store.file(item.source).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {db->
                    db.rawQuery("SELECT id FROM video_metadata WHERE series_key=?",arrayOf(item.series)).use {c->while(c.moveToNext())favorites.remove(c.getString(0))}
                }
            }else {if(values.size>=5000)throw IOException("La mia lista contiene già 5.000 serie. Rimuovine una prima di aggiungerne altre.");values+=item.key}
            prefs.edit().putStringSet("video_saved_series",values).putStringSet("favorites",favorites).commit();_version.value++
        }}
    }
    internal suspend fun videoArtwork(item:VideoItem,backdrop:Boolean):PersonalArtwork? {
        load();return videoData.readSource(item.source){stored,signal->
            val context=requestContext(item.source) ?: return@readSource null
            VideoPerformance.measure("art_lookup") {
                android.database.sqlite.SQLiteDatabase.openDatabase(store.file(item.source).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {db->
                    db.rawQuery("SELECT d.${if(backdrop)"backdrop" else "poster"},r.payload FROM video_metadata d JOIN records r ON r.id=d.id WHERE d.id=? AND r.active=1",arrayOf(item.id),signal).use cursor@ {c->
                        if(!c.moveToFirst())return@cursor null
                        val url=PersonalLogoPolicy.url(c.getString(0),context.settings.artworkHttp) ?: return@cursor null
                        val entry=store.decodeEntry(JSONObject(c.getString(1)),stored.descriptor)
                        val headers=it.lumetv.core.PlaylistHeaders.merge(entry.headers.filterKeys {it.equals("User-Agent",true)},context.settings.rule(PlaylistResource.LOGO).headers)
                        val identity=item.source+"|"+context.root+"|"+context.allowHttp+"|"+context.settings.json().toString()+"|"+url+"|"+JSONObject(headers).toString()
                        val key=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString(""){"%02x".format(it)}
                        PersonalArtwork(url.toString(),key,headers,context)
                    }
                }
            }
        }
    }
    suspend fun catalog(query:PersonalCatalogQuery):PersonalLibraryPage {
        load();return withContext(Dispatchers.IO){mutex.withLock {
            if(query.ids?.isEmpty()==true)return@withLock PersonalLibraryPage(emptyList(),0)
            var skip=query.offset.coerceAtLeast(0);var total=0;val rows=mutableListOf<Channel>()
            val limit=query.limit.coerceIn(1,CATALOG_WINDOW)
            sources.values.filter {it.source.enabled && (query.source==null || it.source.id==query.source)}.forEach {s->
                kotlin.coroutines.coroutineContext.ensureActive()
                val page=store.catalog(s,query.copy(offset=skip,limit=(limit-rows.size).coerceAtLeast(1)))
                total+=page.total
                if(rows.size<limit)rows.addAll(page.rows.take(limit-rows.size))
                skip=(skip-page.total).coerceAtLeast(0)
            }
            PersonalLibraryPage(rows,total)
        }}
    }
    suspend fun groups(query:PersonalCatalogQuery):Pair<List<String>,Int> {
        load();return withContext(Dispatchers.IO){mutex.withLock {
            store.catalogGroups(sources.values.filter {it.source.enabled && (query.source==null || it.source.id==query.source)},query)
        }}
    }
    suspend fun channel(id:String,source:String?=null):Channel? {
        load();return withContext(Dispatchers.IO){mutex.withLock {
            sources.values.asSequence().filter {it.source.enabled && (source==null || it.source.id==source)}
                .mapNotNull {store.displayChannel(it,id)}.firstOrNull()
        }}
    }
    suspend fun next(current:Channel,direction:Int,ids:Set<String>?=null):Channel? {
        load();return withContext(Dispatchers.IO){mutex.withLock {
            sources[current.sourceId]?.takeIf {it.source.enabled}?.let {store.nextChannel(it,store.displayChannel(it,current.id) ?: current,direction,ids)}
        }}
    }
    suspend fun catalogPosition(current:Channel,ids:Set<String>?=null):Int {
        load();return withContext(Dispatchers.IO){mutex.withLock {
            sources[current.sourceId]?.takeIf {it.source.enabled}?.let {stored->
                store.displayChannel(stored,current.id)?.let {store.catalogPosition(stored,it,ids)}
            } ?: 0
        }}
    }
    internal suspend fun guideInputs(only:String?=null):List<PersonalGuideInput> {
        load();return withContext(Dispatchers.IO) {mutex.withLock {
            sources.values.filter {it.source.enabled && it.settings.guide.enabled && (only==null || it.source.id==only)}.mapNotNull {stored->
                val context=requestContext(stored.source.id) ?: return@mapNotNull null
                val candidates=try {store.guideCandidates(stored)}catch(error:Exception) {
                    return@mapNotNull PersonalGuideInput(stored.source,stored.settings,context,stored.descriptor,emptyList(),
                        if(error is SourceException)error.message else "Impossibile leggere i canali della guida personale.")
                }
                if(candidates.none {it.feeds.isNotEmpty()})null else PersonalGuideInput(stored.source,stored.settings,context,stored.descriptor,candidates)
            }
        }}
    }
    internal suspend fun playbackEntry(id:String):PersonalEntrySnapshot {
        load();return withContext(Dispatchers.IO) {mutex.withLock {
            val pair=sources.values.asSequence().filter {it.source.enabled}.mapNotNull {s->store.entry(s.source.id,id)?.let {s to it}}.firstOrNull()
                ?: throw SourceException("Il contenuto personale non è più disponibile.")
            val (s,entry)=pair
            val context=requestContext(s.source.id) ?: throw SourceException("La lista è stata disattivata.")
            PersonalEntrySnapshot(s.source,s.settings,entry,context,s.settings.contentOverride ?: entry.metadata.contentKind)
        }}
    }
    internal suspend fun artwork(channel:Channel):PersonalArtwork? {
        load();return withContext(Dispatchers.IO) {mutex.withLock {
            val context=requestContext(channel.sourceId) ?: return@withLock null
            val entry=store.entry(channel.sourceId,channel.id) ?: return@withLock null
            val raw=entry.logo.ifBlank {PersonalGuideRepository.icon(app,channel.sourceId,channel.id).orEmpty()}
            val url=PersonalLogoPolicy.url(raw,context.settings.artworkHttp) ?: return@withLock null
            val headers=it.lumetv.core.PlaylistHeaders.merge(entry.headers.filterKeys {it.equals("User-Agent",true)},context.settings.rule(PlaylistResource.LOGO).headers)
            val identity=channel.sourceId+"|"+context.allowHttp+"|"+context.root+"|"+context.settings.json().toString()+"|"+JSONObject(headers).toString()+"|"+url
            val key=java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") {"%02x".format(it)}
            PersonalArtwork(url.toString(),key,headers,context)
        }}
    }

    private suspend fun load():Unit { if(loaded)return;withContext(Dispatchers.IO) { mutex.withLock {
        if (loaded) return@withLock
        directory.mkdirs()
        val operation=kotlin.coroutines.coroutineContext
        try {
            if (index.baseFile.exists() || File(index.baseFile.path+".bak").exists()) {
                val root=index.openRead().use { input ->
                    if (index.baseFile.length()>64*1024) throw IOException("Indice troppo grande.")
                    JSONObject(input.bufferedReader().use { it.readText() })
                }
                require(root.getInt("version")==1)
                val ids=root.getJSONArray("ids"); require(ids.length()<=MAX_SOURCES)
                for(i in 0 until ids.length()) {
                    val id=ids.getString(i);require(PersonalPlaylistStore.validId(id));registered+=id
                    val recovered:Pair<PlaylistStored,List<Channel>>?=try {
                        store.read(id,{operation.ensureActive()},::classificationProgress)?.let {it to if(it.source.enabled)preview(it) else emptyList()}
                    }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){null}
                    recovered?.let { (stored,rows) ->
                        if (sources.values.sumOf { it.source.count }+stored.source.count<=MAX_TOTAL) {
                            sources[id]=stored
                            if(stored.source.enabled)catalogs[id]=rows
                        }
                    }
                }
            }
        } catch(cancelled:CancellationException) {registered.clear();sources.clear();catalogs.clear();throw cancelled}
        catch (_: Exception) { storageError="L’archivio delle liste non è leggibile. I dati sono stati conservati." }
        finally {_classification.value=null}
        if (storageError==null) store.cleanup(registered)
        File(app.cacheDir,"personal-imports").listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86_400_000 }?.forEach(File::delete)
        publish(emptySet())
        loaded=true
    } }}

    suspend fun prepareUrl(address: String, defaultKind: MediaKind = MediaKind.TV, allowHttp: Boolean = false,
                           settings: PlaylistSettings = PlaylistSettings(),
                           progress: (PlaylistImportProgress) -> Unit = {}): PlaylistDraft {
        load()
        val url=address.trim();validateUrl(url,allowHttp)
        val config=settings.validated();val context=PersonalRequestContext(config,allowHttp,url)
        return try { imports.withPermit {
            val download=download(url,null,null,allowHttp,context=context)
            if (download.unchanged) { download.close(); throw IOException("Il servizio non ha restituito la lista. Riprova.") }
            stage(download,PlaylistLocation.URL,url,defaultKind,allowHttp,progress,config,context,true)
        } } catch(error:Exception) { context.invalidate();throw error }
    }
    suspend fun prepareFile(bytes: ByteArray, defaultKind: MediaKind = MediaKind.TV, allowHttp: Boolean = false,
                            settings: PlaylistSettings = PlaylistSettings(),
                            progress: (PlaylistImportProgress) -> Unit = {}): PlaylistDraft {
        load()
        val config=settings.validated();val context=PersonalRequestContext(config,allowHttp,"")
        return try { imports.withPermit { stage(PlaylistDownload.stream(bytes.inputStream()),PlaylistLocation.FILE,"",defaultKind,allowHttp,progress,config,context,true) } }
        catch(error:Exception) { context.invalidate();throw error }
    }
    suspend fun prepareFile(file: File, defaultKind: MediaKind = MediaKind.TV, allowHttp: Boolean = false,
                            settings: PlaylistSettings = PlaylistSettings(),
                            progress: (PlaylistImportProgress) -> Unit = {}): PlaylistDraft {
        load()
        val config=settings.validated();val context=PersonalRequestContext(config,allowHttp,"")
        return try { imports.withPermit { stage(PlaylistDownload.stream(file.inputStream()),PlaylistLocation.FILE,"",defaultKind,allowHttp,progress,config,context,true) } }
        catch(error:Exception) { context.invalidate();throw error }
    }

    private suspend fun stage(download: PlaylistDownload, location: PlaylistLocation, address: String, kind: MediaKind,
                              allowHttp: Boolean, progress: (PlaylistImportProgress) -> Unit,
                              settings:PlaylistSettings=PlaylistSettings(), context:PersonalRequestContext?=null,
                              ownsContext:Boolean=false): PlaylistDraft = suspendCancellableCoroutine { continuation ->
        val worker=CoroutineScope(continuation.context).launch(Dispatchers.IO) {
            var result: PlaylistDraft?=null
            try {
                if (download.finalUrl.isNotBlank()) validateUrl(download.finalUrl,allowHttp)
                var bytes=0L;var entries=0;var last=0L
                fun notify(force:Boolean=false) { val time=System.nanoTime(); if(force || time-last>200_000_000) { last=time;progress(PlaylistImportProgress(bytes,entries)) } }
                val budget=PlaylistByteBudget()
                val byteProgress:(Long)->Unit={bytes=it;notify()}
                download.reader(byteProgress,settings.reading.encoding,budget).use { reader ->
                    val staged=store.stage(reader,download.finalUrl.takeIf(String::isNotBlank),kind,allowHttp,
                        { continuation.context.ensureActive() },{ entries=it;notify() },settings,include={ url ->
                            // The parser runs on IO. A nested download is a cancellable child of this import.
                            val child=runBlocking(continuation.context + Dispatchers.IO) { this@PersonalPlaylistRepository.download(url,null,null,allowHttp,context=context) }
                            try {
                                if(child.unchanged)throw IOException("Una lista inclusa non ha restituito il documento.")
                                validateUrl(child.finalUrl,allowHttp)
                                PlaylistDocument(child.reader(byteProgress,settings.reading.encoding,budget),child.finalUrl) {child.close()}
                            } catch(error:Exception) {child.close();throw error}
                        })
                    result=PlaylistDraft(location,address,kind,staged.preview,staged.file,allowHttp,download.etag,download.modified,
                        settings,staged.descriptor,if(ownsContext)context else null)
                }
                continuation.context.ensureActive();notify(true)
                val draft=result!!
                continuation.resume(draft) { _,value,_ -> value.close() }
                result=null
            } catch (error: Exception) {
                result?.close()
                val safe=if(error is java.nio.charset.CharacterCodingException) IOException("La codifica della lista non è valida. Scegli UTF-8, Windows-1252 o Latin-1 nelle impostazioni avanzate.") else error
                if(continuation.isActive) continuation.resumeWithException(safe)
            } finally { download.close() }
        }
        continuation.invokeOnCancellation { download.close();worker.cancel() }
    }
    private suspend fun download(url:String,etag:String?,modified:String?,allowHttp:Boolean,sourceId:String?=null,
                                 context:PersonalRequestContext?=null):PlaylistDownload =
        (fetcher ?: PersonalPlaylistNetwork.fetcher(allowHttp=allowHttp,httpAllowed={ sourceId==null || available(sourceId) && allowsHttp(sourceId) },context=context)).fetch(url,etag,modified)
    private fun validateUrl(url:String,allowHttp:Boolean) {
        val uri=runCatching { URI(url) }.getOrNull()
        if(uri==null || uri.host.isNullOrBlank() || uri.rawUserInfo!=null || uri.rawFragment!=null || url.length>8192 || !PersonalPlaylistNetwork.allowed(uri,allowHttp))
            throw IOException(if(uri?.scheme.equals("http",true) && !allowHttp) "Per usare questo indirizzo abilita Consenti HTTP per questa lista."
                else "Inserisci un indirizzo HTTP o HTTPS valido, senza credenziali prima del nome del server.")
    }

    suspend fun save(name:String,draft:PlaylistDraft,replacing:String?=null,progress:(PlaylistSavePhase)->Unit={}):String {
        load()
        return withContext(Dispatchers.IO) { mutex.withLock {
            writable();val title=title(name,replacing)
            val old=replacing?.let { sources[it] ?: throw IOException("La lista non è più disponibile.") }
            if(old==null && registered.size>=MAX_SOURCES) throw IOException("Puoi aggiungere fino a 10 liste personali.")
            total(draft.report.count,replacing)
            if(!draft.file.isFile || draft.file.parentFile!=directory) throw IOException("La verifica della lista non è più disponibile. Ripetila prima di importare.")
            val id=replacing ?: "user_"+UUID.randomUUID().toString().replace("-","")
            val source=PersonalPlaylistSource(id,title,draft.location,old?.source?.enabled ?: true,draft.report.count,now(),now(),
                (old?.source?.revision ?: 0)+1,draft.defaultKind,allowHttp=draft.allowHttp)
            val stored=PlaylistStored(source,draft.address,draft.etag,draft.modified,settings=draft.settings,descriptor=draft.descriptor,report=draft.report,
                classificationConflicts=draft.report.diagnosticCounts[PlaylistDiagnosticCode.CONTENT_CONFLICT] ?: 0)
            videoData.write(id) {
            progress(PlaylistSavePhase.PREPARING)
            store.prepareCommit(draft.file,stored,old?.let { store.file(id) },now())
            progress(PlaylistSavePhase.SAVING)
            store.commit(draft.file,id)
            if(old==null) {
                try { writeIndex(registered+id) } catch(error:Exception) { store.delete(id);throw error }
                registered+=id
            }
            sources[id]=stored
            draft.context?.let { prepared -> contexts[id]?.let {it.invalidate();clearArtworkFiles(id)};contexts=contexts+(id to prepared);draft.context=null }
            publish(setOf(id));id
            }
        } }
    }
    suspend fun rename(id:String,name:String)=change(id) { old -> old.copy(source=old.source.copy(name=title(name,id),revision=old.source.revision+1)) }
    suspend fun setEnabled(id:String,enabled:Boolean)=change(id) { old -> old.copy(source=old.source.copy(enabled=enabled,revision=old.source.revision+1)) }
    suspend fun setHttpAllowed(id:String,allowed:Boolean)=change(id) { old ->
        old.copy(source=old.source.copy(allowHttp=allowed,revision=old.source.revision+1,error=""),
            attempted=0,retryAt=old.serverRetryAt,failures=0)
    }
    suspend fun setSettings(id:String,settings:PlaylistSettings)=change(id) { old ->
        old.copy(source=old.source.copy(revision=old.source.revision+1,error=""),settings=settings.validated(),
            attempted=0,retryAt=old.serverRetryAt,failures=0,etag=null,modified=null)
    }
    private suspend fun change(id:String,action:(PlaylistStored)->PlaylistStored) {
        load();withContext(Dispatchers.IO) { mutex.withLock {
            writable();val old=sources[id] ?: throw IOException("La lista non è più disponibile.")
            val prepared=action(old)
            val reclassify=prepared.settings.reading.vodDetection!=old.settings.reading.vodDetection || prepared.settings.reading.groupContent!=old.settings.reading.groupContent ||
                prepared.settings!=old.settings && old.classificationVersion<PlaylistContentClassifier.VERSION
            val operation=kotlin.coroutines.coroutineContext
            videoData.write(id) {
            val next=try {store.change(prepared,reclassify,{operation.ensureActive()},::classificationProgress)} finally {_classification.value=null}
            sources[id]=next
            publish(if(reclassify || next.source.enabled!=old.source.enabled || next.settings.contentOverride!=old.settings.contentOverride) setOf(id) else emptySet())
            }
        } }
    }
    suspend fun address(id:String):String { load();return mutex.withLock { sources[id]?.address.orEmpty() } }
    suspend fun delete(id:String) {
        load();withContext(Dispatchers.IO) { mutex.withLock {
            writable();if(id !in registered)return@withLock
            videoData.write(id) {
            writeIndex(registered-id)
            val prefs=app.getSharedPreferences("lume",Context.MODE_PRIVATE)
            val references=buildSet {addAll(prefs.getStringSet("favorites",emptySet()).orEmpty())
                addAll(prefs.getString("recent_channels","").orEmpty().split(','));prefs.getString("last_channel",null)?.let(::add)
                runCatching {val reminders=JSONArray(app.getSharedPreferences("discovery",Context.MODE_PRIVATE).getString("reminders","[]"))
                    for(i in 0 until reminders.length())reminders.optJSONObject(i)?.optString("channel")?.let(::add)}}
            val removed=references.filter {store.containsId(id,it)}.toSet()
            contexts[id]?.invalidate();contexts=contexts-id
            sources.remove(id);registered-=id;store.delete(id);catalogs.remove(id)
            PersonalGuideRepository.removeFiles(app,id)
            PersonalPlaybackStore.get(app).removeSource(id)
            clearArtworkFiles(id)
            prefs.edit().putStringSet("video_saved_series",prefs.getStringSet("video_saved_series",emptySet()).orEmpty().filterNot {it.startsWith("$id:")}.toSet()).commit()
            clearReferences(removed);publish(emptySet())
            }
        } }
    }
    suspend fun refreshAll(force:Boolean=false):Boolean {
        load();val ids=mutex.withLock { sources.values.filter { it.source.enabled && it.source.location==PlaylistLocation.URL }.map { it.source.id } }
        return coroutineScope { ids.map { id -> async { try { refresh(id,force) } catch(c:CancellationException) { throw c } catch(_:Exception) { false } } }.awaitAll().any { it } }
    }
    suspend fun refresh(id:String,force:Boolean=true):Boolean {
        load()
        val previous=withContext(Dispatchers.IO) { mutex.withLock {
            writable();val old=sources[id] ?: throw IOException("La lista non è più disponibile.")
            if(id in busy || old.source.location!=PlaylistLocation.URL || old.retryAt>now() && old.retryAt-now()<=86_400_000 ||
                recent(old.attempted,30_000) || !force && !refreshDue(old)) return@withLock null
            val next=old.copy(attempted=now());videoData.write(id) {store.change(next);sources[id]=next;busy+=id;publish(emptySet(),false)};next
        } } ?: return false
        var draft:PlaylistDraft?=null
        var etag:String?=null;var modified:String?=null
        try {
            imports.withPermit {
                // A source can be disabled, deleted or changed while waiting for another large import.
                if(state.value.none { it.id==id && it.revision==previous.source.revision })return@withPermit
                val context=requestContext(id) ?: return@withPermit
                val complete=previous.source.metadataComplete && !previous.classificationNeedsRefresh && previous.classificationVersion>=PlaylistContentClassifier.VERSION
                var downloaded=download(previous.address,previous.etag.takeIf {complete},previous.modified.takeIf {complete},previous.source.allowHttp,id,context)
                if(downloaded.unchanged && (previous.source.count==0 || !complete)) {
                    downloaded.close();downloaded=download(previous.address,null,null,previous.source.allowHttp,id,context)
                    if(downloaded.unchanged) {downloaded.close();throw IOException("Il servizio non ha restituito i metadati della lista. La copia salvata resta disponibile.")}
                }
                etag=downloaded.etag;modified=downloaded.modified
                if(downloaded.unchanged) downloaded.close()
                else draft=stage(downloaded,PlaylistLocation.URL,previous.address,previous.source.defaultKind,previous.source.allowHttp,{},previous.settings,context)
            }
            return withContext(Dispatchers.IO) { mutex.withLock {
                val current=sources[id]
                if(current==null || current.source.revision!=previous.source.revision) return@withLock false
                val count=draft?.report?.count ?: current.source.count;total(count,id)
                val next=current.copy(source=current.source.copy(count=count,checked=now(),updated=if(draft!=null)now() else current.source.updated,error="",metadataComplete=if(draft!=null)true else current.source.metadataComplete),
                    etag=etag ?: current.etag,modified=modified ?: current.modified,retryAt=0,failures=0,serverRetryAt=0,
                    descriptor=draft?.descriptor ?: current.descriptor,report=draft?.report ?: current.report,
                    classificationVersion=if(draft!=null)PlaylistContentClassifier.VERSION else current.classificationVersion,
                    classificationNeedsRefresh=if(draft!=null)false else current.classificationNeedsRefresh,
                    classificationConflicts=draft?.report?.diagnosticCounts?.get(PlaylistDiagnosticCode.CONTENT_CONFLICT) ?: if(draft!=null)0 else current.classificationConflicts)
                videoData.write(id) {
                if(draft==null) store.change(next) else {
                    store.prepareCommit(draft.file,next,store.file(id),now());store.commit(draft.file,id)
                }
                sources[id]=next;publish(if(draft==null)emptySet() else setOf(id));true
                }
            } }
        } catch(c:CancellationException) { throw c }
        catch(error:Exception) {
            withContext(Dispatchers.IO) { mutex.withLock { sources[id]?.takeIf { it.source.revision==previous.source.revision }?.let { current ->
                val message=when(error) { is PlaylistFetchException,is it.lumetv.core.PlaylistFormatException -> error.message.orEmpty()
                    else -> "Aggiornamento non riuscito. La copia salvata resta disponibile." }
                val server=(error as? PlaylistFetchException)?.retryAt ?: 0
                val delay=if((error as? PlaylistFetchException)?.status==429)3_600_000L else (900_000L shl current.failures.coerceAtMost(2))
                val retry=if(server>now())server else now()+delay
                val serverPause=if(server>now() || (error as? PlaylistFetchException)?.status==429)retry else 0
                val next=current.copy(source=current.source.copy(error=message),failures=(current.failures+1).coerceAtMost(10),retryAt=retry,serverRetryAt=serverPause)
                videoData.write(id) {store.change(next);sources[id]=next;publish(emptySet(),false)}
            } } }
            throw error
        } finally {
            draft?.close()
            withContext(NonCancellable+Dispatchers.IO) { mutex.withLock { busy-=id;publish(emptySet(),false) } }
        }
    }
    suspend fun resolve(id:String,http:okhttp3.OkHttpClient):ResolvedStream {
        load()
        val (stream,context)=withContext(Dispatchers.IO) { mutex.withLock {
            val pair=sources.values.asSequence().filter {it.source.enabled}.mapNotNull {s->store.entry(s.source.id,id)?.let {s to it}}.firstOrNull()
                ?: throw SourceException("Questo canale personale non è più disponibile. Controlla la sua lista.")
            val (source,entry)=pair
            val context=requestContext(source.source.id) ?: throw SourceException("La configurazione della lista è cambiata. Riapri il canale.")
            PersonalPlaybackConfig.parse(entry,source.source.allowHttp,source.settings).copy(personalNetworkToken=context.token) to context
        } }
        val mime=stream.mimeType ?: PersonalPlaylistNetwork.detectMime(stream.url,stream.manifestHeaders ?: stream.headers,http,stream.personalAllowHttp,context)
        if(!context.valid())throw SourceException("La configurazione della lista è cambiata. Riapri il canale.")
        return stream.copy(mimeType=mime)
    }
    private fun publish(changed:Set<String>,increment:Boolean=true) {
        contentKinds.keys.retainAll(sources.keys)
        sources.forEach {(id,stored)->if(id !in contentKinds || id in changed) {
            contentKinds[id]=runCatching {store.contentKinds(stored)}.getOrDefault(emptySet())
        }}
        _videoSources.value=sources.values.filter {it.source.enabled && PlaylistContentKind.VOD in contentKinds[it.source.id].orEmpty()}.map {it.source.id}.toSet()
        val nextContexts=linkedMapOf<String,PersonalRequestContext>()
        sources.forEach { (id,stored) ->
            val old=contexts[id]
            if(stored.source.enabled) {
                nextContexts[id]=old?.takeIf { it.valid() && it.allowHttp==stored.source.allowHttp && it.root==stored.address && it.updateGuideOnly(stored.settings) }
                    ?: PersonalRequestContext(stored.settings,stored.source.allowHttp,stored.address).also {if(old!=null){old.invalidate();clearArtworkFiles(id)}}
            } else if(old!=null) {old.invalidate();clearArtworkFiles(id)}
        }
        contexts.filterKeys { it !in sources }.forEach { (id,context) ->context.invalidate();clearArtworkFiles(id)}
        contexts=nextContexts
        changed.forEach { id -> sources[id]?.let { stored ->
            if(stored.source.enabled) catalogs[id]=preview(stored) else catalogs.remove(id)
        } }
        if(changed.isNotEmpty() || increment) {
            visibleChannels=buildList { sources.values.filter { it.source.enabled }.forEach { addAll(catalogs[it.source.id].orEmpty()) } }
        }
        _state.value=registered.map { id -> sources[id]?.let { it.source.copy(refreshing=id in busy,nextRetry=it.retryAt) }
            ?: PersonalPlaylistSource(id,"Lista non leggibile",PlaylistLocation.FILE,false,0,0,0,0,MediaKind.TV,"L’archivio salvato non è leggibile. Puoi eliminare questa voce e importare di nuovo la lista.") }
        videoData.update(sources.values,_videoSources.value,changed)
        if(increment)_version.value++
    }
    private fun total(count:Int,replacing:String?) {
        if(sources.values.filter { it.source.id!=replacing }.sumOf { it.source.count }+count>MAX_TOTAL)
            throw IOException("Le liste personali superano il limite complessivo di 1.000.000 voci importate.")
    }
    private fun preview(stored:PlaylistStored):List<Channel> {
        val rows=store.catalog(stored,PersonalCatalogQuery(content=null,limit=CATALOG_WINDOW)).rows
        val prefs=app.getSharedPreferences("lume",Context.MODE_PRIVATE)
        val pinned=buildSet {prefs.getString("last_channel",null)?.let(::add)
            addAll(prefs.getString("recent_channels","").orEmpty().split(',').filter(String::isNotBlank).take(40))}
        val extra=pinned.mapNotNull {store.displayChannel(stored,it)}
        return (rows+extra).distinctBy {it.id}
    }
    private fun clearArtworkFiles(id:String) {
        VideoArtworkRepository.invalidateSource(this,id)
        listOf("channel-artwork","video-artwork").forEach {folder->File(app.filesDir,folder).listFiles().orEmpty().filter {it.name.startsWith("$id-")}.forEach(File::delete)}
    }
    private fun title(name:String,replacing:String?):String {
        val value=name.trim().take(100)
        if(value.isBlank())throw IOException("Assegna un nome alla lista")
        if(sources.values.any { it.source.id!=replacing && it.source.name.equals(value,true) })throw IOException("Esiste già una lista con questo nome. Scegli un nome diverso.")
        return value
    }
    private fun recent(time:Long,interval:Long)=time>0 && now()>=time && now()-time<interval
    private fun refreshDue(stored:PlaylistStored):Boolean {
        stored.settings.refreshMinutes?.let {return !recent(stored.source.checked,it*60_000L)}
        val at=stored.descriptor.refreshAt
        if(at!=null && stored.source.checked<at)return now()>=minOf(at,stored.source.checked+7*86_400_000L)
        val interval=stored.descriptor.refreshMillis ?: 86_400_000L
        return !recent(stored.source.checked,interval)
    }
    private fun writable() { storageError?.let { throw IOException(it) } }
    private fun writeIndex(ids:Collection<String>) {
        val bytes=JSONObject().put("version",1).put("ids",JSONArray(ids)).toString().toByteArray()
        val output=index.startWrite()
        try { output.write(bytes);index.finishWrite(output) } catch(error:Exception) { index.failWrite(output);throw error }
    }
    private fun clearReferences(ids:Set<String>) = synchronized(IntegrationStore.cacheLock) {
        val prefs=app.getSharedPreferences("lume",Context.MODE_PRIVATE)
        val editor=prefs.edit().putStringSet("favorites",prefs.getStringSet("favorites",emptySet()).orEmpty()-ids)
            .putString("recent_channels",prefs.getString("recent_channels","").orEmpty().split(',').filterNot { it in ids }.joinToString(","))
        if(prefs.getString("last_channel",null) in ids)editor.remove("last_channel")
        editor.apply()
        val discovery=app.getSharedPreferences("discovery",Context.MODE_PRIVATE)
        runCatching {
            val rows=JSONArray(discovery.getString("reminders","[]"));val kept=JSONArray()
            for(i in 0 until rows.length())rows.optJSONObject(i)?.takeIf { it.optString("channel") !in ids }?.let(kept::put)
            discovery.edit().putString("reminders",kept.toString()).apply()
        }
    }
}

data class PersonalLibraryPage(val rows:List<Channel>,val total:Int)
