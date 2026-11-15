package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaybackTarget
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WatchPosition(val position:Long=0,val duration:Long=0,val completed:Boolean=false,val hidden:Boolean=false) {
    val resume:Long get()=if(completed || position<5_000)0 else position
}
internal data class WatchSession(val source:String,val target:PlaybackTarget,val revision:Long)

/** Bounded private history. It contains opaque identities and positions, never stream credentials. */
class PersonalPlaybackStore private constructor(context:Context) {
    companion object {
        private val instances=java.util.WeakHashMap<Context,PersonalPlaybackStore>()
        fun get(context:Context):PersonalPlaybackStore=synchronized(instances){val app=context.applicationContext;instances.getOrPut(app){PersonalPlaybackStore(app)}}
    }
    private val file=File(context.filesDir,"personal-watch.sqlite")
    private val _version=MutableStateFlow(0L)
    val version=_version.asStateFlow()
    @Volatile private var initialized=false
    private var schemaReady=false
    @Volatile private var positions:Map<Pair<String,String>,WatchPosition>?=null
    private var sequence=0L
    private var active:WatchSession?=null
    private val revisions=LinkedHashMap<Pair<String,PlaybackTarget>,Long>(16,.75f,true)
    @Synchronized internal fun database():File {if(!initialized || !file.exists()){open().close();initialized=true;positions=null};return file}
    internal fun cachedPositions()=positions
    @Synchronized internal fun snapshot():Map<Pair<String,String>,WatchPosition> {
        positions?.takeIf {file.exists()}?.let {return it}
        return open().use {db->db.rawQuery("SELECT source,channel,position,duration,completed,hidden FROM watch WHERE mode='VOD' AND event=''",null).use {c->buildMap {while(c.moveToNext())put(c.getString(0) to c.getString(1),WatchPosition(c.getLong(2),c.getLong(3),c.getInt(4)!=0,c.getInt(5)!=0))}}}.also {positions=it;initialized=true}
    }
    private val writer=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1))
    /** Reserve on the playback thread; queued writes cannot supersede a newer user decision. */
    @Synchronized internal fun session(source:String,target:PlaybackTarget):WatchSession =
        WatchSession(source,target,revise(source,target)).also {active=it}
    @Synchronized internal fun release(session:WatchSession?) {if(active==session)active=null}
    private fun revise(source:String,target:PlaybackTarget):Long {
        val revision=++sequence;revisions[source to target]=revision
        while(revisions.size>5001)revisions.keys.firstOrNull {it!=active?.let {s->s.source to s.target}}?.let {revisions.remove(it)} ?: break
        return revision
    }
    private fun valid(session:WatchSession)=revisions[session.source to session.target]==session.revision
    @Synchronized internal fun isCurrent(session:WatchSession)=valid(session)
    /** A deliberate new playback restores Continue visibility and begins tracking a watched title again. */
    @Synchronized internal fun start(session:WatchSession,position:Long?=null,duration:Long?=null):WatchPosition? {
        if(!valid(session))return null
        val old=read(session.source,session.target)
        val value=WatchPosition(position ?: old.resume,duration?.takeIf {it>0} ?: old.duration)
        save(session.source,session.target,value);return value
    }
    internal fun enqueueStart(context:PersonalRequestContext,session:WatchSession,position:Long,duration:Long) {
        writer.launchWrite {context.whileValid {start(session,position,duration)}}
    }
    internal fun enqueue(context:PersonalRequestContext,session:WatchSession,position:Long,duration:Long,completed:Boolean) {
        writer.launchWrite {context.whileValid {record(session,position,duration,completed)}}
    }
    @Synchronized internal fun record(session:WatchSession,position:Long,duration:Long,completed:Boolean):Boolean {
        if(!valid(session))return false
        save(session.source,session.target,WatchPosition(position.coerceAtLeast(0),duration.coerceAtLeast(0),completed))
        return true
    }
    private fun open():SQLiteDatabase {
        val fresh=!file.exists()
        if(fresh){positions=null;initialized=false}
        return SQLiteDatabase.openOrCreateDatabase(file,null).apply {
        disableWriteAheadLogging()
        execSQL("CREATE TABLE IF NOT EXISTS watch(source TEXT NOT NULL,channel TEXT NOT NULL,mode TEXT NOT NULL,event TEXT NOT NULL,position INTEGER NOT NULL,duration INTEGER NOT NULL,completed INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(source,channel,mode,event))")
        if(fresh || !schemaReady) {
            val columns=rawQuery("PRAGMA table_info(watch)",null).use {c->buildSet {while(c.moveToNext())add(c.getString(1))}}
            if("hidden" !in columns)execSQL("ALTER TABLE watch ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            schemaReady=true
        }
        execSQL("CREATE INDEX IF NOT EXISTS watch_recent ON watch(mode,completed,updated DESC)")
        }
    }
    @Synchronized fun read(source:String,target:PlaybackTarget):WatchPosition = open().use {db->db.rawQuery(
        "SELECT position,duration,completed,hidden FROM watch WHERE source=? AND channel=? AND mode=? AND event=?",arrayOf(source,target.channelId,target.mode.name,target.eventId)).use {c->
        if(c.moveToFirst())WatchPosition(c.getLong(0),c.getLong(1),c.getInt(2)!=0,c.getInt(3)!=0) else WatchPosition()
    }}
    @Synchronized internal fun backupRows():List<BackupWatch> = open().use {db->
        db.rawQuery("SELECT source,channel,mode,event,position,duration,completed,hidden,updated FROM watch ORDER BY updated DESC LIMIT 5000",null).use {c->
            buildList {while(c.moveToNext())add(BackupWatch(c.getString(0),PlaybackTarget(c.getString(1),PlaybackMode.valueOf(c.getString(2)),c.getString(3)),
                WatchPosition(c.getLong(4),c.getLong(5),c.getInt(6)!=0,c.getInt(7)!=0),c.getLong(8)))}
        }
    }
    /** Insert-only import: existing decisions and all existing history capacity are preserved. */
    @Synchronized internal fun <T> restoreMissing(rows:List<BackupWatch>,apply:(Int,Int)->T):T {
        val inserted=mutableListOf<BackupWatch>()
        val result=open().use {db->
            db.beginTransaction()
            try {
                var room=5000-db.rawQuery("SELECT COUNT(*) FROM watch",null).use {it.moveToFirst();it.getInt(0)}
                db.compileStatement("INSERT OR IGNORE INTO watch(source,channel,mode,event,position,duration,completed,hidden,updated) VALUES(?,?,?,?,?,?,?,?,?)").use {statement->
                    for(row in rows) {
                        if(room<=0)break
                        statement.clearBindings();statement.bindString(1,row.source);statement.bindString(2,row.target.channelId)
                        statement.bindString(3,row.target.mode.name);statement.bindString(4,row.target.eventId)
                        statement.bindLong(5,row.value.position);statement.bindLong(6,row.value.duration)
                        statement.bindLong(7,if(row.value.completed)1 else 0);statement.bindLong(8,if(row.value.hidden)1 else 0);statement.bindLong(9,row.updated)
                        if(statement.executeInsert()!=-1L){inserted+=row;room--}
                    }
                }
                val result=apply(inserted.size,rows.size-inserted.size)
                db.setTransactionSuccessful();result
            }finally {db.endTransaction()}
        }
        if(inserted.isNotEmpty()) {
            inserted.forEach {revise(it.source,it.target)}
            positions=null;_version.value++
        }
        return result
    }
    @Synchronized fun write(source:String,target:PlaybackTarget,position:Long,duration:Long,completed:Boolean=false) {
        revise(source,target)
        save(source,target,WatchPosition(position.coerceAtLeast(0),duration.coerceAtLeast(0),completed))
    }
    @Synchronized internal fun complete(source:String,target:PlaybackTarget,completed:Boolean,duration:Long) {
        val old=read(source,target);revise(source,target)
        save(source,target,WatchPosition(if(completed)old.position else 0,old.duration.takeIf {it>0} ?: duration.coerceAtLeast(0),completed))
    }
    @Synchronized internal fun hide(source:String,target:PlaybackTarget,hidden:Boolean):Boolean {
        val old=read(source,target)
        if(old.completed || old.position<5000)return false
        if(old.hidden==hidden)return true
        revise(source,target);save(source,target,old.copy(hidden=hidden));return true
    }
    private fun save(source:String,target:PlaybackTarget,value:WatchPosition) {
        if(target.mode==PlaybackMode.LIVE || !PersonalPlaylistStore.validId(source))return
        open().use {db->db.beginTransaction();try {
            db.execSQL("INSERT OR REPLACE INTO watch(source,channel,mode,event,position,duration,completed,updated,hidden) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(source,target.channelId,target.mode.name,target.eventId,value.position,value.duration,if(value.completed)1 else 0,System.currentTimeMillis(),if(value.hidden)1 else 0))
            db.execSQL("DELETE FROM watch WHERE rowid NOT IN (SELECT rowid FROM watch ORDER BY updated DESC LIMIT 5000)")
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}}
        positions=positions?.let {old->if(target.mode==PlaybackMode.VOD && target.eventId.isEmpty() && (old.size<5000 || source to target.channelId in old))old+( (source to target.channelId) to value) else null}
        _version.value++
    }
    @Synchronized fun removeSource(source:String) {
        revisions.keys.removeAll {it.first==source};if(active?.source==source)active=null
        if(file.exists())open().use {it.execSQL("DELETE FROM watch WHERE source=?",arrayOf<Any?>(source))}
        positions=positions?.filterKeys {it.first!=source};_version.value++
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchWrite(block:()->Unit) = launch {runCatching(block)}
