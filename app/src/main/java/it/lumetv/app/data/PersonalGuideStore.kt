package it.lumetv.app.data

import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.ArchiveEvent
import it.lumetv.core.CatchupUrls
import org.json.JSONArray
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

internal data class GuideFeedState(val id:Int,val signature:String="",val updated:Long=0,val attempted:Long=0,val retry:Long=0,
    val etag:String?=null,val modified:String?=null,val error:String="",val rows:Int=0,val serverRetry:Long=0,val urlHash:String="")
internal class PrivateGuideEvent(val programme:Programme,val catchupId:String,val rawStart:Long,val rawEnd:Long) {
    override fun toString()="PrivateGuideEvent()"
}
data class GuideChannelOption(val id:String,val name:String) {override fun toString()=name}
internal data class GuideMapping(val feed:Int,val xmlId:String)
internal class GuideImport(val file:File,val rows:Int,val matches:Int) : AutoCloseable {
    override fun close(){file.delete();File(file.path+"-journal").delete()}
}

/** XMLTV data stays in a separate private partition. A failed feed never replaces its last valid data. */
internal class PersonalGuideStore(private val directory:File) {
    companion object {
        const val MAX_SOURCE_BYTES=128L*1024*1024
        const val MAX_TOTAL_BYTES=256L*1024*1024
        const val MAX_PROGRAMMES=1_000_000
        private fun valid(id:String)=PersonalPlaylistStore.validId(id)
        fun normalized(value:String)=value.trim().lowercase(Locale.ROOT).replace('_',' ').replace(Regex("\\s+")," ")
    }
    fun file(source:String):File {require(valid(source));return File(directory,"$source.sqlite")}
    private fun open(source:String):SQLiteDatabase {
        directory.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(file(source),null).apply {
            disableWriteAheadLogging();setMaximumSize(MAX_SOURCE_BYTES)
            execSQL("CREATE TABLE IF NOT EXISTS feeds(id INTEGER PRIMARY KEY,signature TEXT NOT NULL,updated INTEGER NOT NULL,attempted INTEGER NOT NULL,retry INTEGER NOT NULL,etag TEXT,modified TEXT,error TEXT NOT NULL,rows INTEGER NOT NULL,server_retry INTEGER NOT NULL DEFAULT 0,url_hash TEXT NOT NULL DEFAULT '')")
            if(version<2) {
                val columns=rawQuery("PRAGMA table_info(feeds)",null).use {c->buildSet {while(c.moveToNext())add(c.getString(1))}}
                if("server_retry" !in columns)execSQL("ALTER TABLE feeds ADD COLUMN server_retry INTEGER NOT NULL DEFAULT 0")
                if("url_hash" !in columns)execSQL("ALTER TABLE feeds ADD COLUMN url_hash TEXT NOT NULL DEFAULT ''")
                version=2
            }
            execSQL("CREATE TABLE IF NOT EXISTS channels(feed INTEGER NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,icon TEXT NOT NULL,PRIMARY KEY(feed,id))")
            execSQL("CREATE TABLE IF NOT EXISTS programmes(feed INTEGER NOT NULL,xml TEXT NOT NULL,event TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL,catchup TEXT NOT NULL,PRIMARY KEY(feed,event))")
            execSQL("CREATE INDEX IF NOT EXISTS programme_window ON programmes(feed,xml,start,end)")
            execSQL("CREATE TABLE IF NOT EXISTS bindings(channel TEXT NOT NULL,feed INTEGER NOT NULL,xml TEXT NOT NULL,shift INTEGER NOT NULL,PRIMARY KEY(channel,feed))")
            execSQL("CREATE INDEX IF NOT EXISTS binding_xml ON bindings(feed,xml)")
            execSQL("CREATE TABLE IF NOT EXISTS manual(channel TEXT PRIMARY KEY,feed INTEGER NOT NULL,xml TEXT NOT NULL)")
        }
    }
    fun feeds(source:String):List<GuideFeedState> = open(source).use {db->db.rawQuery("SELECT id,signature,updated,attempted,retry,etag,modified,error,rows,server_retry,url_hash FROM feeds ORDER BY id",null).use {c->
        buildList {while(c.moveToNext())add(GuideFeedState(c.getInt(0),c.getString(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getString(5),c.getString(6),c.getString(7),c.getInt(8),c.getLong(9),c.getString(10)))}
    }}
    fun state(source:String,state:GuideFeedState)=open(source).use {db->putState(db,state)}
    private fun putState(db:SQLiteDatabase,s:GuideFeedState) {db.execSQL("INSERT OR REPLACE INTO feeds VALUES(?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(s.id,s.signature,s.updated,s.attempted,s.retry,s.etag,s.modified,s.error,s.rows,s.serverRetry,s.urlHash))}
    fun mappings(source:String):Map<String,GuideMapping> = open(source).use {db->db.rawQuery("SELECT channel,feed,xml FROM manual",null).use {c->buildMap {while(c.moveToNext())put(c.getString(0),GuideMapping(c.getInt(1),c.getString(2)))}}}
    fun mapping(source:String,channel:String,feed:Int?,xml:String="")=open(source).use {db->
        db.beginTransaction();try {
            db.execSQL("DELETE FROM manual WHERE channel=?",arrayOf<Any?>(channel));db.execSQL("DELETE FROM bindings WHERE channel=?",arrayOf<Any?>(channel))
            if(feed!=null)db.execSQL("INSERT INTO manual VALUES(?,?,?)",arrayOf<Any?>(channel,feed,xml))
            db.execSQL("UPDATE feeds SET signature='',attempted=0")
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
    fun clear(source:String)=open(source).use {db->
        db.beginTransaction();try {
            for(table in listOf("programmes","channels","bindings","feeds"))db.execSQL("DELETE FROM $table")
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        db.execSQL("VACUUM")
    }
    fun choices(source:String,feed:Int,query:String,offset:Int,limit:Int=30):List<GuideChannelOption> = open(source).use {db->
        db.rawQuery("SELECT id,name FROM channels WHERE feed=? ORDER BY name,id",arrayOf(feed.toString())).use {c->
            buildList {var skipped=0;while(c.moveToNext() && size<limit.coerceIn(1,100)) {
                if(query.isNotBlank() && !c.getString(1).contains(query,true) && !c.getString(0).contains(query,true))continue
                if(skipped++<offset.coerceAtLeast(0))continue
                add(GuideChannelOption(c.getString(0),c.getString(1)))
            }}
        }
    }

    fun stage(input:InputStream,source:PersonalGuideInput,feed:Int,url:String,eligible:List<GuideCandidate>,now:Long,active:()->Unit,resetMappings:Boolean=false):GuideImport {
        directory.mkdirs();if(directory.usableSpace in 1 until 16L*1024*1024)throw SourceException("Spazio insufficiente per aggiornare la guida.")
        val target=File(directory,".guide-${UUID.randomUUID()}.sqlite")
        val eventScope=source.source.id+"|"+hash(url)+"|"
        val manual=mappings(source.source.id).filterValues {!resetMappings || it.feed!=feed}
        val wantedNames=eligible.flatMap {listOf(it.tvgName,it.name)}.filter(String::isNotBlank).map(::normalized).toHashSet()
        val exact=eligible.map {it.tvgId}.filter(String::isNotBlank).toHashSet()
        val known=hashSetOf<String>();val unique=hashMapOf<String,String?>();val usedXml=hashSetOf<String>()
        var alternatives:Map<String,List<String>>?=null;var wanted=emptySet<String>();var channels=0;var rows=0
        val from=now-source.settings.guide.historyDays*86_400_000L-86_400_000L
        val until=now+source.settings.guide.futureHours*3_600_000L+86_400_000L
        fun choices():Map<String,List<String>> {
            alternatives?.let {return it}
            return eligible.associate {c->
                val explicit=manual[c.id]
                val options=when {
                    explicit!=null -> if(explicit.feed==feed && explicit.xmlId.isNotEmpty())listOf(explicit.xmlId) else emptyList()
                    c.tvgId in known -> listOf(c.tvgId)
                    else -> listOf(c.tvgId,unique[normalized(c.tvgName)].orEmpty(),unique[normalized(c.name)].orEmpty()).filter(String::isNotBlank).distinct()
                }
                c.id to options
            }.also {alternatives=it;wanted=it.values.flatten().toHashSet()}
        }
        try {
            SQLiteDatabase.openOrCreateDatabase(target,null).use {db->
                db.disableWriteAheadLogging();db.setMaximumSize(MAX_SOURCE_BYTES)
                db.execSQL("CREATE TABLE channels(id TEXT PRIMARY KEY,name TEXT NOT NULL,icon TEXT NOT NULL)")
                db.execSQL("CREATE TABLE programmes(xml TEXT NOT NULL,event TEXT PRIMARY KEY,start INTEGER NOT NULL,end INTEGER NOT NULL,title TEXT NOT NULL,description TEXT NOT NULL,catchup TEXT NOT NULL)")
                db.execSQL("CREATE INDEX event_time ON programmes(xml,start)")
                db.execSQL("CREATE TABLE bindings(channel TEXT PRIMARY KEY,xml TEXT NOT NULL,shift INTEGER NOT NULL)")
                db.beginTransaction()
                try {
                    db.compileStatement("INSERT OR REPLACE INTO channels VALUES(?,?,?)").use {channelInsert->
                        db.compileStatement("INSERT OR REPLACE INTO programmes VALUES(?,?,?,?,?,?,?)").use {insert->
                            PersonalXmlTv.parse(input,source.settings.guide.language,ZoneId.of(source.settings.guide.xmlZone),active,channel={c->
                                if(++channels>100_000)throw SourceException("La guida supera il limite di 100.000 definizioni di canale.")
                                if(c.id in exact)known+=c.id
                                c.names.forEach {name->val key=normalized(name);if(key in wantedNames) {
                                    if(key !in unique)unique[key]=c.id else if(unique[key]!=c.id)unique[key]=null
                                }}
                                channelInsert.bindString(1,c.id);channelInsert.bindString(2,c.names.firstOrNull() ?: c.id)
                                val icon=runCatching {it.lumetv.core.PlaylistUris.resolve(java.net.URI(url),java.net.URI(c.icon)).toString()}.getOrDefault("")
                                channelInsert.bindString(3,if(c.icon.isBlank())"" else icon);channelInsert.executeInsert()
                            },programme={p->
                                choices()
                                if(p.channel in wanted && p.start<until && (p.end>from || p.end==0L && p.start>=from)) {
                                    if(++rows>MAX_PROGRAMMES)throw SourceException("La guida supera il limite di un milione di programmi.")
                                    val event=hash(eventScope+p.channel+"|"+p.start)
                                    insert.bindString(1,p.channel);insert.bindString(2,event);insert.bindLong(3,p.start);insert.bindLong(4,p.end)
                                    insert.bindString(5,p.title);insert.bindString(6,p.description);insert.bindString(7,p.catchupId);insert.executeInsert()
                                }
                            })
                        }
                    }
                    active()
                    db.execSQL("UPDATE programmes SET end=COALESCE((SELECT MIN(next.start) FROM programmes AS next WHERE next.xml=programmes.xml AND next.start>programmes.start),0) WHERE end=0")
                    db.execSQL("DELETE FROM programmes WHERE end<=start OR end<? OR start>=?",arrayOf<Any?>(from,until))
                    db.rawQuery("SELECT DISTINCT xml FROM programmes",null).use {c->while(c.moveToNext())usedXml+=c.getString(0)}
                    val options=choices()
                    db.compileStatement("INSERT INTO bindings VALUES(?,?,?)").use {insert->eligible.forEach {candidate->
                        val id=options[candidate.id].orEmpty().firstOrNull {it in usedXml}
                        if(id!=null) {insert.bindString(1,candidate.id);insert.bindString(2,id);insert.bindLong(3,candidate.shift);insert.executeInsert()}
                    }}
                    db.execSQL("""DELETE FROM programmes WHERE NOT EXISTS (SELECT 1 FROM bindings b WHERE b.xml=programmes.xml
                        AND programmes.end+b.shift>? AND programmes.start+b.shift<?)""",arrayOf<Any?>(now-source.settings.guide.historyDays*86_400_000L,now+source.settings.guide.futureHours*3_600_000L))
                    rows=db.rawQuery("SELECT COUNT(*) FROM programmes",null).use {it.moveToFirst();it.getInt(0)}
                    db.setTransactionSuccessful()
                }finally {db.endTransaction()}
            }
            val matches=SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READONLY).use {db->db.rawQuery("SELECT COUNT(*) FROM bindings",null).use {it.moveToFirst();it.getInt(0)}}
            if(channels==0 && rows==0)throw SourceException("La guida XMLTV è vuota. La copia precedente resta disponibile.")
            return GuideImport(target,rows,matches)
        }catch(error:Exception){target.delete();File(target.path+"-journal").delete();throw error}
    }

    fun commit(source:String,feed:Int,draft:GuideImport,state:GuideFeedState,now:Long,history:Int,future:Int) {
        val total=directory.listFiles().orEmpty().filter {it.extension=="sqlite" && !it.name.startsWith('.')}.sumOf(File::length)
        if(total+draft.file.length()>MAX_TOTAL_BYTES)throw SourceException("Le guide personali superano il limite complessivo di 256 MiB.")
        open(source).use {db->
            db.execSQL("ATTACH DATABASE ? AS incoming",arrayOf<Any?>(draft.file.path))
            try {db.beginTransaction();try {
                val previousUrl=db.rawQuery("SELECT url_hash FROM feeds WHERE id=?",arrayOf(feed.toString())).use {if(it.moveToFirst())it.getString(0) else null}
                val previousRows=db.rawQuery("SELECT COUNT(*) FROM programmes WHERE feed=?",arrayOf(feed.toString())).use {it.moveToFirst();it.getInt(0)}
                if(draft.rows>0 && previousUrl!=null && previousUrl!=state.urlHash && (previousUrl.isNotBlank() || previousRows>0)) {
                    // A new URL in the same priority slot must never inherit another provider's history or manual matches.
                    db.execSQL("DELETE FROM programmes WHERE feed=?",arrayOf<Any?>(feed))
                    db.execSQL("DELETE FROM bindings WHERE feed=?",arrayOf<Any?>(feed))
                    db.execSQL("DELETE FROM manual WHERE feed=?",arrayOf<Any?>(feed))
                }
                db.execSQL("""DELETE FROM programmes WHERE feed=? AND NOT EXISTS (SELECT 1 FROM bindings b WHERE b.feed=programmes.feed AND b.xml=programmes.xml
                    AND programmes.end+b.shift>? AND programmes.start+b.shift<?)""",arrayOf<Any?>(feed,now-history*86_400_000L,now+future*3_600_000L))
                db.execSQL("DELETE FROM channels WHERE feed=?",arrayOf<Any?>(feed))
                db.execSQL("INSERT INTO channels SELECT ?,id,name,icon FROM incoming.channels",arrayOf<Any?>(feed))
                if(draft.rows>0) {
                    db.execSQL("DELETE FROM bindings WHERE feed=?",arrayOf<Any?>(feed))
                    db.execSQL("INSERT OR REPLACE INTO programmes SELECT ?,xml,event,start,end,title,description,catchup FROM incoming.programmes",arrayOf<Any?>(feed))
                    db.execSQL("INSERT INTO bindings SELECT channel,?,xml,shift FROM incoming.bindings",arrayOf<Any?>(feed))
                }
                val count=db.rawQuery("SELECT COUNT(*) FROM programmes",null).use {it.moveToFirst();it.getInt(0)}
                if(count>MAX_PROGRAMMES)throw SourceException("La guida supera il limite di un milione di programmi per lista.")
                fun pragma(name:String)=db.rawQuery("PRAGMA $name",null).use {it.moveToFirst();it.getLong(0)}
                if(total-file(source).length()+pragma("page_count")*pragma("page_size")>MAX_TOTAL_BYTES)
                    throw SourceException("Le guide personali superano il limite complessivo di 256 MiB.")
                putState(db,state.copy(rows=draft.rows,urlHash=if(draft.rows==0 && previousRows>0)previousUrl.orEmpty() else state.urlHash,
                    error=if(draft.rows==0)"Nessun programma associato. Verifica l’intervallo o associa i canali manualmente." else ""));db.setTransactionSuccessful()
            }finally {db.endTransaction()}}finally {db.execSQL("DETACH DATABASE incoming")}
        }
    }

    fun window(source:String,channels:List<it.lumetv.core.Channel>,from:Long,until:Long,limit:Int=5000):Map<String,List<Programme>> {
        if(!file(source).exists() || channels.isEmpty())return emptyMap()
        return open(source).use {db->
            val result=linkedMapOf<String,MutableList<Programme>>();var rows=0
            for(chunk in channels.chunked(200)) {
                if(rows>=limit)break
                val ids=chunk.map {it.id};val placeholders=ids.joinToString(","){"?"}
                val args=(ids+listOf(from.toString(),until.toString(),(limit-rows).toString())).toTypedArray()
                db.rawQuery("""SELECT b.channel,p.event,p.start+b.shift,p.end+b.shift,p.title,p.description FROM bindings b
                    JOIN programmes p ON p.feed=b.feed AND p.xml=b.xml WHERE b.channel IN ($placeholders)
                    AND b.feed=(SELECT MIN(b2.feed) FROM bindings b2 WHERE b2.channel=b.channel)
                    AND p.end+b.shift>CAST(? AS INTEGER) AND p.start+b.shift<CAST(? AS INTEGER) ORDER BY p.start LIMIT ?""",args).use {c->while(c.moveToNext()) {
                        rows++;val channel=c.getString(0)
                        result.getOrPut(PersonalGuideKeys.channel(source,channel)){mutableListOf()}.add(Programme(c.getString(4),c.getLong(2),c.getLong(3),c.getString(5),c.getString(1),source))
                    }}
            }
            result
        }
    }
    fun event(source:String,channel:String,event:String):PrivateGuideEvent? {
        if(!file(source).exists())return null
        return open(source).use {db->db.rawQuery("""SELECT p.title,p.start+b.shift,p.end+b.shift,p.description,p.catchup,p.start,p.end
            FROM programmes p JOIN bindings b ON b.feed=p.feed AND b.xml=p.xml WHERE b.channel=? AND p.event=? LIMIT 1""",arrayOf(channel,event)).use {c->
            if(!c.moveToFirst())null else PrivateGuideEvent(Programme(c.getString(0),c.getLong(1),c.getLong(2),c.getString(3),event,source),c.getString(4),c.getLong(5),c.getLong(6))
        }}
    }
    fun icon(source:String,channel:String):String? {
        if(!file(source).exists())return null
        return open(source).use {db->db.rawQuery("SELECT c.icon FROM bindings b JOIN channels c ON c.feed=b.feed AND c.id=b.xml WHERE b.channel=? ORDER BY b.feed LIMIT 1",arrayOf(channel)).use {if(it.moveToFirst())it.getString(0).takeIf(String::isNotBlank) else null}}
    }
    fun count(source:String):Pair<Int,Int> = open(source).use {db->
        fun count(sql:String)=db.rawQuery(sql,null).use {it.moveToFirst();it.getInt(0)}
        count("SELECT COUNT(DISTINCT channel) FROM bindings") to count("SELECT COUNT(*) FROM programmes")
    }
    fun prune(source:String,validFeeds:Int,from:Long,until:Long)=open(source).use {db->
        db.beginTransaction();try {
            db.execSQL("""DELETE FROM programmes WHERE feed>=? OR NOT EXISTS (SELECT 1 FROM bindings b WHERE b.feed=programmes.feed AND b.xml=programmes.xml
                AND programmes.end+b.shift>? AND programmes.start+b.shift<?)""",arrayOf<Any?>(validFeeds,from,until))
            for(table in listOf("feeds","channels","bindings"))db.execSQL("DELETE FROM $table WHERE ${if(table=="feeds")"id" else "feed"}>=?",arrayOf<Any?>(validFeeds))
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
    fun search(source:String,channel:String,query:String,from:Long,until:Long):List<Programme> {
        val placeholder=it.lumetv.core.Channel(channel,"",9999,PersonalGuideKeys.channel(source,channel),"","","","personal",channel,sourceId=source)
        return window(source,listOf(placeholder),from,until,2000).values.flatten().filter {it.title.contains(query,true)}.take(100)
    }
    fun searchPage(source:String,channel:String,query:String,from:Long,until:Long,offset:Int):Pair<List<PrivateGuideEvent>,Int> {
        if(!file(source).exists())return emptyList<PrivateGuideEvent>() to 0
        return open(source).use {db->
            val pattern="%"+query.take(300).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
            val condition="""FROM bindings b JOIN programmes p ON p.feed=b.feed AND p.xml=b.xml WHERE b.channel=?
                AND b.feed=(SELECT MIN(b2.feed) FROM bindings b2 WHERE b2.channel=b.channel)
                AND p.end+b.shift>CAST(? AS INTEGER) AND p.start+b.shift<CAST(? AS INTEGER)
                AND (p.title LIKE ? ESCAPE '\' OR p.description LIKE ? ESCAPE '\')"""
            val args=arrayOf(channel,from.toString(),until.toString(),pattern,pattern)
            val total=db.rawQuery("SELECT COUNT(*) $condition",args).use {it.moveToFirst();it.getInt(0)}
            val events=db.rawQuery("SELECT p.title,p.start+b.shift,p.end+b.shift,p.description,p.event,p.catchup,p.start,p.end $condition ORDER BY p.start LIMIT 50 OFFSET ?",args+offset.coerceAtLeast(0).toString()).use {c->buildList {
                while(c.moveToNext())add(PrivateGuideEvent(Programme(c.getString(0),c.getLong(1),c.getLong(2),c.getString(3),c.getString(4),source),c.getString(5),c.getLong(6),c.getLong(7)))
            }}
            events to total
        }
    }
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
}
