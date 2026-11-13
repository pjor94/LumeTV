package it.lumetv.app.data

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import it.lumetv.core.VodKind
import it.lumetv.core.VodMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class VideoShelf { ALL, CONTINUE, SAVED, ADDED }
enum class VideoOrder { PLAYLIST, TITLE, ADDED }
data class VideoQuery(val source:String?=null,val group:String?=null,val text:String="",val kind:VodKind?=null,
    val shelf:VideoShelf=VideoShelf.ALL,val order:VideoOrder=VideoOrder.PLAYLIST,val series:String?=null,val season:Int?=null,
    val collapseSeries:Boolean=true,val offset:Int=0,val limit:Int=50,val id:String?=null,val details:Boolean=true)
/** Display-only rows. Artwork references, headers and stream configuration remain private. */
data class VideoItem(val id:String,val source:String,val title:String,val kind:VodKind,val series:String="",val seriesTitle:String="",
    val season:Int?=null,val episode:Int?=null,val year:Int?=null,val duration:Long=0,val description:String="",
    val genres:List<String> = emptyList(),val groups:List<String> = emptyList(),val added:Long=0,
    val position:Long=0,val watchedDuration:Long=0,val completed:Boolean=false,val saved:Boolean=false,val episodes:Int=1,
    val detailsLoaded:Boolean=true) {
    val isSeries get()=series.isNotBlank()
    val displayTitle get()=if(isSeries)seriesTitle else title
    val resume get()=if(completed || position<5000)0 else position
    val key get()=source+":"+if(isSeries)series else id
}
/** Previews explicitly omit the expensive exact count. Collection pages always have countKnown=true. */
data class VideoPage(val rows:List<VideoItem>,val total:Int,val countKnown:Boolean=true)
internal data class VideoIndexInfo(val allScope:Int)
internal data class VideoSource(val stored:PlaylistStored,val index:VideoIndexInfo) {
    val id get()=stored.source.id
    val scope get()=if(stored.settings.contentOverride==null)0 else index.allScope
}

internal object VideoCatalogIndex {
    const val VERSION="4"
    fun recordAdded(db:SQLiteDatabase,prior:Boolean,now:Long) {
        db.execSQL("CREATE TABLE IF NOT EXISTS video_added(id TEXT PRIMARY KEY,added INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO video_added SELECT id,? FROM records",arrayOf(now))
        if(prior) {
            val known=db.rawQuery("SELECT 1 FROM prior.sqlite_master WHERE name='video_added'",null).use {it.moveToFirst()}
            if(known)db.execSQL("UPDATE video_added SET added=COALESCE((SELECT added FROM prior.video_added p WHERE p.id=video_added.id),added)")
            else db.execSQL("UPDATE video_added SET added=0 WHERE id IN (SELECT id FROM prior.records)")
        }
    }
    fun ensure(store:PersonalPlaylistStore,stored:PlaylistStored,checkActive:()->Unit,progress:(String,Int,Int)->Unit):VideoIndexInfo =
        SQLiteDatabase.openDatabase(store.file(stored.source.id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            val ready=db.rawQuery("SELECT value FROM metadata WHERE key='video-index'",null).use {it.moveToFirst() && it.getString(0)==VERSION}
            if(ready)return@use VideoIndexInfo(db.rawQuery("SELECT value FROM metadata WHERE key='video-all-scope'",null).use {if(it.moveToFirst())it.getInt(0) else 1})
            db.setMaximumSize(PersonalPlaylistStore.MAX_DATABASE);db.execSQL("PRAGMA temp_store=FILE")
            val total=stored.source.count;var processed=0;var notified=0L
            fun notify(force:Boolean=false){val time=System.nanoTime();if(force || time-notified>200_000_000){notified=time;progress(stored.source.name,processed,total)}}
            notify(true);db.beginTransaction()
            try {
                for(table in listOf("video_items","video_metadata","video_titles","video_groups")) {
                    val type=db.rawQuery("SELECT type FROM sqlite_master WHERE name=?",arrayOf(table)).use {if(it.moveToFirst())it.getString(0) else "table"}
                    db.execSQL("DROP ${if(type=="view")"VIEW" else "TABLE"} IF EXISTS $table")
                }
                // One physical metadata row per entry. The lean view avoids copying titles, IDs and series twice.
                db.execSQL("CREATE TABLE video_metadata(id TEXT NOT NULL UNIQUE,title TEXT NOT NULL,kind TEXT NOT NULL,series_key TEXT NOT NULL,series_title TEXT NOT NULL,season INTEGER,episode INTEGER,year INTEGER,duration INTEGER NOT NULL,description TEXT NOT NULL,genres TEXT NOT NULL,poster TEXT NOT NULL,backdrop TEXT NOT NULL,position INTEGER PRIMARY KEY,content TEXT NOT NULL,added INTEGER NOT NULL,display_key TEXT NOT NULL,sort_title TEXT NOT NULL,groups_json TEXT NOT NULL)")
                db.execSQL("CREATE VIEW video_items AS SELECT id,title,kind,series_key,series_title,season,episode,year,duration,position,content,added,display_key,sort_title,groups_json FROM video_metadata")
                db.execSQL("CREATE TABLE video_groups(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE)")
                db.execSQL("CREATE TABLE video_titles(scope INTEGER NOT NULL,group_id INTEGER NOT NULL,display_key TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,position INTEGER NOT NULL,episodes INTEGER NOT NULL,sort_title TEXT NOT NULL,added INTEGER NOT NULL,PRIMARY KEY(scope,group_id,position)) WITHOUT ROWID")
                db.execSQL("CREATE TABLE IF NOT EXISTS video_added(id TEXT PRIMARY KEY,added INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO video_added SELECT id,0 FROM records")
                db.compileStatement("INSERT INTO video_metadata VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use {insert->
                    var last=0L
                    while(processed<total) {
                        checkActive();var count=0
                        // The active/position index cannot satisfy rowid order: SQLite may sort the
                        // remaining source on every batch. Seek the rowid directly, including old archives.
                        db.rawQuery("SELECT r.rowid,r.id,r.payload,r.position,r.content,a.added,r.groups_json FROM records r NOT INDEXED JOIN video_added a ON a.id=r.id WHERE r.active=1 AND r.rowid>? ORDER BY r.rowid LIMIT 200",arrayOf(last.toString())).use {c->while(c.moveToNext()) {
                            checkActive();last=c.getLong(0);val value=VodMetadata.from(store.decodeEntry(JSONObject(c.getString(2)),stored.descriptor))
                            insert.clearBindings()
                            listOf(c.getString(1),value.title,value.kind.name,value.seriesKey,value.seriesTitle).forEachIndexed {i,v->insert.bindString(i+1,v)}
                            listOf(value.season,value.episode,value.year).forEachIndexed {i,v->if(v==null)insert.bindNull(i+6) else insert.bindLong(i+6,v.toLong())}
                            insert.bindLong(9,value.duration);insert.bindString(10,value.description);insert.bindString(11,JSONArray(value.genres).toString())
                            insert.bindString(12,value.poster);insert.bindString(13,value.backdrop)
                            insert.bindLong(14,c.getLong(3));insert.bindString(15,c.getString(4));insert.bindLong(16,c.getLong(5))
                            insert.bindString(17,if(value.seriesKey.isNotBlank())"s:${value.seriesKey}" else "i:${c.getString(1)}")
                            insert.bindString(18,if(value.seriesKey.isNotBlank())value.seriesTitle else value.title);insert.bindString(19,c.getString(6))
                            insert.executeInsert();processed++;count++;notify()
                        }}
                        if(count==0)throw IOException("Impossibile preparare i video. La lista salvata resta disponibile.")
                    }
                }
                checkActive()
                db.execSQL("CREATE INDEX video_item_content ON video_metadata(content,position)")
                db.execSQL("CREATE INDEX video_item_kind ON video_metadata(content,kind,position)")
                db.execSQL("CREATE INDEX video_item_series ON video_metadata(series_key,season,episode,position)")
                db.execSQL("INSERT INTO video_groups VALUES(0,'')")
                db.execSQL("INSERT OR IGNORE INTO video_groups(name) SELECT DISTINCT group_name FROM record_groups ORDER BY group_name")
                val mixed=db.rawQuery("SELECT 1 FROM video_items WHERE content<>'VOD' LIMIT 1",null).use {it.moveToFirst()}
                val anyVod=db.rawQuery("SELECT 1 FROM video_items WHERE content='VOD' LIMIT 1",null).use {it.moveToFirst()}
                val allScope=if(mixed)1 else 0
                for(scope in (if(anyVod)listOf(0) else emptyList())+(if(mixed)listOf(1) else emptyList())) {
                    val filter=if(scope==0)" WHERE m.content='VOD'" else ""
                    for(grouped in listOf(false,true)) {
                        checkActive()
                        val from=if(grouped)"record_groups g JOIN video_items m ON m.position=g.position JOIN video_groups vg ON vg.name=g.group_name" else "video_items m"
                        val group=if(grouped)"vg.id" else "0"
                        val groupedColumns=if(grouped)"vg.id,m.display_key" else "m.display_key"
                        db.execSQL("INSERT INTO video_titles SELECT $scope,x.group_id,x.display_key,m.id,m.kind,m.position,x.episodes,m.sort_title,m.added FROM (SELECT $group AS group_id,m.display_key,MIN(m.position) AS first_position,COUNT(*) AS episodes FROM $from$filter GROUP BY $groupedColumns) x JOIN video_items m ON m.position=x.first_position AND m.display_key=x.display_key")
                    }
                }
                for((name,columns) in listOf("key" to "display_key","kind" to "kind,position,id","title" to "sort_title COLLATE NOCASE,id,kind","added" to "added DESC,id,kind")) {
                    checkActive();db.execSQL("CREATE INDEX video_title_$name ON video_titles(scope,group_id,$columns)")
                }
                checkActive()
                val bytes=db.rawQuery("PRAGMA page_count",null).use {it.moveToFirst();it.getLong(0)}*db.pageSize
                val others=store.directory.listFiles().orEmpty().filter {it.name.endsWith(".sqlite") && !it.name.startsWith(".") && it!=store.file(stored.source.id)}.sumOf(File::length)
                if(bytes>PersonalPlaylistStore.MAX_DATABASE || bytes+others>PersonalPlaylistStore.MAX_DISK)throw IOException("Spazio insufficiente per preparare i video. La lista salvata resta disponibile.")
                db.execSQL("INSERT OR REPLACE INTO metadata VALUES('video-all-scope',?)",arrayOf(allScope.toString()))
                db.execSQL("INSERT OR REPLACE INTO metadata VALUES('video-index',?)",arrayOf(VERSION));db.setTransactionSuccessful()
                notify(true);VideoIndexInfo(allScope)
            }finally {db.endTransaction()}
        }
}

/** The candidate query stays narrow. Extended metadata is read only for the selected window. */
internal fun PersonalPlaylistStore.videoPage(sources:List<VideoSource>,q:VideoQuery,watch:File,favorites:Set<String>,savedSeries:Set<String>,
    signal:CancellationSignal,knownCount:Int?=null,preview:Boolean=false):VideoPage {
    if(sources.isEmpty())return VideoPage(emptyList(),0,!preview)
    return SQLiteDatabase.openDatabase(file(sources.first().id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
        db.execSQL("PRAGMA temp_store=FILE")
        if(q.shelf==VideoShelf.CONTINUE)db.execSQL("ATTACH DATABASE ? AS history",arrayOf(watch.path))
        val schemas=sources.mapIndexed {index,source->if(index==0)"main" else "video$index".also {db.execSQL("ATTACH DATABASE ? AS $it",arrayOf(file(source.id).path))}}
        val savedMode=q.shelf==VideoShelf.SAVED
        if(savedMode || favorites.isNotEmpty()) {
            db.execSQL("CREATE TEMP TABLE favorites(id TEXT PRIMARY KEY)")
            db.compileStatement("INSERT OR IGNORE INTO favorites VALUES(?)").use {s->favorites.forEach {signal.throwIfCanceled();s.bindString(1,it);s.executeInsert()}}
        }
        if(savedMode) {
            db.execSQL("CREATE TEMP TABLE saved_series(source TEXT NOT NULL,series TEXT NOT NULL,PRIMARY KEY(source,series)) WITHOUT ROWID")
            db.compileStatement("INSERT OR IGNORE INTO saved_series VALUES(?,?)").use {s->savedSeries.forEach {value->
                signal.throwIfCanceled();val separator=value.indexOf(':')
                if(separator>0){s.bindString(1,value.substring(0,separator));s.bindString(2,value.substring(separator+1));s.executeInsert()}
            }}
        }
        val args=mutableListOf<String>()
        fun pattern(s:String)="%"+s.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"
        val collapsed=q.collapseSeries && q.shelf!=VideoShelf.CONTINUE
        val static=collapsed && q.text.isBlank() && q.id==null && q.season==null && !savedMode && q.shelf!=VideoShelf.ADDED
        val countSelects=mutableListOf<String>()
        val selects=sources.mapIndexed {index,source->
            val schema=schemas[index];val content=if(source.scope==0)" AND m.content='VOD'" else ""
            if(static) {
                val filter=mutableListOf("t.scope=${source.scope}","t.group_id=(SELECT id FROM $schema.video_groups WHERE name=?)");args+=q.group.orEmpty()
                q.kind?.let {filter+="t.kind=?";args+=it.name};q.series?.let {filter+="t.display_key=?";args+="s:$it"}
                if(q.shelf==VideoShelf.ADDED)filter+="t.added>0"
                val index=when {q.series!=null->"";q.order==VideoOrder.TITLE->" INDEXED BY video_title_title";q.order==VideoOrder.ADDED->" INDEXED BY video_title_added";else->""}
                val select="SELECT '${source.id}' AS source,t.id,t.position AS first_position,t.episodes,t.sort_title,t.added,0 AS season,0 AS episode,0 AS watched"
                val where=filter.joinToString(" AND ")
                countSelects+="$select FROM $schema.video_titles t WHERE $where"
                "$select FROM $schema.video_titles t$index WHERE $where"
            } else {
                val filter=mutableListOf("1$content")
                q.id?.let {filter+="m.id=?";args+=it}
                q.group?.let {filter+="m.position IN (SELECT position FROM $schema.record_groups WHERE group_name=?)";args+=it}
                if(q.text.isNotBlank()){filter+="(m.title LIKE ? ESCAPE '\\' OR m.series_title LIKE ? ESCAPE '\\')";args+=pattern(q.text.take(300));args+=pattern(q.text.take(300))}
                q.kind?.let {filter+="m.kind=?";args+=it.name};q.series?.let {filter+="m.series_key=?";args+=it}
                q.season?.let {filter+="COALESCE(m.season,0)=CAST(? AS INTEGER)";args+=it.toString()}
                if(q.shelf==VideoShelf.ADDED)filter+="m.added>0"
                val joined=if(q.shelf==VideoShelf.CONTINUE) {
                    filter+="w.position>=5000 AND w.completed=0"
                    "history.watch w JOIN $schema.video_items m ON m.id=w.channel AND w.source='${source.id}' AND w.mode='VOD' AND w.event=''"
                } else "$schema.video_items m"
                if(savedMode)filter+="m.position IN (SELECT position FROM $schema.video_metadata WHERE id IN (SELECT id FROM favorites) UNION SELECT position FROM $schema.video_metadata WHERE series_key IN (SELECT series FROM saved_series WHERE source='${source.id}'))"
                val where=filter.joinToString(" AND ")
                val select=if(collapsed) {
                    "SELECT '${source.id}' AS source,m.id,m.position AS first_position,x.episodes,m.sort_title,m.added,COALESCE(m.season,0) AS season,COALESCE(m.episode,0) AS episode,0 AS watched FROM (SELECT m.display_key,MIN(m.position) AS first_position,COUNT(*) AS episodes FROM $joined WHERE $where GROUP BY m.display_key) x JOIN $schema.video_items m ON m.position=x.first_position AND m.display_key=x.display_key"
                } else "SELECT '${source.id}' AS source,m.id,m.position AS first_position,1 AS episodes,m.title AS sort_title,m.added,COALESCE(m.season,0) AS season,COALESCE(m.episode,0) AS episode,${if(q.shelf==VideoShelf.CONTINUE)"w.updated" else "0"} AS watched FROM $joined WHERE $where"
                countSelects+=select;select
            }
        }
        val candidate=selects.joinToString(" UNION ALL ")
        val total=if(preview)0 else knownCount ?: VideoPerformance.measure("count") {
            db.rawQuery("SELECT COUNT(*) FROM (${countSelects.joinToString(" UNION ALL ")})",args.toTypedArray(),signal).use {it.moveToFirst();it.getInt(0)}
        }
        val order=when {q.series!=null && !collapsed->"season,episode,first_position";q.shelf==VideoShelf.CONTINUE->"watched DESC";q.order==VideoOrder.TITLE->"sort_title COLLATE NOCASE";q.shelf==VideoShelf.ADDED || q.order==VideoOrder.ADDED->"added DESC";sources.size==1->"first_position";else->"source,first_position"}
        val tie=if(sources.size==1)"id" else "source,id"
        val pointers=VideoPerformance.measure("page") {db.rawQuery("SELECT * FROM ($candidate) ORDER BY $order,$tie LIMIT ? OFFSET ?",(args+q.limit.coerceIn(1,200).toString()+q.offset.coerceAtLeast(0).toString()).toTypedArray(),signal).use {c->buildList {
            while(c.moveToNext())add(Triple(c.getString(0),c.getString(1),c.getInt(3)))
        }}}
        val result=VideoPerformance.measure("rows") {
            val loaded=hashMapOf<Pair<String,String>,VideoItem>()
            pointers.groupBy {it.first}.forEach {(source,window)->
                signal.throwIfCanceled();val index=sources.indexOfFirst {it.id==source};val schema=schemas[index]
                val ids=window.map {it.second};val placeholders=ids.joinToString(","){"?"};val episodeCounts=window.associate {it.second to it.third}
                val columns=if(q.details)"d.description,d.genres,m.groups_json" else "'', '[]', '[]'"
                val join=if(q.details)" JOIN $schema.video_metadata d ON d.id=m.id" else ""
                val sourceRows=mutableListOf<VideoItem>()
                db.rawQuery("SELECT m.id,m.title,m.kind,m.series_key,m.series_title,m.season,m.episode,m.year,m.duration,m.added,$columns FROM $schema.video_items m$join WHERE m.id IN ($placeholders)",ids.toTypedArray(),signal).use {c->while(c.moveToNext()) {
                    fun number(i:Int)=if(c.isNull(i))null else c.getInt(i)
                    fun strings(i:Int)=JSONArray(c.getString(i)).let {a->(0 until minOf(a.length(),20)).map {a.getString(it)}}
                    val id=c.getString(0);val series=if(collapsed)c.getString(3) else ""
                    sourceRows+=VideoItem(id,source,c.getString(1),VodKind.valueOf(c.getString(2)),series,c.getString(4),number(5),number(6),number(7),c.getLong(8),c.getString(10),strings(11),strings(12),c.getLong(9),saved=id in favorites || source+":"+c.getString(3) in savedSeries,episodes=episodeCounts.getValue(id),detailsLoaded=q.details)
                }}
                val seriesKeys=sourceRows.map {it.series}.filter(String::isNotBlank).distinct()
                val favoriteSeries=hashSetOf<String>()
                if(favorites.isNotEmpty() && seriesKeys.isNotEmpty()) {
                    val filters=mutableListOf("m.id IN (SELECT id FROM favorites)","m.series_key IN (${seriesKeys.joinToString(","){"?"}})")
                    val values=seriesKeys.toMutableList()
                    if(sources[index].scope==0)filters+="m.content='VOD'"
                    q.group?.let {filters+="m.position IN (SELECT position FROM $schema.record_groups WHERE group_name=?)";values+=it}
                    if(q.text.isNotBlank()){filters+="(m.title LIKE ? ESCAPE '\\' OR m.series_title LIKE ? ESCAPE '\\')";values+=pattern(q.text.take(300));values+=pattern(q.text.take(300))}
                    q.kind?.let {filters+="m.kind=?";values+=it.name}
                    q.id?.let {filters+="m.id=?";values+=it}
                    q.season?.let {filters+="COALESCE(m.season,0)=CAST(? AS INTEGER)";values+=it.toString()}
                    if(q.shelf==VideoShelf.ADDED)filters+="m.added>0"
                    db.rawQuery("SELECT DISTINCT m.series_key FROM $schema.video_items m WHERE ${filters.joinToString(" AND ")}",values.toTypedArray(),signal).use {c->while(c.moveToNext())favoriteSeries+=c.getString(0)}
                }
                sourceRows.forEach {item->loaded[source to item.id]=if(item.series in favoriteSeries)item.copy(saved=true) else item}
            }
            pointers.map {loaded.getValue(it.first to it.second)}
        }
        VideoPage(result,if(preview)result.size else total,!preview)
    }
}
