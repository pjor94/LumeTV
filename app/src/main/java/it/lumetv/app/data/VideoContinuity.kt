package it.lumetv.app.data

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import java.io.File

/** Neighbor lookups read indexed display identities, never a complete series or playback credentials. */
internal fun PersonalPlaylistStore.nextEpisodeId(stored:PlaylistStored,id:String,signal:CancellationSignal):String? =
    SQLiteDatabase.openDatabase(file(stored.source.id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
        db.execSQL("PRAGMA temp_store=FILE")
        val content=if(stored.settings.contentOverride==null)" AND content='VOD'" else ""
        val current=db.rawQuery("SELECT series_key,season,episode,position FROM video_items WHERE id=? AND kind='EPISODE'$content",arrayOf(id),signal).use {c->
            if(!c.moveToFirst() || c.getString(0).isBlank())return@use null
            EpisodePoint(c.getString(0),if(c.isNull(1))null else c.getInt(1),if(c.isNull(2))null else c.getInt(2),c.getInt(3))
        } ?: return@use null
        val season=(current.season ?: 0).toString();val episode=(current.episode ?: 0).toString()
        val known=current.season!=null && current.episode!=null
        val tail=if(known)"" else " OR (COALESCE(episode,0)=CAST(? AS INTEGER) AND (position>? OR (position=? AND id>?)))"
        val args=listOf(current.series,season,season,episode)+if(known)emptyList() else listOf(episode,current.position.toString(),current.position.toString(),id)
        db.rawQuery("SELECT id FROM video_items WHERE series_key=? AND kind='EPISODE'$content AND "+
            "(COALESCE(season,0)>CAST(? AS INTEGER) OR (COALESCE(season,0)=CAST(? AS INTEGER) AND (COALESCE(episode,0)>CAST(? AS INTEGER)$tail))) "+
            "ORDER BY COALESCE(season,0),COALESCE(episode,0),position,id LIMIT 1",args.toTypedArray(),signal).use {c->if(c.moveToFirst())c.getString(0) else null}
    }

private data class EpisodePoint(val series:String,val season:Int?,val episode:Int?,val position:Int)

internal fun PersonalPlaylistStore.firstUnwatchedId(stored:PlaylistStored,series:String,watch:File,signal:CancellationSignal):String? =
    SQLiteDatabase.openDatabase(file(stored.source.id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
        db.execSQL("PRAGMA temp_store=FILE")
        db.execSQL("ATTACH DATABASE ? AS history",arrayOf(watch.path))
        val content=if(stored.settings.contentOverride==null)" AND m.content='VOD'" else ""
        db.rawQuery("SELECT m.id FROM video_items m LEFT JOIN history.watch w ON w.channel=m.id AND w.source=? AND w.mode='VOD' AND w.event='' "+
            "WHERE m.series_key=? AND m.kind='EPISODE'$content AND COALESCE(w.completed,0)=0 "+
            "ORDER BY COALESCE(m.season,0),COALESCE(m.episode,0),m.position,m.id LIMIT 1",arrayOf(stored.source.id,series),signal).use {c->if(c.moveToFirst())c.getString(0) else null}
    }
