package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.*
import it.lumetv.app.ui.VideoPosterAdapter
import it.lumetv.app.ui.VideoShelfAdapter
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaybackTarget
import it.lumetv.core.VodKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/** Native queries and the actual unchanged Video screens. Synthetic, unreachable streams are never opened. */
object VideoPerformanceInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val count=args.getString("entries","10000").toInt();require(count in listOf(10000,50000,100000,500000))
        val mobile=args.getString("mode")=="phone";val repo=PersonalPlaylistRepository.get(context);val reuse=args.getString("reuse")=="true"
        val report=JSONObject().put("entries",count).put("mode",if(mobile)"phone" else "tv").put("setup_reused",reuse)
        fun progress(stage:String){File(context.cacheDir,"video-performance-progress.json").writeText(JSONObject(report.toString()).put("stage",stage).toString())}
        fun <T> main(block:()->T):T {val value=AtomicReference<Result<T>>();runner.runOnMainSync {value.set(runCatching(block))};return value.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun <T> timed(name:String,block:()->T):T {progress(name);val start=System.nanoTime();return block().also {report.put(name+"_ms",(System.nanoTime()-start)/1_000_000.0)}}
        fun delta(before:Map<String,Long>)=JSONObject(VideoPerformance.snapshot().mapValues {(key,value)->value-(before[key] ?: 0L)})
        io {repo.local();if(!reuse)repo.state.value.toList().forEach {repo.delete(it.id)}}
        val source=if(reuse)repo.state.value.single {it.name=="Catalogo $count" && it.count==count}.id else {
        val input=File(context.cacheDir,"video-performance.m3u")
        input.bufferedWriter().use {out->out.appendLine("#EXTM3U");repeat(count){i->
            val episode=i%5>=3;val group="Categoria ${(i%20).toString().padStart(2,'0')}"
            val title=if(episode)"Orbita ${(i/20).toString().padStart(6,'0')} S01E${(i%20+1).toString().padStart(2,'0')}" else "Orizzonte ${i.toString().padStart(6,'0')}"
            out.appendLine("#EXTINF:-1 type=\"${if(episode)"series" else "movie"}\" group-title=\"$group\" year=\"2023\" genre=\"Avventura\" plot=\"Trama sintetica per la misura delle prestazioni del catalogo.\",$title")
            out.appendLine("https://invalid.example.org/video/$i.mp4")
        }}
        try {timed("import") {io {repo.prepareFile(input).use {repo.save("Catalogo $count",it)}}}}finally {input.delete()}
        }
        context.getSharedPreferences("lume",0).edit().putString("catalog_source",source).remove("last_channel").remove("favorites").remove("video_saved_series").commit()
        val present=SQLiteDatabase.openDatabase(File(context.filesDir,"personal-playlists/$source.sqlite").path,null,SQLiteDatabase.OPEN_READONLY).use {db->db.rawQuery("SELECT value FROM metadata WHERE key='video-index'",null).use {it.moveToFirst() && it.getString(0)==VideoCatalogIndex.VERSION}}
        report.put("index_present_before",present)
        val beforeIndex=VideoPerformance.snapshot()
        timed(if(present)"ready_index_check" else "initial_index") {io {repo.videos(VideoQuery(source=source,limit=1,details=false))}}
        report.put("initial_index_phases",delta(beforeIndex));report.put("database_bytes",File(context.filesDir,"personal-playlists/$source.sqlite").length())
        val q=VideoQuery(source=source,kind=VodKind.MOVIE,details=false)
        // Replay the previous metadata layout and indexes in TEMP, without enlarging the private source.
        // Setup/copy time is reported separately and excluded from the count+page comparison.
        progress("legacy_films_count_page")
        val legacy=legacyFilms(context.filesDir,source,PersonalPlaybackStore.get(context).database())
        report.put("legacy_films_count_page_ms",legacy.ms).put("legacy_setup_ms",legacy.setup).put("legacy_metadata_layout_and_indexes",true)
        repo.clearVideoReadCache();val before=VideoPerformance.snapshot()
        val first=timed("indexed_films_count_page") {io {repo.videos(q)}}
        check(first.total==legacy.total && first.rows.map {it.id}==legacy.ids){"Legacy result contract changed"}
        report.put("same_first_50_ids_and_total",true).put("movie_total",first.total).put("indexed_phases",delta(before))
        report.put("query_plans",plans(context.filesDir,source))
        val firstSeries=timed("indexed_series_count_page") {io {repo.videos(VideoQuery(source=source,kind=VodKind.EPISODE,details=false))}}
        timed("films_title_order") {check(io {repo.videos(q.copy(order=VideoOrder.TITLE))}.total==first.total)}
        timed("films_added_order") {check(io {repo.videos(q.copy(order=VideoOrder.ADDED))}.total==first.total)}
        report.put("series_total",firstSeries.total)
        val deep=timed("deep_films_page") {io {repo.videos(q.copy(offset=(first.total-50).coerceAtLeast(0)))}}
        check(deep.rows.size==50 && deep.rows.none {it.id in first.rows.map {r->r.id}})
        timed("search") {check(io {repo.videos(q.copy(text="Orizzonte 00000"))}.rows.isNotEmpty())}
        val movie=first.rows.first();val history=PersonalPlaybackStore.get(context)
        io {history.write(source,PlaybackTarget(movie.id,PlaybackMode.VOD),21_000,180_000)}
        repo.clearVideoReadCache()
        val activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val content=main {activity.findViewById<View>(android.R.id.content)}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
        fun visible()=views(content).filter {it.isShown}
        fun await(label:String,condition:()->Boolean){val until=System.nanoTime()+45_000_000_000;while(System.nanoTime()<until){if(condition())return;Thread.sleep(8)};error("Timed out: $label")}
        fun click(label:String){await(label){main {visible().filterIsInstance<TextView>().lastOrNull {it.text.toString()==label && it.isClickable && it.isEnabled}?.let {if(!mobile)it.requestFocus();it.performClick()}==true}}}
        fun homeReady()=main {content.findViewWithTag<RecyclerView>("video_home")?.let {(it.adapter as? VideoShelfAdapter)?.rows?.any {row->row.key=="movies" && row.page.rows.isNotEmpty()}}==true}
        fun grid()=main {visible().filterIsInstance<RecyclerView>().firstOrNull {it.tag=="video_grid_results"}}
        fun gridReady()=grid()?.let {list->main {val p=(list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition();p>=0 && (list.adapter as VideoPosterAdapter).catalog?.peek(p)!=null && list.findViewHolderForAdapterPosition(p)!=null}}==true
        fun back(){main {activity.onBackPressedDispatcher.onBackPressed()}}
        fun capture(name:String){runner.waitForIdleSync();runner.uiAutomation.takeScreenshot()?.let {bitmap->File(context.cacheDir,"video-performance-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
        val frames=ConcurrentLinkedQueue<Long>()
        val listener=android.view.Window.OnFrameMetricsAvailableListener {_,metrics,_->frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))}
        main {activity.window.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper()))}
        timed("ui_home_first_content") {click("Video");await("home film posters",::homeReady);runner.waitForIdleSync()}
        capture("home")
        timed("ui_films_first_content") {click("Film");await("first movie window",::gridReady);runner.waitForIdleSync()}
        capture("films")
        var list=grid()!!
        timed("ui_detail_first_content") {
            main {list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()}
            await("movie details") {main {visible().filterIsInstance<TextView>().any {it.text.toString().contains("Trama sintetica")}}};runner.waitForIdleSync()
        }
        capture("detail")
        timed("ui_return_to_grid") {back();await("grid return",::gridReady);runner.waitForIdleSync()}
        list=grid()!!
        val target=(first.total-50).coerceAtLeast(0)
        timed("ui_deep_scroll") {
            main {if(mobile){content.isFocusableInTouchMode=true;content.requestFocus()};(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target,0)}
            await("deep movie window") {main {(list.adapter as VideoPosterAdapter).catalog?.peek(target)!=null && list.findViewHolderForAdapterPosition(target)!=null}}
            runner.waitForIdleSync()
        }
        check(main {(list.adapter as VideoPosterAdapter).catalog!!.cachedRows<=150})
        timed("ui_back_home") {back();await("home return",::homeReady);runner.waitForIdleSync()}
        val repeats=JSONArray();val repeatStart=VideoPerformance.snapshot()
        repeat(10){
            val start=System.nanoTime();click(if(it%2==0)"Film" else "Serie");await("warm collection",::gridReady);runner.waitForIdleSync()
            val gridShown=System.nanoTime();val current=grid()!!;main {val p=(current.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition();current.findViewHolderForAdapterPosition(p)!!.itemView.performClick()}
            await("warm detail") {main {visible().filterIsInstance<TextView>().any {it.text.toString().contains("Trama sintetica")}}};runner.waitForIdleSync()
            val detailShown=System.nanoTime();back();await("warm return",::gridReady);runner.waitForIdleSync();val returned=System.nanoTime()
            repeats.put(JSONObject().put("collection_ms",(gridShown-start)/1_000_000.0).put("detail_ms",(detailShown-gridShown)/1_000_000.0).put("return_ms",(returned-detailShown)/1_000_000.0))
            back();await("warm home",::homeReady)
        }
        report.put("warm_navigation",repeats).put("warm_phases",delta(repeatStart)).put("cache",JSONObject(repo.videoReadCacheState()))
        check(repo.videoReadCacheState().getValue("rows")<=600 && repo.videoReadCacheState().getValue("counts")<=64)
        click("Cerca");timed("ui_search_including_debounce") {
            main {visible().filterIsInstance<EditText>().single {it.contentDescription=="Cerca un video"}.setText("Orizzonte 00000")}
            await("search results") {gridReady() && grid()?.adapter?.itemCount==6};runner.waitForIdleSync()
        }
        main {activity.window.removeOnFrameMetricsAvailableListener(listener)}
        val sorted=frames.toList().sorted();report.put("frame_count",sorted.size)
        if(sorted.isNotEmpty())report.put("frame_total_p50_ms",sorted[(sorted.lastIndex*.50).toInt()]/1_000_000.0).put("frame_total_p95_ms",sorted[(sorted.lastIndex*.95).toInt()]/1_000_000.0)
        val player=main {activity.javaClass.getDeclaredField(if(mobile)"player" else "tvPlayer").apply {isAccessible=true}.get(activity) as it.lumetv.app.playback.TvPlayer}
        check(main {player.snapshot.channel==null}){"Browsing unexpectedly selected a stream"}
        report.put("passed",true).put("playback_started",false);progress("complete");main {activity.finish()};return report
    }

    private fun plans(directory:File,source:String):JSONObject = SQLiteDatabase.openDatabase(File(directory,"personal-playlists/$source.sqlite").path,null,SQLiteDatabase.OPEN_READONLY).use {db->
        val where="t.scope=0 AND t.group_id=(SELECT id FROM video_groups WHERE name='')"
        val statements=mapOf(
            "all" to "SELECT t.id FROM video_titles t WHERE $where ORDER BY t.position,t.id LIMIT 50",
            "films" to "SELECT t.id FROM video_titles t WHERE $where AND t.kind='MOVIE' ORDER BY t.position,t.id LIMIT 50",
            "title" to "SELECT t.id FROM video_titles t WHERE $where ORDER BY t.sort_title COLLATE NOCASE,t.id LIMIT 50",
            "added" to "SELECT t.id FROM video_titles t WHERE $where ORDER BY t.added DESC,t.id LIMIT 50",
            "films_title" to "SELECT t.id FROM video_titles t INDEXED BY video_title_title WHERE $where AND t.kind='MOVIE' ORDER BY t.sort_title COLLATE NOCASE,t.id LIMIT 50",
            "films_added" to "SELECT t.id FROM video_titles t INDEXED BY video_title_added WHERE $where AND t.kind='MOVIE' ORDER BY t.added DESC,t.id LIMIT 50",
            "group" to "SELECT t.id FROM video_titles t WHERE t.scope=0 AND t.group_id=(SELECT id FROM video_groups WHERE name='Categoria 00') ORDER BY t.position,t.id LIMIT 50",
            "season" to "SELECT id FROM video_items WHERE series_key=? AND season=1 AND content='VOD' ORDER BY season,episode,position LIMIT 50",
            "index_batch" to "SELECT r.rowid,r.id,r.payload,r.position,r.content,a.added,r.groups_json FROM records r NOT INDEXED JOIN video_added a ON a.id=r.id WHERE r.active=1 AND r.rowid>0 ORDER BY r.rowid LIMIT 200",
            "index_batch_previous" to "SELECT r.rowid,r.id,r.payload,r.position,r.content,a.added,r.groups_json FROM records r JOIN video_added a ON a.id=r.id WHERE r.active=1 AND r.rowid>0 ORDER BY r.rowid LIMIT 200")
        JSONObject().also {out->statements.forEach {(name,sql)->
            val values=if(name=="season")arrayOf("synthetic-series") else null
            val rows=db.rawQuery("EXPLAIN QUERY PLAN $sql",values).use {c->buildList {while(c.moveToNext())add(c.getString(3))}}
            out.put(name,JSONArray(rows))
        }}
    }

    private data class Legacy(val total:Int,val ids:List<String>,val ms:Double,val setup:Double)
    private fun legacyFilms(directory:File,source:String,watch:File):Legacy {
      val started=System.nanoTime()
      return SQLiteDatabase.openDatabase(File(directory,"personal-playlists/$source.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
        db.execSQL("PRAGMA temp_store=FILE");db.execSQL("ATTACH DATABASE ? AS history",arrayOf(watch.path))
        db.execSQL("CREATE TEMP TABLE favorites(id TEXT PRIMARY KEY)");db.execSQL("CREATE TEMP TABLE saved_series(id TEXT PRIMARY KEY)")
        db.execSQL("CREATE TEMP TABLE legacy_metadata(id TEXT PRIMARY KEY,title TEXT NOT NULL,kind TEXT NOT NULL,series_key TEXT NOT NULL,series_title TEXT NOT NULL,season INTEGER,episode INTEGER,year INTEGER,duration INTEGER NOT NULL,description TEXT NOT NULL,genres TEXT NOT NULL,poster TEXT NOT NULL,backdrop TEXT NOT NULL)")
        db.execSQL("INSERT INTO legacy_metadata SELECT id,title,kind,series_key,series_title,season,episode,year,duration,description,genres,poster,backdrop FROM video_metadata ORDER BY position")
        db.execSQL("CREATE INDEX legacy_kind_title ON legacy_metadata(kind,title)")
        db.execSQL("CREATE INDEX legacy_series_episode ON legacy_metadata(series_key,season,episode)")
        val prefix="""WITH base AS (SELECT '$source' AS source,r.id,m.title,m.kind,m.series_key,m.series_title,m.season,m.episode,m.year,m.duration,m.description,m.genres,r.groups_json,
            a.added,COALESCE(w.position,0) AS watch_position,COALESCE(w.duration,0) AS watch_duration,COALESCE(w.completed,0) AS completed,
            (r.id IN (SELECT id FROM favorites) OR '$source:'||m.series_key IN (SELECT id FROM saved_series)) AS saved,r.position,COALESCE(w.updated,0) AS watched,
            CASE WHEN m.series_key<>'' THEN 's:'||m.series_key ELSE 'i:'||r.id END AS display_key
            FROM records r JOIN legacy_metadata m ON m.id=r.id LEFT JOIN video_added a ON a.id=r.id
            LEFT JOIN history.watch w ON w.source='$source' AND w.channel=r.id AND w.mode='VOD' AND w.event=''
            WHERE r.active=1 AND r.content='VOD' AND m.kind='MOVIE'),
            grouped AS (SELECT source,display_key,COUNT(*) AS episodes,MAX(saved) AS any_saved,MIN(position) AS first_position FROM base GROUP BY source,display_key),
            result AS (SELECT b.*,g.episodes,g.any_saved,g.first_position FROM base b JOIN grouped g ON b.source=g.source AND b.display_key=g.display_key AND b.position=g.first_position)"""
        val queryStart=System.nanoTime()
        val total=db.rawQuery("$prefix SELECT COUNT(*) FROM result",null).use {it.moveToFirst();it.getInt(0)}
        val ids=db.rawQuery("$prefix SELECT id FROM result ORDER BY first_position,id LIMIT 50",null).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        Legacy(total,ids,(System.nanoTime()-queryStart)/1_000_000.0,(queryStart-started)/1_000_000.0)
      }
    }
}
