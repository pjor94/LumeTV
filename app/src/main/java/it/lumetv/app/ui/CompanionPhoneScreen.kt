package it.lumetv.app.ui

import android.app.AlertDialog
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import it.lumetv.app.BuildConfig
import it.lumetv.app.companion.*
import it.lumetv.app.ui.mobile.MobileStyle
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Phone companion is a separate destination: local playback is never redirected implicitly. */
@android.annotation.SuppressLint("ViewConstructor")
internal class CompanionPhoneScreen(private val activity:ComponentActivity,private val scope:CoroutineScope):FrameLayout(activity) {
    private val store=CompanionStore(activity)
    private val pages=PlaylistPages(activity,scope,true,::hide)
    private var saved=emptyList<SavedTelevision>()
    private var found=emptyList<CompanionEndpoint>()
    private var discoveryStatus:TextView?=null
    private var previous:View?=null
    private val accessibility=linkedMapOf<View,Int>()
    private var pairingJob:Job?=null
    private var opening:Job?=null
    private var automaticEntry=false
    private var watchIntent:WatchIntent?=null
    private var scanning=false
    private var polling:Job?=null
    private var client:CompanionClient?=null
    private var target:SavedTelevision?=null
    private var latest:JSONObject?=null
    private var remote:CompanionRemoteView?=null
    private var sendLists:OptionsRowView?=null
    private var repair:View?=null
    private var needsPairing=false
    private var connected=false
    private var commanding=false
    private var commandJob:Job?=null
    private data class PendingCommand(val session:String,val action:String,val args:JSONObject,val queuedAt:Long)
    private val commands=java.util.ArrayDeque<PendingCommand>()
    private var dialog:AlertDialog?=null
    private val discovery=CompanionDiscovery(activity,{devices->
        found=devices;renderList()
        val selected=target
        val updated=devices.firstOrNull {it.id==selected?.endpoint?.id}
        if(selected!=null && updated!=null && (updated.host!=selected.endpoint.host || updated.port!=selected.endpoint.port)) {
            val controlling=pages.contains("remote")
            if(controlling)stopPolling()
            latest=null;target=selected.copy(endpoint=updated.copy(name=selected.endpoint.name))
            if(controlling)startPolling()
        }
    },{discoveryStatus?.text="Ricerca non disponibile. Usa il QR o inserisci l’indirizzo della TV."})
    var isShowing=false;private set
    private val scan=activity.registerForActivityResult(ScanContract()) {result->
        scanning=false
        if(isShowing && result.contents!=null) {
            val invite=runCatching {CompanionInvite.decode(result.contents)}.getOrNull()
            if(invite==null)pages.current?.message("Questo QR non è un collegamento Lume valido.")
            else pairFromQr(invite)
        }
    }
    init {
        visibility=GONE;setBackgroundColor(TvStyle.background);isClickable=true;isSaveEnabled=false
        addView(pages,LayoutParams(-1,-1))
        pages.onChanged={pages.current?.root?.findViewWithTag<View>("playlist_page_back")?.setOnClickListener {handleBack()}}
        activity.lifecycle.addObserver(object:DefaultLifecycleObserver {
            override fun onStart(owner:LifecycleOwner){if(isShowing){discovery.start();if(pages.contains("remote"))startPolling()}}
            override fun onStop(owner:LifecycleOwner){discovery.stop();stopPolling();dialog?.dismiss();dialog=null}
            override fun onDestroy(owner:LifecycleOwner){opening?.cancel();pairingJob?.cancel();stopPolling()}
        })
    }
    fun show() {
        if(isShowing)return
        previous=activity.currentFocus
        (parent as? ViewGroup)?.let {parent->for(i in 0 until parent.childCount)parent.getChildAt(i).takeIf {it!==this}?.let {
            accessibility[it]=it.importantForAccessibility;it.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }}
        isShowing=true;visibility=VISIBLE;bringToFront();home();automaticEntry=true;discovery.start()
        opening=scope.launch {
            val (known,last)=withContext(Dispatchers.IO){store.saved() to store.lastTelevision}
            if(!isShowing)return@launch
            saved=known;renderList()
            if(automaticEntry && pages.current?.key=="home" && !scanning) {
                automaticEntry=false
                val preferred=saved.firstOrNull {it.endpoint.id==last} ?: saved.singleOrNull()
                if(preferred!=null)connect(preferred) else if(saved.isEmpty())scanQr()
            }
        }
    }
    fun watch(request:WatchIntent) {
        if(isShowing)hide()
        watchIntent=request;show()
    }
    fun hide() {
        if(!isShowing)return
        isShowing=false;automaticEntry=false;watchIntent=null;opening?.cancel();pairingJob?.cancel();stopPolling();discovery.stop();dialog?.dismiss();dialog=null
        pages.reset();visibility=GONE;accessibility.forEach {(v,value)->v.importantForAccessibility=value};accessibility.clear()
        previous?.takeIf {it.isShown}?.requestFocus()
    }
    fun handleBack():Boolean {
        if(!isShowing)return false
        pairingJob?.cancel();commandJob?.cancel();dialog?.dismiss();dialog=null
        if(pages.current?.key in setOf("home","remote"))hide() else pages.back()
        return true
    }
    override fun focusSearch(focused:View?,direction:Int):View? =
        android.view.FocusFinder.getInstance().findNextFocus(this,focused,direction) ?: focused
    private fun home() {
        stopPolling();target=null;latest=null;remote=null;repair=null;automaticEntry=false;discoveryStatus=null
        pages.push("home","Telecomando","Sulla TV: Opzioni → Collega telefono. Inquadra il QR.",replace=pages.current?.key=="home") {page->
            page.root.tag="companion_phone_screen"
            page.onRefresh=::renderList
            page.row("Scansiona QR","Collega una TV senza digitare codici.",::scanQr).tag="companion_scan"
            page.body.addView(LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="companion_tv_list"})
            page.row("Altri metodi","Usa il codice se non puoi inquadrare il QR.",::otherMethods).tag="companion_methods"
            renderList()
        }
    }
    private fun chooseTv() {pages.reset();home();discovery.start()}
    private fun scanQr() {
        automaticEntry=false
        if(scanning)return
        scanning=true
        runCatching {scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
            .setOrientationLocked(false).setCaptureActivity(CompanionCaptureActivity::class.java)
            .setPrompt("Sulla TV: Opzioni → Collega telefono.\nInquadra il QR per aprire il telecomando."))}
            .onFailure {scanning=false;pages.current?.message("Fotocamera non disponibile. Usa Altri metodi per collegare la TV.")}
    }
    private fun otherMethods() {
        automaticEntry=false
        pages.push("methods","Altri metodi","Sulla TV: Opzioni → Collega telefono → Altri metodi.") {page->
            page.body.addView(LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="companion_tv_list"})
            discoveryStatus=page.text("Cerco TV sulla rete di casa…",14f)
            page.row("Inserisci indirizzo","IP, porta e codice mostrati sulla TV.") {pairForm(null)}.tag="companion_manual"
            page.onRefresh=::renderList
            renderList()
        }
    }
    private fun renderList() {
        if(pages.current?.key !in setOf("home","methods"))return
        val page=pages.current ?: return
        val column=page.root.findViewWithTag<LinearLayout>("companion_tv_list") ?: return;column.removeAllViews()
        if(page.key=="methods") {
            found.take(16).forEach {endpoint->page.addRow(column,endpoint.name,"Inserisci il codice mostrato sulla TV.") {pairForm(endpoint)}.tag="companion_discovered_tv"}
            discoveryStatus?.text=if(found.isEmpty())"Nessuna TV trovata. Puoi usare l’indirizzo mostrato sulla TV." else "${found.size} TV trovate sulla rete."
            return
        }
        if(saved.isNotEmpty())column.addView(TvStyle.text(activity,"Le tue TV",14f,TvStyle.accent,true),LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(16);bottomMargin=activity.dp(8)})
        saved.forEach {tv->
            page.addRow(column,tv.endpoint.name,"Apri il telecomando · tieni premuto per rimuovere") {
                automaticEntry=false
                val current=found.firstOrNull {it.id==tv.endpoint.id}
                connect(tv.copy(endpoint=current?.copy(name=tv.endpoint.name) ?: tv.endpoint))
            }.apply {tag="companion_saved_tv";setOnLongClickListener {
                dialog=AlertDialog.Builder(activity).setTitle("Rimuovere ${tv.endpoint.name}?")
                    .setMessage("Elimina il collegamento salvato su questo telefono. Puoi revocare l’autorizzazione anche dalle opzioni della TV.")
                    .setNegativeButton("Annulla",null).setPositiveButton("Rimuovi") {_,_->scope.launch {
                        withContext(Dispatchers.IO){store.forget(tv.endpoint.id)};saved=saved.filterNot {it.endpoint.id==tv.endpoint.id};renderList()
                    }}.show();true
            }}
        }
    }
    private fun pairForm(endpoint:CompanionEndpoint?) {
        automaticEntry=false
        pages.push("pair","Collega con codice","Conferma il collegamento anche sullo schermo della TV.") {page->
            val host=if(endpoint==null)page.field("Indirizzo IP della TV","",32).apply {inputType=InputType.TYPE_CLASS_PHONE;tag="companion_host"} else null
            val port=if(endpoint==null)page.field("Porta","",5).apply {inputType=InputType.TYPE_CLASS_NUMBER;tag="companion_port"} else null
            if(endpoint!=null)page.text("${endpoint.name} · ${endpoint.host}:${endpoint.port}",18f,false)
            val code=page.field("Codice di collegamento","",24).apply {
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                hint="XXXX XXXX XXXX XXXX";tag="companion_code"
            }
            page.text("Sulla TV: Opzioni → Collega telefono → Altri metodi. Il codice contiene 16 lettere e numeri.")
            val connect=page.row("Collega","Richiedi l’autorizzazione sulla TV.") {}
            connect.tag="companion_pair"
            connect.setOnClickListener {
                if(pairingJob?.isActive==true)return@setOnClickListener
                val selected=runCatching {endpoint ?: CompanionEndpoint(host!!.text.toString().trim(),port!!.text.toString().toInt())}.getOrNull()
                val secret=CompanionProtocol.normalizeCode(code.text.toString())
                if(selected==null || !CompanionProtocol.validCode(secret)){page.message("Controlla indirizzo, porta e codice di 16 caratteri.");return@setOnClickListener}
                pages.hideKeyboard();connect.isEnabled=false
                requestPairing(page,selected,secret,null,onFinished={connect.isEnabled=true})
            }
        }
    }
    private fun pairFromQr(invite:CompanionInvite) {
        pages.push("pair","Collegamento alla TV",invite.endpoint.name.ifBlank {"Telecomando"}) {page->
            page.root.tag="companion_pair_connecting"
            page.text("Il telecomando si aprirà automaticamente tra pochi istanti.",18f,false)
            val retry=MobileStyle.button(activity,"Scansiona di nuovo") {chooseTv();scanQr()}.apply {visibility=GONE;tag="companion_scan_again"}
            page.body.addView(retry,LinearLayout.LayoutParams(-1,-2))
            requestPairing(page,invite.endpoint,invite.code,invite.pin,onError={retry.visibility=VISIBLE})
        }
    }
    private fun requestPairing(page:PlaylistPages.Page,endpoint:CompanionEndpoint,secret:String,pin:String?,onError:()->Unit={},onFinished:()->Unit={}) {
        if(pairingJob?.isActive==true)return
        page.message("Collegamento in corso…")
        pairingJob=scope.launch {
            try {
                val tv=CompanionClient(endpoint,pin).use {connection->connection.pair(secret,store.id,
                    CompanionProtocol.display("Telefono · "+Build.MODEL,80)) {name->
                        page.subtitleView.text=name;page.message("Conferma Consenti sulla TV.")
                    }}
                withContext(Dispatchers.IO){store.save(tv)};saved=(saved.filterNot {it.endpoint.id==tv.endpoint.id}+tv).takeLast(8)
                if(isShowing && pages.isCurrent(page)){pairingJob=null;connect(tv)}
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){
                if(BuildConfig.DEBUG)android.util.Log.w("LumeCompanion","Pairing failed",error)
                if(page.alive){page.message(errorMessage(error));onError()}
            }
            finally {if(page.alive)onFinished()}
        }
        page.onDispose={pairingJob?.cancel();pairingJob=null}
    }
    private fun connect(tv:SavedTelevision) {
        automaticEntry=false;pages.reset();target=tv;latest=null;connected=false;needsPairing=false
        if(store.lastTelevision!=tv.endpoint.id)store.lastTelevision=tv.endpoint.id
        val request=watchIntent
        if(request!=null) {
            watchIntent=null
            CompanionWatchSender(pages,{target?.takeIf {it.endpoint.id==tv.endpoint.id} ?: tv},
                it.lumetv.app.data.ChannelRepository(activity,it.lumetv.app.data.HttpClient()),request,
                {watchIntent=request;chooseTv()}, {connect(target ?: tv)}, {selected->connect(selected);bringToFront()}).show()
            return
        }
        pages.push("remote","Telecomando TV",tv.endpoint.name) {page->
            page.heading.textSize=22f;page.heading.letterSpacing=0f;page.scroll.isFillViewport=true
            page.scroll.addOnLayoutChangeListener {_,left,_,right,_,oldLeft,_,oldRight,_->
                if(oldRight>oldLeft && right-left!=oldRight-oldLeft)page.scroll.scrollTo(0,0)
            }
            page.subtitleView.apply {
                text=tv.endpoint.name;maxLines=1;tag="companion_change_tv";minimumHeight=activity.dp(48)
                gravity=android.view.Gravity.CENTER_VERTICAL;isClickable=true;isFocusable=true
                contentDescription="TV controllata: ${tv.endpoint.name}. Cambia TV"
                val chevron=activity.getDrawable(it.lumetv.app.R.drawable.ic_mobile_chevron)!!.mutate().apply {setTint(TvStyle.muted);setBounds(0,0,activity.dp(18),activity.dp(18))}
                setCompoundDrawablesRelative(null,null,chevron,null);compoundDrawablePadding=activity.dp(8)
                background=MobileStyle.ripple(activity);TvStyle.buttonSemantics(this)
                setOnClickListener {chooseTv()}
            }
            repair=MobileStyle.button(activity,"Abbina di nuovo") {chooseTv();scanQr()}.apply {tag="companion_repair";visibility=GONE}
                .also {page.body.addView(it,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(8)})}
            remote=CompanionRemoteView(activity,::command,::tracks,::sleep).also {page.body.addView(it,LinearLayout.LayoutParams(-1,-1))}
            sendLists=page.row("Invia lista alla TV","Scegli una lista salvata sul telefono e controlla l’anteprima.") {
                val selected=target
                if(connected && selected!=null && latest?.optBoolean("playlistTransferSupported")==true)
                    CompanionPlaylistSender(pages,selected,it.lumetv.app.data.PersonalPlaylistRepository.get(activity)).show()
            }.apply {tag="companion_send_lists_entry";isEnabled=false}
            page.root.tag="companion_remote"
            page.onDispose={stopPolling();remote=null;sendLists=null;repair=null;target=null;latest=null}
            startPolling()
        }
    }
    private fun stopPolling() {
        polling?.cancel();polling=null;commandJob?.cancel();commandJob=null;commands.clear();commanding=false;connected=false
        client?.close();client=null
        remote?.setConnectionMessage("Collegamento sospeso.")
        renderRemote()
    }
    private fun startPolling() {
        val tv=target ?: return
        if(polling?.isActive==true || !isShowing || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))return
        val connection=CompanionClient(tv.endpoint,tv.pin,tv.token);client=connection
        polling=scope.launch {
            var failures=0
            while(isActive) {
                try {
                    val state=connection.getState();require(state.getInt("version")==1)
                    connected=true;needsPairing=false;failures=0;remote?.setConnectionMessage("Collegato")
                    accept(state)
                    if(saved.firstOrNull {it.endpoint.id==tv.endpoint.id}?.endpoint!=tv.endpoint) {
                        withContext(Dispatchers.IO){store.save(tv)};saved=saved.filterNot {it.endpoint.id==tv.endpoint.id}+tv
                    }
                }catch(cancelled:CancellationException){throw cancelled}
                catch(error:Exception) {
                    connected=false;needsPairing=error is CompanionFailure && error.status==401 || identityChanged(error);renderRemote()
                    remote?.setConnectionMessage(errorMessage(error,true))
                    if(needsPairing)break
                    failures++
                }
                delay(if(failures==0)1000 else (failures*1000L).coerceAtMost(5000))
            }
        }
    }
    private fun command(action:String,args:JSONObject=JSONObject()) {
        val connection=client ?: return;val state=latest ?: return
        if(!connected || (commanding && action!="navigate") || commands.size>=8)return
        commands.addLast(PendingCommand(state.getString("session"),action,args,android.os.SystemClock.elapsedRealtime()))
        if(commanding)return
        commanding=true;renderRemote()
        commandJob=scope.launch {
            try {
                while(commands.isNotEmpty() && connection===client) {
                    val input=commands.removeFirst()
                    // Preserve quick taps in order, but never replay a navigation backlog after a network stall.
                    if(input.action=="navigate" && android.os.SystemClock.elapsedRealtime()-input.queuedAt>1500)continue
                    val next=connection.command(input.session,input.action,input.args)
                    if(connection===client)accept(next)
                }
            }
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){commands.clear();remote?.setConnectionMessage(errorMessage(error))}
            finally {if(connection===client){commanding=false;commandJob=null;renderRemote()}}
        }
    }
    private fun accept(state:JSONObject) {
        val old=latest
        if(old!=null && old.optString("instance")==state.optString("instance") && old.optLong("sequence")>state.optLong("sequence"))return
        latest=state;renderRemote()
    }
    private fun renderRemote(){
        repair?.visibility=if(needsPairing)VISIBLE else GONE;remote?.render(latest,connected,commanding)
        sendLists?.apply {
            isEnabled=connected && latest?.optBoolean("playlistTransferSupported")==true
            alpha=if(isEnabled)1f else .5f
            bind("Invia lista alla TV",if(connected && latest?.optBoolean("playlistTransferSupported")!=true)"Aggiorna Lume sulla TV per inviare le liste."
                else "Scegli una lista salvata sul telefono e controlla l’anteprima.")
        }
    }

    private fun tracks(field:String,type:Int,title:String) {
        val options=latest?.optJSONObject("options") ?: return
        val rows=options.optJSONArray(field) ?: return
        val labels=listOf("Automatica")+(if(type==3)listOf("Disattiva sottotitoli") else emptyList())+(0 until rows.length()).map {rows.getJSONObject(it).getString("label")}
        val keys=listOf("auto")+(if(type==3)listOf("off") else emptyList())+(0 until rows.length()).map {rows.getJSONObject(it).getString("key")}
        val revision=options.optLong("revision")
        val selected=when {
            type==3 && options.optBoolean("subtitlesDisabled")->1
            options.optBoolean(field+"Automatic")->0
            else->(0 until rows.length()).firstOrNull {rows.getJSONObject(it).optBoolean("selected")}?.let {it+if(type==3)2 else 1} ?: -1
        }
        dialog=AlertDialog.Builder(activity).setTitle(title).setSingleChoiceItems(labels.toTypedArray(),selected) {choice,index->
            choice.dismiss()
            if(keys[index]=="off")command("subtitlesOff",JSONObject().put("revision",revision))
            else command("track",JSONObject().put("type",type).put("key",keys[index]).put("revision",revision))
        }.setNegativeButton("Annulla",null).show()
    }
    private fun sleep() {
        val minutes=listOf(0)+it.lumetv.app.playback.SleepTimer.MINUTES
        dialog=AlertDialog.Builder(activity).setTitle("Timer sulla TV").setItems(minutes.map {if(it==0)"Disattiva timer" else "$it minuti"}.toTypedArray()) {_,index->command("sleep",JSONObject().put("minutes",minutes[index]))}
            .setNegativeButton("Annulla",null).show()
    }
    private fun identityChanged(error:Exception)=generateSequence(error as Throwable?){it.cause}.any {it is java.security.cert.CertificateException}
    private fun errorMessage(error:Exception,retrying:Boolean=false):String = if(identityChanged(error))
        "L’identità della TV è cambiata. Abbina di nuovo il telefono con il QR sulla TV."
        else when((error as? CompanionFailure)?.code) {
        "not_authorized"->"Autorizzazione revocata. Scegli Abbina di nuovo e inquadra il QR sulla TV."
        "pairing_failed","identity_failed"->"Codice o identità della TV non validi. Usa il QR attualmente mostrato sulla TV."
        "pairing_closed","pairing_expired"->"Collegamento scaduto. Sulla TV apri Opzioni → Collega telefono e inquadra il nuovo QR."
        "pairing_denied"->"Collegamento rifiutato sulla TV. Puoi inquadrare il nuovo QR per riprovare."
        "pairing_busy"->"C’è già una richiesta sulla TV. Confermala o rifiutala prima di riprovare."
        "slow_down"->"Troppi tentativi. Attendi un minuto, poi inquadra di nuovo il QR."
        "too_many_devices"->"La TV ha già otto telefoni autorizzati. Rimuovine uno da Gestisci collegamenti sulla TV."
        "tv_busy"->"La TV è occupata. Attendi qualche secondo e riprova."
        "physical_approval_required"->"Per autorizzare o rimuovere telefoni usa il telecomando fisico della TV. Indietro torna a Lume."
        "close_tv_dialog"->"Premi Indietro sul telecomando per chiudere la finestra sulla TV, poi riprova."
        "playback_changed","playback_changing","tracks_changed"->"La riproduzione sulla TV è cambiata. Attendi l’aggiornamento e riprova."
        else->if(retrying)"TV non raggiungibile. Apri Lume sulla TV e controlla la rete; riprovo automaticamente."
            else "TV non raggiungibile. Sulla TV apri Opzioni → Collega telefono e verifica che i dispositivi siano sulla stessa rete."
    }
}
