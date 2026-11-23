package it.lumetv.app.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import it.lumetv.app.companion.*
import it.lumetv.app.data.*
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** The remote stays underneath this flow; only its explicitly selected TV receives the playlist. */
internal class CompanionPlaylistSender(private val pages:PlaylistPages,private val tv:SavedTelevision,
    private val repository:PersonalPlaylistRepository) {
    private val activity=pages.activity
    private val scope=pages.scope
    fun show() {
        pages.push("send-lists","Invia lista alla TV",tv.endpoint.name) {page->
            page.root.tag="companion_send_lists"
            page.text("Scegli una lista salvata sul telefono. Potrai controllare l’anteprima prima di importarla sulla TV.")
            val column=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};page.body.addView(column)
            val empty=page.text("Non ci sono liste da inviare. Aggiungine una da Opzioni → Le mie liste.").apply {visibility=View.GONE}
            var loading:Job?=null
            fun load() {
                loading?.cancel()
                loading=scope.launch {
                    try {
                        repository.local();if(!page.alive)return@launch
                        column.removeAllViews()
                        val sources=repository.state.value.filter {it.count>0}
                        empty.visibility=if(sources.isEmpty())View.VISIBLE else View.GONE
                        page.message("")
                        sources.forEach {source->
                            page.addRow(column,source.name,"${source.count} voci · ${if(source.location==PlaylistLocation.URL)"Da URL" else "Da file"}") {send(source)}
                                .tag="companion_send_source"
                        }
                    }catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){page.message("Non riesco a leggere le liste. Torna a Le mie liste e riprova.",true)}
                }
            }
            page.onRefresh=::load;page.onDispose={loading?.cancel()};load()
        }
    }
    private fun send(source:PersonalPlaylistSource) {
        pages.push("send-playlist","Invia lista",tv.endpoint.name) {page->
            page.root.tag="companion_send_preview"
            val name=page.field("Nome sulla TV",source.name,100).apply {tag="companion_send_name"}
            val progress=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate=true;max=100;progressTintList=ColorStateList.valueOf(TvStyle.accent)
                indeterminateTintList=ColorStateList.valueOf(TvStyle.accent);contentDescription="Invio della lista"
            }
            page.body.addView(progress,LinearLayout.LayoutParams(-1,activity.dp(6)).apply {topMargin=activity.dp(12);bottomMargin=activity.dp(20)})
            val preview=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE};page.body.addView(preview)
            page.text("Include i canali, i video e le impostazioni di accesso e della guida della lista.",14f)
            val retry=page.row("Riprova","Riprendi l’invio alla stessa TV.") {}.apply {visibility=View.GONE;tag="companion_send_retry"}
            val connection=CompanionClient(tv.endpoint,tv.pin,tv.token)
            val client=CompanionPlaylistClient(connection)
            var id=UUID.randomUUID().toString()
            var exported:ExportedPlaylist?=null
            var job:Job?=null
            var status="uploading"
            var confirming=false
            val cancel=page.action("Annulla"){pages.pop()}.apply {tag="companion_send_cancel"}
            lateinit var confirm:android.widget.TextView
            fun showState(state:org.json.JSONObject) {
                status=state.getString("status");progress.visibility=View.GONE
                if(status=="imported") {
                    preview.visibility=View.VISIBLE;preview.removeAllViews()
                    preview.addView(TvStyle.text(activity,"Lista importata",22f,bold=true))
                    preview.addView(TvStyle.text(activity,"La lista è disponibile su ${tv.endpoint.name}.",16f,TvStyle.muted))
                    page.message("Importazione completata sulla TV.");confirm.visibility=View.GONE;retry.visibility=View.GONE
                    name.isEnabled=false;cancel.text="Chiudi";cancel.tag="companion_send_done"
                    return
                }
                if(status=="failed"){confirming=false;cancel.text="Annulla"}
                if(status!="ready")throw CompanionFailure(400,state.optString("error").ifBlank {"playlist_invalid"})
                val report=state.getJSONObject("preview")
                preview.visibility=View.VISIBLE;preview.removeAllViews()
                preview.addView(TvStyle.text(activity,"Anteprima sulla TV",20f,bold=true))
                preview.addView(TvStyle.text(activity,"${report.getInt("count")} voci pronte da importare",16f,TvStyle.muted))
                val sample=report.getJSONArray("sample")
                for(index in 0 until sample.length())preview.addView(TvStyle.text(activity,sample.getString(index),16f))
                confirming=false;cancel.text="Annulla";confirm.isEnabled=true;name.isEnabled=true;confirm.text="Importa sulla TV"
                page.message(if(state.optString("error").isNotBlank())message(state.getString("error")) else "Controlla l’anteprima, poi importa la lista.")
            }
            fun failed(error:Exception) {
                progress.visibility=View.GONE;retry.visibility=View.VISIBLE;name.isEnabled=!confirming
                retry.bind(if(confirming)"Verifica esito" else "Riprova",
                    if(confirming)"Controlla se la TV ha completato l’importazione." else "Riprendi l’invio alla stessa TV.")
                page.message(if(confirming && error !is CompanionFailure)"Collegamento interrotto. Apri Lume sulla TV e premi Verifica esito."
                    else message((error as? CompanionFailure)?.code),true)
            }
            fun upload() {
                if(job?.isActive==true)return
                retry.visibility=View.GONE;confirm.isEnabled=false;progress.visibility=View.VISIBLE;progress.isIndeterminate=true
                page.message(if(confirming)"Controllo l’importazione sulla TV…" else "Preparo la lista da inviare…")
                job=scope.launch {
                    try {
                        val state=withTimeout(5*60_000L) {
                            if(confirming)client.await(id,client.status(id)) else {
                                if(status=="failed"){client.cancel(id);id=UUID.randomUUID().toString();status="uploading"}
                                val file=exported ?: repository.exportPlaylist(source.id).also {exported=it}
                                val uploaded=client.upload(id,file) {sent,total->activity.runOnUiThread {
                                    if(page.alive){progress.isIndeterminate=false;progress.progress=(sent*100/total).toInt();page.message("Invio alla TV… ${sent*100/total}%")}
                                }}
                                if(uploaded.getString("status")=="preparing"){progress.isIndeterminate=true;page.message("Verifico la lista sulla TV…")}
                                client.await(id,uploaded)
                            }
                        }
                        if(page.alive)showState(state)
                    }catch(timeout:TimeoutCancellationException){if(page.alive)failed(timeout)}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){if(page.alive)failed(error)}
                }
            }
            confirm=page.action("Importa sulla TV",true) {
                if(job?.isActive==true || status!="ready")return@action
                val title=name.text.toString().trim()
                if(title.isBlank()){page.message("Assegna un nome alla lista sulla TV.",true);name.requestFocus();return@action}
                pages.hideKeyboard();confirming=true;confirm.isEnabled=false;name.isEnabled=false;retry.visibility=View.GONE;cancel.text="Chiudi"
                progress.visibility=View.VISIBLE;progress.isIndeterminate=true;page.message("Importo la lista sulla TV…")
                job=scope.launch {
                    try {val state=withTimeout(5*60_000L){client.await(id,client.commit(id,title))};if(page.alive){confirming=false;showState(state)}}
                    catch(timeout:TimeoutCancellationException){if(page.alive)failed(timeout)}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){if(page.alive){
                        if((error as? CompanionFailure)?.code in setOf("playlist_name_exists","playlist_limit","playlist_total")) {
                            confirming=false;progress.visibility=View.GONE;confirm.isEnabled=true;name.isEnabled=true;cancel.text="Annulla"
                            page.message(message((error as CompanionFailure).code),true)
                        }else failed(error)
                    }}
                }
            }.apply {tag="companion_send_import";isEnabled=false}
            retry.setOnClickListener {upload()}
            page.onDispose={
                job?.cancel();exported?.close()
                scope.launch(Dispatchers.IO){try {if(status!="imported")withTimeoutOrNull(2000){client.cancel(id)}}catch(_:Exception){}finally {connection.close()}}
            }
            upload()
        }
    }
    private fun message(code:String?):String=when(code) {
        "not_found"->"Aggiorna Lume sulla TV per ricevere le liste dal telefono."
        "not_authorized"->"Il collegamento è stato revocato. Torna al telecomando e abbina di nuovo la TV."
        "playlist_busy"->"La TV sta ricevendo un’altra lista. Completa o annulla quell’invio, poi riprova."
        "playlist_name_exists"->"Sulla TV esiste già una lista con questo nome. Scegli un nome diverso."
        "playlist_limit"->"La TV contiene già 10 liste. Rimuovine una dalla TV e riprova."
        "playlist_total"->"L’importazione supererebbe il limite di un milione di voci sulla TV."
        "playlist_space"->"Spazio insufficiente sulla TV. Libera spazio e riprova."
        "playlist_invalid","playlist_integrity"->"La lista ricevuta non è valida. Riprova l’invio dal telefono."
        "playlist_missing"->"L’invio sulla TV è scaduto o è stato interrotto. Controlla Le mie liste sulla TV prima di riprovare."
        "playlist_save_failed"->"La TV non ha salvato la lista. Controlla lo spazio disponibile e riprova."
        else->"Collegamento interrotto. Apri Lume sulla TV, controlla la rete e premi Riprova."
    }
}
