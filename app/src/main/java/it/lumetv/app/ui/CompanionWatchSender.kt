package it.lumetv.app.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import it.lumetv.app.companion.*
import it.lumetv.app.data.*
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

internal class WatchIntent(val channel:Channel,val position:suspend ()->Long,val onStarted:()->Unit)

/** Keeps local playback intact until the selected TV reports that this operation is playing. */
internal class CompanionWatchSender(private val pages:PlaylistPages,private val currentTv:()->SavedTelevision,
    private val repository:ChannelRepository,private val intent:WatchIntent,
    private val changeTv:()->Unit,private val remote:()->Unit,private val started:(SavedTelevision)->Unit) {
    fun show() {
        val activity=pages.activity;val scope=pages.scope
        pages.push("watch","Guarda sulla TV",currentTv().endpoint.name) {page->
            page.root.tag="companion_watch"
            page.text(intent.channel.name,22f,false)
            val summary=page.text("Verifico che il contenuto sia disponibile sulla TV.")
            val progress=ProgressBar(activity).apply {indeterminateTintList=ColorStateList.valueOf(TvStyle.accent)}
            page.body.addView(progress,LinearLayout.LayoutParams(activity.dp(32),activity.dp(32)).apply {bottomMargin=activity.dp(16)})
            val choose=page.row("Cambia TV","Scegli un altro televisore abbinato.",changeTv).apply {tag="companion_watch_change_tv"}
            val openRemote=page.row("Apri il telecomando","Puoi inviare la lista dal telefono alla TV.",remote).apply {visibility=View.GONE;tag="companion_watch_remote"}
            var connection:CompanionClient?=null;var endpoint:CompanionEndpoint?=null
            var id=UUID.randomUUID().toString();var reference:WatchReference?=null
            var state="new";var startSent=false;var job:Job?=null
            fun client():CompanionClient {
                val tv=currentTv()
                if(endpoint!=tv.endpoint){connection?.close();connection=CompanionClient(tv.endpoint,tv.pin,tv.token);endpoint=tv.endpoint}
                return requireNotNull(connection)
            }
            suspend fun call(action:String,args:JSONObject=JSONObject())=client().exchange("/v1/watch/$action",JSONObject(args.toString()).put("id",id),true)
            suspend fun await(initial:JSONObject):JSONObject {
                var value=initial
                while(value.getString("status") in setOf("resolving","starting")){delay(400);value=call("status")}
                return value
            }
            lateinit var primary:android.widget.TextView
            val close=page.action("Annulla"){pages.pop()}.apply {tag="companion_watch_close"}
            fun idle(){progress.visibility=View.GONE;choose.isEnabled=!startSent;close.text=if(startSent)"Chiudi" else "Annulla"}
            fun render(value:JSONObject) {
                state=value.getString("status");idle()
                when(state) {
                    "ready"->{
                        startSent=false;choose.isEnabled=true;close.text="Annulla";primary.text="Guarda sulla TV";primary.isEnabled=true
                        summary.text="${value.getString("title")} è disponibile su ${currentTv().endpoint.name}."
                        page.message(if(intent.channel.isVod)"Il video riprenderà dal punto raggiunto sul telefono." else "Il canale si aprirà sulla TV.")
                    }
                    "started"->{
                        primary.isEnabled=false;summary.text="Riproduzione avviata sulla TV."
                        if(page.alive){intent.onStarted();started(currentTv())}
                    }
                    else->{startSent=false;choose.isEnabled=true;close.text="Annulla";throw CompanionFailure(409,value.optString("error").ifBlank {"watch_cancelled"})}
                }
            }
            fun failure(error:Exception) {
                idle();primary.isEnabled=true;primary.text=if(startSent)"Verifica avvio" else "Riprova"
                val code=(error as? CompanionFailure)?.code ?: (error as? WatchMatchException)?.code
                if(code in setOf("watch_missing_request","not_authorized","not_found")){startSent=false;choose.isEnabled=true;primary.text="Riprova";close.text="Annulla"}
                openRemote.visibility=if(code=="watch_missing")View.VISIBLE else View.GONE
                summary.text=message(code,startSent)
                page.message(if(startSent)"Premi Verifica avvio per controllare la TV." else "Puoi scegliere un’altra TV o riprovare.",true)
            }
            fun launch(start:Boolean=false) {
                if(job?.isActive==true)return
                primary.isEnabled=false;choose.isEnabled=false;progress.visibility=View.VISIBLE;openRemote.visibility=View.GONE
                pages.hideKeyboard()
                job=scope.launch {
                    try {
                        val response=withTimeout(60_000) {
                            when {
                                startSent->await(call("status"))
                                start->{
                                    val current=repository.watchReference(intent.channel)
                                    if(current!=reference){reference=current;state="failed";throw CompanionFailure(409,"watch_local_changed")}
                                    val point=intent.position().coerceAtLeast(0)
                                    startSent=true;close.text="Chiudi"
                                    page.message("Attendo l’avvio sulla TV. La riproduzione sul telefono continua.")
                                    await(call("start",JSONObject().put("position",point)))
                                }
                                else->{
                                    if(state !in setOf("new","resolving")){id=UUID.randomUUID().toString();state="new"}
                                    page.message("Verifico il contenuto sulla TV…")
                                    val capabilities=client().getState()
                                    if(!capabilities.optBoolean("watchSupported"))throw CompanionFailure(404,"watch_unsupported")
                                    val ref=reference ?: repository.watchReference(intent.channel).also {reference=it}
                                    val value=call("resolve",JSONObject().put("reference",ref.json()));state="resolving"
                                    await(value)
                                }
                            }
                        }
                        if(page.alive)render(response)
                    }catch(timeout:TimeoutCancellationException){if(page.alive)failure(timeout)}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){if(page.alive)failure(error)}
                }
            }
            primary=page.action("Verifico…",true){launch(state=="ready" && !startSent)}.apply {tag="companion_watch_start";isEnabled=false}
            page.onDispose={
                job?.cancel()
                val existing=connection
                if(!startSent && existing!=null)scope.launch(Dispatchers.IO){try {withTimeoutOrNull(1500){existing.exchange("/v1/watch/cancel",JSONObject().put("id",id),true)}}catch(_:Exception){}finally {existing.close()}}
                else existing?.close()
            }
            launch()
        }
    }
    private fun message(code:String?,verifying:Boolean)=when(code) {
        "watch_missing"->"Il contenuto o la stessa lista non sono disponibili sulla TV. Puoi inviare la lista dal telecomando del telefono."
        "watch_ambiguous"->"Sulla TV ci sono più copie corrispondenti. Disattiva la lista duplicata e riprova."
        "watch_tv_changed"->"Il contenuto sulla TV è cambiato. Premi Riprova per verificare di nuovo prima di avviare."
        "watch_local_changed"->"La lista sul telefono è cambiata. Premi Riprova per verificare di nuovo il contenuto."
        "watch_not_seekable"->"Questo video sulla TV non consente di riprendere dal punto raggiunto. La visione sul telefono continua."
        "watch_playback_failed"->"La TV non riesce ad avviare il contenuto. La visione sul telefono continua."
        "watch_timeout","watch_failed"->"La TV non ha confermato l’avvio del contenuto. La visione sul telefono continua."
        "watch_unsupported","not_found"->"Aggiorna Lume sulla TV per usare Guarda sulla TV."
        "not_authorized"->"Il collegamento è stato revocato. Cambia TV o abbina di nuovo il telefono con il QR."
        "physical_approval_required","close_tv_dialog"->"Chiudi la finestra aperta sulla TV con il telecomando, poi premi Riprova."
        "tv_busy","watch_busy"->"La TV è occupata. Attendi qualche secondo e riprova."
        "watch_missing_request"->"La richiesta non è più disponibile. Controlla la TV e premi Riprova per una nuova verifica."
        else->if(verifying)"Collegamento interrotto. Apri Lume sulla TV e premi Verifica avvio. Il telefono continua a riprodurre."
            else "TV non raggiungibile. Apri Lume sulla TV, controlla la rete e premi Riprova."
    }
}
