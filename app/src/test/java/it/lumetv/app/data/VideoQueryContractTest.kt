package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Replay the previous query contract across filters; the optimized tables are never used by the oracle. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VideoQueryContractTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val prefs get()=context.getSharedPreferences("lume",0)
    private val store get()=PersonalPlaylistStore(File(context.filesDir,"personal-playlists"))
    @Before fun reset(){File(context.filesDir,"personal-playlists").deleteRecursively();File(context.filesDir,"personal-watch.sqlite").delete();prefs.edit().clear().commit()}
    private fun list()=buildString {appendLine("#EXTM3U");repeat(32){i->
        val kind=when(i%4){0->"movie";1,2->"series";else->"live"}
        val title=if(kind=="series")"Orbita ${i/12} S0${i%2+1}E${(i+1).toString().padStart(2,'0')}" else "Film 100%_ ${31-i}"
        appendLine("#EXTINF:-1 type=\"$kind\" group-title=\"${if(i%2==0)"A" else "B"}\" plot=\"Test\",$title");appendLine("https://example.org/$i.mp4")
    }}.toByteArray()
    private data class Expected(val total:Int,val rows:List<Triple<String,String,Pair<Int,Boolean>>>)
    private fun reference(repo:PersonalPlaylistRepository,q:VideoQuery):Expected {
        val sources=repo.state.value.filter {it.id in repo.videoSources.value && (q.source==null || q.source==it.id)}.map {store.read(it.id)!!}
        if(sources.isEmpty())return Expected(0,emptyList())
        return SQLiteDatabase.openDatabase(store.file(sources.first().source.id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            val schemas=sources.mapIndexed {i,s->if(i==0)"main" else "s$i".also {db.execSQL("ATTACH DATABASE ? AS $it",arrayOf(store.file(s.source.id).path))}}
            db.execSQL("ATTACH DATABASE ? AS history",arrayOf(PersonalPlaybackStore.get(context).database().path))
            for((name,values) in listOf("favorites" to prefs.getStringSet("favorites",emptySet()).orEmpty(),"saved_series" to prefs.getStringSet("video_saved_series",emptySet()).orEmpty())) {
                db.execSQL("CREATE TEMP TABLE $name(id TEXT PRIMARY KEY)");values.forEach {db.execSQL("INSERT INTO $name VALUES(?)",arrayOf(it))}
            }
            val args=mutableListOf<String>();val collapsed=q.collapseSeries && q.shelf!=VideoShelf.CONTINUE
            val base=sources.mapIndexed {index,stored->
                val id=stored.source.id;val schema=schemas[index];val filters=mutableListOf("r.active=1")
                if(stored.settings.contentOverride==null)filters+="r.content='VOD'"
                q.id?.let {filters+="r.id=?";args+=it}
                q.group?.let {filters+="r.position IN (SELECT position FROM $schema.record_groups WHERE group_name=?)";args+=it}
                if(q.text.isNotBlank()) {val text="%"+q.text.take(300).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";filters+="(m.title LIKE ? ESCAPE '\\' OR m.series_title LIKE ? ESCAPE '\\')";args+=text;args+=text}
                q.kind?.let {filters+="m.kind=?";args+=it.name};q.series?.let {filters+="m.series_key=?";args+=it};q.season?.let {filters+="COALESCE(m.season,0)=CAST(? AS INTEGER)";args+=it.toString()}
                val saved="(r.id IN (SELECT id FROM favorites) OR '$id:'||m.series_key IN (SELECT id FROM saved_series))"
                when(q.shelf){VideoShelf.CONTINUE->filters+="w.position>=5000 AND w.completed=0";VideoShelf.SAVED->filters+=saved;VideoShelf.ADDED->filters+="a.added>0";else->Unit}
                val display=if(collapsed)"CASE WHEN m.series_key<>'' THEN 's:'||m.series_key ELSE 'i:'||r.id END" else "r.id"
                val title=if(collapsed)"CASE WHEN m.series_key<>'' THEN m.series_title ELSE m.title END" else "m.title"
                "SELECT '$id' AS source,r.id,r.position,$display AS display_key,$title AS sort_title,COALESCE(m.season,0) AS season,COALESCE(m.episode,0) AS episode,a.added,COALESCE(w.updated,0) AS watched,$saved AS saved FROM $schema.records r JOIN $schema.video_metadata m ON m.id=r.id LEFT JOIN $schema.video_added a ON a.id=r.id LEFT JOIN history.watch w ON w.source='$id' AND w.channel=r.id AND w.mode='VOD' AND w.event='' WHERE ${filters.joinToString(" AND ")}"
            }.joinToString(" UNION ALL ")
            val prefix="WITH base AS ($base), grouped AS (SELECT source,display_key,COUNT(*) AS episodes,MAX(saved) AS any_saved,MIN(position) AS first_position FROM base GROUP BY source,display_key), result AS (SELECT b.*,g.episodes,g.any_saved,g.first_position FROM base b JOIN grouped g ON b.source=g.source AND b.display_key=g.display_key AND b.position=g.first_position)"
            val order=when {q.series!=null && !collapsed->"season,episode,first_position";q.shelf==VideoShelf.CONTINUE->"watched DESC";q.order==VideoOrder.TITLE->"sort_title COLLATE NOCASE";q.order==VideoOrder.ADDED || q.shelf==VideoShelf.ADDED->"added DESC";sources.size==1->"first_position";else->"source,first_position"}
            val total=db.rawQuery("$prefix SELECT COUNT(*) FROM result",args.toTypedArray()).use {it.moveToFirst();it.getInt(0)}
            val rows=db.rawQuery("$prefix SELECT source,id,episodes,any_saved FROM result ORDER BY $order,source,id LIMIT ? OFFSET ?",(args+q.limit.toString()+q.offset.toString()).toTypedArray()).use {c->buildList {while(c.moveToNext())add(Triple(c.getString(0),c.getString(1),c.getInt(2) to (c.getInt(3)!=0)))}}
            Expected(total,rows)
        }
    }
    @Test fun `indexed queries preserve representatives counts saved flags and global ordering across combined filters`()=runBlocking {
        val repo=PersonalPlaylistRepository(context);val first=repo.save("Mista",repo.prepareFile(list()))
        val forced=repo.save("Forzata",repo.prepareFile(list(),settings=PlaylistSettings(contentOverride=PlaylistContentKind.VOD)))
        repo.save("Esclusa",repo.prepareFile(list(),settings=PlaylistSettings(contentOverride=PlaylistContentKind.LIVE)))
        // Simulate old episodes without an added date mixed with episodes added in later refreshes.
        SQLiteDatabase.openDatabase(store.file(first).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.execSQL("UPDATE video_added SET added=0 WHERE id IN (SELECT id FROM records WHERE position%3<>0)")
        }
        val items=repo.videos(VideoQuery(collapseSeries=false,limit=200)).rows
        val favorites=items.filterIndexed {i,_->i%7==0}.map {it.id}.toSet()
        val series=repo.videos(VideoQuery(source=forced,kind=VodKind.EPISODE)).rows.last()
        prefs.edit().putStringSet("favorites",favorites).putStringSet("video_saved_series",setOf("$forced:${series.series}")).commit()
        items.take(7).forEachIndexed {i,item->PersonalPlaybackStore.get(context).write(item.source,PlaybackTarget(item.id,PlaybackMode.VOD),if(i%2==0)21_000 else 3000,180_000,completed=i==4)}
        val queries=buildList {
            for(source in listOf(null,first,forced))for(group in listOf(null,"A","B"))for(kind in listOf(null,VodKind.MOVIE,VodKind.EPISODE))for(shelf in VideoShelf.entries)for(order in VideoOrder.entries)
                add(VideoQuery(source=source,group=group,kind=kind,shelf=shelf,order=order,limit=9,offset=1))
            for(text in listOf("100%_","Orbita 0","S02","inesistente","\\"))for(group in listOf(null,"A","B"))for(shelf in VideoShelf.entries)add(VideoQuery(text=text,group=group,shelf=shelf))
            for(season in listOf(1,2))for(group in listOf(null,"A","B"))for(collapse in listOf(true,false))for(shelf in VideoShelf.entries)add(VideoQuery(source=forced,series=series.series,season=season,group=group,collapseSeries=collapse,shelf=shelf))
            items.take(8).forEach {item->add(VideoQuery(source=item.source,id=item.id));add(VideoQuery(source=item.source,id=item.id,shelf=VideoShelf.SAVED))}
            add(VideoQuery(group=""));add(VideoQuery(group="",shelf=VideoShelf.SAVED))
        }
        for(query in queries) {
            val expected=reference(repo,query);val actual=repo.videos(query)
            assertEquals(query.toString(),expected.total,actual.total)
            assertEquals(query.toString(),expected.rows,actual.rows.map {Triple(it.source,it.id,it.episodes to it.saved)})
        }
        assertTrue(queries.size>400)
    }
    @Test fun `category ordering across sources follows SQLite NOCASE for non Latin names`()=runBlocking {
        val repo=PersonalPlaylistRepository(context)
        for((index,group) in listOf("Άλφα","Ωμέγα","αρχή","Zeta","alfa","Alfa","🪐").withIndex()) {
            val bytes="#EXTM3U\n#EXTINF:-1 type=\"movie\" group-title=\"$group\",Film $index\nhttps://example.org/$index.mp4\n".toByteArray()
            repo.save("Lista $index",repo.prepareFile(bytes))
        }
        assertEquals(listOf("Alfa","alfa","Zeta","Άλφα","Ωμέγα","αρχή"),repo.videoGroups(VideoQuery()))
    }
}
