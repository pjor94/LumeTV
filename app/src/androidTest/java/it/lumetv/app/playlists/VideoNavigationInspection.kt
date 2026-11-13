package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PlaylistSettings
import it.lumetv.app.ui.VideoCatalogPanel
import it.lumetv.core.PlaylistContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real conditional navigation, with owned synthetic lists on disposable emulators. */
object VideoNavigationInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val mobile=args.getString("mode")=="phone"
        val fixture=requireNotNull(args.getString("fixtureRoot"));require(Uri.parse(fixture).host=="10.0.2.2")
        val checks=mutableListOf<String>()
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun record(label:String){checks+=label;File(context.cacheDir,"video-library-progress.json").writeText(JSONArray(checks).toString())}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(200);runner.uiAutomation.takeScreenshot()?.let {bitmap->
            File(context.cacheDir,"video-library").apply {mkdirs()}.resolve("$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        fun verify(label:String,test:()->Boolean){if(!test()){capture("failure");error(label)};record(label)}
        fun await(label:String,test:()->Boolean){val end=System.currentTimeMillis()+30_000;while(System.currentTimeMillis()<end){if(test()){record(label);return};Thread.sleep(50)};capture("failure");error(label)}
        val repo=PersonalPlaylistRepository.get(context)
        io {repo.local();repo.state.value.toList().forEach {repo.delete(it.id)}}
        fun playlist(vod:Boolean)=buildString {
            appendLine("#EXTM3U")
            repeat(300){i->appendLine("#EXTINF:-1 type=\"live\",Diretta $i");appendLine("$fixture/video.mp4?live=$i")}
            if(vod)repeat(3){i->appendLine("#EXTINF:-1 type=\"movie\" group-title=\"Film\" year=\"2024\" plot=\"Una trama di prova.\",Pellicola $i");appendLine("$fixture/video.mp4?movie=$i")}
        }.toByteArray()
        io {repo.prepareFile(playlist(false),allowHttp=true).use {repo.save("Solo dirette",it)}}
        context.getSharedPreferences("lume",0).edit().putString("catalog_source","builtin").remove("last_channel").commit()
        var activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        var content=main {activity.findViewById<View>(android.R.id.content)}
        fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList()
        fun visible()=all(content).filter {it.isShown}
        fun bar()=content.findViewWithTag<ViewGroup>(if(mobile)"mobile_navigation" else "tv_navigation")
        fun tab(label:String)=bar()?.findViewWithTag<View>(label)
        fun selected()=bar()?.let {v->(0 until v.childCount).map {v.getChildAt(it)}.filter {it.isSelected}.map {it.tag?.toString()}}.orEmpty()
        fun video()=content.findViewWithTag<View>(VideoCatalogPanel.TAG)?.isShown==true
        fun grid()=content.findViewWithTag<RecyclerView>("video_grid_results")?.takeIf {it.isShown}
        fun clickTab(label:String){await("navigation $label is reachable"){main {tab(label)?.takeIf {it.isShown && it.isEnabled}?.let {if(!mobile)it.requestFocus();it.performClick()}==true}}}
        fun click(label:String){await("action $label is reachable"){main {visible().filterIsInstance<TextView>().lastOrNull {it.isClickable && it.isEnabled && it.text.toString()==label}?.let {if(!mobile)it.requestFocus();it.performClick()}==true}}}
        fun back()=main {activity.onBackPressedDispatcher.onBackPressed()}
        await("live-only lists leave Video out of the main bar"){main {bar()!=null && tab("Video")==null && selected()==listOf("Canali")}}
        capture("00-no-video")
        val source=io {repo.prepareFile(playlist(true),allowHttp=true).use {repo.save("Cinema",it)}}
        await("VOD beyond the first 200 entries adds Video to the main bar"){main {tab("Video")?.isShown==true}}
        verify("Video follows Canali and precedes Radio"){main {val nav=bar()!!;(0 until 3).map {nav.getChildAt(it).tag}==listOf("Canali","Video","Radio")}}
        verify("the channel page contains no duplicate Video shortcut"){main {
            visible().filter {it.isClickable && (it.contentDescription?.toString()=="Video" || it is TextView && it.text.toString()=="Video")}==listOf(tab("Video"))
        }}
        fun checkBar(label:String) {
            verify(label+" keeps every navigation control inside the bar"){main {
                val nav=bar()!!;(0 until nav.childCount).map {nav.getChildAt(it)}.filter {it.isShown}.all {it.left>=0 && it.right<=nav.width && it.top>=0 && it.bottom<=nav.height}
            }}
            if(mobile) {
                verify(label+" keeps touch targets at least 48dp wide and high"){main {val nav=bar()!!;val minimum=48*activity.resources.displayMetrics.density-1;(0 until nav.childCount).map {nav.getChildAt(it)}.all {it.width>=minimum && it.height>=minimum}}}
                verify(label+" keeps navigation labels on one complete line"){main {val nav=bar()!!;(0 until nav.childCount).map {nav.getChildAt(it) as ViewGroup}.all {item->val text=item.getChildAt(1) as TextView;!text.isShown || text.layout?.let {it.lineCount==1 && it.getEllipsisCount(0)==0}==true}}}
            } else {
                verify(label+" keeps the brand, bar and clock within the TV header"){main {
                    val header=bar()!!.parent as ViewGroup
                    (0 until header.childCount).map {header.getChildAt(it)}.filter {it.isShown}.all {it.left>=header.paddingLeft && it.right<=header.width-header.paddingRight}
                }}
            }
        }
        runner.waitForIdleSync();checkBar("VOD navigation")
        capture("01-video-tab")
        if(!mobile) {
            main {tab("Canali")!!.requestFocus()};runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad reaches Video from Canali"){main {activity.currentFocus===tab("Video")}}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad reaches Radio after Video"){main {activity.currentFocus===tab("Radio")}}
        } else {
            for(destination in listOf("Preferiti","Guida","Scopri","Canali")) {
                clickTab(destination);await("$destination remains selectable alongside Video"){main {selected()==listOf(destination)}}
                runner.waitForIdleSync();checkBar(destination)
            }
        }
        clickTab("Video")
        await("Video is the sole selected navigation destination"){main {video() && selected()==listOf("Video")}}
        verify("the main Video destination has no header Back control"){main {content.findViewWithTag<View>("video_back")?.isShown!=true}}
        capture("02-video-active")
        click("Cerca")
        main {visible().filterIsInstance<EditText>().single {it.contentDescription=="Cerca un video"}.setText("Pellicola 2")}
        await("Video search shows its matching title"){main {grid()?.adapter?.itemCount==1}}
        if(mobile)verify("a Video collection keeps its header Back control"){main {content.findViewWithTag<View>("video_back")?.isShown==true}}
        clickTab("Radio")
        await("Radio replaces Video and is selected"){main {!video() && selected()==listOf("Radio")}}
        clickTab("Video")
        await("returning to Video restores search and its collection"){main {video() && selected()==listOf("Video") && grid()?.adapter?.itemCount==1 && visible().filterIsInstance<TextView>().any {it.text.toString()=="Ricerca: Pellicola 2"}}}
        val monitor=runner.addMonitor(activity.javaClass.name,null,false);main {activity.recreate()}
        activity=monitor.waitForActivityWithTimeout(15_000) as? ComponentActivity ?: error("Recreation timed out");runner.removeMonitor(monitor)
        content=main {activity.findViewById(android.R.id.content)}
        await("recreation restores Video selection and its current page"){main {video() && selected()==listOf("Video") && grid()?.adapter?.itemCount==1}}
        val results=main {grid()!!};await("the restored poster is ready"){main {results.findViewHolderForAdapterPosition(0)!=null}}
        main {results.findViewHolderForAdapterPosition(0)!!.itemView.performClick()}
        await("title details retain Video selection"){main {content.findViewWithTag<View>("video_detail")?.isShown==true && selected()==listOf("Video")}}
        // Details hide the search controls; return to the grid before editing the retained query.
        if(mobile) {
            verify("title details keep a reachable header Back control"){main {content.findViewWithTag<View>("video_back")?.let {it.isShown && it.performClick()}==true}}
        } else back()
        await("Back from details restores the searched collection"){main {grid()?.adapter?.itemCount==1}}
        click("Cerca")
        verify("the reopened search field retains its query"){main {visible().filterIsInstance<EditText>().single {it.contentDescription=="Cerca un video"}.text.toString()=="Pellicola 2"}}
        // Clear the query, return through the ordinary Back path, and retain the originating bar control.
        main {visible().filterIsInstance<EditText>().single {it.contentDescription=="Cerca un video"}.setText("")}
        await("clearing the query restores the Video home"){main {content.findViewWithTag<View>("video_home")?.isShown==true}}
        verify("returning to the Video home removes the header Back control"){main {content.findViewWithTag<View>("video_back")?.isShown!=true}}
        repeat(2){if(main {video()}){back();runner.waitForIdleSync()}}
        await("Back from Video returns to the previous Radio destination"){main {!video() && selected()==listOf("Radio")}}
        if(!mobile)verify("Back restores remote focus to the Video tab"){main {activity.currentFocus===tab("Video")}}
        if(!mobile) {
            clickTab("Opzioni");clickTab("Video")
            await("the TV Video tab also opens from Options"){main {video() && selected()==listOf("Video")}}
            back()
            await("Back from Video opened in Options restores the Video tab focus"){main {!video() && selected()==listOf("Radio") && activity.currentFocus===tab("Video")}}
            clickTab("Video")
        } else clickTab("Video")
        io {repo.setSettings(source,PlaylistSettings(contentOverride=PlaylistContentKind.LIVE))}
        await("forcing the last VOD list to live removes Video and selects Canali"){main {tab("Video")==null && !video() && selected()==listOf("Canali")}}
        runner.waitForIdleSync()
        if(!mobile)await("removing the focused Video destination leaves remote focus on Canali"){main {activity.currentFocus===tab("Canali")}}
        capture("03-video-hidden")
        io {repo.setSettings(source,PlaylistSettings())}
        await("restoring automatic classification brings the Video tab back"){main {tab("Video")?.isShown==true}}
        val other=io {repo.prepareFile("#EXTM3U\n#EXTINF:-1 type=\"movie\",Altro film\n$fixture/video.mp4?other=1\n".toByteArray(),allowHttp=true).use {repo.save("Secondo cinema",it)}}
        clickTab("Video")
        io {repo.setEnabled(source,false)}
        await("Video stays available while another enabled list contains VOD"){main {tab("Video")?.isShown==true && video() && selected()==listOf("Video")}}
        io {repo.delete(other)}
        await("deleting the last enabled VOD source removes the destination"){main {tab("Video")==null && !video() && selected()==listOf("Canali")}}
        io {repo.setEnabled(source,true)}
        await("reenabling a VOD source restores the bar destination"){main {tab("Video")?.isShown==true}}
        if(mobile) {
            clickTab("Video")
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("landscape keeps Video selected and available"){main {activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && selected()==listOf("Video") && video()}}
            runner.waitForIdleSync();checkBar("Landscape navigation");capture("04-landscape-navigation")
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait restores the full Video navigation"){main {activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT && selected()==listOf("Video")}}
        }
        runner.waitForIdleSync();checkBar("Final navigation");capture("05-final-navigation")
        return JSONObject().put("passed",true).put("mode",if(mobile)"phone" else "tv").put("checks",JSONArray(checks))
    }
}
