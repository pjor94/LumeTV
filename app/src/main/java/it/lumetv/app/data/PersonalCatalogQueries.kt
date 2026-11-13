package it.lumetv.app.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import it.lumetv.core.PlaylistContentKind
import org.json.JSONArray

/** A window owns at most [limit] display rows; URLs and player configuration stay in SQLite. */
data class PersonalCatalogQuery(val source: String? = null, val kind: MediaKind? = null,
    val content: PlaylistContentKind? = PlaylistContentKind.LIVE, val text: String = "",
    val group: String? = null, val offset: Int = 0, val limit: Int = 200,
    val ids: Set<String>? = null, val number:Int? = null)

internal object PlaylistCatalogIndex {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS record_groups(group_name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(group_name,position))")
        db.execSQL("CREATE INDEX IF NOT EXISTS kind_content_position ON records(active,kind,content,position)")
    }
    fun populate(db:SQLiteDatabase) {
        db.compileStatement("INSERT OR IGNORE INTO record_groups(group_name,position) VALUES(?,?)").use {insert->
            db.rawQuery("SELECT groups_json,position FROM records WHERE active=1",null).use {cursor->
                while(cursor.moveToNext()) {
                    val groups=JSONArray(cursor.getString(0))
                    for(i in 0 until minOf(groups.length(),20)) {
                        insert.bindString(1,groups.getString(i));insert.bindLong(2,cursor.getLong(1));insert.executeInsert()
                    }
                }
            }
        }
    }
}

/** Older snapshots gain only a derived index; their private payload and stable IDs are unchanged. */
internal fun PersonalPlaylistStore.ensureCatalogIndex(id: String) {
    val path=file(id)
    val ready=SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('record_groups','kind_content_position')",null).use {it.moveToFirst();it.getInt(0)==2}
    }
    if(ready)return
    SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.beginTransaction()
        try {
            PlaylistCatalogIndex.create(db)
            PlaylistCatalogIndex.populate(db)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
}

private fun where(stored: PlaylistStored,q: PersonalCatalogQuery,prefix:String=""): Pair<String,List<String>> {
    val args=mutableListOf<String>()
    val clauses=mutableListOf("r.active=1")
    q.kind?.let {clauses+="r.kind=?";args+=it.name}
    q.number?.let {clauses+="r.number=?";args+=it.toString()}
    q.content?.let { wanted ->
        if(stored.settings.contentOverride!=null) {
            if(stored.settings.contentOverride!=wanted)clauses+="0"
        } else {clauses+="r.content=?";args+=wanted.name}
    }
    if(q.text.isNotBlank()) {
        clauses+="(r.name LIKE ? ESCAPE '\\' OR CAST(r.number AS TEXT)=?)"
        args+=pattern(q.text.take(300));args+=q.text.take(300)
    }
    q.group?.let {clauses+="r.position IN (SELECT position FROM ${prefix}record_groups WHERE group_name=?)";args+=it}
    if(q.ids!=null)clauses+="r.id IN (SELECT id FROM selected_ids)"
    return clauses.joinToString(" AND ") to args
}
private fun pattern(value:String)="%"+value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"

private fun display(cursor:Cursor,source:String,override:PlaylistContentKind?):Channel {
    val id=cursor.getString(0)
    val array=JSONArray(cursor.getString(4))
    val groups=(0 until minOf(array.length(),20)).map {array.getString(it).take(100)}
    val content=override ?: PlaylistContentKind.valueOf(cursor.getString(6))
    return Channel(id,cursor.getString(1),if(cursor.isNull(2))9999 else cursor.getInt(2),
        if(content==PlaylistContentKind.LIVE)PersonalGuideKeys.channel(source,id) else "",groups.joinToString(" · "),
        "","","personal",id,kind=MediaKind.valueOf(cursor.getString(3)),sourceId=source,position=cursor.getInt(5),
        groups=groups,numberKnown=!cursor.isNull(2),contentKind=content)
}

internal fun PersonalPlaylistStore.catalog(stored:PlaylistStored,q:PersonalCatalogQuery):PersonalLibraryPage =
    SQLiteDatabase.openDatabase(file(stored.source.id).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.execSQL("PRAGMA temp_store=FILE")
        q.ids?.let { ids ->
            db.execSQL("CREATE TEMP TABLE selected_ids(id TEXT PRIMARY KEY)")
            db.compileStatement("INSERT OR IGNORE INTO selected_ids(id) VALUES(?)").use { statement ->
                ids.forEach {statement.bindString(1,it);statement.executeInsert()}
            }
        }
        val (filter,args)=where(stored,q)
        val total=db.rawQuery("SELECT COUNT(*) FROM records r WHERE $filter",args.toTypedArray()).use {it.moveToFirst();it.getInt(0)}
        val rows=db.rawQuery("SELECT r.id,r.name,r.number,r.kind,r.groups_json,r.position,r.content FROM records r WHERE $filter ORDER BY r.position,r.id LIMIT ? OFFSET ?",
            (args+q.limit.coerceIn(1,200).toString()+q.offset.coerceAtLeast(0).toString()).toTypedArray()).use { cursor ->
            buildList {while(cursor.moveToNext())add(display(cursor,stored.source.id,stored.settings.contentOverride))}
        }
        PersonalLibraryPage(rows,total)
    }

internal fun PersonalPlaylistStore.displayChannel(stored:PlaylistStored,id:String):Channel? =
    SQLiteDatabase.openDatabase(file(stored.source.id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
        db.rawQuery("SELECT id,name,number,kind,groups_json,position,content FROM records WHERE active=1 AND id=?",arrayOf(id)).use {cursor->
            if(cursor.moveToFirst())display(cursor,stored.source.id,stored.settings.contentOverride) else null
        }
    }

internal fun PersonalPlaylistStore.containsId(source:String,id:String):Boolean =
    SQLiteDatabase.openDatabase(file(source).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
        db.rawQuery("SELECT 1 FROM records WHERE id=?",arrayOf(id)).use {it.moveToFirst()}
    }

/** Read only the indexed content column; availability must not depend on the 200-row preview. */
internal fun PersonalPlaylistStore.contentKinds(stored:PlaylistStored):Set<PlaylistContentKind> {
    if(stored.source.count==0)return emptySet()
    stored.settings.contentOverride?.let {return setOf(it)}
    return SQLiteDatabase.openDatabase(file(stored.source.id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
        db.rawQuery("SELECT DISTINCT content FROM records WHERE active=1",null).use {cursor->
            buildSet {while(cursor.moveToNext())add(PlaylistContentKind.valueOf(cursor.getString(0)))}
        }
    }
}

/** Locate a playing channel without materialising the preceding rows. */
internal fun PersonalPlaylistStore.catalogPosition(stored:PlaylistStored,current:Channel,ids:Set<String>?):Int =
    SQLiteDatabase.openDatabase(file(stored.source.id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
        if(ids!=null) {
            db.execSQL("PRAGMA temp_store=FILE");db.execSQL("CREATE TEMP TABLE selected_ids(id TEXT PRIMARY KEY)")
            db.compileStatement("INSERT OR IGNORE INTO selected_ids VALUES(?)").use {s->ids.forEach {s.bindString(1,it);s.executeInsert()}}
        }
        if(ids!=null && current.id !in ids)return@use 0
        val (filter,args)=where(stored,PersonalCatalogQuery(kind=current.kind,content=current.contentKind,ids=ids))
        db.rawQuery("SELECT COUNT(*) FROM records r WHERE $filter AND (r.position<? OR (r.position=? AND r.id<?))",
            (args+current.position.toString()+current.position.toString()+current.id).toTypedArray()).use {it.moveToFirst();it.getInt(0)}
    }

internal fun PersonalPlaylistStore.nextChannel(stored:PlaylistStored,current:Channel,direction:Int,ids:Set<String>?):Channel? {
    val request=PersonalCatalogQuery(kind=current.kind,ids=ids,limit=200)
    // Indexed position windows wrap once; the number of channels never determines the allocation.
    return SQLiteDatabase.openDatabase(file(stored.source.id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
        if(ids!=null) {
            db.execSQL("PRAGMA temp_store=FILE");db.execSQL("CREATE TEMP TABLE selected_ids(id TEXT PRIMARY KEY)")
            db.compileStatement("INSERT OR IGNORE INTO selected_ids VALUES(?)").use {s->ids.forEach {s.bindString(1,it);s.executeInsert()}}
        }
        val (filter,args)=where(stored,request)
        fun find(wrap:Boolean):Channel? {
            val order=if(direction>=0)"ASC" else "DESC"
            val boundary=if(wrap)"" else " AND r.position ${if(direction>=0) ">" else "<"} ?"
            val values=if(wrap)args else args+current.position.toString()
            return db.rawQuery("SELECT r.id,r.name,r.number,r.kind,r.groups_json,r.position,r.content FROM records r WHERE $filter$boundary ORDER BY r.position $order,r.id $order LIMIT 1",values.toTypedArray()).use {c->
                if(c.moveToFirst())display(c,stored.source.id,stored.settings.contentOverride) else null
            }
        }
        find(false) ?: find(true)
    }
}

internal fun PersonalPlaylistStore.catalogGroups(sources:List<PlaylistStored>,query:PersonalCatalogQuery):Pair<List<String>,Int> {
    if(sources.isEmpty())return emptyList<String>() to 0
    return SQLiteDatabase.openDatabase(file(sources.first().source.id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
        db.execSQL("PRAGMA temp_store=FILE")
        val args=mutableListOf<String>()
        val selects=sources.mapIndexed {index,stored->
            val schema=if(index==0)"main" else "catalog$index".also {db.execSQL("ATTACH DATABASE ? AS $it",arrayOf(file(stored.source.id).path))}
            val (filter,values)=where(stored,query.copy(text="",group=null,ids=null),"$schema.")
            args+=values
            "SELECT DISTINCT g.group_name AS name FROM $schema.record_groups g JOIN $schema.records r ON r.position=g.position WHERE $filter"
        }
        val sql="SELECT name FROM (${selects.joinToString(" UNION ")}) WHERE name LIKE ? ESCAPE '\\'"
        args+=pattern(query.text.take(300))
        val total=db.rawQuery("SELECT COUNT(*) FROM ($sql)",args.toTypedArray()).use {it.moveToFirst();it.getInt(0)}
        val rows=db.rawQuery("$sql ORDER BY name COLLATE NOCASE,name LIMIT ? OFFSET ?",(args+query.limit.coerceIn(1,200).toString()+query.offset.coerceAtLeast(0).toString()).toTypedArray()).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        rows to total
    }
}
