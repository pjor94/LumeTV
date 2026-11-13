package it.lumetv.app.data

import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import it.lumetv.core.PersonalM3uParser
import it.lumetv.core.PlaylistEntry
import it.lumetv.core.PlaylistFormatException
import it.lumetv.core.PlaylistIdentity
import it.lumetv.core.PlaylistMetadata
import it.lumetv.core.PlaylistDescriptor
import it.lumetv.core.PlaylistContentKind
import it.lumetv.core.PlaylistDiagnostic
import it.lumetv.core.PlaylistDiagnosticCode
import it.lumetv.core.PlaylistDocument
import it.lumetv.core.PlaylistContentClassifier
import it.lumetv.core.PlaylistClassificationReason
import it.lumetv.core.PlaylistVodMarker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.Reader
import java.security.MessageDigest
import java.util.UUID

internal data class PlaylistStored(val source: PersonalPlaylistSource, val address: String,
    val etag: String? = null, val modified: String? = null, val attempted: Long = 0,
    val retryAt: Long = 0, val failures: Int = 0, val serverRetryAt: Long = 0,
    val settings: PlaylistSettings = PlaylistSettings(), val descriptor: PlaylistDescriptor = PlaylistDescriptor(),
    val report: PlaylistPreview? = null,val classificationVersion:Int=PlaylistContentClassifier.VERSION,
    val classificationNeedsRefresh:Boolean=false,val classificationConflicts:Int=0)

internal data class PlaylistStageResult(val file: File, val preview: PlaylistPreview, val descriptor: PlaylistDescriptor)

/** One closed SQLite snapshot per source; an import only replaces the live snapshot after validation. */
internal class PersonalPlaylistStore(val directory: File) {
    companion object {
        const val MAX_DATABASE = 1024L * 1024 * 1024
        const val MAX_DISK = 2L * 1024 * 1024 * 1024
        const val MAX_ARCHIVED = 5_000
        private const val CLASSIFICATION_DEFERRED = "Riconoscimento VOD non aggiornato. La copia salvata resta disponibile. Libera spazio e riapri l’app, oppure verifica di nuovo la lista."
        fun validId(id: String) = Regex("user_[a-f0-9]{32}").matches(id)
    }
    fun file(id: String): File { require(validId(id)); return File(directory, "$id.sqlite") }
    fun cleanup(registered: Set<String>) {
        directory.listFiles()?.forEach { file ->
            val id = file.name.substringBefore('.')
            if (validId(id) && id !in registered) file.delete()
            if (file.name.startsWith(".draft-") && System.currentTimeMillis() - file.lastModified() > 86_400_000) file.delete()
        }
    }

    fun stage(reader: Reader, base: String?, kind: MediaKind, allowHttp: Boolean,
              checkActive: () -> Unit, progress: (Int) -> Unit, settings: PlaylistSettings = PlaylistSettings(),
              include: ((String) -> PlaylistDocument)? = null): PlaylistStageResult {
        directory.mkdirs()
        checkSpace(16L * 1024 * 1024)
        val target = File(directory, ".draft-${UUID.randomUUID()}.sqlite")
        try {
            val sample = mutableListOf<String>()
            var drm = 0
            val report = create(target).use { db ->
                db.execSQL("PRAGMA temp_store=FILE")
                attachWork(db,target)
                db.beginTransaction()
                try {
                    db.execSQL("CREATE TABLE work.import_fingerprints(value TEXT PRIMARY KEY)")
                    db.compileStatement("INSERT OR IGNORE INTO work.import_fingerprints(value) VALUES(?)").use { fingerprint ->
                    db.compileStatement("INSERT OR IGNORE INTO record_groups(group_name,position) VALUES(?,?)").use { group ->
                    db.compileStatement("INSERT INTO records(id,payload,name,number,logo,kind,groups_json,position,active,seen,identity1,identity2,identity3,content) VALUES(?,?,?,?,?,?,?,?,1,0,?,?,?,?)").use { insert ->
                        val result = PersonalM3uParser.scan(reader, base, kind, { PersonalPlaylistNetwork.allowed(it, allowHttp) }, checkActive, {
                            progress(it); if (it % 256 == 0) checkSpace(8L * 1024 * 1024)
                        }, settings.reading, budget = it.lumetv.core.PlaylistScanBudget { value ->
                            fingerprint.bindString(1,value);fingerprint.executeInsert()!=-1L
                        }, include = include) { entry ->
                            try {
                                val playback = PersonalPlaybackConfig.parse(entry, allowHttp, settings)
                                bindEntry(insert, entry, PlaylistIdentity.newId(), 0)
                                insert.executeInsert()
                                entry.groups.forEach { name -> group.bindString(1,name);group.bindLong(2,entry.position.toLong());group.executeInsert() }
                                if (sample.size < 5) sample += entry.name
                                if (playback.drm != null) drm++
                                null
                            } catch (error: PlaylistFormatException) { error.message ?: "La configurazione del canale non è supportata." }
                        }
                        db.setTransactionSuccessful(); result
                    } } }
                } finally { db.endTransaction() }
            }
            return PlaylistStageResult(target, PlaylistPreview(report.imported, sample, report.excluded, report.duplicates,
                report.warnings, report.httpEntries, drm, report.diagnostics, report.diagnosticCounts), report.descriptor)
        } catch (error: Exception) { target.delete(); File(target.path + "-journal").delete(); throw error }
        finally {deleteWork(target)}
    }

    fun prepareCommit(draft: File, stored: PlaylistStored, previous: File?, now: Long) {
        checkSpace(8L * 1024 * 1024)
        try {writeDb(draft).use { db ->
            if (previous != null) db.execSQL("ATTACH DATABASE ? AS prior", arrayOf(previous.absolutePath))
            attachWork(db,draft)
            try {
                db.beginTransaction()
                try {
                    if (previous != null) {
                        db.execSQL("CREATE TABLE work.matches(row_id INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE)")
                        db.execSQL("CREATE TABLE work.fresh_keys(k TEXT PRIMARY KEY,rid INTEGER NOT NULL,n INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE work.old_keys(k TEXT PRIMARY KEY,id TEXT NOT NULL,n INTEGER NOT NULL)")
                        val freshCount=db.rawQuery("SELECT COUNT(*) FROM records",null).use {it.moveToFirst();it.getLong(0)}
                        for (key in 1..3) {
                            db.execSQL("DELETE FROM work.fresh_keys");db.execSQL("DELETE FROM work.old_keys")
                            // Ordered index scans stream each group into an on-disk table instead of
                            // materializing two complete GROUP BY subqueries in SQLite's native heap.
                            db.execSQL("""INSERT INTO work.fresh_keys SELECT identity$key,MIN(rowid),COUNT(*)
                                FROM records INDEXED BY match$key WHERE identity$key IS NOT NULL
                                AND rowid NOT IN (SELECT row_id FROM work.matches) GROUP BY identity$key""")
                            db.execSQL("""INSERT INTO work.old_keys SELECT identity$key,MIN(id),COUNT(*)
                                FROM prior.records INDEXED BY match$key WHERE identity$key IS NOT NULL
                                AND id NOT IN (SELECT id FROM work.matches) GROUP BY identity$key""")
                            db.execSQL("""INSERT INTO work.matches(row_id,id) SELECT fresh.rid,old.id
                                FROM work.fresh_keys fresh JOIN work.old_keys old ON fresh.k=old.k
                                WHERE fresh.n=1 AND old.n=1""")
                            val matched=db.rawQuery("SELECT COUNT(*) FROM work.matches",null).use {it.moveToFirst();it.getLong(0)}
                            if(matched==freshCount)break
                        }
                        db.execSQL("UPDATE records SET id=(SELECT id FROM work.matches WHERE matches.row_id=records.rowid) WHERE rowid IN (SELECT row_id FROM work.matches)")
                    }
                    db.execSQL("UPDATE records SET seen=?", arrayOf(now))
                    if (previous != null) {
                        db.execSQL("CREATE TABLE work.archived_ids(id TEXT PRIMARY KEY)")
                        db.execSQL("""INSERT INTO work.archived_ids SELECT id FROM prior.records
                            WHERE id NOT IN (SELECT id FROM records) AND seen>=?
                            ORDER BY seen DESC,rowid DESC LIMIT $MAX_ARCHIVED""",arrayOf(now-30L*86_400_000))
                        db.execSQL("""INSERT OR IGNORE INTO records
                            SELECT id,payload,name,number,logo,kind,groups_json,position,0,seen,identity1,identity2,identity3,content
                            FROM prior.records WHERE id IN (SELECT id FROM work.archived_ids)""")
                    }
                    VideoCatalogIndex.recordAdded(db,previous!=null,now)
                    writeMetadata(db, stored)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            } finally { if (previous != null) db.execSQL("DETACH DATABASE prior") }
        }}finally {deleteWork(draft)}
    }

    private fun attachWork(db:SQLiteDatabase,draft:File) {
        db.execSQL("ATTACH DATABASE ? AS work",arrayOf(draft.path+".work"))
        db.execSQL("PRAGMA work.cache_size=-2048")
    }
    private fun deleteWork(draft:File) {val file=File(draft.path+".work");sidecars(file);file.delete()}

    fun commit(draft: File, id: String) {
        val destination = file(id)
        val size = directory.listFiles().orEmpty().filter { it.name.endsWith(".sqlite") && !it.name.startsWith(".") && it != destination }.sumOf(File::length) + draft.length()
        if (draft.length() > MAX_DATABASE || size > MAX_DISK) throw IOException("Le liste personali superano il limite di spazio previsto (1 GiB per lista, 2 GiB complessivi).")
        // Both files are on the app's private filesystem and all connections are closed.
        sidecars(destination)
        if(!draft.renameTo(destination))throw IOException("Non riesco a salvare la lista. La copia precedente è stata conservata.")
        sidecars(draft)
    }

    fun read(id: String,checkActive:()->Unit={},progress:(String,Int,Int)->Unit={_,_,_->}): PlaylistStored? {
        val source = file(id)
        if (!source.exists()) migrate(id)
        if (!source.exists()) return null
        var oldFormat = false
        // Startup must allow SQLite to recover a hot rollback journal left by a killed index build.
        // Ordinary catalog reads stay read-only after this short recovery/metadata check.
        val result = SQLiteDatabase.openDatabase(source.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            val stored = db.rawQuery("SELECT value FROM metadata WHERE key='source'", null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("Archivio della lista incompleto.")
                val root = JSONObject(cursor.getString(0)); oldFormat = root.getInt("version") < 4
                decodeMetadata(root)
            }
            require(stored.source.id == id && stored.source.count in 0..PersonalM3uParser.MAX_ENTRIES)
            val count = db.rawQuery("SELECT COUNT(*) FROM records WHERE active=1", null).use { it.moveToFirst(); it.getInt(0) }
            require(count == stored.source.count)
            stored
        }
        // V2 remains readable if there is temporarily no room for the metadata-only upgrade.
        val compatible=if(oldFormat)result.copy(classificationVersion=0).also {upgradeContent(it)} else result
        ensureCatalogIndex(id)
        if(compatible.classificationVersion>=PlaylistContentClassifier.VERSION)return compatible
        return try {change(compatible,true,checkActive,progress)}
        catch(_:IOException){classificationDeferred(compatible)}
        catch(_:android.database.sqlite.SQLiteException){classificationDeferred(compatible)}
    }

    // An optional local upgrade must never make an otherwise readable saved list disappear.
    private fun classificationDeferred(stored:PlaylistStored)=stored.copy(source=stored.source.copy(error=CLASSIFICATION_DEFERRED))

    fun change(stored: PlaylistStored,reclassify:Boolean=false,checkActive:()->Unit={},progress:(String,Int,Int)->Unit={_,_,_->}):PlaylistStored = writeDb(file(stored.source.id)).use { db ->
        if(reclassify)checkSpace(8L*1024*1024)
        db.beginTransaction()
        try {
            val next=if(reclassify)classify(db,stored,checkActive,progress) else stored
            checkActive();writeMetadata(db,next)
            if(reclassify) {
                db.execSQL("DELETE FROM metadata WHERE key='video-index'")
                val bytes=db.rawQuery("PRAGMA page_count",null).use {it.moveToFirst();it.getLong(0)}*db.pageSize
                val others=directory.listFiles().orEmpty().filter {it.name.endsWith(".sqlite") && !it.name.startsWith(".") && it!=file(stored.source.id)}.sumOf(File::length)
                if(bytes>MAX_DATABASE || bytes+others>MAX_DISK)throw IOException("La classificazione supera il limite di spazio delle liste (1 GiB per lista, 2 GiB complessivi). La copia precedente è stata conservata.")
            }
            db.setTransactionSuccessful();next
        } finally { db.endTransaction() }
    }

    /** Keyset batches bound memory; source settings and both classification representations commit together. */
    private fun classify(db:SQLiteDatabase,stored:PlaylistStored,checkActive:()->Unit,progress:(String,Int,Int)->Unit):PlaylistStored {
        val classifier=PlaylistContentClassifier(stored.settings.reading)
        val total=db.rawQuery("SELECT COUNT(*) FROM records",null).use {it.moveToFirst();it.getInt(0)}
        var last=0L;var processed=0;var legacy=false;var notified=0L;var conflicts=0
        fun notify(force:Boolean=false) {val time=System.nanoTime();if(force || time-notified>200_000_000){notified=time;progress(stored.source.name,processed,total)}}
        notify(true)
        db.compileStatement("UPDATE records SET payload=?,content=? WHERE rowid=?").use {update->
            while(processed<total) {
                checkActive()
                var read=0
                db.rawQuery("SELECT rowid,payload,active FROM records WHERE rowid>? ORDER BY rowid LIMIT 200",arrayOf(last.toString())).use {cursor->
                    while(cursor.moveToNext()) {
                        checkActive();last=cursor.getLong(0);val root=JSONObject(cursor.getString(1));val entry=decodeEntry(root,stored.descriptor)
                        val retained=entry.metadata.legacyVod || entry.metadata.classificationVersion==0 && entry.metadata.contentKind==PlaylistContentKind.VOD
                        val result=classifier.classify(entry.metadata.attributes,entry.metadata.defaults,entry.metadata.duration,
                            runCatching {java.net.URI(entry.url)}.getOrNull(),entry.groups,entry.metadata.vodMarker,retained)
                        val metadata=root.optJSONObject("metadata") ?: JSONObject().also {root.put("metadata",it)}
                        val structural=result.kind==PlaylistContentKind.PLAYLIST
                        val kind=if(structural)entry.metadata.contentKind.takeUnless {it==PlaylistContentKind.PLAYLIST} ?: PlaylistContentKind.LIVE else result.kind
                        metadata.put("content",kind.name).put("vodMarker",entry.metadata.vodMarker.name)
                            .put("classificationReason",result.reason.name).put("classificationVersion",PlaylistContentClassifier.VERSION).put("legacyVod",retained)
                        update.bindString(1,root.toString());update.bindString(2,kind.name);update.bindLong(3,last);update.executeUpdateDelete()
                        if(cursor.getInt(2)==1) {
                            if(result.reason==PlaylistClassificationReason.LEGACY_VOD || structural)legacy=true
                            if(result.conflicting)conflicts++
                        }
                        processed++;read++;notify()
                    }
                }
                if(read==0)throw IOException("Impossibile completare la classificazione. La copia precedente è stata conservata.")
            }
        }
        notify(true)
        return stored.copy(source=if(stored.source.error==CLASSIFICATION_DEFERRED)stored.source.copy(error="") else stored.source,
            classificationVersion=PlaylistContentClassifier.VERSION,classificationNeedsRefresh=legacy,classificationConflicts=conflicts)
    }

    fun channels(id: String,override:PlaylistContentKind?=null): List<Channel> = readDb(file(id)).use { db ->
        db.rawQuery("SELECT id,name,number,logo,kind,groups_json,position,content FROM records WHERE active=1 ORDER BY position", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val groups = groups(JSONArray(cursor.getString(5)))
                    val key = cursor.getString(0)
                    val content=override ?: PlaylistContentKind.valueOf(cursor.getString(7))
                    add(Channel(key, cursor.getString(1), if (cursor.isNull(2)) 9999 else cursor.getInt(2), if(content==PlaylistContentKind.LIVE)PersonalGuideKeys.channel(id,key) else "", groups.joinToString(" · "),
                        "", "", "personal", key, kind = MediaKind.valueOf(cursor.getString(4)), sourceId = id,
                        position = cursor.getInt(6), groups = groups, numberKnown = !cursor.isNull(2),contentKind=content))
                }
            }
        }
    }
    fun knownIds(id: String): Set<String> = readDb(file(id)).use { db ->
        db.rawQuery("SELECT id FROM records", null).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }
    fun entry(id: String, channelId: String): PlaylistEntry? = readDb(file(id)).use { db ->
        val descriptor = db.rawQuery("SELECT value FROM metadata WHERE key='source'", null).use { c ->
            if (c.moveToFirst()) JSONObject(c.getString(0)).let {readDescriptor(it.optJSONObject("documents"),it.optJSONObject("bases"))} else PlaylistDescriptor()
        }
        db.rawQuery("SELECT payload FROM records WHERE id=? AND active=1", arrayOf(channelId)).use { cursor ->
            if (cursor.moveToFirst()) decodeEntry(JSONObject(cursor.getString(0)), descriptor) else null
        }
    }
    fun delete(id: String) {
        file(id).delete(); sidecars(file(id))
        AtomicFile(File(directory, "$id.json")).delete()
    }
    private fun sidecars(file:File) {listOf("-journal","-wal","-shm").forEach {File(file.path+it).delete()}}

    private fun migrate(id: String) {
        val legacy = AtomicFile(File(directory, "$id.json"))
        if (!legacy.baseFile.exists() && !File(legacy.baseFile.path + ".bak").exists()) return
        val target = File(directory, ".draft-migration-${UUID.randomUUID()}.sqlite")
        try {
            create(target).use { db ->
                db.beginTransaction()
                try {
                    var count = 0
                    var insecure = false
                    val root = JSONObject()
                    var recordsRead = false
                    db.compileStatement("INSERT INTO records(id,payload,name,number,logo,kind,groups_json,position,active,seen,identity1,identity2,identity3,content) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { insert ->
                        legacy.openRead().use { input ->
                            if (legacy.baseFile.length() > 32L * 1024 * 1024) throw IOException("Archivio precedente troppo grande.")
                            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val name = reader.nextName()
                                    if (name != "records") {
                                        require(root.length() < 32 && !root.has(name))
                                        root.put(name, readLegacyValue(reader))
                                    } else {
                                        require(!recordsRead); recordsRead = true
                                        var rows = 0
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            require(++rows <= 10_000)
                                            val record = readLegacyValue(reader) as? JSONObject ?: throw IOException("Archivio precedente non valido.")
                                            val entry = decodeEntry(record)
                                            require(PersonalPlaylistNetwork.allowed(java.net.URI(entry.url), true))
                                            val key = record.getString("id"); require(Regex("[a-f0-9]{24}").matches(key))
                                            val active = record.optBoolean("active")
                                            bindEntry(insert, entry, key, record.getLong("seen"), active)
                                            insert.executeInsert()
                                            if (active) { count++; if (java.net.URI(entry.url).scheme.equals("http", true)) insecure = true }
                                        }
                                        reader.endArray()
                                    }
                                }
                                reader.endObject(); require(reader.peek() == JsonToken.END_DOCUMENT)
                            }
                        }
                    }
                    require(recordsRead && root.getInt("version") == 1 && root.getString("id") == id && count <= 5_000)
                    PlaylistCatalogIndex.populate(db)
                    val source = PersonalPlaylistSource(id, root.getString("name").take(100), PlaylistLocation.valueOf(root.getString("location")),
                        root.getBoolean("enabled") && !insecure, count, root.getLong("updated"), root.getLong("checked"), root.getLong("revision"),
                        MediaKind.valueOf(root.getString("kind")), root.optString("error"), metadataComplete = false)
                    writeMetadata(db, PlaylistStored(source, root.optString("address"), optional(root,"etag"), optional(root,"modified"),
                        root.optLong("attempted"), root.optLong("retryAt"), root.optInt("failures").coerceIn(0, 10),classificationVersion=0))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            commit(target, id)
            legacy.delete()
        } finally { target.delete(); File(target.path + "-journal").delete() }
    }

    private fun readLegacyValue(reader: JsonReader, depth: Int = 0): Any {
        require(depth <= 4)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName(); require(length() < 32 && !has(key))
                    put(key, readLegacyValue(reader, depth + 1))
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray()
                while (reader.hasNext()) { require(length() < 32); put(readLegacyValue(reader, depth + 1)) }
                reader.endArray()
            }
            JsonToken.STRING -> reader.nextString().also { require(it.length <= 64 * 1024) }
            JsonToken.NUMBER -> reader.nextLong()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> throw IOException("Archivio precedente non valido.")
        }
    }

    private fun create(file: File): SQLiteDatabase {
        val db = writeDb(file)
        try {
            db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,payload TEXT NOT NULL,name TEXT NOT NULL,number INTEGER,logo TEXT NOT NULL,kind TEXT NOT NULL,groups_json TEXT NOT NULL,position INTEGER NOT NULL,active INTEGER NOT NULL,seen INTEGER NOT NULL,identity1 TEXT,identity2 TEXT NOT NULL,identity3 TEXT NOT NULL,content TEXT NOT NULL DEFAULT 'LIVE')")
            db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE INDEX active_position ON records(active,position)")
            db.execSQL("CREATE INDEX content_position ON records(active,content,position)")
            PlaylistCatalogIndex.create(db)
            for (i in 1..3) db.execSQL("CREATE INDEX match$i ON records(identity$i)")
            return db
        } catch (error: Exception) { db.close(); throw error }
    }
    private fun writeDb(file: File): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        disableWriteAheadLogging(); setMaximumSize(MAX_DATABASE)
        execSQL("PRAGMA temp_store=FILE")
    }
    private fun readDb(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private fun bindEntry(statement: android.database.sqlite.SQLiteStatement, entry: PlaylistEntry, id: String, seen: Long, active: Boolean? = null) {
        statement.clearBindings()
        statement.bindString(1,id); statement.bindString(2,encodeEntry(entry).toString()); statement.bindString(3,entry.name)
        val number=entry.number
        if (number == null) statement.bindNull(4) else statement.bindLong(4,number.toLong())
        statement.bindString(5,entry.logo); statement.bindString(6,entry.kind.name); statement.bindString(7,JSONArray(entry.groups).toString())
        statement.bindLong(8,entry.position.toLong())
        val offset = if (active == null) 9 else 11
        if (active != null) { statement.bindLong(9,if (active) 1 else 0); statement.bindLong(10,seen) }
        PlaylistIdentity.keys(entry).forEachIndexed { i,value ->
            if (value == null) statement.bindNull(offset+i) else statement.bindString(offset+i,hash(value))
        }
        statement.bindString(offset+3,entry.metadata.contentKind.name)
    }
    private fun hash(value: String): String = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()), android.util.Base64.NO_WRAP)
    private fun encodeEntry(e: PlaylistEntry) = JSONObject().put("name",e.name).put("url",e.url).put("tvgId",e.tvgId).put("logo",e.logo)
        .put("number",e.number).put("groups",JSONArray(e.groups)).put("kind",e.kind.name).put("headers",JSONObject(e.headers))
        .put("mime",e.mimeType).put("position",e.position).put("properties",JSONObject(e.properties))
        .put("metadata",JSONObject().put("attributes",JSONObject(e.metadata.attributes)).put("duration",e.metadata.duration)
            .put("document",e.metadata.document).put("seekable",e.metadata.seekable).put("content",e.metadata.contentKind.name)
            .put("vodMarker",e.metadata.vodMarker.name).put("classificationReason",e.metadata.classificationReason?.name)
            .put("classificationVersion",e.metadata.classificationVersion).put("legacyVod",e.metadata.legacyVod))
    internal fun decodeEntry(root: JSONObject, descriptor: PlaylistDescriptor = PlaylistDescriptor()): PlaylistEntry {
        fun map(name: String): Map<String,String> = root.optJSONObject(name)?.let { obj ->
            require(obj.length() <= 32)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        }.orEmpty()
        val m = root.optJSONObject("metadata") ?: JSONObject()
        val document = m.optString("document", "root")
        return PlaylistEntry(root.getString("name").take(300),root.getString("url"),root.optString("tvgId").take(300),root.optString("logo").take(8192),
            root.optInt("number").takeIf { it in 1..999_999 },groups(root.getJSONArray("groups")),MediaKind.valueOf(root.getString("kind")),
            map("headers"),optional(root,"mime"),root.getInt("position"),map("properties"),
            PlaylistMetadata(readAttributes(m.optJSONObject("attributes")),descriptor.documents[document].orEmpty(),
                if (m.isNull("duration")) null else m.getDouble("duration"),document,
                if (m.isNull("seekable")) null else m.getBoolean("seekable"), PlaylistContentKind.valueOf(m.optString("content", "LIVE")),descriptor.bases[document],
                PlaylistVodMarker.valueOf(m.optString("vodMarker","NONE")),optional(m,"classificationReason")?.let(PlaylistClassificationReason::valueOf),
                m.optInt("classificationVersion"),m.optBoolean("legacyVod")))
    }
    private fun groups(array: JSONArray): List<String> = (0 until minOf(array.length(),20)).map { array.getString(it).take(100) }
    private fun optional(obj: JSONObject, key: String) = obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun writeMetadata(db: SQLiteDatabase, stored: PlaylistStored) {
        val s=stored.source
        val value=JSONObject().put("version",4).put("id",s.id).put("name",s.name).put("location",s.location.name).put("enabled",s.enabled)
            .put("count",s.count).put("updated",s.updated).put("checked",s.checked).put("revision",s.revision).put("kind",s.defaultKind.name)
            .put("allowHttp",s.allowHttp).put("error",s.error).put("address",stored.address).put("etag",stored.etag).put("modified",stored.modified)
            .put("attempted",stored.attempted).put("retryAt",stored.retryAt).put("failures",stored.failures).put("serverRetryAt",stored.serverRetryAt)
            .put("complete",s.metadataComplete).put("settings",stored.settings.json())
            .put("classificationVersion",stored.classificationVersion).put("classificationNeedsRefresh",stored.classificationNeedsRefresh).put("classificationConflicts",stored.classificationConflicts)
            .put("documents",JSONObject().apply { stored.descriptor.documents.forEach { (id, attrs) -> put(id,JSONObject(attrs)) } })
            .put("bases",JSONObject(stored.descriptor.bases))
            .put("report",stored.report?.let(::encodeReport))
        db.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES('source',?)",arrayOf(value.toString()))
    }
    private fun decodeMetadata(root: JSONObject): PlaylistStored {
        require(root.getInt("version") in 2..4)
        val id=root.getString("id");require(validId(id))
        val source=PersonalPlaylistSource(id,root.getString("name"),PlaylistLocation.valueOf(root.getString("location")),root.getBoolean("enabled"),
            root.getInt("count"),root.getLong("updated"),root.getLong("checked"),root.getLong("revision"),MediaKind.valueOf(root.getString("kind")),
            root.optString("error"),allowHttp=root.optBoolean("allowHttp"),metadataComplete=root.optBoolean("complete",false))
        return PlaylistStored(source,root.optString("address"),optional(root,"etag"),optional(root,"modified"),root.optLong("attempted"),root.optLong("retryAt"),root.optInt("failures"),root.optLong("serverRetryAt"),
            PlaylistSettings.read(root.optJSONObject("settings")),readDescriptor(root.optJSONObject("documents"),root.optJSONObject("bases")),root.optJSONObject("report")?.let(::decodeReport),
            root.optInt("classificationVersion"),root.optBoolean("classificationNeedsRefresh"),root.optInt("classificationConflicts"))
    }

    private fun readAttributes(value: JSONObject?): Map<String,String> {
        if (value == null) return emptyMap()
        require(value.length() <= 64)
        return value.keys().asSequence().associateWith { value.getString(it).also { text -> require(text.length <= PersonalM3uParser.MAX_LINE) } }
    }
    private fun readDescriptor(value: JSONObject?,bases:JSONObject?=null): PlaylistDescriptor {
        if (value == null) return PlaylistDescriptor()
        require(value.length() <= 16)
        require((bases?.length() ?: 0)<=16)
        return PlaylistDescriptor(value.keys().asSequence().associateWith { readAttributes(value.getJSONObject(it)) },
            bases?.keys()?.asSequence()?.associateWith {bases.getString(it)}.orEmpty())
    }
    private fun encodeReport(r: PlaylistPreview) = JSONObject().put("count",r.count).put("sample",JSONArray(r.sample)).put("excluded",r.excluded)
        .put("duplicates",r.duplicates).put("warnings",JSONArray(r.warnings)).put("http",r.httpEntries).put("drm",r.drmEntries)
        .put("diagnostics",JSONArray().apply { r.diagnostics.forEach { put(JSONObject().put("code",it.code.name).put("line",it.line).put("entry",it.entry).put("excluded",it.excluded)) } })
        .put("counts",JSONObject().apply { r.diagnosticCounts.forEach { (code,n) -> put(code.name,n) } })
    private fun decodeReport(r: JSONObject): PlaylistPreview {
        fun strings(name:String,max:Int) = r.optJSONArray(name)?.let { a -> require(a.length()<=max); (0 until a.length()).map { a.getString(it).take(500) } }.orEmpty()
        val d = r.optJSONArray("diagnostics") ?: JSONArray(); require(d.length()<=20)
        val counts=r.optJSONObject("counts") ?: JSONObject();require(counts.length()<=PlaylistDiagnosticCode.entries.size)
        return PlaylistPreview(r.getInt("count"),strings("sample",5),r.getInt("excluded"),r.getInt("duplicates"),strings("warnings",20),r.getInt("http"),r.getInt("drm"),
            (0 until d.length()).map { i -> d.getJSONObject(i).let { PlaylistDiagnostic(PlaylistDiagnosticCode.valueOf(it.getString("code")),it.getInt("line"),it.getInt("entry"),it.getBoolean("excluded")) } },
            counts.keys().asSequence().associate { PlaylistDiagnosticCode.valueOf(it) to counts.getInt(it) })
    }
    private fun checkSpace(reserve:Long) {
        val free=directory.usableSpace
        if(free>0 && free<reserve)throw IOException("Spazio insufficiente per verificare la lista. Libera spazio e riprova; la copia salvata resta disponibile.")
    }

    private fun upgradeContent(stored:PlaylistStored) = writeDb(file(stored.source.id)).use {db->
        val exists=db.rawQuery("PRAGMA table_info(records)",null).use {c->var found=false;while(c.moveToNext())if(c.getString(1)=="content")found=true;found}
        db.beginTransaction()
        try {
            if(!exists) {
                db.execSQL("ALTER TABLE records ADD COLUMN content TEXT NOT NULL DEFAULT 'LIVE'")
                db.compileStatement("UPDATE records SET content=? WHERE rowid=?").use {update->
                    db.rawQuery("SELECT rowid,payload FROM records",null).use {c->while(c.moveToNext()) {
                        val kind=JSONObject(c.getString(1)).optJSONObject("metadata")?.optString("content","LIVE") ?: "LIVE"
                        update.bindString(1,PlaylistContentKind.valueOf(kind).name);update.bindLong(2,c.getLong(0));update.executeUpdateDelete()
                    }}
                }
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS content_position ON records(active,content,position)")
            writeMetadata(db,stored);db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    fun guideCandidates(stored:PlaylistStored,maxCandidates:Int=100_000):List<GuideCandidate> = readDb(file(stored.source.id)).use {db->
        if(stored.settings.contentOverride==PlaylistContentKind.VOD)return@use emptyList()
        val defaults=stored.descriptor
        val feedDefaults=defaults.documents.mapValues {(key,attrs)->guideUrls(attrs,defaults.bases[key])}
        val entryFeedsOnly=stored.settings.guide.urls.isEmpty() && feedDefaults.values.all {it.isEmpty()}
        val feedFilter=if(entryFeedsOnly)" AND (payload LIKE '%\"url-tvg\"%' OR payload LIKE '%\"x-tvg-url\"%')" else ""
        val filter="active=1"+(if(stored.settings.contentOverride==PlaylistContentKind.LIVE)"" else " AND content='LIVE'")+feedFilter
        val count=db.rawQuery("SELECT COUNT(*) FROM records WHERE $filter",null).use {it.moveToFirst();it.getInt(0)}
        if(count>maxCandidates)throw SourceException("La guida personale supporta fino a 100.000 dirette per lista. Il catalogo e la riproduzione restano disponibili; la guida precedente è stata conservata.")
        db.rawQuery("SELECT id,payload FROM records WHERE $filter",null).use {c->
            buildList {while(c.moveToNext()) {
                val e=decodeEntry(JSONObject(c.getString(1)),defaults);val key=c.getString(0)
                val own=if(e.metadata.attributes.keys.any {it in setOf("url-tvg","x-tvg-url")})guideUrls(e.metadata.attributes,e.metadata.baseUrl) else feedDefaults[e.metadata.document].orEmpty()
                add(GuideCandidate(key,PersonalGuideKeys.channel(stored.source.id,key),e.tvgId,e.metadata.tvgName,e.name,e.metadata.document,
                    ((stored.settings.guide.shiftHours ?: e.metadata.epgShiftHours ?: 0.0)*3_600_000).toLong(),stored.settings.guide.urls.takeIf {it.isNotEmpty()} ?: own))
            }}
        }
    }
    fun library(id:String,override:PlaylistContentKind?,query:String,group:String?,offset:Int,limit:Int):Pair<List<String>,Int> = readDb(file(id)).use {db->
        if(override==PlaylistContentKind.LIVE)return@use emptyList<String>() to 0
        val where=StringBuilder("active=1"+if(override==PlaylistContentKind.VOD)"" else " AND content='VOD'")
        val args=mutableListOf<String>()
        fun pattern(value:String)="%"+value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
        if(query.isNotBlank()){where.append(" AND name LIKE ? ESCAPE '\\'");args+=pattern(query.take(300))}
        if(!group.isNullOrBlank()){where.append(" AND groups_json LIKE ? ESCAPE '\\'");args+=pattern(org.json.JSONArray(listOf(group)).toString().removePrefix("[").removeSuffix("]"))}
        val count=db.rawQuery("SELECT COUNT(*) FROM records WHERE $where",args.toTypedArray()).use {it.moveToFirst();it.getInt(0)}
        val ids=db.rawQuery("SELECT id FROM records WHERE $where ORDER BY position,id LIMIT ? OFFSET ?",(args+listOf(limit.coerceIn(1,50).toString(),offset.coerceAtLeast(0).toString())).toTypedArray()).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        ids to count
    }
}
