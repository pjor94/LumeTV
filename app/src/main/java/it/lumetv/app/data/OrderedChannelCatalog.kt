package it.lumetv.app.data

import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.Channel
import org.json.JSONArray

/** Merge display rows in SQLite so a page never materialises the preceding catalog metadata. */
internal fun <T> PersonalPlaylistStore.withOrderedCatalog(sources:List<PlaylistStored>,builtin:List<Channel>,q:PersonalCatalogQuery,
    read:(SQLiteDatabase,String,List<String>)->T):T = SQLiteDatabase.create(null).use {db->
    db.execSQL("PRAGMA temp_store=FILE")
    prepareHidden(db,q.excluded)
    q.ids?.let {ids->
        db.execSQL("CREATE TEMP TABLE selected_ids(id TEXT PRIMARY KEY)")
        db.compileStatement("INSERT OR IGNORE INTO selected_ids VALUES(?)").use {s->ids.forEach {s.bindString(1,it);s.executeInsert()}}
    }
    if(q.favoriteOrder && q.ranks.isNotEmpty()) {
        db.execSQL("CREATE TEMP TABLE ranked_ids(id TEXT PRIMARY KEY,rank INTEGER NOT NULL)")
        db.compileStatement("INSERT OR IGNORE INTO ranked_ids VALUES(?,?)").use {s->q.ranks.forEachIndexed {rank,id->if(q.ids==null || id in q.ids){s.bindString(1,id);s.bindLong(2,rank.toLong());s.executeInsert()}}}
    }
    val rank=if(q.favoriteOrder && q.ranks.isNotEmpty())"COALESCE((SELECT rank FROM ranked_ids WHERE id=r.id),2147483647)" else "2147483647"
    val selects=mutableListOf<String>();val args=mutableListOf<String>()
    val builtinRows=builtin.filter {
        (q.kind==null || it.kind==q.kind) && (q.content==null || it.contentKind==q.content) &&
            (q.number==null || it.displayNumber==q.number) && (q.ids==null || it.id in q.ids) &&
        (q.source==null || q.source=="builtin" || it.sourceId==q.source) && (q.group==null || q.group in it.groups) &&
            (q.text.isBlank() || if(it.isRadio)RadioCatalog.matches(it,q.text) else it.name.contains(q.text,true) || it.displayNumber?.toString()==q.text)
    }
    if(builtinRows.isNotEmpty()) {
        db.execSQL("CREATE TEMP TABLE builtin_rows(id TEXT PRIMARY KEY,name TEXT,number INTEGER,kind TEXT,groups_json TEXT,position INTEGER,content TEXT,active INTEGER,source TEXT)")
        db.compileStatement("INSERT INTO builtin_rows VALUES(?,?,?,?,?,?,?,1,?)").use {s->builtinRows.forEachIndexed {position,c->
            s.bindString(1,c.id);s.bindString(2,c.name);c.displayNumber?.let {s.bindLong(3,it.toLong())} ?: s.bindNull(3)
            s.bindString(4,c.kind.name);s.bindString(5,JSONArray(c.groups).toString());s.bindLong(6,position.toLong());s.bindString(7,c.contentKind.name);s.bindString(8,c.sourceId);s.executeInsert()
        }}
        val (filter,values)=catalogWhere(null,q.copy(group=null,text=""));args+=values
        selects+="SELECT r.id,r.name,r.number,r.kind,r.groups_json,r.position,r.content,r.source,0 AS source_rank,$rank AS preference_rank FROM builtin_rows r WHERE $filter"
    }
    sources.forEachIndexed {index,stored->
        val schema="catalog$index"
        db.execSQL("ATTACH DATABASE ? AS $schema",arrayOf(file(stored.source.id).path))
        val (filter,values)=catalogWhere(stored,q,"$schema.");args+=values
        // With favorites, visit their IDs first and use records' primary key, rather than scan a large list.
        val from=if(q.ids!=null)"selected_ids selected CROSS JOIN $schema.records r ON r.id=selected.id" else "$schema.records r"
        selects+="SELECT r.id,r.name,r.number,r.kind,r.groups_json,r.position,r.content,'${stored.source.id}' AS source,${index+1} AS source_rank,$rank AS preference_rank FROM $from WHERE $filter"
    }
    val sql=if(selects.isEmpty())"SELECT '' AS id,'' AS name,NULL AS number,'TV' AS kind,'[]' AS groups_json,0 AS position,'LIVE' AS content,'' AS source,0 AS source_rank,0 AS preference_rank WHERE 0"
        else selects.joinToString(" UNION ALL ")
    read(db,sql,args)
}

internal fun PersonalPlaylistStore.orderedCatalog(sources:List<PlaylistStored>,builtin:List<Channel>,q:PersonalCatalogQuery):PersonalLibraryPage =
    withOrderedCatalog(sources,builtin,q){db,sql,args->
        val total=db.rawQuery("SELECT COUNT(*) FROM ($sql)",args.toTypedArray()).use {it.moveToFirst();it.getInt(0)}
        val originals=builtin.associateBy {it.id}
        val bySource=sources.associateBy {it.source.id}
        val rows=db.rawQuery("SELECT * FROM ($sql) ORDER BY preference_rank,source_rank,position,id LIMIT ? OFFSET ?",
            (args+q.limit.coerceIn(1,200).toString()+q.offset.coerceAtLeast(0).toString()).toTypedArray()).use {c->buildList {
            while(c.moveToNext()) add(originals[c.getString(0)] ?: catalogDisplay(c,c.getString(7),bySource[c.getString(7)]?.settings?.contentOverride))
        }}
        PersonalLibraryPage(rows,total)
    }

/** Reordering retains only IDs of favorites; complete Channel/stream objects are never loaded. */
internal fun PersonalPlaylistStore.orderedCatalogIds(sources:List<PlaylistStored>,builtin:List<Channel>,q:PersonalCatalogQuery):List<String> =
    withOrderedCatalog(sources,builtin,q){db,sql,args->
        db.rawQuery("SELECT id FROM ($sql) ORDER BY preference_rank,source_rank,position,id",args.toTypedArray()).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
    }

/** Source ownership also survives a provider removing a channel and the snapshot's archive expiring. */
internal fun PersonalPlaylistStore.orderedCatalogOwners(sources:List<PlaylistStored>,builtin:List<Channel>,q:PersonalCatalogQuery):Map<String,String> =
    withOrderedCatalog(sources,builtin,q){db,sql,args->
        db.rawQuery("SELECT id,source FROM ($sql) ORDER BY preference_rank,source_rank,position,id",args.toTypedArray()).use {c->buildMap {while(c.moveToNext())put(c.getString(0),c.getString(1))}}
    }
