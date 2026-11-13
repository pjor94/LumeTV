package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.*
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.ui.VideoPosterAdapter
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaybackTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Owned synthetic artwork and media; called only by the emulator-guarded test instrumentation. */
object VideoLibraryInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val fixture=requireNotNull(args.getString("fixtureRoot"));require(Uri.parse(fixture).host=="10.0.2.2")
        val mobile=args.getString("mode")=="phone";val checks=mutableListOf<String>()
        var diagnose:(()->JSONObject)?=null
        fun <T> main(block:()->T):T {val value=AtomicReference<Result<T>>();runner.runOnMainSync {value.set(runCatching(block))};return value.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(450);val directory=File(context.cacheDir,"video-library").apply {mkdirs()};diagnose?.invoke()?.let {directory.resolve("$name-state.json").writeText(it.toString())};runner.uiAutomation.takeScreenshot()?.let {b->directory.resolve("$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        fun record(label:String){checks+=label;File(context.cacheDir,"video-library-progress.json").writeText(JSONArray(checks).toString())}
        fun verify(label:String,test:()->Boolean){if(!test()){capture("failure");error(label)};record(label)}
        fun await(label:String,test:()->Boolean){val end=System.currentTimeMillis()+45_000;while(System.currentTimeMillis()<end){if(test()){record(label);return};Thread.sleep(75)};capture("failure");error(label)}
        val repo=PersonalPlaylistRepository.get(context)
        val source=io {
            repo.local();repo.state.value.forEach {repo.delete(it.id)}
            val list=buildString {
                appendLine("#EXTM3U")
                for(i in 1..220){appendLine("#EXTINF:-1 type=\"live\",Diretta $i");appendLine("$fixture/video.mp4?live=$i")}
                for(i in 1..180){appendLine("#EXTINF:-1 type=\"movie\" tvg-id=\"film-$i\" group-title=\"${if(i%2==0)"Avventura" else "Dramma"}\" year=\"2023\" genre=\"Avventura\" plot=\"Un viaggio tra stelle e oceani. Contenuto sintetico per il collaudo.\" poster=\"https://posters.example.org/movie-$i.png\" backdrop=\"https://posters.example.org/scene-$i.png\",Orizzonte ${i.toString().padStart(3,'0')}");appendLine("$fixture/video.mp4?movie=$i")}
                for((season,episode) in listOf(2 to 2,1 to 1,2 to 1)){appendLine("#EXTINF:-1 type=\"series\" group-title=\"Fantascienza\" poster=\"https://posters.example.org/orbita.png\" plot=\"Una serie sintetica in due stagioni.\",Orbita S0${season}E0${episode}");appendLine("$fixture/video.mp4?episode=$season-$episode")}
                appendLine("#EXTINF:-1 type=\"vod\" group-title=\"Musica\",Concerto senza copertina");appendLine("$fixture/video.mp4?concert=1")
            }
            repo.prepareFile(list.toByteArray(),allowHttp=true,settings=PlaylistSettings(artworkHttp=true)).use {repo.save("Cinema di prova",it)}
        }
        val second=io {repo.prepareFile("#EXTM3U\n#EXTINF:-1 type=\"movie\",Alfa\n$fixture/video.mp4?second=1\n".toByteArray(),allowHttp=true).use {repo.save("Seconda lista",it)}}
        val all=io {repo.videos(VideoQuery(source=source,collapseSeries=false,limit=200)).rows}
        val movie=all.first {it.title=="Orizzonte 001"};val history=PersonalPlaybackStore.get(context)
        history.write(source,PlaybackTarget(movie.id,PlaybackMode.VOD),21_000,180_000)
        context.getSharedPreferences("lume",0).edit().putString("catalog_source",source).remove("last_channel").putStringSet("favorites",setOf(all.first {it.title=="Orizzonte 002"}.id)).commit()
        // Populate the real cache with locally authored bitmap fixtures; no external image server is contacted.
        val directory=File(context.filesDir,"video-artwork").apply {mkdirs()}
        io {for((index,item) in all.withIndex())for(backdrop in listOf(false,true)) {
            val art=repo.videoArtwork(item,backdrop) ?: continue
            val bitmap=Bitmap.createBitmap(if(backdrop)1024 else 512,if(backdrop)576 else 768,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG)
            val colors=listOf(Color.rgb(25,59,78),Color.rgb(78,45,51),Color.rgb(48,62,52),Color.rgb(47,45,75))
            canvas.drawColor(colors[index%colors.size]);paint.color=Color.rgb(203,172,119)
            canvas.drawCircle(bitmap.width*.68f,bitmap.height*.29f,bitmap.width*.20f,paint)
            paint.color=Color.argb(130,9,17,27);canvas.drawRect(0f,bitmap.height*.57f,bitmap.width.toFloat(),bitmap.height.toFloat(),paint)
            paint.color=Color.WHITE;paint.textSize=if(backdrop)55f else 44f;paint.typeface=android.graphics.Typeface.create("sans-serif-medium",0)
            canvas.drawText(if(item.title.startsWith("Orbita"))"ORBITA" else "ORIZZONTE",32f,bitmap.height*.78f,paint)
            paint.textSize=25f;canvas.drawText("CATALOGO DI PROVA",32f,bitmap.height*.88f,paint)
            File(directory,"${item.source}-${art.cacheKey}-${if(backdrop)"b" else "p"}.image").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        var activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://catalog")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        var content=main {activity.findViewById<View>(android.R.id.content)}
        fun allViews(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {allViews(v.getChildAt(it))} else emptyList()
        fun visible()=allViews(content).filter {it.isShown}
        diagnose={main {
            val home=content.findViewWithTag<RecyclerView>("video_home")
            val manager=home?.layoutManager as? LinearLayoutManager
            fun describe(v:View?)=JSONObject().put("class",v?.javaClass?.simpleName.orEmpty()).put("text",(v as? TextView)?.text?.toString().orEmpty()).put("description",v?.contentDescription?.toString().orEmpty()).put("shown",v?.isShown==true).put("enabled",v?.isEnabled==true).put("top",v?.top ?: -1)
            JSONObject().put("focus",describe(activity.currentFocus)).put("first",manager?.findFirstVisibleItemPosition() ?: -1)
                .put("rows",JSONArray((home?.adapter as? it.lumetv.app.ui.VideoShelfAdapter)?.rows?.map {it.key}.orEmpty()))
                .put("more",JSONArray(visible().filter {it.contentDescription?.toString()?.startsWith("Mostra tutto:")==true}.map(::describe)))
                .put("children",JSONArray(home?.let {h->(0 until h.childCount).map {i->val child=h.getChildAt(i);describe(child).put("position",h.getChildViewHolder(child).bindingAdapterPosition)}}.orEmpty()))
        }}
        fun text(value:String)=main {visible().filterIsInstance<TextView>().any {it.text.toString().contains(value)}}
        fun matches(v:View,label:String)=v.isClickable && v !is EditText && (v is TextView && v.text.toString()==label || v.contentDescription?.toString()?.let {it==label || it.startsWith("$label:") || it.startsWith("$label,")}==true)
        fun click(label:String) {
            if(label.startsWith("Mostra tutto:")) {
                val target=label.substringAfter(":").trim()
                await("collection $target is available"){main {content.findViewWithTag<RecyclerView>("video_home")?.let {(it.adapter as? it.lumetv.app.ui.VideoShelfAdapter)?.rows?.any {row->row.title==target}}==true}}
                main {if(mobile){content.isFocusableInTouchMode=true;content.requestFocus()};val home=content.findViewWithTag<RecyclerView>("video_home");val index=(home.adapter as it.lumetv.app.ui.VideoShelfAdapter).rows.indexOfFirst {it.title==target};(home.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index,0)}
                runner.waitForIdleSync()
            }
            await("action $label is reachable"){main {
                val views=visible()
                val library=views.firstOrNull {it.tag=="lume_video_catalog"}
                val actions=library?.let(::allViews) ?: views
                val action=actions.lastOrNull {it.isShown && matches(it,label) && it.isEnabled}
                if(action==null) {
                    // Progressive rows and a saved home anchor can settle after the first scroll.
                    // Keep targeting the requested shelf until its real, enabled action is bound.
                    if(label.startsWith("Mostra tutto:"))content.findViewWithTag<RecyclerView>("video_home")?.let {home->
                        val index=(home.adapter as it.lumetv.app.ui.VideoShelfAdapter).rows.indexOfFirst {it.title==label.substringAfter(':').trim()}
                        if(index>=0)(home.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index,0)
                    }
                    false
                } else {action.requestRectangleOnScreen(android.graphics.Rect(0,0,action.width,action.height),true);if(!mobile)action.requestFocus();action.performClick()}
            }}
        }
        fun back()=main {activity.onBackPressedDispatcher.onBackPressed()}
        fun grid()=main {visible().filterIsInstance<RecyclerView>().firstOrNull {it.tag=="video_grid_results"}}
        fun player()=main {activity.javaClass.getDeclaredField(if(mobile)"player" else "tvPlayer").apply {isAccessible=true}.get(activity) as TvPlayer}
        fun posterAt(list:RecyclerView,position:Int)=main {(list.adapter as VideoPosterAdapter).catalog?.peek(position) ?: (list.adapter as VideoPosterAdapter).items.getOrNull(position)}
        await("source is loaded beyond the bounded live preview"){repo.videoSources.value==setOf(source,second)}
        click("Video")
        await("home contains continue watching films series and saved collections"){text("Continua a guardare") && text("Film") && main {content.findViewWithTag<RecyclerView>("video_home")?.adapter?.itemCount?.let {it>=5}==true}}
        verify("app brand and original navigation remain visible around Video"){main {visible().filterIsInstance<ImageView>().any {it.contentDescription=="Lume TV"} && visible().any {matches(it,"Canali")}}}
        await("movie artwork is rendered above channel-logo resolution"){main {visible().filterIsInstance<ImageView>().any {(it.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.width?.let {it>256}==true}}}
        capture("00-home")
        click("Mostra tutto: Film")
        await("film collection exposes every result with poster cards"){grid()?.adapter?.itemCount==181}
        if(!mobile)await("opening a collection puts remote focus on its posters"){grid()?.let {main {it.findContainingViewHolder(activity.currentFocus ?: it)!=null}}==true}
        var list=grid()!!;main {if(mobile){content.isFocusableInTouchMode=true;content.requestFocus()}else visible().filterIsInstance<TextView>().first {it.text.toString()=="Cerca"}.requestFocus();(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(155,0)}
        await("poster grid crosses three blocks without pagination"){posterAt(list,155)!=null && main {list.findViewHolderForAdapterPosition(155)!=null}}
        verify("poster grid keeps its display cache bounded"){main {(list.adapter as VideoPosterAdapter).catalog!!.cachedRows<=150}}
        if(!mobile) {
            val navigationPosition=main {if(155%(list.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount==0)156 else 155}
            await("deep poster is ready for directional navigation"){main {list.findViewHolderForAdapterPosition(navigationPosition)!=null}}
            main {list.findViewHolderForAdapterPosition(navigationPosition)!!.itemView.requestFocus()}
            runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            await("D-pad moves between deep poster cards"){main {list.findContainingViewHolder(activity.currentFocus ?: list)?.bindingAdapterPosition==navigationPosition-1}}
            runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            await("D-pad returns to the selected movie"){main {list.findContainingViewHolder(activity.currentFocus ?: list)?.bindingAdapterPosition==navigationPosition}}
        }
        capture("01-films")
        val selected=posterAt(list,155)!!
        main {list.findViewHolderForAdapterPosition(155)!!.itemView.apply {requestFocus();performClick()}}
        await("selecting a poster opens its title details before playback"){text(selected.title) && text("2023") && text("Un viaggio") && text("Guarda")}
        verify("browsing a movie does not automatically start its stream"){player().snapshot.channel==null}
        capture("02-details")
        click("Aggiungi alla mia lista")
        await("saved status updates on the details page"){text("Rimuovi dalla mia lista")}
        click("Guarda")
        await("movie plays through the existing VOD player"){player().snapshot.let {it.channel?.id==selected.id && it.mode==PlaybackMode.VOD && it.playing}}
        back();await("Back from playback restores the movie details"){text("Rimuovi dalla mia lista")}
        back();await("Back from details restores the deep poster-grid position"){main {list.isShown && list.findViewHolderForAdapterPosition(155)!=null}}
        if(!mobile)await("Back restores D-pad focus to the same poster"){main {list.findContainingViewHolder(activity.currentFocus ?: list)?.bindingAdapterPosition==155}}
        val monitor=runner.addMonitor(activity.javaClass.name,null,false);main {activity.recreate()}
        activity=monitor.waitForActivityWithTimeout(20_000) as? ComponentActivity ?: error("Activity recreation timed out");runner.removeMonitor(monitor);content=main {activity.findViewById(android.R.id.content)}
        await("recreation restores the Video route and its poster-grid anchor"){grid()?.let {main {it.adapter?.itemCount==181 && it.findViewHolderForAdapterPosition(155)!=null}}==true}
        list=grid()!!
        if(mobile) {
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await("landscape adapts poster columns and keeps an accessible row"){main {activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE && (list.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()>=0}}
            capture("03-landscape")
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            await("portrait returns to the selected title in a usable poster grid"){main {activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT && list.findViewHolderForAdapterPosition(155)?.itemView?.isShown==true}}
        }
        back();click("Serie")
        await("series appear as one title rather than separate channel rows"){grid()?.adapter?.itemCount==1}
        list=grid()!!;await("series poster is loaded"){posterAt(list,0)?.displayTitle=="Orbita" && main {list.findViewHolderForAdapterPosition(0)!=null}}
        main {list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()}
        await("series detail identifies its episode count"){text("3 episodi") && text("Stagioni ed episodi")}
        capture("04-series")
        click("Stagioni ed episodi")
        await("series episodes are sorted by season and episode"){grid()?.let {posterAt(it,0)?.title=="Orbita S01E01"}==true}
        click("Tutte le stagioni");click("Stagione 2")
        await("selecting a season returns exactly its episodes"){grid()?.adapter?.itemCount==2}
        list=grid()!!;await("selected season starts from its first episode"){posterAt(list,0)?.title=="Orbita S02E01" && main {list.findViewHolderForAdapterPosition(0)!=null}}
        if(!mobile)await("choosing a season returns remote focus to its episodes"){main {list.findContainingViewHolder(activity.currentFocus ?: list)!=null}}
        capture("05-episodes")
        main {list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()};click("Guarda")
        await("an episode plays in VOD mode without changing its stable ID"){player().snapshot.let {it.channel?.name=="Orbita S02E01" && it.mode==PlaybackMode.VOD && it.playing}}
        back();back();back();back();back()
        await("return from episodes restores the Video home"){main {content.findViewWithTag<RecyclerView>("video_home")?.isShown==true}}
        click("Mostra tutto: Continua a guardare")
        await("continue watching includes the previously saved progress"){grid()?.let {list->main {(list.adapter as VideoPosterAdapter).catalog?.cached?.any {it.id==movie.id && it.resume==21_000L}==true}}==true}
        list=grid()!!
        val resumeIndex=main {(list.adapter as VideoPosterAdapter).catalog!!.indexOf {it.id==movie.id}!!}
        main {list.findViewHolderForAdapterPosition(resumeIndex)!!.itemView.performClick()}
        click("Dall’inizio")
        await("starting a different video from the beginning clears its stored position"){player().snapshot.let {it.channel?.id==movie.id && it.playing && it.position<10_000}}
        val activePlayer=player();main {activePlayer.seek(17_000)}
        await("the shared VOD player can seek before restarting the same title"){player().snapshot.position>=16_000}
        back();await("return to the playing title offers its actual resume position"){text("Dall’inizio")}
        main {activePlayer.pauseForCatalog();activePlayer.seek(42_000)}
        await("paused video reaches the passive progress fixture"){player().snapshot.position>=41_000}
        val playBeforeProgress=main {visible().first {it.tag=="video_play"}}
        io {history.write(source,PlaybackTarget(movie.id,PlaybackMode.VOD),42_000,180_000)}
        await("visible details receive passive progress changes"){text("Riprendi da 0:42")}
        verify("passive detail progress keeps the existing action views"){main {visible().first {it.tag=="video_play"}===playBeforeProgress}}
        click("Dall’inizio")
        await("restarting the current video begins from zero without a second selection"){player().snapshot.let {it.channel?.id==movie.id && it.playing && it.position<10_000}}
        back();back()
        back()
        if(!mobile) {
            io {history.write(source,PlaybackTarget(selected.id,PlaybackMode.VOD),40_000,180_000)}
            val home=main {content.findViewWithTag<RecyclerView>("video_home")}
            fun continued()=main {(home.adapter as it.lumetv.app.ui.VideoShelfAdapter).rows.firstOrNull {it.key=="continue"}}
            await("continue shelf receives a passive progress update"){continued()?.page?.rows?.any {it.id==selected.id && it.position==40_000L}==true}
            val shelfIndex=main {(home.adapter as it.lumetv.app.ui.VideoShelfAdapter).rows.indexOfFirst {it.key=="continue"}}
            main {(home.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(shelfIndex,0)}
            await("continue posters are ready for remote focus"){main {(home.findViewHolderForAdapterPosition(shelfIndex) as? it.lumetv.app.ui.VideoShelfAdapter.Holder)?.list?.findViewHolderForAdapterPosition(0)!=null}}
            val shelf=main {home.findViewHolderForAdapterPosition(shelfIndex) as it.lumetv.app.ui.VideoShelfAdapter.Holder}
            val index=main {shelf.posters!!.items.indexOfFirst {it.id==selected.id}}
            await("updated title can receive focus"){main {shelf.list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()==true}}
            io {history.write(source,PlaybackTarget(selected.id,PlaybackMode.VOD),41_000,180_000)}
            await("continue shelf refreshes the changed progress"){continued()?.page?.rows?.any {it.id==selected.id && it.position==41_000L}==true}
            runner.waitForIdleSync()
            verify("a passive progress update preserves the focused continue poster"){main {
                val position=shelf.list.findContainingViewHolder(activity.currentFocus ?: shelf.list)?.bindingAdapterPosition ?: -1
                shelf.posters?.items?.getOrNull(position)?.id==selected.id
            }}
        }
        click("Cerca")
        main {visible().filterIsInstance<EditText>().single {it.contentDescription=="Cerca un video"}.setText("Concerto senza copertina")}
        await("search finds generic VOD without artwork or movie metadata"){grid()?.adapter?.itemCount==1 && grid()?.let {posterAt(it,0)?.title=="Concerto senza copertina"}==true}
        capture("06-no-cover")
        main {visible().filterIsInstance<EditText>().single {it.contentDescription=="Cerca un video"}.setText("")};await("clearing search restores the Video home"){main {content.findViewWithTag<RecyclerView>("video_home")?.isShown==true}};back();click("Filtri");click("Tutte le liste");click("Seconda lista")
        await("source filter scopes the home collections"){text("Seconda lista") && main {content.findViewWithTag<RecyclerView>("video_home")?.isShown==true}}
        click("Mostra tutto: Film");await("source filter applies to the full catalog"){grid()?.adapter?.itemCount==1 && grid()?.let {posterAt(it,0)?.title=="Alfa"}==true}
        io {repo.setEnabled(second,false);repo.setEnabled(source,false)}
        await("disabling all VOD sources restores the live catalog shell"){main {content.findViewWithTag<View>("lume_video_catalog")==null && visible().none {matches(it,"Video")}}}
        verify("navigation remains available after the last Video source disappears"){main {visible().any {matches(it,"Canali")}}}
        return JSONObject().put("passed",true).put("checks",JSONArray(checks)).put("mode",if(mobile)"phone" else "tv")
    }
}
