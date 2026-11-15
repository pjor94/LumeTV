package it.lumetv.app.playlists

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.*
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaybackTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Own encrypted files, real app pages and document-result contracts on disposable emulators. */
object BackupInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val mobile=args.getString("mode")=="phone"
        val fixture=requireNotNull(args.getString("fixtureRoot"));require(Uri.parse(fixture).host=="10.0.2.2")
        val checks=mutableListOf<String>();val captures=File(context.cacheDir,"backup-inspection").apply {mkdirs()}
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun <T> io(block:suspend ()->T)=runBlocking(Dispatchers.IO){block()}
        fun record(label:String){checks+=label;File(captures,"progress.json").writeText(JSONArray(checks).toString())}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(if(name=="04-landscape")1100 else 200);runner.uiAutomation.takeScreenshot()?.let {bitmap->
            File(captures,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        fun await(label:String,test:()->Boolean){val until=SystemClock.elapsedRealtime()+45_000
            while(SystemClock.elapsedRealtime()<until){if(main(test)){record(label);return};Thread.sleep(90)};capture("failure");error(label)}
        fun verify(label:String,test:()->Boolean){if(!main(test)){capture("failure");error(label)};record(label)}
        val repo=PersonalPlaylistRepository.get(context);val history=PersonalPlaybackStore.get(context)
        val prefs=context.getSharedPreferences("lume",0);val layout=ChannelPersonalization.get(context)
        io {repo.local();repo.state.value.toList().forEach {repo.delete(it.id)}}
        val liveInput=("#EXTM3U\n#EXTINF:-1 type=\"live\",Prima diretta\n$fixture/video.mp4?a=1\n"+
            "#EXTINF:-1 radio=\"true\",Radio di prova\n$fixture/video.mp4?a=2\n"+
            "#EXTINF:-1 type=\"live\",Seconda diretta\n$fixture/video.mp4?a=3\n").toByteArray()
        val vodInput=("#EXTM3U\n#EXTINF:-1 type=\"movie\",Film di prova\n$fixture/video.mp4?b=1\n"+
            "#EXTINF:-1 type=\"episode\",Orbita S01E01\n$fixture/video.mp4?b=2\n"+
            "#EXTINF:-1 type=\"episode\",Orbita S01E02\n$fixture/video.mp4?b=3\n").toByteArray()
        val settings=PlaylistSettings(guide=PersonalGuideSettings(enabled=false))
        val a=io {repo.prepareFile(liveInput,allowHttp=true,settings=settings).use {repo.save("Dirette di prova 日本語",it)}}
        val b=io {repo.prepareFile(vodInput,allowHttp=true,settings=settings).use {repo.save("Cinema e serie da conservare",it)}}
        val live=io {repo.catalog(PersonalCatalogQuery(source=a,content=null)).rows}
        val videos=io {repo.videos(VideoQuery(source=b,collapseSeries=false)).rows}
        val movie=videos.first {it.title=="Film di prova"};val episode=videos.first {it.title=="Orbita S01E01"}
        val series=io {repo.videos(VideoQuery(source=b)).rows}.first {it.isSeries}
        val archive=PlaybackTarget(live[0].id,PlaybackMode.ARCHIVE,"evento-di-prova")
        io {history.write(b,PlaybackTarget(movie.id,PlaybackMode.VOD),42_000,180_000);history.hide(b,PlaybackTarget(movie.id,PlaybackMode.VOD),true)
            history.write(b,PlaybackTarget(episode.id,PlaybackMode.VOD),120_000,180_000,true);history.write(a,archive,50_000,180_000)}
        main {
            prefs.edit().putStringSet("favorites",setOf(live[0].id,live[1].id,movie.id)).putStringSet("video_saved_series",setOf(series.key))
                .putString("audio_language","en").putString("subtitle_mode","OFF").putBoolean("data_saver",true).commit()
            layout.hide(live[1]);layout.reorder(listOf(live[1].id,live[0].id),listOf(live[0].id,live[1].id),layout.revision.value,mapOf(live[0].id to a,live[1].id to a))
        }
        var activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun field(name:String)=activity.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(activity)
        fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {all(view.getChildAt(it))} else emptyList()
        fun content()=activity.findViewById<View>(android.R.id.content)
        fun visible()=all(content()).filter {it.isShown}
        fun text(value:String)=visible().filterIsInstance<TextView>().any {it.text.toString()==value || it.text.toString().startsWith(value)}
        fun perform(view:View):Boolean {view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true);if(!mobile)view.requestFocusFromTouch();view.performClick();return true}
        fun click(label:String){await("action: "+label){visible().lastOrNull {it.isClickable && it.isEnabled && (it is TextView && it.text.toString()==label || it.contentDescription?.toString()==label || it.contentDescription?.toString()?.startsWith(label+":")==true)}?.let(::perform)==true}}
        fun clickTag(tag:String){await("action: "+tag){content().findViewWithTag<View>(tag)?.takeIf {it.isShown && it.isEnabled}?.let(::perform)==true}}
        fun enter(tag:String,value:String){main {(content().findViewWithTag<EditText>(tag) ?: error(tag)).setText(value)}}
        fun backupShowing()=(field("backup") as View).isShown
        fun document(action:String,result:Int,file:File):Instrumentation.ActivityMonitor {
            val filter=IntentFilter(action).apply {addCategory(Intent.CATEGORY_OPENABLE);addDataType("*/*")}
            return runner.addMonitor(filter,Instrumentation.ActivityResult(result,Intent().setData(Uri.fromFile(file))),true)
        }
        val saved=File(context.cacheDir,"inspection-saved.lumebackup").apply {delete()}
        await("app options are available"){visible().any {it.isClickable && (it.contentDescription?.toString()=="Opzioni" || it is TextView && it.text.toString()=="Opzioni")}}
        click("Opzioni");click("Backup e ripristino")
        await("backup opens inside the existing app window"){backupShowing() && text("Crea backup") && text("Ripristina backup")}
        capture("00-overview")
        click("Crea backup");capture("01-password")
        enter("backup_password","Password di prova 2026");enter("backup_confirm","Una password diversa")
        clickTag("backup_create")
        await("mismatched export passwords receive a recoverable inline error"){text("Le password non coincidono")}
        enter("backup_confirm","Password di prova 2026")
        val exportMonitor=document(Intent.ACTION_CREATE_DOCUMENT,Activity.RESULT_OK,saved)
        try {
            if(!mobile) {
                main {content().findViewWithTag<View>("backup_create")!!.requestFocusFromTouch()}
                runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            } else clickTag("backup_create")
            await("the document contract saves an encrypted backup"){text("Backup salvato") && saved.isFile && saved.length()>32}
            verify("document creation went through the Android contract"){exportMonitor.hits>0}
            verify("the exported file does not contain playlist addresses"){!saved.readBytes().toString(Charsets.ISO_8859_1).contains(fixture)}
        }finally {runner.removeMonitor(exportMonitor)}
        click("Fine");await("export returns to app options"){!backupShowing()}
        io {repo.rename(a,"Sorgente attuale");repo.delete(b);history.write(a,archive,75_000,180_000)
            repo.prepareFile(vodInput,allowHttp=true,settings=settings).use {repo.save("Cinema e serie da conservare",it)}}
        main {
            prefs.edit().putStringSet("favorites",setOf(live[2].id)).remove("channel_layout").putString("audio_language","it").putBoolean("data_saver",false).commit()
            layout.reorder(listOf(live[2].id),listOf(live[2].id),layout.revision.value,mapOf(live[2].id to a))
        }
        fun openSaved() {
            click("Backup e ripristino")
            val monitor=document(Intent.ACTION_OPEN_DOCUMENT,Activity.RESULT_OK,saved)
            try {click("Ripristina backup");await("the selected encrypted file asks for its password"){text("Apri backup")};verify("file selection uses the Android document contract"){monitor.hits>0}}
            finally {runner.removeMonitor(monitor)}
        }
        openSaved();enter("backup_password","Password errata 2026");clickTag("backup_verify")
        await("an incorrect password allows another attempt"){text("Password errata o backup danneggiato")}
        verify("an invalid password leaves current data untouched"){repo.state.value.size==2 && repo.state.value.any {it.name=="Sorgente attuale"} && prefs.getString("audio_language",null)=="it"}
        enter("backup_password","Password di prova 2026");clickTag("backup_verify")
        await("a verified backup offers category and individual list selection"){content().findViewWithTag<View>("backup_restore")?.isShown==true}
        verify("the existing source is explicitly kept in preview"){(content().findViewWithTag<CheckBox>("backup_source_"+a)?.isEnabled==false)}
        capture("02-preview")
        if(mobile) {
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("rotation retains the verified preview and selected categories"){activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && content().findViewWithTag<CheckBox>("backup_watch")?.isChecked==true}
            capture("04-landscape")
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait retains the preview"){activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT && text("Scegli cosa ripristinare")}
        } else {
            main {content().findViewWithTag<View>("backup_personal")!!.requestFocusFromTouch()}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            verify("remote focus stays within backup pages"){activity.currentFocus?.let {it in all(field("backup") as View)}==true}
        }
        listOf("backup_lists","backup_personal","backup_watch","backup_preferences").forEach(::clickTag)
        clickTag("backup_restore")
        await("an empty selection cannot start a restore"){text("Scegli almeno una categoria")}
        listOf("backup_lists","backup_personal","backup_watch").forEach(::clickTag)
        clickTag("backup_restore")
        await("selected categories restore successfully"){text("Ripristino completato")}
        capture("03-restored")
        verify("only the missing source was added and its name collision is resolved"){repo.state.value.size==3 && repo.state.value.first {it.id==a}.name=="Sorgente attuale" && repo.state.value.first {it.id==b}.name.endsWith("(backup)")}
        verify("movie resume and Continue visibility are preserved"){io {history.read(b,PlaybackTarget(movie.id,PlaybackMode.VOD))}==WatchPosition(42_000,180_000,false,true)}
        verify("watched episodes and saved series are restored"){io {history.read(b,PlaybackTarget(episode.id,PlaybackMode.VOD))}.completed && series.key in prefs.getStringSet("video_saved_series",emptySet()).orEmpty()}
        verify("the archive position already on the device wins"){io {history.read(a,archive)}.position==75_000L}
        verify("favorites merge without removing the current favorite"){prefs.getStringSet("favorites",emptySet()).orEmpty().containsAll(listOf(live[2].id,live[0].id,live[1].id,movie.id))}
        verify("current favorite order wins while hidden channels return"){layout.order.firstOrNull()==live[2].id && live[1].id in layout.hiddenIds}
        verify("an unchecked preference category remains unchanged"){prefs.getString("audio_language",null)=="it" && !prefs.getBoolean("data_saver",true)}
        click("Fine");await("restore returns to options"){!backupShowing()}
        openSaved();enter("backup_password","Password di prova 2026");clickTag("backup_verify")
        await("the same backup can be verified again"){content().findViewWithTag<View>("backup_restore")?.isShown==true}
        clickTag("backup_restore");await("a repeated restore completes"){text("Ripristino completato")}
        verify("repeat restore creates no duplicate lists and can apply chosen preferences"){repo.state.value.size==3 && prefs.getString("audio_language",null)=="en" && prefs.getBoolean("data_saver",false)}
        click("Fine");await("the repeated restore closes"){!backupShowing()}
        click("Backup e ripristino");click("Crea backup")
        enter("backup_password","Password di prova 2026");enter("backup_confirm","Password di prova 2026")
        val cancelMonitor=document(Intent.ACTION_CREATE_DOCUMENT,Activity.RESULT_CANCELED,saved)
        try {clickTag("backup_create");await("cancelling file selection is recoverable"){text("Esportazione annullata")}}
        finally {runner.removeMonitor(cancelMonitor)}
        verify("temporary export files are removed after chooser cancellation"){File(context.cacheDir,"backup-work").listFiles().orEmpty().none {it.name.startsWith("export-")}}
        main {activity.onBackPressedDispatcher.onBackPressed();activity.onBackPressedDispatcher.onBackPressed()}
        await("Back restores options after cancellation"){!backupShowing()}
        verify("closed previews leave no decrypted backup directories"){File(context.cacheDir,"backup-work").listFiles().orEmpty().none {it.isDirectory}}
        main {activity.finish()};saved.delete()
        return JSONObject().put("passed",true).put("mode",if(mobile)"phone" else "tv").put("checks",JSONArray(checks))
    }
}
