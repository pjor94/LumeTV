package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaybackTarget
import java.io.File
import kotlinx.coroutines.launch

data class WatchPosition(val position:Long=0,val duration:Long=0,val completed:Boolean=false) {
    val resume:Long get()=if(completed || position<5_000)0 else position
}

/** Bounded private history. It contains opaque identities and positions, never stream credentials. */
class PersonalPlaybackStore private constructor(context:Context) {
    companion object {
        private val instances=java.util.WeakHashMap<Context,PersonalPlaybackStore>()
        fun get(context:Context):PersonalPlaybackStore=synchronized(instances){val app=context.applicationContext;instances.getOrPut(app){PersonalPlaybackStore(app)}}
    }
    private val file=File(context.filesDir,"personal-watch.sqlite")
    private val writer=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1))
    internal fun enqueue(context:PersonalRequestContext,source:String,target:PlaybackTarget,position:Long,duration:Long,completed:Boolean) {
        writer.launchWrite {context.whileValid {write(source,target,position,duration,completed)}}
    }
    private fun open()=SQLiteDatabase.openOrCreateDatabase(file,null).apply {
        disableWriteAheadLogging()
        execSQL("CREATE TABLE IF NOT EXISTS watch(source TEXT NOT NULL,channel TEXT NOT NULL,mode TEXT NOT NULL,event TEXT NOT NULL,position INTEGER NOT NULL,duration INTEGER NOT NULL,completed INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(source,channel,mode,event))")
    }
    @Synchronized fun read(source:String,target:PlaybackTarget):WatchPosition = open().use {db->db.rawQuery(
        "SELECT position,duration,completed FROM watch WHERE source=? AND channel=? AND mode=? AND event=?",arrayOf(source,target.channelId,target.mode.name,target.eventId)).use {c->
        if(c.moveToFirst())WatchPosition(c.getLong(0),c.getLong(1),c.getInt(2)!=0) else WatchPosition()
    }}
    @Synchronized fun write(source:String,target:PlaybackTarget,position:Long,duration:Long,completed:Boolean=false) {
        if(target.mode==PlaybackMode.LIVE || !PersonalPlaylistStore.validId(source))return
        open().use {db->db.beginTransaction();try {
            db.execSQL("INSERT OR REPLACE INTO watch VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any?>(source,target.channelId,target.mode.name,target.eventId,position.coerceAtLeast(0),duration.coerceAtLeast(0),if(completed)1 else 0,System.currentTimeMillis()))
            db.execSQL("DELETE FROM watch WHERE rowid NOT IN (SELECT rowid FROM watch ORDER BY updated DESC LIMIT 5000)")
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}}
    }
    @Synchronized fun removeSource(source:String) {if(file.exists())open().use {it.execSQL("DELETE FROM watch WHERE source=?",arrayOf<Any?>(source))}}
}

private fun kotlinx.coroutines.CoroutineScope.launchWrite(block:()->Unit) = launch {runCatching(block)}
