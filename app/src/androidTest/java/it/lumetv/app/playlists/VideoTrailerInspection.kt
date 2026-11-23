package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.*
import it.lumetv.app.ui.OnDemandControls
import it.lumetv.app.ui.TrailerPlayerDialog
import it.lumetv.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Local media and synthetic metadata, exclusively on disposable emulators. */
object VideoTrailerInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        check(Build.HARDWARE in setOf("ranchu","goldfish"))
        val context=runner.targetContext
        val mobile=args.getString("mode")=="phone"
        val fixture=requireNotNull(args.getString("fixtureRoot"))
        require(Uri.parse(fixture).host=="10.0.2.2")
        val checks=mutableListOf<String>()
        val attempts=AtomicInteger();val cancelled=AtomicInteger()
        val behavior=AtomicReference("play")
        fun <T> main(block:()->T):T {
            val answer=AtomicReference<Result<T>>()
            runner.runOnMainSync {answer.set(runCatching(block))}
            return answer.get().getOrThrow()
        }
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun views(root:View):List<View> = listOf(root)+if(root is ViewGroup)(0 until root.childCount).flatMap {views(root.getChildAt(it))} else emptyList()
        fun all():List<View> = WindowInspector.getGlobalWindowViews().flatMap(::views)
        fun trailer():View?=all().lastOrNull {it.tag=="video_trailer_player" && it.isShown}
        fun controls():OnDemandControls?=trailer()?.let(::views)?.filterIsInstance<OnDemandControls>()?.singleOrNull()
        fun capture(name:String){
            runner.waitForIdleSync();Thread.sleep(250)
            runner.uiAutomation.takeScreenshot()?.let {bitmap->
                File(context.cacheDir,"video-library/$name.png").apply {parentFile!!.mkdirs();outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
                bitmap.recycle()
            }
        }
        fun await(label:String,test:()->Boolean){
            val deadline=System.currentTimeMillis()+20_000
            while(System.currentTimeMillis()<deadline){if(test()){checks+=label;return};Thread.sleep(75)}
            capture("failure");error(label)
        }
        fun verify(label:String,test:()->Boolean){if(!test()){capture("failure");error(label)};checks+=label}
        fun click(label:String)=await("action $label") {main {
            val target=all().lastOrNull {it.isShown && it.isEnabled && it.isClickable && it !is EditText &&
                (it is TextView && it.text.toString()==label || it.contentDescription?.toString()?.let {copy->copy==label || copy.startsWith("$label:") || copy.startsWith("$label,") }==true)}
            if(target==null)false else {
                target.requestRectangleOnScreen(android.graphics.Rect(0,0,target.width,target.height),true)
                if(!mobile)target.requestFocus()
                target.performClick()
            }
        }}
        fun textInTrailer(text:String)=main {trailer()?.let(::views)?.filterIsInstance<TextView>()?.any {it.isShown && it.text.contains(text)}==true}
        val italian=TmdbTrailer("AAAAAAAAAAA","YouTube","it",official=true)
        val english=TmdbTrailer("BBBBBBBBBBB","YouTube","en",official=true)
        val repo=PersonalPlaylistRepository.get(context)
        repo.configureVideoMetadataForTests(object:TmdbService {
            override val configured=true
            override suspend fun search(query:VodLookupQuery)=listOf(VodTitleCandidate(
                if(query.type==VodMediaType.TV)43 else if(query.title=="Senza trailer")44 else 42,query.type,query.title,year=query.year))
            override suspend fun details(type:VodMediaType,id:Int)=TmdbMetadata(id,type,if(id==43)"Orbita" else "Orizzonte",2023,
                "Una spedizione attraversa un oceano sconosciuto. Scheda sintetica per il collaudo del trailer.",
                trailers=when(id){43->listOf(english);44->emptyList();else->listOf(italian,english)})
        })
        context.getSharedPreferences("video-metadata",0).edit().clear().commit()
        val source=io {
            repo.local();repo.state.value.forEach {repo.delete(it.id)}
            repo.prepareFile(("#EXTM3U\n"+
                "#EXTINF:-1 type=\"movie\" year=\"2023\",Orizzonte (2023)\n$fixture/video.mp4?film=1\n"+
                "#EXTINF:-1 type=\"series\" series-year=\"2023\",Orbita S01E01\n$fixture/video.mp4?episode=1\n"+
                "#EXTINF:-1 type=\"movie\" year=\"2023\",Senza trailer\n$fixture/video.mp4?film=2\n").toByteArray(),allowHttp=true).use {repo.save("Cinema di prova",it)}
        }
        val movie=io {repo.videos(VideoQuery(kind=VodKind.MOVIE)).rows.first {it.title.startsWith("Orizzonte")}}
        val history=PersonalPlaybackStore.get(context)
        io {history.write(source,PlaybackTarget(movie.id,PlaybackMode.VOD),20_000,180_000)}
        val before=history.read(source,PlaybackTarget(movie.id,PlaybackMode.VOD))
        main {TrailerPlayerDialog.resolverForTests=TrailerResolver {candidate->
            attempts.incrementAndGet()
            if(behavior.get()=="wait")try {awaitCancellation()}finally {cancelled.incrementAndGet()}
            delay(150)
            if(behavior.get()=="missing" || behavior.get()=="english" && candidate.language=="it")throw TrailerUnavailableException()
            TrailerStream(candidate,if(behavior.get()=="bad-media" && candidate.language=="it")"$fixture/unavailable.mp4" else "$fixture/video.mp4","video/mp4",
                if(args.getString("separateTrailerAudio")=="true")"$fixture/trailer-audio.m4a" else null,"audio/mp4")
        }}
        val activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        try {
            click("Video");click("Orizzonte (2023)")
            await("Italian trailer action appears in current details"){main {all().any {it.tag=="video_trailer" && it.isShown}}}
            verify("browsing and metadata do not resolve any stream"){attempts.get()==0}
            capture("trailer-01-details")
            click("Trailer in italiano")
            await("native trailer actually advances"){main {controls()?.timeline?.position?.let {it>500}==true}}
            await("native audio and video tracks are selected"){main {
                val native=trailer()?.let(::views)?.filterIsInstance<PlayerView>()?.singleOrNull()?.player
                listOf(C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_VIDEO).all {type->native?.currentTracks?.groups?.any {it.type==type && it.isSelected}==true}
            }}
            verify("Italian is labelled in the native player"){textInTrailer("Trailer in italiano")}
            capture("trailer-02-playing")
            if(mobile){
                main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
                await("native trailer supports landscape"){main {trailer()?.let {it.width>it.height}==true}}
                capture("trailer-02-landscape")
                main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
                await("native trailer returns to portrait"){main {trailer()?.let {it.height>it.width}==true}}
            }
            click("Pausa")
            val paused=main {controls()!!.timeline.position};Thread.sleep(900)
            verify("native pause stops progress"){main {kotlin.math.abs(controls()!!.timeline.position-paused)<700}}
            click("Riprendi");click("Avanti di 30 secondi")
            await("native forward control seeks"){main {controls()?.timeline?.position?.let {it>=30_000}==true}}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Back closes the native player"){main {trailer()==null}}
            verify("trailer does not alter the movie watch history"){history.read(source,PlaybackTarget(movie.id,PlaybackMode.VOD))==before}
            if(!mobile)verify("TV focus returns to the trailer action"){main {activity.currentFocus?.tag=="video_trailer"}}

            behavior.set("english");click("Trailer in italiano")
            await("English trailer plays after unavailable Italian"){main {controls()?.timeline?.position?.let {it>500}==true} && textInTrailer("Trailer in inglese")}
            capture("trailer-03-english")
            main {controls()!!.let {it.onSeek(it.timeline.duration-400)}}
            await("end of trailer returns to the title"){main {trailer()==null}}
            verify("end of trailer does not mark the movie watched"){history.read(source,PlaybackTarget(movie.id,PlaybackMode.VOD))==before}

            behavior.set("bad-media");click("Trailer in italiano")
            await("native media failure also falls back to English"){main {controls()?.timeline?.position?.let {it>500}==true} && textInTrailer("Trailer in inglese")}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("fallback after media failure closes"){main {trailer()==null}}

            behavior.set("missing");click("Trailer in italiano")
            await("unavailable videos show an internal retry"){textInTrailer("Trailer non disponibile")}
            capture("trailer-04-unavailable")
            behavior.set("play");click("Riprova")
            await("retry starts a fresh native session"){main {controls()?.timeline?.position?.let {it>500}==true}}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("retry player closes"){main {trailer()==null}}

            behavior.set("wait");val initialAttempts=attempts.get();click("Trailer in italiano")
            await("pending resolution started"){attempts.get()>initialAttempts && main {trailer()?.hasWindowFocus()==true}}
            runner.waitForIdleSync()
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("leaving cancels pending resolution"){cancelled.get()>0 && main {trailer()==null}}
            val stoppedAttempts=attempts.get();Thread.sleep(350)
            verify("cancellation does not launch the English fallback"){attempts.get()==stoppedAttempts}
            main {activity.onBackPressedDispatcher.onBackPressed()}
            click("Serie");click("Orbita")
            await("English-only series has an explicit English action"){main {all().any {it.tag=="video_trailer" && it is TextView && it.text.toString()=="Trailer in inglese"}}}
            capture("trailer-05-series")
            main {activity.onBackPressedDispatcher.onBackPressed()};click("Film");click("Senza trailer")
            await("title without trailers finishes metadata"){main {all().filterIsInstance<TextView>().any {it.tag=="video_metadata_status" && it.text.contains("Informazioni da TMDB")}}}
            verify("no trailer action is shown for an empty list"){main {all().none {it.tag=="video_trailer" && it.isShown}}}
            val real=args.getString("realTrailer")=="true"
            if(real){
                main {
                    TrailerPlayerDialog.resolverForTests=null
                    TrailerPlayerDialog(activity,"Interstellar",listOf(TmdbTrailer("gnOCZhluoow","YouTube","it",official=true)),mobile).show()
                }
                await("real YouTube trailer advances in native Android player"){main {controls()?.timeline?.position?.let {it>500}==true}}
                await("real trailer has native audio and video tracks"){main {
                    val native=trailer()?.let(::views)?.filterIsInstance<PlayerView>()?.singleOrNull()?.player
                    listOf(C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_VIDEO).all {type->native?.currentTracks?.groups?.any {it.type==type && it.isSelected}==true}
                }}
                main {controls()!!.onSeek(30_000)}
                await("real trailer resumes after a native seek"){main {
                    val native=trailer()?.let(::views)?.filterIsInstance<PlayerView>()?.singleOrNull()?.player
                    native?.isPlaying==true && native.currentPosition>30_500
                }}
                capture("trailer-06-real")
                runner.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("real trailer closes internally"){main {trailer()==null}}
            }
            return JSONObject().put("passed",true).put("checks",JSONArray(checks)).put("resolved_streams",attempts.get()).put("live_trailer_checked",real)
        } finally {
            main {activity.finish();TrailerPlayerDialog.resolverForTests=null}
        }
    }
}
