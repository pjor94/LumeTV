package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.*
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.ui.PersonalPlaylistsScreen
import it.lumetv.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Exercises classification upgrades and their settings through the real native UI. Emulators only. */
object VodDetectionInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val root=requireNotNull(args.getString("fixtureRoot"));require(Uri.parse(root).host=="10.0.2.2")
        val mobile=args.getString("mode")=="phone";val checks=mutableListOf<String>()
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(180);runner.uiAutomation.takeScreenshot()?.let {image->
            File(context.cacheDir,"vod-detection").apply {mkdirs()}.resolve("$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }}
        fun record(label:String){checks+=label;File(context.cacheDir,"vod-progress.json").writeText(JSONArray(checks).toString())}
        fun verify(label:String,test:()->Boolean){check(test()){label};record(label)}
        fun await(label:String,test:()->Boolean){val end=System.currentTimeMillis()+30_000;while(System.currentTimeMillis()<end){if(test()){record(label);return};Thread.sleep(70)};capture("failure");error(label)}
        val fixture=buildString {
            appendLine("#EXTM3U")
            for(i in 1..220){appendLine("#EXTINF:-1 tvg-id=\"live-$i\" type=\"live\",Diretta $i");appendLine("$root/live/u/p/$i.mp4")}
            fun row(name:String,attributes:String,path:String="video.mp4") {
                appendLine("#EXTINF:-1 tvg-id=\"$name\" $attributes,$name");appendLine("$root/$path?item=$name")
            }
            row("Esplicito","type=\"vod\" group-title=\"Film\"")
            row("Alias","content-type=\"movie\" group-title=\"Film\"")
            row("Percorso","","movie/u/p/3301.mp4");row("Episodio","","series/u/p/3302.mkv")
            row("Cartella","media-dir=\"/movies/scifi\"");row("Dimensione","media-size=\"102400\"")
            row("Ambiguo","group-title=\"Film\"");row("Soloestensione","group-title=\"Generale\"")
            row("Cinemaindiretta","type=\"live\" group-title=\"Film\"")
            row("Personalizzato","group-title=\"Personalizzati\"");row("Altro","group-title=\"Altro gruppo\"")
            appendLine("#EXTINF:-1 tvg-id=\"MarcatoKodi\",MarcatoKodi");appendLine("#EXT-X-PLAYLIST-TYPE:VOD");appendLine("$root/video.mp4?item=MarcatoKodi")
            row("DopoKodi","")
        }
        val seed=PersonalPlaylistRepository(context)
        val id=io {seed.local();seed.state.value.forEach {seed.delete(it.id)}
            seed.prepareFile(fixture.toByteArray(),allowHttp=true,settings=PlaylistSettings(reading=PlaylistReadOptions(groups=PlaylistGroupProfile.KODI))).use {seed.save("Riconoscimento VOD",it)}}
        val before=io {seed.catalog(PersonalCatalogQuery(source=id,content=null,limit=200)).rows+
            seed.catalog(PersonalCatalogQuery(source=id,content=null,offset=200,limit=200)).rows}.associate {it.name to it.id}
        context.getSharedPreferences("lume",0).edit().putString("catalog_source",id).remove("last_channel").putStringSet("favorites",setOf(before.getValue("Esplicito"))).commit()
        val watch=PersonalPlaybackStore.get(context);val target=PlaybackTarget(before.getValue("Esplicito"),PlaybackMode.VOD);watch.write(id,target,14_000,180_000)
        SQLiteDatabase.openDatabase(File(context.filesDir,"personal-playlists/$id.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.beginTransaction();try {
                val source=db.rawQuery("SELECT value FROM metadata WHERE key='source'",null).use {it.moveToFirst();JSONObject(it.getString(0))}
                source.remove("classificationVersion");source.remove("classificationNeedsRefresh");db.execSQL("UPDATE metadata SET value=? WHERE key='source'",arrayOf(source.toString()))
                db.rawQuery("SELECT rowid,payload FROM records",null).use {cursor->while(cursor.moveToNext()) {
                    val entry=JSONObject(cursor.getString(1));val metadata=entry.getJSONObject("metadata")
                    val kind=if(entry.getString("name") in setOf("MarcatoKodi","DopoKodi"))"VOD" else "LIVE"
                    listOf("vodMarker","classificationReason","classificationVersion","legacyVod").forEach(metadata::remove);metadata.put("content",kind)
                    db.execSQL("UPDATE records SET payload=?,content=? WHERE rowid=?",arrayOf<Any>(entry.toString(),kind,cursor.getLong(0)))
                }};db.setTransactionSuccessful()
            }finally {db.endTransaction()}
        }
        val repo=PersonalPlaylistRepository.get(context)
        val activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val content=main {activity.findViewById<View>(android.R.id.content)}
        fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList()
        fun visible()=all(content).filter {it.isShown}
        fun text(value:String)=main {visible().filterIsInstance<TextView>().any {it.text.toString().contains(value)}}
        fun matches(view:View,label:String)=view.isShown && view.isClickable && view !is EditText && (view is TextView && view.text.toString()==label || view.contentDescription?.toString()?.let {it==label || it.startsWith("$label:") || it.startsWith("$label,")}==true)
        fun click(label:String) {
            if(label.startsWith("Mostra tutto:")) {
                val target=label.substringAfter(":").trim()
                await("collection $target is available"){main {content.findViewWithTag<RecyclerView>("video_home")?.let {(it.adapter as? it.lumetv.app.ui.VideoShelfAdapter)?.rows?.any {row->row.title==target}}==true}}
                main {if(mobile){content.isFocusableInTouchMode=true;content.requestFocus()};val home=content.findViewWithTag<RecyclerView>("video_home");val index=(home.adapter as it.lumetv.app.ui.VideoShelfAdapter).rows.indexOfFirst {it.title==target};(home.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(index,0)}
                runner.waitForIdleSync()
            }
            await("action $label is available"){main {visible().any {matches(it,label) && it.isEnabled}}}
            main {visible().last {matches(it,label) && it.isEnabled}.apply {requestRectangleOnScreen(android.graphics.Rect(0,0,width,height),true);if(!mobile)requestFocus()}}
            runner.waitForIdleSync();main {visible().last {matches(it,label) && it.isEnabled}.performClick()}
        }
        fun edit(label:String,value:String)=main {visible().filterIsInstance<EditText>().single {it.contentDescription==label}.setText(value)}
        fun back()=main {activity.onBackPressedDispatcher.onBackPressed()}
        fun names()=io {repo.library(id,"",null).rows.map {it.name}.toSet()}
        fun manage(){click("Opzioni");click("Le mie liste");click("Riconoscimento VOD")}
        fun closeManagement(){main {all(content).filterIsInstance<PersonalPlaylistsScreen>().single().hide()};back()}
        await("migrated source is published"){repo.state.value.any {it.id==id}}
        verify("migration discovers explicit Kodi and provider VOD past the preview"){names()==setOf("Esplicito","Alias","Percorso","Episodio","Cartella","Dimensione","MarcatoKodi","DopoKodi")}
        verify("preview remains bounded and does not control Video availability"){repo.cached().size==200 && repo.cached().none {it.isVod} && repo.videoSources.value==setOf(id)}
        verify("migration preserves favorites and watch position"){context.getSharedPreferences("lume",0).getStringSet("favorites",emptySet())==setOf(before.getValue("Esplicito")) && watch.read(id,target).position==14_000L}
        capture("00-catalog")
        manage();click("Stato e ultima importazione")
        await("legacy marker ambiguity is explained in the status page"){text("Per completare il riconoscimento")};back()
        click("Lettura e contenuti");click("Esteso · anche gruppi video e file multimediali");capture("01-detection")
        back();await("changed recognition uses inline discard protection"){text("Hai modifiche non salvate.")};click("Continua a modificare")
        click("Salva modifiche")
        await("extended recognition updates the saved catalog immediately"){io {repo.settings(id).reading.vodDetection==PlaylistVodDetection.EXTENDED} && "Ambiguo" in names()}
        verify("weak signals do not move live cinema or ungrouped files"){names().none {it in setOf("Soloestensione","Cinemaindiretta","Personalizzato")}}
        click("Lettura e contenuti");click("Regole per i gruppi");click("Aggiungi regola");edit("Nome del gruppo","Personalizzati");click("Video on demand");click("Salva regola")
        await("group rule is staged on an ordinary settings page"){text("Personalizzati") && io {repo.settings(id).reading.groupContent.isEmpty()}}
        capture("02-group-rules")
        click("Aggiungi regola");edit("Nome del gruppo","personalizzati");click("Salva regola")
        verify("duplicate group names stay inline without replacing the previous rule"){text("Esiste già una regola")}
        click("Annulla");click("Scarta modifiche")
        click("Aggiungi regola");click("Scegli un gruppo della lista");edit("Cerca","Altro gruppo")
        click("Altro gruppo")
        await("existing group names can be chosen without retyping"){main {visible().filterIsInstance<EditText>().any {it.contentDescription=="Nome del gruppo" && it.text.toString()=="Altro gruppo"}}}
        click("Annulla");click("Scarta modifiche");back();click("Salva modifiche")
        await("saving a manual group rule reclassifies existing rows"){io {repo.settings(id).reading.groupContent["Personalizzati"]==PlaylistContentKind.VOD} && "Personalizzato" in names()}
        click("Lettura e contenuti");click("Regole per i gruppi");click("Personalizzati");click("Rimuovi regola");back();click("Annulla");click("Scarta modifiche")
        verify("discarding a staged removal preserves the saved group rule"){io {repo.settings(id).reading.groupContent["Personalizzati"]==PlaylistContentKind.VOD}}
        click("Lettura e contenuti");click("Tutte dirette");click("Salva modifiche")
        await("whole-list manual live override wins over every detection rule"){repo.videoSources.value.isEmpty() && names().isEmpty()}
        closeManagement();await("the catalog hides Video after forcing all entries live"){main {visible().none {matches(it,"Video")}}}
        manage();click("Lettura e contenuti");click("Automatico · indicazione di ogni voce");click("Salva modifiche")
        await("returning to Automatic restores recognition and manual group rules"){"Personalizzato" in names() && "Ambiguo" in names()}
        val settings=io {repo.settings(id)}
        io {repo.prepareFile(fixture.toByteArray(),allowHttp=true,settings=settings).use {repo.save("Riconoscimento VOD",it,id)}}
        verify("rereading original directives repairs marker scope without losing IDs"){!io {repo.classificationNeedsRefresh(id)} && "MarcatoKodi" in names() && "DopoKodi" !in names() &&
            io {repo.catalog(PersonalCatalogQuery(source=id,content=null,limit=200)).rows+repo.catalog(PersonalCatalogQuery(source=id,content=null,offset=200,limit=200)).rows}.associate {it.name to it.id}==before}
        closeManagement();click("Video");click("Mostra tutto: Dalle tue liste")
        val expectedVideos=names().size
        await("recognized VOD is available in the broad catalog"){main {visible().filterIsInstance<RecyclerView>().any {it.adapter?.itemCount==expectedVideos}}}
        capture("03-videos")
        val videos=main {content.findViewWithTag<RecyclerView>("video_grid_results")}
        await("first recognized VOD is rendered"){main {videos.findViewHolderForAdapterPosition(0)!=null}}
        main {videos.findViewHolderForAdapterPosition(0)!!.itemView.performClick()}
        click("Riprendi")
        val player=main {activity.javaClass.getDeclaredField(if(mobile)"player" else "tvPlayer").apply {isAccessible=true}.get(activity) as TvPlayer}
        await("a duration-minus-one explicit VOD plays in VOD mode"){main {player.snapshot.channel?.name=="Esplicito" && player.snapshot.mode==PlaybackMode.VOD && player.snapshot.playing}}
        back();back();await("return from recognized VOD restores its catalog"){main {videos.isShown}}
        return JSONObject().put("passed",true).put("checks",JSONArray(checks)).put("mode",if(mobile)"phone" else "tv").put("source",id)
    }
}
