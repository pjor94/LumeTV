package it.lumetv.app.data

import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import it.lumetv.core.PlaylistEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParser

internal interface CatalogGuideSink {
    fun channel(channel:XmlChannel)
    fun programme(programme:XmlProgramme)
}

internal class CatalogGuideImport(val file:File,val programmes:Map<String,List<Programme>>) : AutoCloseable {
    override fun close(){file.delete();File(file.path+"-journal").delete()}
}

/** One public index for the entire Lume feed. Personal identifiers and credentials never enter it. */
internal class CatalogGuideStore(private val directory:File,private val feed:PublicGuideFeed=PublicGuideFeed.LUME) {
    companion object {
        const val FUTURE=48*3_600_000L
        private const val MAX_BYTES=128L*1024*1024
        private val locks=ConcurrentHashMap<String,Any>()
        private val versions=ConcurrentHashMap<String,MutableStateFlow<Long>>()
    }
    private val cache=AtomicFile(File(directory,feed.filename))
    private val lock=locks.getOrPut(cache.baseFile.path){Any()}
    private val version=versions.getOrPut(cache.baseFile.path){MutableStateFlow(0)}
    val revisions=version.asStateFlow()

    fun stage(input:InputStream,wanted:Set<String>,now:Long,parser:XmlPullParser=android.util.Xml.newPullParser(),
              active:()->Unit={},onLogos:(Map<String,String>)->Unit={}):CatalogGuideImport {
        directory.mkdirs()
        val target=File(directory,".catalog-guide-${UUID.randomUUID()}.sqlite")
        try {
            val programmes=SQLiteDatabase.openOrCreateDatabase(target,null).use {db->
                db.disableWriteAheadLogging();db.setMaximumSize(MAX_BYTES)
                db.execSQL("CREATE TABLE channels(id TEXT PRIMARY KEY,icon TEXT NOT NULL)")
                db.execSQL("CREATE TABLE lookup(kind INTEGER NOT NULL,key TEXT NOT NULL,channel TEXT NOT NULL,PRIMARY KEY(kind,key,channel))")
                db.execSQL("CREATE INDEX lookup_channel ON lookup(channel,kind)")
                db.execSQL("CREATE TABLE programmes(channel TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL,PRIMARY KEY(channel,start,end,title))")
                db.execSQL("CREATE INDEX programme_window ON programmes(channel,start,end)")
                var channels=0;var events=0
                db.beginTransaction()
                try {
                    db.compileStatement("INSERT OR REPLACE INTO channels VALUES(?,?)").use {channelInsert->
                        db.compileStatement("INSERT OR IGNORE INTO lookup VALUES(?,?,?)").use {keyInsert->
                            db.compileStatement("INSERT OR REPLACE INTO programmes VALUES(?,?,?,?,?)").use {eventInsert->
                                fun key(kind:Int,key:String,id:String) {
                                    if(key.isEmpty())return
                                    keyInsert.bindLong(1,kind.toLong());keyInsert.bindString(2,key);keyInsert.bindString(3,id);keyInsert.executeInsert()
                                }
                                val sink=object:CatalogGuideSink {
                                    override fun channel(channel:XmlChannel) {
                                        active();if(++channels>100_000)throw SourceException("La guida supera il limite di canali previsto.")
                                        val logo=LogoPolicy.url(channel.icon)?.toString().orEmpty()
                                        channelInsert.bindString(1,channel.id);channelInsert.bindString(2,logo);channelInsert.executeInsert()
                                        key(0,CatalogGuideMatch.id(channel.id),channel.id)
                                        key(2,channel.id.lowercase(java.util.Locale.ROOT),channel.id)
                                        key(1,CatalogGuideMatch.name(channel.id.replace(Regex("(?i)\\.it$"),"")),channel.id)
                                        channel.names.forEach {key(1,CatalogGuideMatch.name(it),channel.id)}
                                        when(channel.id) {
                                            "rairadio2visualradio" -> key(0,"rairadio2",channel.id)
                                            "Rai3TGR.it" -> key(0,"rai3tgrveneto",channel.id)
                                        }
                                    }
                                    override fun programme(programme:XmlProgramme) {
                                        if(++events%256==0)active()
                                        if(events>500_000)throw SourceException("La guida supera il limite di programmi previsto.")
                                        eventInsert.bindString(1,programme.channel);eventInsert.bindLong(2,programme.start);eventInsert.bindLong(3,programme.end)
                                        eventInsert.bindString(4,programme.title);eventInsert.bindString(5,programme.description);eventInsert.executeInsert()
                                    }
                                }
                                val result=XmlTvGuideParser.parse(input,wanted,now,parser,records=sink,onLogos=onLogos)
                                active()
                                if(events==0)throw SourceException("La guida ricevuta non contiene programmi aggiornati.")
                                db.setTransactionSuccessful()
                                result
                            }
                        }
                    }
                }finally {db.endTransaction()}
            }
            return CatalogGuideImport(target,programmes)
        }catch(error:Exception){target.delete();File(target.path+"-journal").delete();throw error}
    }

    fun commit(draft:CatalogGuideImport) {
        synchronized(lock) {
            val output=cache.startWrite()
            try {draft.file.inputStream().use {it.copyTo(output)};cache.finishWrite(output)}
            catch(error:Exception){cache.failWrite(output);throw error}
        }
        version.update {it+1}
    }

    private fun <T> read(empty:T,block:(SQLiteDatabase)->T):T = synchronized(lock) {
        if(!cache.baseFile.exists() && !File(cache.baseFile.path+".bak").exists())return@synchronized empty
        runCatching {
            cache.openRead().close()
            SQLiteDatabase.openDatabase(cache.baseFile.path,null,SQLiteDatabase.OPEN_READONLY).use(block)
        }.getOrDefault(empty)
    }
    private fun resolve(db:SQLiteDatabase,entry:PlaylistEntry):CatalogGuideProbe {
        val names=listOf(entry.metadata.tvgName,entry.name).filter(String::isNotBlank).distinct()
        val named=names.flatMap {matches(db,1,CatalogGuideMatch.name(it))}.toSet()
        fun accepted(id:String):CatalogGuideProbe {
            val targetNames=displayNames(db,id)
            return if((named.isEmpty() || id in named) && CatalogGuideMatch.compatible(entry,id,targetNames))CatalogGuideProbe(id)
                else CatalogGuideProbe(conflict=true)
        }
        if(entry.tvgId.isNotBlank()) {
            val exact=matches(db,2,entry.tvgId.trim().lowercase(java.util.Locale.ROOT))
            if(exact.isNotEmpty())return exact.singleOrNull()?.let(::accepted) ?: CatalogGuideProbe(conflict=true)
            db.rawQuery("SELECT id FROM channels WHERE id=?",arrayOf(entry.tvgId)).use {if(it.moveToFirst())return accepted(it.getString(0))}
            val ids=matches(db,0,CatalogGuideMatch.id(entry.tvgId))
            if(ids.isNotEmpty())return ids.singleOrNull()?.let(::accepted) ?: CatalogGuideProbe(conflict=true)
        }
        if(named.isNotEmpty())return named.singleOrNull()?.let(::accepted) ?: CatalogGuideProbe(conflict=true)
        if(feed==PublicGuideFeed.EPGSHARE) {
            val alias=epgShareAliases.firstOrNull {it.playlistId.equals(entry.tvgId.trim(),true)}
            if(alias!=null && names.isNotEmpty() && names.all {CatalogGuideMatch.name(it)==CatalogGuideMatch.name(alias.playlistName)}) {
                val ids=matches(db,2,alias.feedId)
                val id=ids.singleOrNull() ?: return CatalogGuideProbe(conflict=ids.isNotEmpty())
                return if(displayNames(db,id).any {it==CatalogGuideMatch.name(alias.feedName)} && CatalogGuideMatch.compatible(entry,id,displayNames(db,id)))
                    CatalogGuideProbe(id) else CatalogGuideProbe(conflict=true)
            }
        }
        return CatalogGuideProbe()
    }
    private fun displayNames(db:SQLiteDatabase,id:String):List<String> = db.rawQuery("SELECT key FROM lookup WHERE channel=? AND kind=1",arrayOf(id)).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
    private fun matches(db:SQLiteDatabase,kind:Int,key:String):List<String> =
        db.rawQuery("SELECT channel FROM lookup WHERE kind=? AND key=? LIMIT 3",arrayOf(kind.toString(),key)).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}

    fun ready(now:Long):Boolean=read(false){db->db.rawQuery("SELECT 1 FROM programmes WHERE end>? LIMIT 1",arrayOf(now.toString())).use {it.moveToFirst()}}
    private fun hasProgrammes(db: SQLiteDatabase, channel: String, now: Long): Boolean =
        db.rawQuery("SELECT 1 FROM programmes WHERE channel=? AND end>? AND start<? LIMIT 1",
            arrayOf(channel, now.toString(), (now + FUTURE).toString())).use { it.moveToFirst() }

    fun probe(entry:PlaylistEntry,now:Long):CatalogGuideProbe=read(CatalogGuideProbe()){db->
        val resolution=resolve(db,entry);val id=resolution.channel ?: return@read resolution
        val icon=db.rawQuery("SELECT icon FROM channels WHERE id=?",arrayOf(id)).use {if(it.moveToFirst())LogoPolicy.url(it.getString(0))?.toString() else null}
        resolution.copy(hasProgrammes=hasProgrammes(db,id,now),logo=icon)
    }

    /** A present key keeps this feed's precedence even during a programme gap or an ambiguous match. */
    fun onAir(entries: Map<String, PlaylistEntry>, now: Long): Map<String, Programme?> {
        if (entries.isEmpty()) return emptyMap()
        return read(emptyMap()) { db ->
            buildMap {
                for ((id, entry) in entries) {
                    val match = resolve(db, entry)
                    if (match.conflict) {
                        put(id, null)
                        continue
                    }
                    val channel = match.channel ?: continue
                    val programme = db.rawQuery("""
                        SELECT title, start, end FROM programmes WHERE channel=? AND start<=? AND end>?
                        AND start>0 AND end>start AND trim(title)<>'' ORDER BY start DESC, rowid LIMIT 1
                    """.trimIndent(), arrayOf(channel, now.toString(), now.toString())).use { cursor ->
                        if (cursor.moveToFirst()) Programme(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)) else null
                    }
                    if (programme != null || hasProgrammes(db, channel, now)) put(id, programme)
                }
            }
        }
    }

    fun window(entry:PlaylistEntry,from:Long,until:Long):List<Programme> = read(emptyList()) {db->
        val id=resolve(db,entry).channel ?: return@read emptyList()
        db.rawQuery("SELECT title,start,end,description FROM programmes WHERE channel=? AND end>? AND start<? ORDER BY start LIMIT 96",
            arrayOf(id,from.toString(),until.toString())).use {c->buildList {while(c.moveToNext())add(Programme(c.getString(0),c.getLong(1),c.getLong(2),c.getString(3)))}}
    }
    fun search(entry:PlaylistEntry,query:String,from:Long,until:Long,offset:Int):Pair<List<Programme>,Int> = read(emptyList<Programme>() to 0) {db->
        val id=resolve(db,entry).channel ?: return@read emptyList<Programme>() to 0
        val pattern="%"+query.take(300).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
        val clause="FROM programmes WHERE channel=? AND end>? AND start<? AND (title LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\')"
        val args=arrayOf(id,from.toString(),until.toString(),pattern,pattern)
        val count=db.rawQuery("SELECT COUNT(*) $clause",args).use {it.moveToFirst();it.getInt(0)}
        val shows=db.rawQuery("SELECT title,start,end,description $clause ORDER BY start LIMIT 50 OFFSET ?",args+offset.coerceAtLeast(0).toString()).use {c->
            buildList {while(c.moveToNext())add(Programme(c.getString(0),c.getLong(1),c.getLong(2),c.getString(3)))}
        }
        shows to count
    }
    fun logo(entry:PlaylistEntry):String? = read(null as String?) {db->
        val id=resolve(db,entry).channel ?: return@read null
        db.rawQuery("SELECT icon FROM channels WHERE id=?",arrayOf(id)).use {if(it.moveToFirst())it.getString(0).takeIf(String::isNotBlank) else null}
    }
}
