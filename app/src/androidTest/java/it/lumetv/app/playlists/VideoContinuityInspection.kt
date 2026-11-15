package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.*
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.ui.VideoCatalogPanel
import it.lumetv.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** End-to-end continuity with owned local media. Never opens a provider or runs on physical hardware. */
object VideoContinuityInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val mobile=args.getString("mode")=="phone"
        val fixture=requireNotNull(args.getString("fixtureRoot"))
        require(Uri.parse(fixture).host=="10.0.2.2" && Uri.parse(fixture).scheme=="http")
        val checks=mutableListOf<String>()
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun record(label:String){checks+=label;File(context.cacheDir,"video-library-progress.json").writeText(JSONArray(checks).toString())}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(if(name=="05-landscape")1100 else 200);runner.uiAutomation.takeScreenshot()?.let {bitmap->
            File(context.cacheDir,"video-library").apply {mkdirs()}.resolve("$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        fun await(label:String,test:()->Boolean){val until=SystemClock.elapsedRealtime()+35_000
            while(SystemClock.elapsedRealtime()<until){if(main(test)){record(label);return};Thread.sleep(70)};capture("failure");error(label)}
        fun verify(label:String,test:()->Boolean){if(!main(test)){capture("failure");error(label)};record(label)}
        val repo=PersonalPlaylistRepository.get(context);val history=PersonalPlaybackStore.get(context)
        io {repo.local();repo.state.value.toList().forEach {repo.delete(it.id)}}
        val input=buildString {
            appendLine("#EXTM3U")
            listOf("Film di prova","Orbita S02E01","Orbita S01E10","Orbita S01E02","Orbita S01E01","Altra S01E01").forEachIndexed {i,title->
                appendLine("#EXTINF:-1 type=\"${if(i==0)"movie" else "episode"}\" tvg-id=\"continuity$i\",$title");appendLine("$fixture/video.mp4?item=$i")
            }
            appendLine("#EXTINF:-1 type=\"movie\",Film non disponibile");appendLine("$fixture/unavailable.mp4")
        }.toByteArray()
        val source=io {repo.prepareFile(input,allowHttp=true).use {repo.save("Cinema di prova",it)}}
        io {repo.prepareFile("#EXTM3U\n#EXTINF:-1 type=\"episode\",Orbita S01E03\n$fixture/video.mp4?copy=1\n".toByteArray(),allowHttp=true).use {repo.save("Altra lista",it)}}
        val items=io {repo.videos(VideoQuery(source=source,collapseSeries=false)).rows}.associateBy {it.title}
        val movie=items.getValue("Film di prova");val first=items.getValue("Orbita S01E01");val second=items.getValue("Orbita S01E02")
        val tenth=items.getValue("Orbita S01E10");val last=items.getValue("Orbita S02E01")
        val unavailable=items.getValue("Film non disponibile")
        fun target(item:VideoItem)=PlaybackTarget(item.id,PlaybackMode.VOD)
        io {history.write(source,target(movie),42_000,180_000);history.write(source,target(first),12_000,180_000)}
        main {context.getSharedPreferences("lume",0).edit().putStringSet("favorites",setOf(movie.id)).putString("catalog_source","builtin").remove("last_channel").commit()}
        val activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun field(name:String)=activity.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(activity)
        val player=main {field(if(mobile)"player" else "tvPlayer") as TvPlayer}
        val content=main {activity.findViewById<View>(android.R.id.content)}
        fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {all(view.getChildAt(it))} else emptyList()
        fun visible()=all(content).filter {it.isShown}
        fun click(label:String){await("action: $label") {visible().lastOrNull {it.isClickable && it.isEnabled && (it is TextView && it.text.toString()==label || it.contentDescription?.toString()==label || it.contentDescription?.toString()?.startsWith("$label:")==true)}?.let {if(!mobile)it.requestFocus();it.performClick()}==true}}
        fun clickTag(tag:String){await("action: $tag"){content.findViewWithTag<View>(tag)?.takeIf {it.isShown && it.isEnabled}?.let {if(!mobile)it.requestFocus();it.performClick()}==true}}
        fun back()=main {activity.onBackPressedDispatcher.onBackPressed()}
        fun watch(item:VideoItem)=io {history.read(source,target(item))}
        fun launch(item:VideoItem){main {context.startActivity(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://channel/${item.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))}}
        await("Video is available"){content.findViewWithTag<View>("Video")?.isShown==true}
        main {content.findViewWithTag<View>("Video")!!.performClick()}
        await("video library opens inside the app shell"){content.findViewWithTag<View>(VideoCatalogPanel.TAG)?.isShown==true}
        await("the Continue movie can be opened"){visible().lastOrNull {it.isClickable && it.contentDescription?.toString()?.startsWith("Film di prova,")==true}?.let {if(!mobile)it.requestFocus();it.performClick()}==true}
        await("movie details offer resume and manual watched state"){content.findViewWithTag<View>("video_watched")?.isShown==true && (content.findViewWithTag<TextView>("video_play")?.text?.toString()=="Riprendi")}
        capture("01-watched")
        clickTag("video_watched")
        await("mark watched changes the visible action"){content.findViewWithTag<TextView>("video_watched")?.text?.toString()=="Segna come non visto"}
        verify("watch state is saved and the movie leaves Continue") {watch(movie).completed && io {repo.videos(VideoQuery(shelf=VideoShelf.CONTINUE)).rows.none {it.id==movie.id}}}
        clickTag("video_watched")
        await("mark not watched restores a fresh start"){content.findViewWithTag<TextView>("video_play")?.text?.toString()=="Guarda" && watch(movie).position==0L}
        verify("manual watch actions preserve La mia lista"){movie.id in context.getSharedPreferences("lume",0).getStringSet("favorites",emptySet()).orEmpty()}
        io {history.write(source,target(movie),42_000,180_000)}
        await("resumable movie exposes Continue management"){content.findViewWithTag<View>("video_continue")?.isShown==true}
        clickTag("video_continue")
        await("removal from Continue has a reversible action"){content.findViewWithTag<TextView>("video_continue")?.text?.toString()=="Mostra in Continua a guardare"}
        verify("removing Continue keeps the saved point and does not mark watched"){watch(movie).let {it.hidden && !it.completed && it.resume==42_000L}}
        clickTag("video_continue")
        await("Continue visibility can be restored"){content.findViewWithTag<TextView>("video_continue")?.text?.toString()=="Rimuovi da Continua a guardare"}
        clickTag("video_continue");capture("02-hidden")
        clickTag("video_play")
        await("hidden movie resumes from its saved point"){player.snapshot.channel?.id==movie.id && player.snapshot.playing && player.snapshot.position>=42_000}
        if(mobile)await("opening a video enters fullscreen automatically") {
            activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                activity.requestedOrientation==ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
                androidx.core.view.ViewCompat.getRootWindowInsets(content)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars())==false
        }
        await("deliberate playback restores Continue visibility"){!watch(movie).hidden}
        main {player.togglePause()};back()
        await("returning from the player restores movie details"){content.findViewWithTag<View>("video_watched")?.isShown==true}
        clickTag("video_watched")
        await("mark watched is applied while the old player remains open"){watch(movie).completed}
        main {player.seek(80_000)};Thread.sleep(600)
        verify("late progress from the open player cannot overwrite manual watched state"){watch(movie).let {it.completed && it.position<70_000}}
        main {activity.javaClass.getDeclaredMethod("openPlayback").apply {isAccessible=true}.invoke(activity)}
        verify("opening the existing player keeps pause and manual watch state"){!player.snapshot.wantsToPlay && watch(movie).completed}
        if(mobile)await("reopening the paused player enters fullscreen without resuming") {
            activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && !player.snapshot.wantsToPlay && watch(movie).completed
        }
        main {player.togglePause()}
        await("explicit resume in the player honors manual watched state"){player.snapshot.playing && player.snapshot.position<5000 && !watch(movie).completed}
        main {player.togglePause()};back()
        await("return from the existing player restores the same detail page"){content.findViewWithTag<TextView>("video_watched")?.text?.toString()=="Segna come visto"}
        clickTag("video_watched")
        await("manual watched can be set again"){watch(movie).completed && content.findViewWithTag<TextView>("video_watched")?.text?.toString()=="Segna come non visto"}
        clickTag("video_watched")
        await("manual not watched sets the next playback to zero"){watch(movie).position==0L && !watch(movie).completed}
        clickTag("video_play")
        await("reopening the same movie honors the manual reset"){player.snapshot.channel?.id==movie.id && player.snapshot.playing && player.snapshot.position<5_000}

        io {history.write(source,target(unavailable),90_000,180_000,true)}
        launch(unavailable)
        await("unavailable video reaches a recoverable error"){player.snapshot.channel?.id==unavailable.id && player.snapshot.message.startsWith("Riconnetto")}
        verify("a failed playback does not erase manual watched state"){watch(unavailable).completed}
        main {player.stop()}

        launch(first)
        await("an episode resumes and discovers the next episode in the same source"){player.snapshot.channel?.id==first.id && player.snapshot.playing && player.snapshot.position>=12_000 && player.snapshot.nextEpisode?.id==second.id}
        main {player.setSleepTimer(15)}
        val remaining=main {player.snapshot.options.sleepRemaining}
        main {player.seek(player.snapshot.duration-600)}
        await("the end of the episode offers an explicit next action"){player.snapshot.ended && visible().any {it.isClickable && it.contentDescription?.toString()?.startsWith("Guarda il prossimo episodio:")==true}}
        Thread.sleep(1400)
        verify("the next episode never starts automatically"){player.snapshot.channel?.id==first.id && player.snapshot.ended && !player.snapshot.wantsToPlay}
        capture("03-next-episode")
        if(mobile) {
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("landscape keeps the ended episode and next action"){activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && player.snapshot.ended}
            capture("05-landscape")
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait keeps the pending episode choice"){activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT && player.snapshot.channel?.id==first.id}
        } else verify("TV focuses the next episode action at the end") {activity.currentFocus?.contentDescription?.toString()?.startsWith("Guarda il prossimo episodio:")==true}
        if(mobile)click("Guarda il prossimo episodio") else runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("explicit confirmation starts the following episode"){player.snapshot.channel?.id==second.id && player.snapshot.playing}
        verify("the completed episode remains watched"){watch(first).completed}
        verify("the sleep timer continues across episode changes"){player.snapshot.options.sleepRemaining in 1..remaining}
        await("following episode order is numeric and source isolated"){player.snapshot.nextEpisode?.id==tenth.id}
        if(!mobile) {
            main {player.seek(40_000);visible().filterIsInstance<TextView>().last {it.isClickable && it.text.toString()=="−10 s"}.requestFocus()}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("TV OK activates the focused seek button without restarting the episode"){player.snapshot.position in 28_000..33_000 && player.snapshot.channel?.id==second.id && player.snapshot.wantsToPlay}
        }
        main {player.playFollowingEpisode(first.id,second.id)};Thread.sleep(200)
        verify("a stale next action cannot skip the current episode"){player.snapshot.channel?.id==second.id}
        capture("04-resumed")
        click("Episodio successivo")
        await("quick next reaches episode ten"){player.snapshot.channel?.id==tenth.id && player.snapshot.nextEpisode?.id==last.id && player.snapshot.playing}
        verify("skipping an episode does not mark it watched"){!watch(second).completed}
        click("Episodio successivo")
        await("next crosses the season boundary"){player.snapshot.channel?.id==last.id && player.snapshot.playing && !player.snapshot.nextEpisodeLoading}
        verify("the final episode offers no unrelated next title"){player.snapshot.nextEpisode==null && visible().none {it.isClickable && it.contentDescription?.toString()?.startsWith("Episodio successivo:")==true}}
        main {player.seek(player.snapshot.duration-600)}
        await("the final episode ends normally"){player.snapshot.ended}
        main {player.stop();activity.finish()}
        return JSONObject().put("passed",true).put("mode",if(mobile)"phone" else "tv").put("checks",JSONArray(checks))
    }
}
