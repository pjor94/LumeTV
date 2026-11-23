package it.lumetv.app.playback

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
import android.widget.ImageView
import androidx.activity.ComponentActivity
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.PersonalCatalogQuery
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.ui.OnDemandControls
import it.lumetv.app.ui.mobile.MobilePlaybackView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real playback and input against owned, range-capable local media on disposable emulators. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VodPlayerInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val root=requireNotNull(args.getString("fixtureRoot"));require(Uri.parse(root).host=="10.0.2.2")
        val mobile=args.getString("mode")=="phone"
        val checks=mutableListOf<String>()
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun <T> io(block:suspend ()->T)=runBlocking(Dispatchers.IO){block()}
        fun record(label:String){checks+=label;File(runner.targetContext.cacheDir,"vod-player-progress.json").writeText(JSONArray(checks).toString())}
        fun await(label:String,timeout:Long=30_000,test:()->Boolean){val deadline=SystemClock.elapsedRealtime()+timeout
            while(SystemClock.elapsedRealtime()<deadline){if(main(test)){record(label);return};Thread.sleep(100)};error(label)}
        val repository=PersonalPlaylistRepository.get(runner.targetContext)
        io {repository.local();repository.state.value.toList().forEach {repository.delete(it.id)}}
        val input=File(runner.targetContext.cacheDir,"vod-player.m3u").apply {writeText("#EXTM3U\n#EXTINF:240 type=\"movie\",Visione di prova\n$root/movie.mp4\n#EXTINF:240 type=\"movie\",Secondo video\n$root/movie.mp4?second=1\n")}
        val source=io {repository.prepareFile(input,allowHttp=true).use {repository.save("Player VOD",it)}}
        val channels=io {repository.catalog(PersonalCatalogQuery(source=source,content=null)).rows}
        val channel=channels.single {it.name=="Visione di prova"};val second=channels.single {it.name=="Secondo video"}
        val activity=runner.startActivitySync(Intent(runner.targetContext,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://channel/${channel.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun field(name:String)=activity.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(activity)
        val player=main {field(if(mobile)"player" else "tvPlayer") as TvPlayer}
        val content=main {activity.findViewById<View>(android.R.id.content)}
        fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {all(view.getChildAt(it))}else emptyList()
        lateinit var controls:OnDemandControls
        await("VOD is playing with a known duration"){player.snapshot.playing && player.snapshot.duration>230_000}
        controls=main {all(content).filterIsInstance<OnDemandControls>().single()}
        val service=main {TvPlayer::class.java.getDeclaredField("service").apply {isAccessible=true}.get(player) as PlaybackService}
        fun cacheSession()=PlaybackService::class.java.getDeclaredField("vodCache").apply {isAccessible=true}.get(service) as VodMediaCache.Session
        val cache=VodMediaCache.get(runner.targetContext)
        val phone=if(mobile)main {field("playback") as MobilePlaybackView} else null
        val video=phone?.video ?: main {field("video") as androidx.media3.ui.PlayerView}
        main {player.setPlaybackPreferences(PlaybackPreferences(subtitleMode=SubtitleMode.LANGUAGE,subtitleLanguage="it"))}
        await("embedded Italian subtitles remain selectable in VOD"){player.snapshot.options.subtitles.any {it.selected}}
        fun show()=main {if(phone!=null)phone.setControlsVisible(true)else controls.showControls(true)}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(if(name.contains("portrait") || name.contains("landscape"))1_200 else 200);runner.uiAutomation.takeScreenshot()?.let {bitmap->
            val folder=File(runner.targetContext.cacheDir,"vod-player").apply {mkdirs()};File(folder,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
        fun back()=main {activity.onBackPressedDispatcher.onBackPressed()}
        await("timeline receives the actual buffered position"){player.snapshot.bufferedPosition>player.snapshot.position+30_000 && controls.timeline.buffered==player.snapshot.bufferedPosition}
        await("look-ahead loads additional media into the shared cache"){player.snapshot.preloadedBytes>0}
        val firstCache=main {cacheSession()}
        show();capture("01-controls")
        await("controls disappear despite continuing position updates",9_000){!controls.isShown}
        await("captions reclaim the video after controls hide"){video.subtitleView?.paddingBottom==0}
        capture("00-clean")
        val hiddenAt=main {player.snapshot.position};Thread.sleep(1_200)
        check(main {!controls.isShown && player.snapshot.position>hiddenAt});record("progress cannot reopen hidden controls")
        show();main {controls.timeline.requestFocus()}
        repeat(3){runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)}
        val pending=main {controls.timeline.preview ?: error("No preview cursor")}
        check(main {controls.isScrubbing && player.snapshot.position<pending-10_000});record("D-pad exploration leaves playback untouched")
        await("a real frame preview is available",12_000){controls.findViewWithTag<ImageView>("vod_scene_preview").let {it.isShown && it.drawable!=null}}
        capture("02-scene")
        check(main {
            val output=video.subtitleView!!.getChildAt(0);val caption=IntArray(2);val overlay=IntArray(2)
            output.getLocationOnScreen(caption);controls.subtitleBoundary.getLocationOnScreen(overlay)
            caption[1]+output.height<=overlay[1]
        });record("the subtitle drawing area stays above the scene controls")
        back();check(main {!controls.isScrubbing && player.snapshot.position<pending-8_000});record("Back cancels scene exploration")
        main {controls.movePreview(1)}
        val destination=main {controls.timeline.preview!!}
        runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        await("OK commits exactly the selected seek"){!controls.isScrubbing && player.snapshot.position>=destination && player.snapshot.position<destination+8_000}
        if(!mobile){
            main {controls.movePreview(1)}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            await("hardware fast forward clears the pending scene cursor"){!controls.isScrubbing && controls.timeline.preview==null && player.snapshot.playing}
            main {player.togglePause();controls.movePreview(1)}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
            await("hardware play clears a paused scene preview"){!controls.isScrubbing && player.snapshot.playing}
        }
        show();main {all(controls).first {it.isClickable && it.contentDescription=="Audio e opzioni"}.performClick()}
        val options=main {field("playbackOptions") as it.lumetv.app.ui.PlaybackOptionsPanel}
        await("the VOD audio and options action opens its real panel"){options.isShowing}
        back();await("Back from options returns to the same film"){!options.isShowing && player.snapshot.channel?.id==channel.id}
        main {player.togglePause()};await("pause keeps controls visible"){!player.snapshot.wantsToPlay && controls.isShown}
        Thread.sleep(5_500);check(main {controls.isShown});record("paused controls do not time out")
        check(main {cacheSession()===firstCache && !firstCache.cleared.isCompleted && cache.cache.cacheSpace>0});record("pause retains the active content cache")
        capture("03-paused")
        main {player.togglePause()};await("playback resumes"){player.snapshot.playing}
        show();back();check(main {!controls.isShown && field("showingCatalog")==false});record("first Back closes controls without leaving the film")
        show();main {player.seek(180_000)};await("a distant seek remains usable"){player.snapshot.playing && player.snapshot.position>=180_000}
        await("reserve is rebuilt after the distant seek"){player.snapshot.bufferedDuration>=30_000}
        val before=main {player.snapshot.position};val stalls=main {player.snapshot.rebufferCount}
        java.net.URL("$root/__pause?seconds=7").readText()
        Thread.sleep(8_000)
        check(main {player.snapshot.playing && player.snapshot.position>=before+6_000 && player.snapshot.rebufferCount==stalls})
        record("a short network blackout is absorbed by the video reserve")
        if(phone!=null){
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait keeps the active VOD session"){phone.height>phone.width && player.snapshot.playing}
            show();capture("04-portrait")
            check(main {
                val bounds=android.graphics.Rect();controls.getGlobalVisibleRect(bounds)
                listOf("Pausa","Indietro di 10 secondi","Avanti di 30 secondi","Audio e opzioni","Altro").all {label->
                    all(controls).single {it.isClickable && it.contentDescription==label}.let {button->
                        val location=IntArray(2);button.getLocationOnScreen(location)
                        button.isShown && location[0]>=bounds.left && location[0]+button.width<=bounds.right
                    }
                }
            });record("all essential icon actions fit in portrait with accessible names")
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("landscape keeps the active VOD session"){phone.width>phone.height && player.snapshot.playing}
            show();capture("05-landscape")
            main {
                val line=controls.timeline;val now=SystemClock.uptimeMillis();val x=line.width*.75f;val y=line.height/2f
                android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0).also {line.dispatchTouchEvent(it);it.recycle()}
                check(controls.isScrubbing)
                android.view.MotionEvent.obtain(now,now+50,android.view.MotionEvent.ACTION_UP,x,y,0).also {line.dispatchTouchEvent(it);it.recycle()}
            }
            await("releasing a touch seek commits its destination"){!controls.isScrubbing && player.snapshot.position in 175_000..190_000}
        }
        val snapshot=main {player.snapshot}
        main {player.select(second,mobile)}
        await("changing content clears the previous cache without stopping the new video"){player.snapshot.channel?.id==second.id && player.snapshot.playing && firstCache.cleared.isCompleted && cache.cache.keys.none {it.startsWith(firstCache.namespace(""))}}
        await("the new content preloads into its own cache"){player.snapshot.preloadedBytes>0 && cache.cache.cacheSpace>0}
        val secondCache=main {cacheSession()}
        main {player.stop()}
        await("explicit stop removes all downloaded video bytes"){secondCache.cleared.isCompleted && cache.cache.cacheSpace==0L && cache.cache.keys.isEmpty()}
        main {player.select(channel,mobile)}
        await("a stopped video can start a fresh cache session"){player.snapshot.playing && player.snapshot.bufferedDuration>3_000 && cache.cache.cacheSpace>0}
        val endCache=main {cacheSession()}
        main {player.seek(player.snapshot.duration-250)}
        await("finishing a video clears its downloaded bytes"){player.snapshot.ended && endCache.cleared.isCompleted && cache.cache.cacheSpace==0L}
        main {player.restart()}
        await("replay after cache deletion starts normally"){player.snapshot.playing && player.snapshot.position<10_000 && cache.cache.cacheSpace>0}
        val closeCache=main {cacheSession()}
        main {service.onTaskRemoved(Intent());activity.finish()}
        await("closing the app task clears the video cache"){closeCache.cleared.isCompleted && cache.cache.cacheSpace==0L && cache.cache.keys.isEmpty()}
        return JSONObject().put("passed",true).put("mode",if(mobile)"phone" else "tv").put("checks",JSONArray(checks))
            .put("bufferedDuration",snapshot.bufferedDuration).put("rebufferCount",snapshot.rebufferCount).put("rebufferDuration",snapshot.rebufferDuration).put("preloadedBytes",snapshot.preloadedBytes)
    }
}
