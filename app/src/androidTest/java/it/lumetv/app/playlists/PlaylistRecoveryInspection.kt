package it.lumetv.app.playlists

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PersonalCatalogQuery
import it.lumetv.core.PlaylistLocalplay
import it.lumetv.app.ui.PersonalPlaylistsScreen
import it.lumetv.core.DrmScheme
import it.lumetv.core.PlaylistDiagnosticCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Uses only owned synthetic records; HomeInspection refuses physical devices. No playback is started. */
object PlaylistRecoveryInspection {
    fun run(runner:Instrumentation,args:Bundle):JSONObject {
        val context=runner.targetContext;val mobile=args.getString("mode")=="phone"
        val compatibility=args.getString("operation")=="playlist-import-compatibility"
        val expected=if(compatibility)250 else 4
        val root=requireNotNull(args.getString("fixtureRoot"));require(Uri.parse(root).host=="10.0.2.2")
        val checks=mutableListOf<String>()
        fun <T> main(block:()->T):T {val result=AtomicReference<Result<T>>();runner.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
        fun <T> io(block:suspend()->T)=runBlocking(Dispatchers.IO){block()}
        fun capture(name:String){runner.waitForIdleSync();Thread.sleep(200);runner.uiAutomation.takeScreenshot()?.let {bitmap->
            File(context.cacheDir,"playlist-recovery").apply {mkdirs()}.resolve("$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        fun record(label:String){checks+=label;File(context.cacheDir,"playlist-recovery-progress.json").writeText(JSONArray(checks).toString())}
        fun verify(label:String,test:()->Boolean){if(!test()){capture("failure");error(label)};record(label)}
        fun await(label:String,test:()->Boolean){val end=System.currentTimeMillis()+30_000;while(System.currentTimeMillis()<end){if(test()){record(label);return};Thread.sleep(50)};capture("failure");error(label)}
        val repo=PersonalPlaylistRepository.get(context)
        io {repo.local();repo.state.value.toList().forEach {repo.delete(it.id)}}
        context.getSharedPreferences("lume",0).edit().putString("catalog_source","builtin").remove("last_channel").commit()
        var activity=runner.startActivitySync(Intent(context,if(mobile)MobileActivity::class.java else TvActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        runner.waitForIdleSync()
        var content=main {activity.findViewById<View>(android.R.id.content)}
        fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList()
        fun visible()=all(content).filter {it.isShown}
        fun text(value:String)=main {visible().filterIsInstance<TextView>().any {it.text.toString().contains(value)}}
        fun matches(v:View,label:String):Boolean {
            if(!v.isClickable || !v.isEnabled)return false
            val value=if(v is TextView)v.text.toString() else v.contentDescription?.toString().orEmpty()
            return value==label || value.startsWith(label+"\n") || value.startsWith(label+":") || value.startsWith(label+",")
        }
        fun click(label:String){await("action $label is reachable"){main {visible().lastOrNull {matches(it,label)}?.let {if(!mobile)it.requestFocus();it.performClick();true}==true}}}
        fun edit(label:String,value:String)=main {visible().filterIsInstance<EditText>().single {it.contentDescription==label}.setText(value)}
        click("Opzioni");click("Le mie liste");click("Aggiungi URL")
        edit("Nome della lista","Recupero importazione");edit("Indirizzo HTTP o HTTPS della lista","$root/${if(compatibility)"compatibility" else "recovery"}.m3u")
        click("Consenti HTTP per questa lista");click("Verifica lista")
        await("preview contains all valid entries"){text("$expected voci importabili") && text("Importa lista")}
        runner.waitForIdleSync();Thread.sleep(200)
        if(compatibility) {
            verify("preview retains three rejection details ahead of 27 template lines"){text("3 escluse") && text("27 righe ignorate") && text("Bad DRM") && text("Bad Headers") && text("Bad Addon")}
            verify("a directive-only trailer is informational"){text("direttive finali senza canale ignorate") && !text("voce incompleta: indirizzo mancante")}
        } else verify("preview distinguishes recovered entries from ignored lines"){text("1 voce recuperata") && text("2 righe ignorate")}
        if(!compatibility)verify("preview warns about the incomplete entry"){text("voci incomplete escluse") && text("riga") && text("1 esclusa")}
        verify("late progress cannot overwrite the final verification message"){!text("Verifico…") && !text("Verifico la lista")}
        verify("diagnostics do not display the synthetic key or private header"){!text("ffeeddccbbaa99887766554433221100") && !text("private-value")}
        capture("01-preview")
        click("Importa lista")
        await("saving opens source management with all recovered channels"){text("Stato e ultima importazione") && repo.state.value.singleOrNull()?.count==expected}
        val id=repo.state.value.single().id
        val channels=io {repo.local()}
        if(compatibility) {
            verify("the full catalog remains searchable beyond its cached preview"){io {repo.catalog(PersonalCatalogQuery(source=id,text="Canale 249")).rows.singleOrNull()?.name=="Canale 249"}}
            verify("RSI channels remain available in the personal source"){channels.count {it.name.startsWith("RSI LA")}==2}
            for(name in listOf("K2","Frisbee")) {
                val secure=io {repo.resolve(channels.single {it.name==name}.id,okhttp3.OkHttpClient())}
                verify("$name infers six ClearKey pairs without an explicit DRM type"){secure.drm?.scheme==DrmScheme.CLEARKEY && JSONObject(secure.drm!!.clearKeyResponse!!).getJSONArray("keys").length()==6}
            }
            val rai=io {repo.resolve(channels.single {it.name=="Rai 4K"}.id,okhttp3.OkHttpClient())}
            verify("Rai 4K uses its abbreviated user agent and HLS format hint"){rai.headers["User-Agent"]=="HbbTV/1.6.1" && rai.mimeType=="application/x-mpegURL"}
            val normal=io {repo.resolve(channels.single {it.name=="Canale 13"}.id,okhttp3.OkHttpClient())}
            verify("following channels inherit neither implicit ClearKey nor abbreviated headers"){normal.drm==null && normal.headers["User-Agent"]!="HbbTV/1.6.1"}
            val commands=channels.filter {it.name in setOf("Nove","Real Time","Food Network","Discovery","Giallo","DMAX","HGTV Italia","Motor Trend")}
            var providerCalls=0
            val client=okhttp3.OkHttpClient.Builder().addInterceptor {chain->
                providerCalls++
                val request=chain.request()
                val body=when {
                    request.method=="POST"->"{\"data\":{\"attributes\":{\"streaming\":[{\"type\":\"hls\",\"url\":\"$root/owned/live.m3u8\"}]}}}"
                    "/token" in request.url.toString()->"{\"data\":{\"attributes\":{\"token\":\"owned-token\"}}}"
                    else->"{\"settings\":{\"site\":{\"simulcast\":{\"sonicEndpoint\":\"$root\",\"sonicRealm\":\"it\",\"channelId\":\"3\"}}}}"
                }
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build()
            verify("all eight localplay routes persist as typed sources"){commands.size==8 && io {commands.all {repo.playbackEntry(it.id).entry.localplay!=null}}}
            for(channel in commands) {
                val stream=io {repo.resolve(channel.id,client)}
                verify("${channel.name} uses the shared resolver and retains personal isolation"){stream.url=="$root/owned/live.m3u8" && stream.personal && stream.personalNetworkToken.isNotBlank()}
            }
            verify("MotorTrend uses the explicit Turbo alias"){io {repo.playbackEntry(commands.single {it.name=="Motor Trend"}.id).entry.localplay==PlaylistLocalplay.MOTOR_TREND}}
            verify("provider resolution uses the existing three-step protocol for all routes"){providerCalls==24}
        } else {
        verify("the channel following the incomplete block is retained"){channels.any {it.name=="Ultima"}}
        val secure=io {repo.resolve(channels.single {it.name=="Canale protetto"}.id,okhttp3.OkHttpClient())}
        verify("prefix ClearKey MIME and headers reach the playback configuration"){
            secure.drm?.scheme==DrmScheme.CLEARKEY && secure.mimeType=="application/dash+xml" && secure.headers["User-Agent"]=="RecoveryPlayer" && secure.headers["X-Diag"]=="private-value"
        }
        val normal=io {repo.resolve(channels.single {it.name=="Normale"}.id,okhttp3.OkHttpClient())}
        verify("the next channel keeps its own HLS MIME without inherited DRM or private headers"){normal.drm==null && normal.mimeType=="application/x-mpegURL" && "X-Diag" !in normal.headers && "RecoveryPlayer" !in normal.headers.values}
        }
        click("Stato e ultima importazione")
        await("saved status retains the recovery counts"){text("$expected voci importate") && if(compatibility)text("27 righe ignorate") && text("3 escluse") else text("1 voce recuperata") && text("2 righe ignorate")}
        capture("02-status")
        val restored=io {PersonalPlaylistRepository(context).apply {local()}.importReport(id)}
        verify("a newly loaded repository retains the structured diagnostics"){
            if(compatibility)restored?.count==250 && restored.excluded==3 && restored.structuralIssues==0 && restored.ignoredLines==27 && restored.diagnostics.take(3).all {it.excluded && it.channel.startsWith("Bad ") && it.reason!=null} && restored.diagnosticCounts[PlaylistDiagnosticCode.GROUP_ARTWORK]==16
                else restored?.recovered==1 && restored.ignoredLines==2 && restored.structuralIssues==1 && restored.diagnostics.any {it.code==PlaylistDiagnosticCode.MISSING_STREAM && it.line>0}
        }
        val monitor=runner.addMonitor(activity.javaClass.name,null,false);main {activity.recreate()}
        activity=monitor.waitForActivityWithTimeout(15_000) as? ComponentActivity ?: error("Recreation timed out");runner.removeMonitor(monitor)
        content=main {activity.findViewById(android.R.id.content)}
        await("recreation returns to the saved source management"){text("Stato e ultima importazione")}
        click("Stato e ultima importazione")
        await("recreation preserves counts without reimporting"){text("$expected voci importate") && if(compatibility)text("27 righe ignorate") && text("3 escluse") else text("1 voce recuperata") && text("2 righe ignorate")}
        verify("management remains inside the ordinary Activity window"){main {visible().filterIsInstance<PersonalPlaylistsScreen>().singleOrNull()?.isShowing==true}}
        capture("03-restored-status")
        return JSONObject().put("passed",true).put("mode",if(mobile)"phone" else "tv").put("checks",JSONArray(checks))
    }
}
