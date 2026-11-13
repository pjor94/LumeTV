package it.lumetv.app.playlists

import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.ui.ChannelAdapter
import it.lumetv.app.ui.PersonalCatalogPanels
import it.lumetv.app.ui.CatalogPanelHost
import it.lumetv.app.ui.VideoCatalogPanel
import it.lumetv.app.ui.VideoPosterAdapter
import it.lumetv.app.ui.PersonalPlaylistsScreen
import it.lumetv.app.ui.mobile.MobileChannelAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.lifecycleScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Drives the real recycled catalogs on disposable emulators, including cross-block D-pad focus. */
object ContinuousCatalogInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext
        val root=requireNotNull(args.getString("fixtureRoot"))
        require(Uri.parse(root).host=="10.0.2.2")
        val mobile=args.getString("mode")=="phone"
        fun <T> main(block:()->T):T {val r=AtomicReference<Result<T>>();runner.runOnMainSync {r.set(runCatching(block))};return r.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        val checks=mutableListOf<String>()
        fun record(label:String){checks+=label;File(context.cacheDir,"continuous-progress.json").writeText(JSONArray(checks).toString())}
        fun verify(label:String,block:()->Boolean){check(block()){label};record(label)}
        fun await(label:String,block:()->Boolean){val end=System.currentTimeMillis()+30_000;while(System.currentTimeMillis()<end){if(block()){record(label);return};Thread.sleep(60)}
            runner.uiAutomation.takeScreenshot()?.let {image->File(context.cacheDir,"continuous-failure.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()};error(label)}
        val repo=PersonalPlaylistRepository.get(context)
        val source=io {
            repo.local();repo.state.value.forEach {repo.delete(it.id)}
            val content=buildString {
                appendLine("#EXTM3U")
                for(i in 1..5650) {
                    appendLine("#EXTINF:${if(i>5000)120 else -1} tvg-id=\"C$i\" tvg-chno=\"$i\" group-title=\"Gruppo ${(i%300).toString().padStart(3,'0')}\",${if(i>5000)"Video" else "Canale"} ${i.toString().padStart(4,'0')}")
                    appendLine("$root/video.mp4?id=$i")
                }
            }
            repo.prepareFile(content.toByteArray(),allowHttp=true).use {repo.save("Scorrimento continuo",it)}
        }
        context.getSharedPreferences("lume",0).edit().putString("catalog_source",source).remove("last_channel").apply()
        var activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList()
        var content=main {activity.findViewById<View>(android.R.id.content)}
        fun views()=all(content).filter {it.isShown}
        fun catalog()=views().filterIsInstance<RecyclerView>().single {it.adapter is ChannelAdapter || it.adapter is MobileChannelAdapter}
        fun row(rv:RecyclerView,p:Int)=when(val a=rv.adapter){is ChannelAdapter->a.channelAt(p);is MobileChannelAdapter->a.itemAt(p)?.channel;else->null}
        fun cached(rv:RecyclerView)=when(val a=rv.adapter){is ChannelAdapter->a.catalog?.cachedRows;is MobileChannelAdapter->a.catalog?.cachedRows;else->null} ?: 0
        fun text(v:View,value:String)=all(v).filterIsInstance<TextView>().any {it.text.toString().contains(value)}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(500);runner.waitForIdleSync();runner.uiAutomation.takeScreenshot()?.let {bitmap->
            File(context.cacheDir,"continuous-catalog").apply {mkdirs()}.resolve("$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        fun match(view:View,label:String)=view.isClickable && view !is EditText && (view is TextView && view.text.toString()==label || view.contentDescription?.toString()?.let {it==label || it.startsWith("$label:") || it.startsWith("$label,") }==true)
        fun click(label:String) {
            if(label.startsWith("Mostra tutto:")) {
                val target=label.substringAfter(":").trim()
                await("collection $target is available"){main {content.findViewWithTag<RecyclerView>("video_home")?.let {(it.adapter as? it.lumetv.app.ui.VideoShelfAdapter)?.rows?.any {row->row.title==target}}==true}}
                main {if(mobile){content.isFocusableInTouchMode=true;content.requestFocus()};val home=content.findViewWithTag<RecyclerView>("video_home");val index=(home.adapter as it.lumetv.app.ui.VideoShelfAdapter).rows.indexOfFirst {it.title==target};(home.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index,0)}
                runner.waitForIdleSync()
            }
            await("action $label is available"){main {views().any {match(it,label)}}}
            main {views().last {match(it,label)}.performClick()}
        }
        await("catalog exposes all 5000 positions without manual pages"){main {runCatching {catalog().adapter?.itemCount==5000 && row(catalog(),0)?.name=="Canale 0001"}.getOrDefault(false)}}
        var rv=main {catalog()}
        if(!mobile && context.resources.configuration.fontScale<=1f)verify("compact TV header leaves two complete card rows"){main {
            rv.findViewHolderForAdapterPosition(7)?.itemView?.let {it.bottom<=rv.height-rv.paddingBottom}==true
        }}
        verify("pagination controls are absent"){main {views().none {it.contentDescription?.toString() in setOf("Pagina precedente","Pagina successiva") || it is TextView && it.text.toString().startsWith("Pagina ")}}}
        capture("00-catalog")
        for(position in listOf(190,200,400,650,2000,4998,400,0)) {
            main {if(!mobile)views().filterIsInstance<TextView>().first {it.text.toString()=="Opzioni"}.requestFocus() else {content.isFocusableInTouchMode=true;content.requestFocus()};(rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position,0)}
            await("position $position loads in the same continuous list"){main {rv.findViewHolderForAdapterPosition(position)?.itemView?.let {text(it,"Canale ${(position+1).toString().padStart(4,'0')}")}==true}}
            verify("cache stays bounded at position $position"){main {cached(rv)<=600}}
        }
        main {(rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(198,0)}
        await("boundary row is rendered"){main {rv.findViewHolderForAdapterPosition(198)?.itemView?.let {text(it,"Canale 0199")}==true}}
        if(!mobile) {
            main {rv.findViewHolderForAdapterPosition(198)!!.itemView.requestFocus()}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad crosses block 200 retaining focus on a channel"){main {rv.findContainingViewHolder(activity.currentFocus ?: rv)?.bindingAdapterPosition?.let {it>=200}==true}}
            val focused=main {rv.findContainingViewHolder(activity.currentFocus!!)!!.bindingAdapterPosition}
            Thread.sleep(300)
            verify("asynchronous row binding preserves the focused position"){main {rv.findContainingViewHolder(activity.currentFocus ?: rv)?.bindingAdapterPosition==focused}}
        } else {
            repeat(3){main {rv.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null)};Thread.sleep(300)}
            verify("phone scroll continues through the block boundary"){main {(rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()>200}}
        }
        capture("01-boundary")
        main {(rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(650,0)}
        await("off-window channel becomes selectable"){main {row(rv,650)?.name=="Canale 0651" && rv.findViewHolderForAdapterPosition(650)!=null}}
        if(!mobile) {
            main {rv.findViewHolderForAdapterPosition(648)!!.itemView.requestFocus()}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            await("Left from a deep first-column card reaches filters"){main {activity.currentFocus?.contentDescription?.toString()?.startsWith("Lista:")==true}}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("Down from filters returns to the same deep card"){main {rv.findContainingViewHolder(activity.currentFocus ?: rv)?.bindingAdapterPosition==648}}
        }
        main {rv.findViewHolderForAdapterPosition(650)!!.itemView.apply {requestFocus();performClick()}}
        var player=main {activity.javaClass.getDeclaredField(if(mobile)"player" else "tvPlayer").apply {isAccessible=true}.get(activity) as TvPlayer}
        await("off-window channel plays local media"){main {player.snapshot.channel?.name=="Canale 0651" && player.snapshot.playing}}
        main {activity.javaClass.getDeclaredMethod(if(mobile)"showChannels" else "showLiveChannels").apply {isAccessible=true}.invoke(activity)}
        val liveList=main {
            val picker=activity.javaClass.getDeclaredField(if(mobile)"channelsDialog" else "liveChannels").apply {isAccessible=true}.get(activity)
            val view=if(mobile)(picker as android.app.Dialog).window!!.decorView else picker as View
            all(view).filterIsInstance<RecyclerView>().single()
        }
        await("personal player uses its native picker with all 5000 channels"){main {liveList.adapter?.itemCount==5000 && liveList.findViewHolderForAdapterPosition(650)?.itemView?.let {text(it,"Canale 0651")}==true}}
        main {(liveList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(750,0)}
        await("personal player picker scrolls beyond the preview"){main {liveList.findViewHolderForAdapterPosition(750)?.itemView?.let {text(it,"Canale 0751")}==true}}
        if(mobile)main {(activity.javaClass.getDeclaredField("channelsDialog").apply {isAccessible=true}.get(activity) as android.app.Dialog).dismiss()}
        else main {activity.onBackPressedDispatcher.onBackPressed()}
        main {activity.onBackPressedDispatcher.onBackPressed()}
        await("return from player restores the same catalog position"){main {rv.isShown && rv.findViewHolderForAdapterPosition(650)?.itemView?.isShown==true}}
        if(!mobile)await("return from player restores D-pad focus"){main {rv.findContainingViewHolder(activity.currentFocus ?: rv)?.bindingAdapterPosition==650}}
        capture("02-return")
        val monitor=runner.addMonitor(activity.javaClass.name,null,false)
        main {activity.recreate()}
        activity=monitor.waitForActivityWithTimeout(15_000) as? ComponentActivity ?: error("Activity recreation timed out")
        runner.removeMonitor(monitor)
        content=main {activity.findViewById(android.R.id.content)}
        player=main {activity.javaClass.getDeclaredField(if(mobile)"player" else "tvPlayer").apply {isAccessible=true}.get(activity) as TvPlayer}
        await("recreated catalog restores the full result count"){main {runCatching {catalog().adapter?.itemCount==5000}.getOrDefault(false)}}
        rv=main {catalog()}
        await("recreated catalog retains its off-window scroll position"){main {rv.findViewHolderForAdapterPosition(650)?.itemView?.isShown==true}}
        if(!mobile)await("recreated TV restores the selected channel focus"){main {rv.findContainingViewHolder(activity.currentFocus ?: rv)?.bindingAdapterPosition==650}}
        capture("02-recreated")
        if(mobile) {
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("landscape retains the continuous catalog position"){main {activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && content.width>content.height && rv.findViewHolderForAdapterPosition(650)?.itemView?.isShown==true}}
            capture("02-landscape")
            verify("landscape leaves a complete channel row with enlarged text"){main {rv.findViewHolderForAdapterPosition(650)?.itemView?.let {it.top>=rv.paddingTop && it.bottom<=rv.height-rv.paddingBottom}==true}}
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait retains the continuous catalog position"){main {activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT && content.height>content.width && rv.findViewHolderForAdapterPosition(650)?.itemView?.isShown==true}}
        }
        click("Radio");click("Canali")
        await("returning to Canali remembers its separate scroll context"){main {rv.findViewHolderForAdapterPosition(650)?.itemView?.isShown==true}}
        if(mobile)main {views().filterIsInstance<EditText>().single().setText("0651")}
        else {
            click("Cerca")
            main {views().filterIsInstance<EditText>().single().setText("0651")}
            click("Cerca")
        }
        await("active catalog search filters the full source"){main {rv.adapter?.itemCount==1 && row(rv,0)?.name=="Canale 0651"}}
        verify("the active query remains visible in the catalog"){main {views().filterIsInstance<TextView>().any {it.text.toString().contains("0651") && (it is EditText || it.text.toString().contains("Ricerca") || it.text.toString().startsWith("Cerca:"))}}}
        click("Radio");click("Canali")
        await("returning to Canali restores its query"){main {rv.adapter?.itemCount==1 && row(rv,0)?.name=="Canale 0651"}}
        click("Azzera filtri")
        await("reset clears query and group and restores the source"){main {rv.adapter?.itemCount==5000 && row(rv,0)?.name=="Canale 0001"}}
        // Real group and video selectors, using the same component as the production actions.
        var selected=""
        fun panel()=content.findViewWithTag<CatalogPanelHost>("lume_catalog_panels")
        main {PersonalCatalogPanels(activity,activity.lifecycleScope).group(repo,it.lumetv.app.data.PersonalCatalogQuery(source=source)){selected=it}}
        await("group selector exposes all 301 positions"){main {panel()?.let {all(it).filterIsInstance<RecyclerView>().singleOrNull()?.adapter?.itemCount==301}==true}}
        val groups=main {all(panel()).filterIsInstance<RecyclerView>().single()}
        main {(groups.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(299,0)}
        await("group selector scrolls beyond its 50-row blocks"){main {groups.findViewHolderForAdapterPosition(299)?.itemView?.let {text(it,"Gruppo 298")}==true}}
        capture("03-groups")
        main {groups.findViewHolderForAdapterPosition(299)!!.itemView.performClick()}
        verify("off-window group selection invokes its action"){selected=="Gruppo 298"}
        fun videoGrid()=content.findViewWithTag<RecyclerView>("video_grid_results")
        fun videoRoot()=content.findViewWithTag<View>(VideoCatalogPanel.TAG)
        click("Video")
        await("Video home is integrated with the catalog shell"){main {videoRoot()?.isShown==true && content.findViewWithTag<RecyclerView>("video_home")?.adapter?.itemCount?.let {it>0}==true}}
        verify("Video is available even though the first 200 source entries are live"){repo.videoSources.value==setOf(source) && repo.cached().none {it.isVod}}
        click("Filtri");click("Tutte le liste");click("Scorrimento continuo")
        await("return from source choice restores Video home"){main {content.findViewWithTag<RecyclerView>("video_home")?.isShown==true}}
        click("Mostra tutto: Dalle tue liste")
        await("video collection exposes all 650 positions"){main {videoGrid()?.adapter?.itemCount==650}}
        click("Cerca")
        val videoSearch=main {views().filterIsInstance<EditText>().single {it.contentDescription=="Cerca un video"}}
        main {videoSearch.setText("5650")}
        await("video search queries the full database"){main {videoGrid()?.let {(it.adapter as VideoPosterAdapter).catalog?.peek(0)?.title=="Video 5650" && it.adapter?.itemCount==1}==true}}
        main {videoSearch.setText("")}
        await("clearing video search restores the home collections"){main {content.findViewWithTag<RecyclerView>("video_home")?.isShown==true}}
        main {activity.onBackPressedDispatcher.onBackPressed()}
        click("Mostra tutto: Dalle tue liste")
        await("opening the full collection restores all results"){main {videoGrid()?.adapter?.itemCount==650}}
        var videos=main {videoGrid()}
        main {if(!mobile)views().filterIsInstance<TextView>().first {it.text.toString()=="Cerca"}.requestFocus();(videos.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(649,0)}
        await("video grid reaches its final item without page controls"){main {videos.findViewHolderForAdapterPosition(649)?.itemView?.let {text(it,"Video 5650")}==true}}
        verify("VOD display cache remains bounded"){main {(videos.adapter as VideoPosterAdapter).catalog!!.cachedRows<=150}}
        capture("04-videos")
        main {videos.findViewHolderForAdapterPosition(649)!!.itemView.apply {requestFocus();performClick()}}
        click("Guarda")
        await("last video remains playable"){main {player.snapshot.channel?.name=="Video 5650" && player.snapshot.playing}}
        main {activity.onBackPressedDispatcher.onBackPressed()}
        await("Back from VOD restores its title details"){main {content.findViewWithTag<View>("video_detail")?.isShown==true}}
        main {activity.onBackPressedDispatcher.onBackPressed()}
        await("Back from details restores the grid and position"){main {videos.findViewHolderForAdapterPosition(649)?.itemView?.isShown==true}}
        main {activity.onBackPressedDispatcher.onBackPressed();activity.onBackPressedDispatcher.onBackPressed()}
        await("Back closes Video through ordinary page navigation"){main {videoRoot()==null}}
        click("Video");click("Mostra tutto: Dalle tue liste")
        await("reopening a collection remembers its own scroll position"){main {videoGrid()?.findViewHolderForAdapterPosition(649)?.itemView?.isShown==true}}
        val videoMonitor=runner.addMonitor(activity.javaClass.name,null,false)
        main {activity.recreate()}
        activity=videoMonitor.waitForActivityWithTimeout(15_000) as? ComponentActivity ?: error("Video recreation timed out")
        runner.removeMonitor(videoMonitor);content=main {activity.findViewById(android.R.id.content)}
        await("recreated Video restores its route and off-window position"){main {videoGrid()?.let {it.adapter?.itemCount==650 && it.findViewHolderForAdapterPosition(649)?.itemView?.isShown==true}==true}}
        videos=main {videoGrid()};capture("04-video-recreated")
        if(mobile) {
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("landscape Video leaves a complete poster row"){main {activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && (videos.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()>=0}}
            capture("04-video-landscape")
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait Video retains its position"){main {activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT && videos.findViewHolderForAdapterPosition(649)?.itemView?.isShown==true}}
        }
        main {activity.onBackPressedDispatcher.onBackPressed();activity.onBackPressedDispatcher.onBackPressed()}
        rv=main {catalog()}
        // The real management route must recycle its channel-matching choices too.
        click("Opzioni");click("Le mie liste");click("Scorrimento continuo");click("Guida e archivio");click("Associa canali")
        val screen=main {all(content).filterIsInstance<PersonalPlaylistsScreen>().single()}
        await("guide matching list uses the entire source"){main {all(screen).filterIsInstance<RecyclerView>().any {it.isShown && it.adapter?.itemCount==5000}}}
        val matching=main {all(screen).filterIsInstance<RecyclerView>().single {it.isShown}}
        main {(matching.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(201,0)}
        await("guide matching scrolls across multiple blocks"){main {matching.findViewHolderForAdapterPosition(201)?.itemView?.let {text(it,"Canale 0202")}==true}}
        capture("05-matching")
        main {matching.findViewHolderForAdapterPosition(201)!!.itemView.apply {requestFocus();performClick()}}
        main {screen.handleBack()}
        await("return from matching detail keeps the list position"){main {matching.isShown && matching.findViewHolderForAdapterPosition(201)?.itemView?.isShown==true}}
        if(!mobile)await("matching detail restores the focused row"){main {matching.findContainingViewHolder(activity.currentFocus ?: matching)?.bindingAdapterPosition==201}}
        main {screen.hide()}
        main {activity.onBackPressedDispatcher.onBackPressed()}
        io {repo.setEnabled(source,false)}
        await("disabling the last VOD source hides Video"){main {views().none {match(it,"Video")}}}
        io {repo.setEnabled(source,true)}
        await("reenabling the VOD source restores Video"){main {views().any {match(it,"Video")}}}
        return JSONObject().put("passed",true).put("mode",if(mobile)"phone" else "tv").put("checks",JSONArray(checks))
            .put("source",source).put("cacheRows",main {cached(rv)}).put("pssKiB",Debug.getPss())
    }
}
