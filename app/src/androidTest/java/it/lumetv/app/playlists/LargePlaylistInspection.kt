package it.lumetv.app.playlists

import android.content.Context
import android.os.Bundle
import android.os.Debug
import it.lumetv.app.data.*
import it.lumetv.core.PlaylistContentKind
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Real SQLite, file and HTTP imports. Invoked only by the emulator-guarded test runner. */
object LargePlaylistInspection {
    fun run(context:Context,args:Bundle):JSONObject=runBlocking(Dispatchers.IO) {
        val root=requireNotNull(args.getString("fixtureRoot"))
        check(URI(root).host=="10.0.2.2" && URI(root).scheme=="http")
        val count=args.getString("count")?.toInt() ?: 500_000
        check(count in 50_001..500_000)
        if(args.getString("phase")=="refresh")return@runBlocking refresh(context,count)
        val repository=PersonalPlaylistRepository(context)
        repository.local();repository.state.value.forEach {repository.delete(it.id)}
        val progress=File(context.cacheDir,"large-playlists-progress.json")
        val checks=JSONArray();val metrics=JSONArray()
        fun passed(value:String){checks.put(value)}
        fun update(phase:String,entries:Int){progress.writeText(JSONObject().put("phase",phase).put("entries",entries).put("pssKiB",Debug.getPss()).toString())}
        val file=File(context.cacheDir,"large-playlist.m3u")
        fun write(indices:Sequence<Int>,rename:Boolean=false) {
            file.bufferedWriter().use {out->out.appendLine("#EXTM3U")
                indices.forEach {i->out.appendLine("#EXTINF:${if(i%10==0)120 else -1} tvg-id=\"A$i\" tvg-chno=\"${i+1}\" group-title=\"Gruppo ${i%50}\",Carico A ${if(rename)"nuovo " else ""}voce ${i+1}")
                    out.appendLine("$root/video.mp4?source=A&id=$i")}
            }
        }
        write((0 until count).asSequence())
        val start=System.nanoTime()
        val a=repository.prepareFile(file,allowHttp=true,progress={update("file-A",it.entries)}).use {draft->
            check(draft.report.count==count);repository.save("Carico A",draft)
        }
        metrics.put(JSONObject().put("phase","file-A").put("entries",count).put("milliseconds",(System.nanoTime()-start)/1_000_000).put("pssKiB",Debug.getPss()))
        passed("50000-entry limit exceeded through a streamed file import")
        check(repository.local().size<=200);passed("catalog snapshot bounded to 200 display rows")
        val last=repository.catalog(PersonalCatalogQuery(source=a,content=null,offset=count-1,limit=1)).rows.single()
        check(last.name=="Carico A voce $count");check(repository.channel(last.id)==last)
        check(repository.playbackEntry(last.id).entry.metadata["tvg-id"]=="A${count-1}")
        passed("last entry is addressable and playable outside the cached window")
        val prefs=context.getSharedPreferences("lume",Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorites",setOf(last.id)).commit()
        check(repository.catalog(PersonalCatalogQuery(ids=setOf(last.id))).rows.single().id==last.id)
        check(repository.next(last,1)?.position==1)
        check(repository.next(last,-1)?.position==count-2)
        passed("favorite and zapping cross catalog windows and wrap at source boundaries")
        val filtered=repository.catalog(PersonalCatalogQuery(source=a,text="voce $count",group="Gruppo ${(count-1)%50}"))
        check(filtered.total==1 && filtered.rows.single().id==last.id)
        check(repository.groups(PersonalCatalogQuery(source=a,content=null)).second==50)
        val video=repository.library(a,"",null,count/10-1)
        check(video.total==count/10 && video.rows.single().position==count-10)
        passed("search groups and final VOD page query the whole source")
        val second=System.nanoTime()
        val b=repository.prepareUrl("$root/large.m3u",allowHttp=true,progress={update("http-B",it.entries)}).use {draft->
            check(draft.report.count==count);repository.save("Carico B",draft)
        }
        metrics.put(JSONObject().put("phase","http-B").put("entries",count).put("milliseconds",(System.nanoTime()-second)/1_000_000).put("pssKiB",Debug.getPss()))
        check(repository.state.value.sumOf {it.count}==2*count)
        check(repository.local().size<=400)
        val across=repository.catalog(PersonalCatalogQuery(content=null,offset=count-25,limit=50))
        check(across.total==2*count && across.rows.size==50 && across.rows.first().sourceId==a && across.rows.last().sourceId==b)
        val lastB=repository.catalog(PersonalCatalogQuery(source=b,content=null,offset=count-1,limit=1)).rows.single()
        passed("two streamed sources reach the combined capacity with bounded catalog memory")
        if(count==500_000)repository.prepareFile("#EXTM3U\n#EXTINF:-1,Extra\nhttps://example.com/extra".toByteArray()).use {draft->
            try {repository.save("Oltre limite",draft);error("Combined limit ignored")}
            catch(_:java.io.IOException){check(repository.state.value.sumOf {it.count}==1_000_000)}
            passed("combined overflow keeps both prior snapshots")
        }
        val cancellation=launch {repository.prepareFile(file,allowHttp=true,progress={if(it.entries>=2048)cancel("Fixture cancellation")}).close()}
        cancellation.join();check(cancellation.isCancelled)
        check(repository.state.value.sumOf {it.count}==2*count)
        passed("cancelled import removes its draft and keeps saved sources")
        val restarted=PersonalPlaylistRepository(context)
        check(restarted.local().size<=400);check(restarted.state.value.sumOf {it.count}==2*count)
        check(restarted.channel(lastB.id)?.id==lastB.id)
        passed("cold repository restores large snapshots without loading every display row")
        // Reconcile against a large previous snapshot, including an ID far beyond its first page.
        write(sequenceOf(count-1,0),true)
        restarted.prepareFile(file,allowHttp=true).use {restarted.save("Carico A",it,a)}
        check(restarted.channel(last.id)?.name=="Carico A nuovo voce $count")
        check(prefs.getStringSet("favorites",emptySet())==setOf(last.id))
        passed("update reconciles the previous large snapshot and retains an off-window favorite")
        check(File(context.filesDir,"personal-playlists").listFiles().orEmpty().none {it.name.startsWith(".draft-")})
        file.delete();update("complete",2*count)
        prefs.edit().putString("catalog_source",b).putString("last_channel",lastB.id).commit()
        JSONObject().put("passed",true).put("largestSource",count).put("combinedEntriesTested",2*count)
            .put("cachedRows",restarted.local().size).put("pssKiB",Debug.getPss()).put("maxHeapBytes",Runtime.getRuntime().maxMemory())
            .put("imports",metrics).put("checks",checks).put("sourceA",a).put("sourceB",b)
            .put("lastChannel",lastB.id).put("lastName",lastB.name).put("favoriteChannel",last.id)
    }
    private suspend fun refresh(context:Context,count:Int):JSONObject {
        val repo=PersonalPlaylistRepository(context);repo.local()
        val source=repo.state.value.single {it.name=="Carico B"}
        check(source.count==count)
        val last=repo.catalog(PersonalCatalogQuery(source=source.id,content=null,offset=count-1,limit=1)).rows.single()
        val prefs=context.getSharedPreferences("lume",Context.MODE_PRIVATE)
        val favorites=prefs.getStringSet("favorites",emptySet()).orEmpty().toSet()+last.id
        prefs.edit().putStringSet("favorites",favorites).commit()
        // Invalidate conditional validators so the real 500k snapshot is re-read and reconciled.
        repo.setSettings(source.id,repo.settings(source.id))
        val start=System.nanoTime()
        check(repo.refresh(source.id,true))
        check(repo.state.value.single {it.id==source.id}.count==count)
        check(repo.channel(last.id)?.name==last.name)
        check(prefs.getStringSet("favorites",emptySet())==favorites)
        check(repo.local().size<=442)
        check(File(context.filesDir,"personal-playlists").listFiles().orEmpty().none {it.name.startsWith(".draft-")})
        return JSONObject().put("passed",true).put("entries",count).put("milliseconds",(System.nanoTime()-start)/1_000_000)
            .put("pssKiB",Debug.getPss()).put("cachedRows",repo.local().size)
            .put("checks",JSONArray(listOf("complete large HTTP refresh","last stable ID preserved","favorites preserved","bounded catalog after refresh","drafts cleaned")))
    }
}
