package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.*
import it.lumetv.app.ui.VideoMetadataPanel
import it.lumetv.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic metadata on disposable emulators. No TMDB token or external catalog request is used. */
object VideoMetadataInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val mobile=args.getString("mode")=="phone"
        val fixture=requireNotNull(args.getString("fixtureRoot"));require(Uri.parse(fixture).host=="10.0.2.2")
        val checks=mutableListOf<String>();val searches=AtomicInteger();val downloads=AtomicInteger()
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(350);runner.uiAutomation.takeScreenshot()?.let {bitmap->
            val file=File(context.cacheDir,"video-library/$name.png");file.parentFile!!.mkdirs()
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        fun verify(label:String,condition:()->Boolean){if(!condition()){capture("failure");error(label)};checks+=label}
        fun await(label:String,condition:()->Boolean){val deadline=System.currentTimeMillis()+20_000
            while(System.currentTimeMillis()<deadline){if(condition()){checks+=label;return};Thread.sleep(75)};capture("failure");error(label)}
        val api=object:TmdbService {
            override val configured=true
            override suspend fun search(query:VodLookupQuery):List<VodTitleCandidate> {
                searches.incrementAndGet();delay(150)
                return listOf(VodTitleCandidate(51,VodMediaType.MOVIE,"Orizzonte",year=2023),VodTitleCandidate(52,VodMediaType.MOVIE,"Orizzonte",year=1984))
            }
            override suspend fun details(type:VodMediaType,id:Int):TmdbMetadata {
                downloads.incrementAndGet();delay(150)
                return TmdbMetadata(id,type,"Orizzonte",if(id==51)2023 else 1984,
                    "Una spedizione attraversa un oceano sconosciuto. Trama italiana sintetica per il collaudo di TMDB.",
                    genres=listOf("Avventura"),cast=if(id==51)listOf("Anna Rossi","Luca Verdi") else listOf("Elena Neri"),
                    directors=listOf("Ada Bianchi"),runtimeMinutes=112)
            }
        }
        val repo=PersonalPlaylistRepository.get(context)
        repo.configureVideoMetadataForTests(api)
        context.getSharedPreferences("video-metadata",0).edit().clear().commit()
        val source=io {
            repo.local();repo.state.value.forEach {repo.delete(it.id)}
            repo.prepareFile("#EXTM3U\n#EXTINF:-1 type=\"movie\" year=\"2023\" poster=\"https://posters.example.org/tmdb-fixture.png\",Orizzonte (2023)\n$fixture/video.mp4\n".toByteArray(),
                allowHttp=true,settings=PlaylistSettings(artworkHttp=true)).use {repo.save("Cinema di prova",it)}
        }
        val item=io {repo.videos().rows.single()}
        io {
            val art=repo.videoArtwork(item,false)!!
            val bitmap=Bitmap.createBitmap(512,768,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(27,55,74))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(203,172,119)};canvas.drawCircle(340f,220f,105f,paint)
            paint.color=Color.WHITE;paint.textSize=38f;canvas.drawText("ORIZZONTE",35f,595f,paint)
            val file=File(context.filesDir,"video-artwork/$source-${art.cacheKey}-p.image");file.parentFile!!.mkdirs()
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        val activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        val content=main {activity.findViewById<View>(android.R.id.content)}
        fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {views(v.getChildAt(it))} else emptyList()
        fun text(value:String)=main {views(content).filterIsInstance<TextView>().any {it.isShown && it.text.contains(value)}}
        fun click(label:String)=await("action $label"){main {
            val target=views(content).lastOrNull {it.isShown && it.isEnabled && it.isClickable && it !is EditText &&
                (it is TextView && it.text.toString()==label || it.contentDescription?.toString()?.let {d->d==label || d.startsWith("$label,") || d.startsWith("$label:")}==true)}
            if(target==null)false else {target.requestRectangleOnScreen(android.graphics.Rect(0,0,target.width,target.height),true);if(!mobile)target.requestFocus();target.performClick()}
        }}
        click("Video");await("movie is available"){text("Orizzonte (2023)")}
        verify("browsing does not start metadata requests"){searches.get()==0}
        click("Orizzonte (2023)")
        await("Italian synopsis and cast are rendered"){text("Trama italiana sintetica") && text("Anna Rossi") && text("Ada Bianchi")}
        verify("search and details share one title lookup"){searches.get()==1 && downloads.get()==1}
        verify("editorial runtime does not alter playback data"){io {repo.videoDetails(item)!!.duration==0L}}
        if(!mobile)verify("remote focus stays on the playback action"){main {activity.currentFocus?.tag=="video_play"}}
        io {PersonalPlaybackStore.get(context).write(source,PlaybackTarget(item.id,PlaybackMode.VOD),20_000,180_000)}
        await("watch progress updates keep metadata visible"){text(if(mobile)"Riprendi da" else "min rimasti") && text("Anna Rossi")}
        if(!mobile)verify("passive watch updates preserve remote focus"){main {activity.currentFocus?.tag=="video_play"}}
        main {content.findViewWithTag<View>("video_cast").let {it.requestRectangleOnScreen(android.graphics.Rect(0,0,it.width,it.height),true)}}
        capture("tmdb-01-details")
        if(mobile) {
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("metadata supports landscape"){main {content.width>content.height}}
            capture("tmdb-01-landscape")
            main {activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait is restored"){main {content.height>content.width}}
        }
        click("Correggi abbinamento");await("manual candidates include title and year"){text("Orizzonte (1984)")}
        capture("tmdb-02-match");click("Orizzonte (1984)")
        await("manual correction updates the cast"){text("Elena Neri")}
        capture("tmdb-03-corrected")
        main {activity.onBackPressedDispatcher.onBackPressed()};click("Orizzonte (2023)")
        await("reopened details reuse the manual association"){text("Elena Neri")}
        verify("reopening performs no additional metadata requests"){searches.get()==1 && downloads.get()==2}
        click("Correggi abbinamento");click("Non associare questo titolo")
        await("manual exclusion is selectable and hides enrichment"){text("Ricerca online disattivata per questo titolo") && !text("Elena Neri")}
        capture("tmdb-03-excluded")
        click("Associa il titolo");click("Orizzonte (1984)")
        await("an excluded title can be associated again"){text("Elena Neri")}
        verify("reassociating reuses downloaded details"){downloads.get()==2}
        val beforeDisable=searches.get() to downloads.get()
        main {VideoMetadataPanel(activity,repo,activity.lifecycleScope,mobile).show()}
        await("online settings are reachable"){text("Disattiva informazioni online")};capture("tmdb-04-settings")
        click("Disattiva informazioni online");main {activity.onBackPressedDispatcher.onBackPressed()}
        await("disabling online information removes remote cast"){!text("Elena Neri")}
        capture("tmdb-05-disabled")
        verify("disabled mode does not issue requests"){searches.get()==beforeDisable.first && downloads.get()==beforeDisable.second}
        main {activity.finish()}
        return JSONObject().put("passed",true).put("checks",JSONArray(checks)).put("searches",searches.get()).put("details",downloads.get()).put("external_metadata_requests",0)
    }
}
