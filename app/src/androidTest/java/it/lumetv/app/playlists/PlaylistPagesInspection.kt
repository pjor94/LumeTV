package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.PersonalGuideRepository
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PlaylistResource
import it.lumetv.app.ui.PersonalPlaylistsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Walks the real retained pages and private repositories. The runner rejects physical devices. */
object PlaylistPagesInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val root=requireNotNull(args.getString("fixtureRoot"))
        require(java.net.URI(root).host=="10.0.2.2" && java.net.URI(root).scheme=="http")
        val context=runner.targetContext
        val mobile=args.getString("mode")=="phone"
        val repo=PersonalPlaylistRepository.get(context)
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        val checks=mutableListOf<String>()
        fun verify(label:String,test:()->Boolean) {check(test()){label};checks+=label}
        fun await(label:String,test:()->Boolean) {val end=System.currentTimeMillis()+25_000;while(System.currentTimeMillis()<end){if(test()){checks+=label;return};Thread.sleep(80)};error(label)}
        io {repo.local();repo.state.value.forEach {repo.delete(it.id)}}
        val activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap {all(view.getChildAt(it))} else emptyList()
        val screen=main {all(activity.findViewById(android.R.id.content)).filterIsInstance<PersonalPlaylistsScreen>().single()}
        fun visible()=all(screen).filter {it.isShown}
        fun text(value:String)=main {visible().filterIsInstance<TextView>().any {it.text.toString().contains(value)}}
        fun matches(view:View,value:String):Boolean {
            if(!view.isClickable || !view.isShown)return false
            val label=if(view is TextView)view.text.toString() else view.contentDescription?.toString().orEmpty()
            return label==value || label.startsWith(value+"\n") || label.startsWith(value+":") || label.startsWith(value+",")
        }
        fun action(value:String)=visible().lastOrNull {matches(it,value)}
        fun click(value:String) {
            val end=System.currentTimeMillis()+5_000
            while(!main {action(value)?.isEnabled==true} && System.currentTimeMillis()<end)Thread.sleep(50)
            main {action(value)?.let {check(it.isEnabled){"Disabled: $value"};it.performClick()} ?: error("Missing action: $value")}
        }
        fun field(label:String)=visible().filterIsInstance<EditText>().singleOrNull {it.contentDescription==label}
            ?: error("Missing field: $label; page: "+visible().filterIsInstance<TextView>().take(5).joinToString {it.text.toString()})
        fun edit(label:String,value:String) {
            val end=System.currentTimeMillis()+5_000
            while(!main {visible().filterIsInstance<EditText>().any {it.contentDescription==label}} && System.currentTimeMillis()<end)Thread.sleep(50)
            main {field(label).setText(value)}
        }
        fun value(label:String)=main {field(label).text.toString()}
        fun back()=main {check(screen.handleBack())}
        fun capture(name:String) {runner.waitForIdleSync();Thread.sleep(200);runner.uiAutomation.takeScreenshot()?.let {image->
            File(context.cacheDir,"playlist-pages").apply {mkdirs()}.resolve(name+".png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        } ?: error("Screenshot unavailable")}
        val content=main {activity.findViewById<View>(android.R.id.content)}
        await("Options is available from the real catalog navigation"){main {all(content).any {matches(it,"Opzioni")}}}
        main {all(content).last {matches(it,"Opzioni")}.performClick()}
        await("Options displays its playlist entry"){main {all(content).any {matches(it,"Le mie liste")}}}
        capture("00-options")
        main {all(content).last {matches(it,"Le mie liste")}.performClick()}
        await("empty list page is in the Activity window"){text("Nessuna lista personale")}
        capture("01-empty")
        click("Aggiungi URL");edit("Nome della lista","Lista di prova");edit("Indirizzo HTTP o HTTPS della lista","$root/list.m3u")
        click("Lettura e contenuti");click("Windows-1252");click("Salva modifiche")
        await("reading editor returns to the retained import form"){text("Verifica lista")}
        verify("name and URL survive child navigation"){value("Nome della lista")=="Lista di prova" && value("Indirizzo HTTP o HTTPS della lista")=="$root/list.m3u"}
        click("Accesso ai server");click("Download della lista")
        edit("Header HTTP · un header per riga","Header non valido");click("Salva accesso")
        verify("invalid header stays inline with its original input"){text("Nome: valore") && value("Header HTTP · un header per riga")=="Header non valido"}
        edit("Header HTTP · un header per riga","X-List: ui-fixture");back()
        verify("dirty form uses inline discard actions"){text("Hai modifiche non salvate.") && text("Scarta modifiche")}
        capture("02-inline-discard");click("Continua a modificare")
        verify("continue editing preserves the header"){value("Header HTTP · un header per riga")=="X-List: ui-fixture"}
        click("Salva accesso");await("role editor returns to access page"){text("Mantieni cookie di sessione")}
        click("Salva modifiche");await("access editor returns to import page"){text("Verifica lista")}
        click("Verifica lista");await("HTTP rejection is inline and the form remains editable"){text("Consenti HTTP") && main {visible().filterIsInstance<TextView>().any {it.text.toString()=="Verifica lista" && it.isEnabled}}}
        click("Consenti HTTP per questa lista");click("Verifica lista")
        await("verification shows the preview on the original page"){text("Anteprima della lista") && text("Importa lista")}
        capture("03-import-preview")
        click("Importa lista");await("import opens the new source page"){text("Stato e ultima importazione") && repo.state.value.size==1}
        val id=repo.state.value.single().id
        verify("staged headers and encoding are persisted once"){io {repo.settings(id).rule(PlaylistResource.PLAYLIST).headers["X-List"]=="ui-fixture" && repo.settings(id).reading.encoding==it.lumetv.core.PlaylistEncoding.WINDOWS_1252}}
        capture("04-source")
        back();capture("04a-list-overview");click("Lista di prova")
        await("the styled source row opens its management page"){text("Stato e ultima importazione")}
        click("Lettura e contenuti");await("existing reading settings open as a page"){text("Codifica del testo")};click("Annulla")
        verify("returning from a loaded section clears its temporary message"){text("Stato e ultima importazione") && !text("Carico le impostazioni")}
        click("Sorgente");edit("Nome della lista","Lista rinominata");click("Modifica URL")
        verify("replacement asks to save pending source changes inline"){text("Salva le modifiche prima") && value("Nome della lista")=="Lista rinominata"}
        click("Salva modifiche");await("renaming refreshes the source heading"){text("Lista rinominata") && text("Stato e ultima importazione")}
        click("Sorgente");click("Modifica URL");await("replacement opens with saved settings"){text("Sostituisci lista") && text("Verifica lista")}
        edit("Indirizzo HTTP o HTTPS della lista","$root/broken.m3u");click("Verifica lista")
        await("failed replacement reports its error inline"){text("Verifica lista") && main {visible().filterIsInstance<TextView>().any {it.text.toString()=="Verifica lista" && it.isEnabled}}}
        verify("failed replacement keeps the saved snapshot and address"){repo.state.value.single().count==2 && io {repo.address(id)=="$root/list.m3u"}}
        back();click("Scarta modifiche");back()
        click("Guida e archivio");click("Impostazioni guida")
        edit("Giorni precedenti da conservare · 1–7","0");click("Salva modifiche")
        verify("invalid guide settings stay in their page"){text("La guida conserva fino a 7 giorni") && value("Giorni precedenti da conservare · 1–7")=="0"}
        edit("Giorni precedenti da conservare · 1–7","7");click("Consenti HTTP per questa guida");click("Salva modifiche")
        await("guide settings save and refresh without closing management"){text("Associa canali") && io {PersonalGuideRepository.get(context).state.value.any {it.sourceId==id && it.programmes>0 && !it.refreshing}}}
        click("Archivio");edit("Giorni di archivio · vuoto: usa la lista","5");click("Salva modifiche")
        await("archive options return to the guide overview"){text("Associa canali")}
        verify("guide and archive settings persist independently"){io {repo.settings(id).guide.historyDays==7 && repo.settings(id).guide.archiveDays==5}}
        click("Associa canali");await("channel matching is paged within management"){text("Canale uno")}
        click("Canale uno");click("Scegli canale XMLTV");await("guide picker remains an internal page"){text("Scegli la guida")}
        click("Guida 1");await("XMLTV choices are searchable in a page"){text("Canale XMLTV") && text("Nome guida")}
        click("Nome guida · manual");await("manual match returns directly to channel list"){text("Associa canali") && text("Canale uno")}
        capture("05-matching");back()
        click("Svuota guida scaricata");verify("guide clearing uses a dedicated confirmation page"){text("Indirizzi, associazioni")};click("Annulla")
        click("Impostazioni guida");capture("06-guide-settings");back();back()
        click("Accesso ai server");click("Video e audio");edit("Header HTTP · un header per riga","X-Video: unsaved")
        back();click("Scarta modifiche");back()
        verify("discarding a role leaves the source settings unchanged"){io {repo.settings(id).rule(PlaylistResource.MEDIA).headers.isEmpty()}}
        click("Stato e ultima importazione");await("import status is an ordinary page"){text("2 voci importate")};back()
        click("Sorgente");click("Modifica URL");edit("Indirizzo HTTP o HTTPS della lista","$root/slow.m3u");click("Verifica lista")
        await("slow verification exposes its cancellation action"){text("Interrompi verifica")}
        click("Interrompi verifica");await("cancel returns to the populated form"){text("Verifica interrotta") && main {visible().filterIsInstance<TextView>().any {it.text.toString()=="Verifica lista" && it.isEnabled}}}
        verify("cancelled verification never replaces the source"){io {repo.address(id)=="$root/list.m3u"} && value("Indirizzo HTTP o HTTPS della lista")=="$root/slow.m3u"}
        back();click("Scarta modifiche");back()
        if(!mobile) {
            main {check(action("Sorgente")?.requestFocus()==true)}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            verify("Right keeps focus in the TV settings column"){main {activity.currentFocus?.let {matches(it,"Sorgente")}==true}}
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
            verify("Left keeps focus in the TV settings column"){main {activity.currentFocus?.let {matches(it,"Sorgente")}==true}}
            repeat(24){runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)}
            verify("D-pad focus stays inside management at its lower boundary"){main {var view=activity.currentFocus;val focused=view;while(view!=null && view!==screen)view=view.parent as? View;view===screen && focused!==screen}}
            repeat(24){runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)}
            verify("D-pad focus stays inside management at its upper boundary"){main {var view=activity.currentFocus;while(view!=null && view!==screen)view=view.parent as? View;view===screen}}
        }
        click("Elimina lista");capture("07-delete");click("Annulla")
        verify("cancelled deletion keeps the source"){repo.state.value.size==1 && text("Stato e ultima importazione")}
        click("Elimina lista");click("Elimina definitivamente");await("deletion returns to the empty overview"){text("Nessuna lista personale") && repo.state.value.isEmpty()}
        verify("deletion removes associated private settings"){!File(context.filesDir,"personal-playlists/$id.sqlite").exists()}
        click("Aggiungi URL");edit("Nome della lista","Form da conservare");edit("Indirizzo HTTP o HTTPS della lista","$root/list.m3u")
        val saved=Bundle();main {screen.saveState(saved)}
        verify("saved state contains routes without URLs or form values"){!saved.toString().contains(root) && !saved.toString().contains("Form da conservare") && saved.getString("personal_playlists_source")==null}
        if(mobile) {
            main {val input=field("Nome della lista");input.requestFocus();(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
            await("phone keyboard opens without leaving the page"){main {androidx.core.view.ViewCompat.getRootWindowInsets(screen)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            capture("08-keyboard");back()
            await("Back first hides the keyboard and preserves both fields"){main {androidx.core.view.ViewCompat.getRootWindowInsets(screen)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==false} && value("Nome della lista")=="Form da conservare"}
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            Thread.sleep(700)
            verify("orientation change retains both import fields"){value("Nome della lista")=="Form da conservare" && value("Indirizzo HTTP o HTTPS della lista")=="$root/list.m3u"}
            capture("08-landscape")
            main {activity.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
        }
        main {screen.hide();screen.show()}
        verify("closing management releases abandoned imports"){File(context.filesDir,"personal-playlists").listFiles().orEmpty().none {it.name.startsWith(".draft-")}}
        capture("09-final")
        main {screen.hide();screen.chooseSource("builtin",false,null){}}
        val pickerState=Bundle();main {screen.saveState(pickerState)}
        verify("catalog source choice is an internal page without a management restore route"){text("Scegli la lista") && !pickerState.getBoolean("personal_playlists_open")}
        capture("10-source-picker");back()
        verify("Back closes the source chooser without selecting"){!screen.isShowing}
        var selected="";main {screen.chooseSource("builtin",false,null){selected=it}};click("Catalogo Lume")
        verify("source selection closes its page and invokes the catalog callback"){selected=="builtin" && !screen.isShowing}
        val fallbackFixture=io {
            val now=System.currentTimeMillis()
            val date=java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(java.time.ZoneOffset.UTC)
            fun stamp(time:Long)=date.format(java.time.Instant.ofEpochMilli(time))
            val xml="<tv><channel id=\"rai1\"><display-name>Rai 1</display-name></channel>"+
                "<channel id=\"lume-extra.it\"><display-name>Canale fuori catalogo</display-name><icon src=\"https://public.example.com/lume-extra.png\"/></channel>"+
                "<programme channel=\"rai1\" start=\"${stamp(now-60_000)}\" stop=\"${stamp(now+3_600_000)}\"><title>Programma integrato</title></programme>"+
                "<programme channel=\"lume-extra.it\" start=\"${stamp(now-60_000)}\" stop=\"${stamp(now+3_600_000)}\"><title>Programma fuori catalogo</title></programme></tv>"
            val shared=it.lumetv.app.data.CatalogGuideStore(context.filesDir)
            shared.stage(xml.byteInputStream(),setOf("rai1"),now).use {shared.commit(it)}
            val extraXml="<tv><channel id=\"Sky.Uno.it\"><display-name>Sky Uno</display-name><icon src=\"https://public.example.com/epgshare.png\"/></channel>"+
                "<channel id=\"lume-extra.it\"><display-name>Canale fuori catalogo</display-name></channel>"+
                "<programme channel=\"Sky.Uno.it\" start=\"${stamp(now-60_000)}\" stop=\"${stamp(now+3_600_000)}\"><title>Programma EPGShare</title><desc>Programmazione supplementare di prova.</desc></programme>"+
                "<programme channel=\"lume-extra.it\" start=\"${stamp(now-60_000)}\" stop=\"${stamp(now+3_600_000)}\"><title>Supplemento senza precedenza</title></programme></tv>"
            it.lumetv.app.data.CatalogGuideStore(context.filesDir,it.lumetv.app.data.PublicGuideFeed.EPGSHARE).let {extra->
                extra.stage(extraXml.byteInputStream(),emptySet(),now).use {extra.commit(it)}
            }
            val draft=repo.prepareFile(("#EXTM3U\n#EXTINF:-1 tvg-id=\"lume-extra.it\",Canale fuori catalogo\nhttps://media.example.com/live.m3u8\n"+
                "#EXTINF:-1 tvg-id=\"SkyUno.it\",Sky Uno\nhttps://media.example.com/sky.m3u8").toByteArray(),
                settings=it.lumetv.app.data.PlaylistSettings(access=mapOf(PlaylistResource.LOGO to it.lumetv.app.data.PlaylistAccessRule(mapOf("X-Logo" to "private-access")))))
            val source=try{repo.save("Ripiego Lume",draft)}finally{draft.close()}
            val channel=repo.local().single {it.sourceId==source && it.name=="Canale fuori catalogo"}
            val extraChannel=repo.local().single {it.sourceId==source && it.name=="Sky Uno"}
            val guide=PersonalGuideRepository.get(context)
            verify("native guide resolves a personal channel outside the built-in catalog from the full public index") {
                io {guide.window(listOf(channel),now,now+3_600_000)[channel.epgId]?.single()?.title=="Programma fuori catalogo"}
            }
            verify("public guide results remain searchable without personal XMLTV") {io {guide.search(channel,"fuori").second==1}}
            val extraShow=guide.window(listOf(extraChannel),now,now+3_600_000)[extraChannel.epgId]!!.single()
            verify("EPGShare completes the native personal guide after Lume has no matching schedule"){extraShow.title=="Programma EPGShare" && extraShow.guideOrigin=="epgshare" && !extraShow.replayAvailable}
            verify("secondary programmes never replace a known Lume schedule on an empty search"){io {guide.search(channel,"Supplemento").second==0}}
            val requests=java.util.Collections.synchronizedList(mutableListOf<okhttp3.Request>())
            for(url in listOf("https://public.example.com/lume-extra.png","https://public.example.com/epgshare.png")) {
                val key=java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}
                listOf(".image",".json").forEach {File(context.filesDir,"channel-artwork/$key$it").delete()}
            }
            val image=java.io.ByteArrayOutputStream().also {out->Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}.compress(Bitmap.CompressFormat.PNG,100,out)}.toByteArray()
            val client=okhttp3.OkHttpClient.Builder().addInterceptor {chain->
                val request=chain.request();requests+=request
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("Native fixture")
                    .body(image.toResponseBody("image/png".toMediaType())).build()
            }.build()
            val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.Default)
            try {
                val logos=it.lumetv.app.data.ChannelLogoRepository(context,it.lumetv.app.data.HttpClient(client),scope)
                var color=0
                kotlinx.coroutines.withTimeout(15_000){logos.request(channel){color=it?.bitmap?.getPixel(0,0) ?: 0}.join()}
                verify("native logo fallback loads artwork for a channel outside the built-in catalog"){color==android.graphics.Color.BLUE}
                verify("public logo requests contain no personal logo access headers"){requests.size==1 && requests.single().header("X-Logo")==null}
                requests.clear()
                kotlinx.coroutines.withTimeout(15_000){logos.request(extraChannel){color=it?.bitmap?.getPixel(0,0) ?: 0}.join()}
                verify("secondary public logo fills the missing Lume artwork without personal headers"){color==android.graphics.Color.BLUE && requests.size==1 && requests.single().url.encodedPath=="/epgshare.png" && requests.single().header("X-Logo")==null}
            }finally {scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()}
            guide.map(source,channel.id,-1)
            verify("native manual exclusion disables the public guide fallback"){io {guide.window(listOf(channel),now,now+3_600_000).isEmpty()}}
            guide.map(source,channel.id,null)
            guide.map(source,extraChannel.id,-1)
            verify("manual exclusion also blocks the supplemental schedule"){io {guide.window(listOf(extraChannel),now,now+3_600_000).isEmpty()}}
            guide.map(source,extraChannel.id,null)
            Triple(source,extraChannel,extraShow)
        }
        main {screen.show()};click("Ripiego Lume");click("Guida e archivio")
        verify("guide overview explains the ordered Lume and EPGShare fallback"){text("prima in Lume, poi in EPGShare IT1")}
        capture("11-lume-fallback")
        click("Impostazioni guida")
        verify("guide settings label controls personal and fallback guide together"){text("Attiva la guida per questa lista") && text("poi in EPGShare IT1")}
        capture("12-lume-fallback-settings")
        main {screen.hide()}
        val programmeDialog=main {
            if(mobile) {
                MobileActivity::class.java.getDeclaredMethod("showProgramme",it.lumetv.core.Channel::class.java,it.lumetv.app.data.Programme::class.java).apply {isAccessible=true}
                    .invoke(activity,fallbackFixture.second,fallbackFixture.third)
                MobileActivity::class.java.getDeclaredField("programmeDialog").apply {isAccessible=true}.get(activity) as android.app.Dialog
            }else {
                val guideView=TvActivity::class.java.getDeclaredField("guideView").apply {isAccessible=true}.get(activity) as it.lumetv.app.ui.GuideView
                guideView.showProgramme(fallbackFixture.second,fallbackFixture.third)
                it.lumetv.app.ui.GuideView::class.java.getDeclaredField("dialog").apply {isAccessible=true}.get(guideView) as android.app.Dialog
            }
        }
        verify("native programme details identify EPGShare as their public source") {main {all(programmeDialog.window!!.decorView).filterIsInstance<TextView>().any {it.text.toString()=="Guida: EPGShare IT1"}}}
        capture("13-epgshare-programme")
        main {programmeDialog.dismiss()};io {repo.delete(fallbackFixture.first)}
        return JSONObject().put("passed",true).put("checks",JSONArray(checks)).put("mode",args.getString("mode"))
    }
}
