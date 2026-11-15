package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.ChannelRepository
import it.lumetv.app.data.PersonalCatalogQuery
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.ui.ChannelManagementScreen
import it.lumetv.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Native touch, D-pad and persistence checks. Executed manually on disposable emulators. */
object ChannelPersonalizationInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val mobile=args.getString("mode")=="phone"
        val count=args.getString("count")?.toInt() ?: 450
        require(count in 450..100_000)
        val checks=mutableListOf<String>()
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun record(label:String){checks+=label;File(context.cacheDir,"channel-editor-progress.json").writeText(JSONArray(checks).toString())}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(if(name=="05-landscape")1000 else 200);runner.uiAutomation.takeScreenshot()?.let {bitmap->
            File(context.cacheDir,"channel-editor").apply {mkdirs()}.resolve("$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        fun await(label:String,test:()->Boolean){val deadline=SystemClock.elapsedRealtime()+30_000
            while(SystemClock.elapsedRealtime()<deadline){if(main(test)){record(label);return};Thread.sleep(70)};capture("failure");error(label)}
        fun verify(label:String,test:()->Boolean){if(!main(test)){capture("failure");error(label)};record(label)}
        val personal=PersonalPlaylistRepository.get(context)
        io {personal.local();personal.state.value.toList().forEach {personal.delete(it.id)}}
        val prefs=context.getSharedPreferences("lume",0)
        main {prefs.edit().remove("channel_layout").putStringSet("favorites",emptySet()).remove("last_channel").commit()}
        val source=io {personal.prepareFile(buildString {
            appendLine("#EXTM3U")
            repeat(count){i->appendLine("#EXTINF:-1 tvg-id=\"live$i\" tvg-chno=\"${i+1}\" group-title=\"Gruppo ${i%3}\",Canale personale $i");appendLine("https://example.org/live/$i")}
            repeat(3){i->appendLine("#EXTINF:-1 radio=\"true\",Radio personale $i");appendLine("https://example.org/radio/$i")}
        }.toByteArray()).use {personal.save("Casa",it)}}
        val cinema=io {personal.prepareFile("#EXTM3U\n#EXTINF:-1 type=\"movie\",Film salvato\nhttps://example.org/movie/1.mp4\n".toByteArray()).use {personal.save("Cinema",it)}}
        val first=io {personal.catalog(PersonalCatalogQuery(source=source,kind=MediaKind.TV,limit=1)).rows.single()}
        val middle=io {personal.catalog(PersonalCatalogQuery(source=source,kind=MediaKind.TV,offset=240,limit=1)).rows.single()}
        val last=io {personal.catalog(PersonalCatalogQuery(source=source,kind=MediaKind.TV,offset=count-1,limit=1)).rows.single()}
        val radio=io {personal.catalog(PersonalCatalogQuery(source=source,kind=MediaKind.RADIO)).rows}
        val movie=io {personal.videos().rows.single()}
        val initial=listOf(first.id,middle.id,last.id)
        main {prefs.edit().putString("catalog_source",source).putStringSet("favorites",(initial+radio.take(2).map {it.id}+movie.id).toSet()).commit()}
        var activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun field(name:String)=activity.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(activity)
        var content=main {activity.findViewById<View>(android.R.id.content)}
        fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {all(view.getChildAt(it))} else emptyList()
        fun click(root:View,label:String){await("action $label"){all(root).filter {it.isShown && it.isClickable && it.isEnabled}.lastOrNull {
            it is TextView && it.text.toString()==label || it.contentDescription?.toString()==label || it.contentDescription?.toString()?.startsWith("$label:")==true
        }?.let {if(!mobile)it.requestFocus();it.performClick()}==true}}
        fun back()=main {activity.onBackPressedDispatcher.onBackPressed()}
        val repo=main {field("repository") as ChannelRepository}
        var editor=main {field("channelManagement") as ChannelManagementScreen}
        fun list()=all(editor).filterIsInstance<RecyclerView>().single()
        fun row(position:Int)=list().findViewHolderForAdapterPosition(position)?.itemView?.findViewWithTag<View>("channel_action")
        fun openRow(position:Int){await("row $position is loaded"){row(position)?.let {view->if(!mobile)view.requestFocus();view.performClick()}==true}}
        fun editorReady(size:Int)=await("editor has $size rows"){editor.isShowing && list().adapter?.itemCount==size && row(0)?.contentDescription?.toString()?.contains("Canale personale")==true}
        await("the catalog and Video destination load"){all(content).any {it.isShown && it.tag=="Video"}}
        if(mobile) {
            val bar=main {content.findViewWithTag<ViewGroup>("mobile_navigation")}
            verify("phone bar has five destinations with Video and no favorites tab"){
                bar.childCount==5 && (0 until bar.childCount).map {bar.getChildAt(it).tag}==listOf("Canali","Video","Radio","Guida","Scopri")
            }
            verify("all three quick filters are visible with advanced filters closed"){
                listOf("Tutti","Preferiti","Recenti").all {label->all(content).any {it.isShown && it is TextView && it.text.toString()==label && it.isClickable}}
            }
            click(content,"Preferiti")
            await("quick favorites reaches channels beyond the catalog preview"){
                all(content).filterIsInstance<RecyclerView>().any {it.isShown && it.adapter?.itemCount==3}
            }
            capture("01-phone-navigation")
            click(content,"Gestisci canali e preferiti")
        } else {click(content,"Opzioni");click(content,"Canali e preferiti")}
        editorReady(3)
        verify("management rows occupy the full list width"){list().findViewHolderForAdapterPosition(0)?.itemView?.width==list().width-list().paddingLeft-list().paddingRight}
        capture("02-favorites")
        openRow(0);click(editor,"Sposta")
        if(mobile)click(editor,"Giù") else runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        back();verify("Back cancels a preview without persisting the order"){repo.personalization.order.isEmpty()}
        openRow(0);click(editor,"Sposta")
        if(mobile){click(editor,"Giù");click(editor,"Salva")}else {
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad moves the selected favorite preview"){editor.javaClass.getDeclaredField("movingIndex").apply {isAccessible=true}.get(editor)==1}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        }
        await("explicit move saves the new favorite order"){repo.personalization.order.take(3)==listOf(middle.id,first.id,last.id)}
        editorReady(3)
        verify("confirming a move returns to the list"){
            (editor.javaClass.getDeclaredField("details").apply {isAccessible=true}.get(editor) as View).visibility==View.GONE
        }
        if(mobile) {
            val before=io {repo.favoriteSequence(MediaKind.TV)}
            val coordinates=main {
                val grip=all(list().findViewHolderForAdapterPosition(0)!!.itemView).first {it.contentDescription?.toString()?.startsWith("Sposta Canale")==true}
                val target=list().findViewHolderForAdapterPosition(1)!!.itemView
                val point=IntArray(2);val destination=IntArray(2);grip.getLocationOnScreen(point);target.getLocationOnScreen(destination)
                floatArrayOf(point[0]+grip.width/2f,point[1]+grip.height/2f,destination[1]+target.height*.75f)
            }
            fun gesture(end:Int) {
                val start=SystemClock.uptimeMillis()
                fun event(action:Int,y:Float,time:Long){MotionEvent.obtain(start,time,action,coordinates[0],y,0).let {runner.sendPointerSync(it);it.recycle()}}
                event(MotionEvent.ACTION_DOWN,coordinates[1],start)
                verify("touch handle starts a move preview") {editor.javaClass.getDeclaredField("moving").apply {isAccessible=true}.get(editor)!=null}
                repeat(12){i->Thread.sleep(30);event(MotionEvent.ACTION_MOVE,coordinates[1]+(coordinates[2]-coordinates[1])*(i+1)/12,SystemClock.uptimeMillis())}
                event(end,coordinates[2],SystemClock.uptimeMillis())
            }
            gesture(MotionEvent.ACTION_UP)
            await("touch drag commits a reordered favorite"){repo.personalization.order.take(3)==listOf(before[1],before[0],before[2])}
            editorReady(3)
            val saved=main {repo.personalization.order}
            gesture(MotionEvent.ACTION_CANCEL);runner.waitForIdleSync();Thread.sleep(400)
            verify("a canceled touch gesture preserves the saved order") {repo.personalization.order==saved && editor.javaClass.getDeclaredField("moving").apply {isAccessible=true}.get(editor)==null}
        }
        openRow(0);click(editor,"Nascondi canale")
        await("hidden favorite leaves the editor without losing its saved heart"){repo.personalization.hidden.size==1 && list().adapter?.itemCount==2}
        val hidden=main {repo.personalization.hidden.single()}
        verify("Video saved title survives hide and reorder"){movie.id in repo.favorites()}
        val absent=io {repo.catalog(PersonalCatalogQuery(source=source,kind=MediaKind.TV,number=if(hidden.id==first.id)1 else 241))}
        verify("numeric selection excludes the hidden channel"){absent.total==0}
        click(editor,"Nascosti")
        await("hidden channels remain findable for restoration"){list().adapter?.itemCount==1}
        capture("03-hidden")
        openRow(0);click(editor,"Ripristina canale")
        await("restoring a hidden channel clears the hidden view"){repo.personalization.hidden.isEmpty() && list().adapter?.itemCount==0}
        click(editor,"Preferiti");editorReady(3)
        val expected=main {repo.personalization.order}
        click(editor,"Radio")
        await("radio favorites are separate from television"){list().adapter?.itemCount==2 && row(0)?.contentDescription?.toString()?.contains("Radio personale")==true}
        click(editor,"TV");editorReady(3)
        openRow(0);click(editor,"Sposta")
        main {editor.cancelPendingMove()}
        verify("background interruption cancels the uncommitted move"){repo.personalization.order==expected}
        click(editor,"Tutti")
        await("all-catalog browsing retains full size with bounded metadata"){list().adapter?.itemCount?.let {it>=count}==true}
        verify("management metadata cache remains bounded to three blocks"){
            val cache=editor.javaClass.getDeclaredField("data").apply {isAccessible=true}.get(editor) as it.lumetv.app.ui.ContinuousCatalog<*>
            cache.cachedRows<=600
        }
        val search=main {all(editor).filterIsInstance<EditText>().single()}
        main {search.setText("Canale personale ${count-1}")}
        await("manager search finds the final channel outside the preview"){list().adapter?.itemCount==1 && row(0)?.contentDescription?.toString()?.contains("Canale personale ${count-1}")==true}
        main {search.setText("")};click(editor,"Preferiti");editorReady(3)
        capture("04-final-editor")
        back();await("closing management restores the catalog or options"){!editor.isShowing}
        if(!mobile)back()
        if(mobile) {
            main {context.startActivity(Intent(context,MobileActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://favorites")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
            await("legacy favorites deep link remains available and highlights Canali"){
                val bar=content.findViewWithTag<ViewGroup>("mobile_navigation")
                bar?.findViewWithTag<View>("Canali")?.isSelected==true && all(content).filterIsInstance<TextView>().any {it.isShown && it.text.toString()=="Preferiti"}
            }
            io {personal.setEnabled(cinema,false)}
            await("phone bar returns to four destinations when Video is unavailable"){content.findViewWithTag<ViewGroup>("mobile_navigation")?.childCount==4}
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("quick filters remain reachable in landscape"){activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && all(content).any {it.isShown && it.contentDescription=="Gestisci canali e preferiti"}}
            capture("05-landscape")
        }
        main {activity.finish()}
        return JSONObject().put("passed",true).put("mode",if(mobile)"phone" else "tv").put("catalogSize",count).put("checks",JSONArray(checks))
    }
}
