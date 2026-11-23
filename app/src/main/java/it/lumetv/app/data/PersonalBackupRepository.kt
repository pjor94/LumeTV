package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaybackTarget
import it.lumetv.core.PlaylistContentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Only an authenticated, fully staged preview can reach the additive restore path. */
internal class PersonalBackupRepository(
    context:Context,
    private val repository:PersonalPlaylistRepository=PersonalPlaylistRepository.get(context),
    private val catalog:suspend ()->List<Channel> = {ChannelRepository(context,HttpClient()).backupBuiltins()},
) {
    private val app=context.applicationContext
    private val work=File(app.cacheDir,"backup-work")
    private val history=PersonalPlaybackStore.get(app)
    private val prefs=app.getSharedPreferences("lume",Context.MODE_PRIVATE)
    private val mutex=synchronized(locks){locks.getOrPut(app){Mutex()}}
    companion object {
        private val locks=java.util.WeakHashMap<Context,Mutex>()
        private val activePreviews=hashSetOf<String>()
    }
    private fun cleanWork() {
        work.mkdirs()
        synchronized(activePreviews) {
            work.listFiles().orEmpty().filter {
                if(it.isDirectory && it.name.startsWith("preview-"))it.path !in activePreviews
                else System.currentTimeMillis()-it.lastModified()>86_400_000
            }.forEach(File::deleteRecursively)
        }
    }

    suspend fun export(password:CharArray,progress:(String)->Unit={}):File = withContext(Dispatchers.IO){mutex.withLock {
        val operation=coroutineContext;val check={operation.ensureActive()}
        cleanWork()
        BackupCipher.requirePassword(password)
        val builtin=catalog().associateBy {it.id}
        val personal=BackupPersonalData.capture(app);val references=BackupPersonalData.references(personal)
        val preferences=BackupPersonalData.preferences(app);BackupPersonalData.validatePreferences(preferences)
        val target=File(work,"export-"+UUID.randomUUID()+".lumebackup")
        try {
            repository.backupSnapshot {sources,store->
                val owners=references.filter {it in builtin}.associateWith {builtin.getValue(it).sourceId}.toMutableMap()
                target.outputStream().buffered().use {output->
                    BackupCipher.encrypt(output,password,check) {encrypted->
                        ZipOutputStream(encrypted).use {zip->
                            sources.forEachIndexed {index,source->
                                check();progress("Salvo lista "+(index+1)+" / "+sources.size+"…")
                                zip.putNextEntry(ZipEntry("lists/"+source.source.id+".jsonl"))
                                store.writeBackup(source,zip,check){id->if(id in references)owners[id]=source.source.id}
                                zip.closeEntry()
                            }
                            val resolved=BackupPersonalData.resolveCaptured(personal,owners,sources.map {it.source.id}.toSet())
                            BackupPersonalData.validate(resolved,sources.map {it.source.id}.toSet())
                            val watches=history.backupRows().filter {row->sources.any {it.source.id==row.source}}
                            val manifest=JSONObject().put("format","lumetv-backup").put("version",1).put("created",System.currentTimeMillis())
                                .put("sources",JSONArray(sources.map {it.source.id})).put("personal",resolved).put("preferences",preferences)
                                .put("watch",JSONArray().apply {watches.forEach {put(watchJson(it))}})
                            progress("Proteggo le impostazioni e gli avanzamenti…")
                            zip.putNextEntry(ZipEntry("manifest.json"));BackupIO.writeJson(zip,manifest);zip.closeEntry()
                        }
                    }
                }
            }
            target
        }catch(error:Exception){target.delete();if(error is CancellationException)throw error;throw safe(error,"Impossibile creare il backup. I dati salvati sono conservati.")}
    }}

    /** Copies only the encrypted document. Passwords and plaintext never enter Android saved state. */
    suspend fun copyEncrypted(input:InputStream):File = withContext(Dispatchers.IO) {
        cleanWork()
        val target=File(work,"input-"+UUID.randomUUID()+".lumebackup")
        try {
            input.use {source->target.outputStream().use {output->
                var total=0L;val buffer=ByteArray(64*1024)
                while(true){coroutineContext.ensureActive();val count=source.read(buffer);if(count<0)break
                    total+=count;if(total>BackupCipher.MAX_BYTES+1024*1024)throw IOException("Il backup supera il limite di 2 GiB.")
                    output.write(buffer,0,count)}
                output.fd.sync()
            }}
            target
        }catch(error:Exception){target.delete();throw error}
    }
    fun exported(name:String?):File? = name?.takeIf {Regex("export-[a-f0-9-]{36}\\.lumebackup").matches(it)}
        ?.let {File(work,it)}?.takeIf(File::isFile)

    suspend fun prepare(file:File,password:CharArray,progress:(String)->Unit={}):BackupPreview {
        val caller=coroutineContext[Job]
        return withContext(Dispatchers.IO){mutex.withLock {
        val operation=coroutineContext;val check={operation.ensureActive()}
        cleanWork()
        val directory=File(work,"preview-"+UUID.randomUUID())
        synchronized(activePreviews){activePreviews.add(directory.path);directory.mkdirs()}
        try {
            val zipFile=File(directory,"archive.zip")
            progress("Verifico password e integrità del backup…")
            file.inputStream().use {input->zipFile.outputStream().buffered().use {BackupCipher.decrypt(input,it,password,check)}}
            check()
            val builtin=catalog().map {it.id}.toSet()
            val store=PersonalPlaylistStore(File(directory,"lists"))
            val candidates=mutableListOf<BackupPlaylist>();val seen=hashSetOf<String>();var manifest:JSONObject?=null
            var totalBytes=0L;var records=0
            SQLiteDatabase.openOrCreateDatabase(File(directory,"identities.sqlite"),null).use {registry->
                registry.execSQL("CREATE TABLE identities(id TEXT PRIMARY KEY,source TEXT NOT NULL)")
                registry.beginTransaction()
                try {
                    registry.compileStatement("INSERT INTO identities(id,source) VALUES(?,?)").use {insert->
                        ZipInputStream(zipFile.inputStream().buffered()).use {zip->
                            while(true) {
                                check();val entry=zip.nextEntry ?: break
                                require(!entry.isDirectory && seen.add(entry.name) && seen.size<=PersonalPlaylistRepository.MAX_SOURCES+1)
                                val limited=object:InputStream() {
                                    override fun read():Int {val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
                                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                                        check();val count=zip.read(bytes,offset,length)
                                        if(count>0){totalBytes+=count;if(totalBytes>BackupCipher.MAX_BYTES)throw IOException("Il backup espanso supera il limite di 2 GiB.")}
                                        return count
                                    }
                                }.buffered(64*1024)
                                if(entry.name=="manifest.json")manifest=BackupIO.json(limited)
                                else {
                                    require(Regex("lists/user_[a-f0-9]{32}\\.jsonl").matches(entry.name))
                                    val id=entry.name.substringAfter('/').removeSuffix(".jsonl")
                                    progress("Verifico lista "+(candidates.size+1)+"…")
                                    candidates+=store.readBackup(limited,id,check){key->
                                        require(key !in builtin && ++records<=PersonalPlaylistRepository.MAX_TOTAL+PersonalPlaylistRepository.MAX_SOURCES*PersonalPlaylistStore.MAX_ARCHIVED)
                                        insert.bindString(1,key);insert.bindString(2,id);insert.executeInsert()
                                    }
                                    require(candidates.sumOf {it.stored.source.count}<=PersonalPlaylistRepository.MAX_TOTAL)
                                }
                                zip.closeEntry()
                            }
                        }
                    }
                    val root=manifest ?: throw IOException("Il backup è incompleto.")
                    require(root.getString("format")=="lumetv-backup")
                    if(root.getInt("version")!=1)throw IOException("Questa versione del backup non è supportata. Aggiorna Lume e riprova.")
                    val ids=BackupPersonalData.strings(root.getJSONArray("sources")).toSet()
                    require(ids==candidates.map {it.stored.source.id}.toSet())
                    require(root.getLong("created") in 0..4_102_444_800_000L)
                    val personal=root.getJSONObject("personal");BackupPersonalData.validate(personal,ids)
                    val preferences=root.getJSONObject("preferences");BackupPersonalData.validatePreferences(preferences)
                    registry.compileStatement("SELECT source FROM identities WHERE id=?").use {owner->
                        val owners=personal.getJSONObject("owners")
                        owners.keys().forEach {key->
                            check()
                            if(owners.getString(key) !in BackupPersonalData.builtinSources){owner.bindString(1,key);require(owner.simpleQueryForString()==owners.getString(key))}
                        }
                    }
                    val watches=readWatch(root.getJSONArray("watch"),ids)
                    registry.setTransactionSuccessful()
                    zipFile.delete()
                    BackupPreview(directory,root.getLong("created"),candidates,personal,watches,preferences) {
                        synchronized(activePreviews){activePreviews.remove(directory.path);Unit}
                    }.also {value->
                        caller?.invokeOnCompletion {if(caller.isCancelled)value.close()}
                    }
                }finally {registry.endTransaction()}
            }
        }catch(error:Exception){directory.deleteRecursively();synchronized(activePreviews){activePreviews.remove(directory.path)}
            if(error is CancellationException)throw error
            throw safe(error,"Il backup contiene dati non validi o non compatibili. I dati sul dispositivo sono conservati.")}
    }}}

    suspend fun restore(preview:BackupPreview,selection:BackupSelection,progress:(String)->Unit={}):BackupRestoreResult {
        if(!preview.acquire())throw IOException("L’anteprima non è più disponibile. Seleziona di nuovo il backup.")
        try {return withContext(Dispatchers.IO+kotlinx.coroutines.NonCancellable){mutex.withLock {
            if(!preview.directory.isDirectory)throw IOException("L’anteprima non è più disponibile. Seleziona di nuovo il backup.")
            if(selection.categories.isEmpty())throw IOException("Scegli almeno una categoria da ripristinare.")
            val operation=coroutineContext;val check={operation.ensureActive()}
            val builtins=catalog().associate {it.id to BackupReference(it.sourceId,it.kind,PlaylistContentKind.LIVE)}
            val referenceIds=BackupPersonalData.references(preview.personal)+preview.watches.map {it.target.channelId}
            var previous:Map<String,Any?>?=null
            try {
                val chosen=preview.playlists.filter {selection.playlistIds==null || it.stored.source.id in selection.playlistIds}
                repository.restoreBackup(chosen,BackupCategory.PLAYLISTS in selection,referenceIds,check) {personal,sourceIds,added,kept,commit->
                    check()
                    val references=builtins+personal
                    val owners=preview.personal.getJSONObject("owners")
                    fun eligible(id:String)=references[id]?.source==owners.optString(id)
                    val watches=if(BackupCategory.WATCH in selection)preview.watches.filter {row->
                        references[row.target.channelId]?.let {it.source==row.source && if(row.target.mode==PlaybackMode.VOD)it.content==PlaylistContentKind.VOD else it.content==PlaylistContentKind.LIVE && it.kind==MediaKind.TV}==true
                    } else emptyList()
                    var ignored=if(BackupCategory.WATCH in selection)preview.watches.size-watches.size else 0
                    synchronized(IntegrationStore.cacheLock) {
                        val changes=linkedMapOf<String,Any>()
                        var favoriteCount=0
                        if(BackupCategory.PERSONAL in selection) {
                            val value=preview.personal
                            val favorites=BackupPersonalData.strings(value.getJSONArray("favorites")).filter(::eligible).toSet()
                            ignored+=value.getJSONArray("favorites").length()-favorites.size
                            val current=prefs.getStringSet("favorites",emptySet()).orEmpty()
                            favoriteCount=(favorites-current).size;changes["favorites"]=current+favorites
                            val layout=ChannelPersonalization.get(app).backupLayout();val raw=value.getJSONObject("layout")
                            val order=BackupPersonalData.strings(raw.getJSONArray("order")).filter(::eligible)
                            val hidden=raw.getJSONArray("hidden")
                            val restored=buildList {
                                for(i in 0 until hidden.length()) {
                                    val row=hidden.getJSONObject(i);val id=row.getString("id")
                                    if(eligible(id) && references[id]?.content!=PlaylistContentKind.VOD)add(HiddenChannel(id,row.getString("source"),row.getString("name"),it.lumetv.core.MediaKind.valueOf(row.getString("kind"))))
                                }
                            }
                            val combined=(layout.order+order).distinct()
                            val hiddenIds=layout.hiddenIds
                            val merged=ChannelLayout(combined,layout.hidden+restored.filter {row->row.id !in hiddenIds},
                                layout.owners+order.associateWith {owners.getString(it)})
                            val text=BackupPersonalData.layoutJson(merged).toString()
                            if(text.length>8*1024*1024)throw IOException("Le personalizzazioni unite superano il limite di spazio.")
                            changes["channel_layout"]=text
                            val series=BackupPersonalData.strings(value.getJSONArray("series")).filter {it.substringBefore(':') in sourceIds}
                            val saved=prefs.getStringSet("video_saved_series",emptySet()).orEmpty()+series
                            if(saved.size>5000)throw IOException("Il ripristino supera il limite di 5.000 serie nella mia lista.")
                            changes["video_saved_series"]=saved
                        }
                        if(BackupCategory.PREFERENCES in selection) {
                            BackupPersonalData.preferenceKeys.forEach {key->changes[key]=preview.preferences.get(key)}
                        }
                        previous=changes.keys.associateWith {prefs.all[it]}
                        progress("Applico il ripristino…")
                        val metadata=JSONArray().apply {
                            if(BackupCategory.PERSONAL in selection) {
                                val rows=preview.personal.optJSONArray("metadata") ?: JSONArray()
                                for(i in 0 until rows.length()) {
                                    val row=rows.getJSONObject(i);val key=row.getString("key")
                                    if(row.getString("source") in sourceIds && (key.startsWith("s:") || eligible(key.removePrefix("i:"))))put(row)
                                }
                            }
                        }
                        VideoMetadataStore(app).use {metadataStore->metadataStore.restoreWithChoices(metadata,sourceIds) {
                        history.restoreMissing(watches) {imported,skipped->
                            ignored+=skipped
                            if(!commitPreferences(changes))throw IOException("Impossibile salvare le impostazioni. Riprova.")
                            commit()
                            BackupRestoreResult(added,kept,favoriteCount,imported,ignored,BackupCategory.PREFERENCES in selection)
                        }
                        }}
                    }
                }.also {repository.metadataRestored();runCatching {IntegrationStore.changed(app)}}
            }catch(error:Exception) {
                previous?.let {before->synchronized(IntegrationStore.cacheLock){commitPreferences(before)}}
                if(error is CancellationException)throw error
                throw safe(error,"Il ripristino non è riuscito. I dati già presenti sono conservati.")
            }
        }}}finally {preview.release()}
    }

    @android.annotation.SuppressLint("UseKtx") // The commit result is required for rollback.
    private fun commitPreferences(values:Map<String,Any?>):Boolean {
        val editor=prefs.edit()
        values.forEach {(key,value)->when(value) {
            null->editor.remove(key)
            is Boolean->editor.putBoolean(key,value)
            is String->editor.putString(key,value)
            is Set<*>->editor.putStringSet(key,value.filterIsInstance<String>().toSet())
            else->throw IllegalArgumentException("Unsupported preference")
        }}
        return editor.commit()
    }
    private fun watchJson(row:BackupWatch)=JSONObject().put("source",row.source).put("id",row.target.channelId).put("mode",row.target.mode.name)
        .put("event",row.target.eventId).put("position",row.value.position).put("duration",row.value.duration)
        .put("completed",row.value.completed).put("hidden",row.value.hidden).put("updated",row.updated)
    private fun readWatch(values:JSONArray,sources:Set<String>):List<BackupWatch> {
        require(values.length()<=5000);val seen=hashSetOf<Pair<String,PlaybackTarget>>()
        return (0 until values.length()).map {i->values.getJSONObject(i).let {row->
            val source=row.getString("source");val id=row.getString("id");require(source in sources && BackupIO.validChannel(id))
            val mode=PlaybackMode.valueOf(row.getString("mode"));val event=row.getString("event")
            require(mode!=PlaybackMode.LIVE && event.length<=512 && (mode!=PlaybackMode.VOD || event.isEmpty()))
            val position=row.getLong("position");val duration=row.getLong("duration");val updated=row.getLong("updated")
            require(position in 0..31_536_000_000L && duration in 0..31_536_000_000L && updated>=0)
            val value=BackupWatch(source,PlaybackTarget(id,mode,event),WatchPosition(position,duration,row.getBoolean("completed"),row.getBoolean("hidden")),minOf(updated,System.currentTimeMillis()))
            require(seen.add(source to value.target));value
        }}
    }
    private fun safe(error:Exception,fallback:String):IOException =
        if(error.javaClass==IOException::class.java)error as IOException else IOException(fallback)
}
