package it.lumetv.app.ui

import android.content.Context
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import it.lumetv.app.data.*
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** List management lives entirely in the Activity window. Only document selection belongs to Android. */
class PersonalPlaylistsScreen(
    private val activity:ComponentActivity,
    private val scope:CoroutineScope,
    private val repository:PersonalPlaylistRepository,
    private val mobile:Boolean,
    private val closed:()->Unit,
    private val openPlayback:()->Unit,
    private val stopPlayback:()->Unit,
):FrameLayout(activity) {
    private val pages=PlaylistPages(activity,scope,mobile){hide()}
    private val guideEditor=PersonalGuideEditor(pages,repository)
    private val playbackControls=LinearLayout(activity)
    private var playbackActive=false
    private var selectedId:String?=null
    private var replacingFile:String?=null
    private var filePickerPackage:String?=null
    private var restoreSource:String?=null
    private var previousFocus:View?=null
    private val hiddenAccessibility=linkedMapOf<View,Int>()
    var isShowing=false;private set
    private val picker=activity.registerForActivityResult(object:ActivityResultContracts.OpenDocument() {
        override fun createIntent(context:Context,input:Array<String>)=super.createIntent(context,input).apply {filePickerPackage?.let(::setPackage)}
    }) {uri->
        val replacing=replacingFile;replacingFile=null
        if(uri!=null)copyFile(uri,replacing) else focusSelection()
    }
    init {
        visibility=GONE;setBackgroundColor(TvStyle.background);isClickable=true;isFocusable=false;isSaveEnabled=false
        if(!mobile)addView(CatalogBackdrop(activity),LayoutParams(-1,-1))
        val layout=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        layout.addView(pages,LinearLayout.LayoutParams(-1,0,1f))
        playbackControls.apply {
            setPadding(activity.dp(if(mobile)20 else 42),0,activity.dp(if(mobile)20 else 42),activity.dp(10))
            addView(pages.button("Apri riproduzione"){hide();openPlayback()},LinearLayout.LayoutParams(0,-2,1f))
            addView(pages.button("Interrompi"){stopPlayback();bindPlayback(false)},LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=activity.dp(8)})
        }
        layout.addView(playbackControls,LinearLayout.LayoutParams(-1,-2));addView(layout,LayoutParams(-1,-1))
        pages.onChanged=::updatePlaybackControls
        scope.launch {repository.state.collect {if(isShowing)pages.refresh()}}
        scope.launch {PersonalGuideRepository.get(activity).state.collect {if(isShowing)pages.refresh()}}
        scope.launch {repository.classification.collect {state->
            if(isShowing && state!=null)pages.current?.takeIf {it.busy && it.key=="reading"}?.message("Classifico i contenuti… ${state.processed} / ${state.total} voci")
        }}
    }
    fun show() {
        showWindow();pages.reset();overview()
        scope.launch {repository.local();if(isShowing){pages.refresh();restoreSource?.let {id->restoreSource=null;source(id)}}}
    }
    private fun showWindow() {
        if(!isShowing){previousFocus=activity.currentFocus
            (parent as? ViewGroup)?.let {parent->for(i in 0 until parent.childCount){val view=parent.getChildAt(i);if(view!==this){hiddenAccessibility[view]=view.importantForAccessibility;view.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}}}}
        isShowing=true;visibility=VISIBLE;bringToFront()
    }
    fun hide() {
        pages.hideKeyboard();pages.reset();isShowing=false;visibility=GONE
        hiddenAccessibility.forEach {(view,value)->view.importantForAccessibility=value};hiddenAccessibility.clear()
        closed();previousFocus?.takeIf {it.isShown}?.requestFocus();previousFocus=null
    }
    fun handleBack():Boolean {
        if(!isShowing)return false
        if(ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime())==true){pages.hideKeyboard();return true}
        return pages.back()
    }
    fun focusSelection(){if(isShowing && !hasFocus())pages.focus()}
    override fun focusSearch(focused:View?,direction:Int):View? = FocusFinder.getInstance().findNextFocus(this,focused,direction) ?: focused
    override fun onWindowFocusChanged(hasWindowFocus:Boolean){super.onWindowFocusChanged(hasWindowFocus);if(hasWindowFocus)focusSelection()}
    fun bindPlayback(active:Boolean){playbackActive=active;updatePlaybackControls()}
    private fun updatePlaybackControls(){playbackControls.visibility=if(playbackActive && (pages.current?.key=="lists" || selectedId!=null && pages.current?.key=="source-$selectedId"))VISIBLE else GONE}
    fun saveState(out:Bundle) {
        out.putBoolean("personal_playlists_open",isShowing && pages.current?.key!="source-picker");out.putString("personal_playlists_selection",selectedId)
        out.putString("personal_playlists_file_replacing",replacingFile)
        // Routes are safe to restore. Credentials and uncommitted form values never go into Android's saved state.
        out.putString("personal_playlists_source",selectedId?.takeIf {pages.contains("source-$it")})
    }
    fun restoreState(saved:Bundle?) {
        selectedId=saved?.getString("personal_playlists_selection");replacingFile=saved?.getString("personal_playlists_file_replacing")
        restoreSource=saved?.getString("personal_playlists_source")
        if(saved?.getBoolean("personal_playlists_open")==true)show()
    }

    private fun overview() {
        pages.push("lists","Le mie liste","Aggiungi i tuoi canali TV e radio. Le liste restano su questo dispositivo.",navigation=true){page->
            page.section("Su questo dispositivo",first=true)
            val rows=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL};page.body.addView(rows)
            var add:View?=null
            var rendered:Pair<List<PersonalPlaylistSource>,String?>?=null
            fun render() {
                val signature=repository.state.value to repository.storageError
                if(rendered==signature)return
                rendered=signature
                val focus=rows.findFocus()?.tag as? String
                val scroll=page.scroll.scrollY;rows.removeAllViews()
                repository.storageError?.let {rows.addView(TvStyle.text(activity,it,15f,pages.secondary))}
                if(repository.state.value.isEmpty()) {
                    rows.addView(TvStyle.text(activity,"Nessuna lista personale",20f,bold=true))
                    rows.addView(TvStyle.text(activity,"Aggiungi un indirizzo HTTP/HTTPS oppure importa un file M3U/M3U8.",15f,pages.secondary),LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(10)})
                }
                repository.state.value.forEach {item->
                    val state=when {item.refreshing->"Aggiornamento in corso";!item.enabled->"Disattivata";item.error.isNotBlank()->"Copia salvata · Aggiornamento non riuscito";else->"Attiva"}
                    val count=if(item.count==1)"1 voce" else "${item.count} voci"
                    page.addRow(rows,item.name,"$count · $state · ${if(item.location==PlaylistLocation.URL)"URL" else "File"}"){selectedId=item.id;source(item.id)}.apply {
                        tag=item.id
                        contentDescription="${item.name}, ${item.count} voci, $state. Gestisci lista"
                    }
                }
                page.initialFocus=(0 until rows.childCount).map(rows::getChildAt).firstOrNull {it.isFocusable} ?: add
                if(pages.isCurrent(page))rows.post {page.scroll.scrollTo(0,scroll);if(!mobile && focus!=null)rows.findViewWithTag<View>(focus)?.requestFocus()}
            }
            page.onRefresh=::render;render()
            page.text("Fino a 10 liste · 500.000 voci per lista · 256 MiB per file",13f)
            page.action("Importa file"){chooseFile(null)}
            add=page.action("Aggiungi URL",true){importForm()}
            if(page.initialFocus==null)page.initialFocus=add
        }
    }
    private fun source(id:String,replace:Boolean=false) {
        val item=repository.state.value.firstOrNull {it.id==id} ?: return
        selectedId=id
        pages.push("source-$id",item.name,replace=replace,navigation=true){page->
            val summary=page.text("",14f)
            fun update() {
                val current=repository.state.value.firstOrNull {it.id==id}
                if(current==null){page.message("La lista non è più disponibile.",true);return}
                page.heading.text=current.name
                summary.text=(if(current.count==1)"1 voce" else "${current.count} voci")+" · "+if(current.enabled)"Attiva" else "Disattivata"
                if(current.refreshing)page.message("Aggiornamento della lista in corso…")
            }
            page.onRefresh=::update;update()
            if(item.revision>0) {
                page.section("Preferenze",first=true)
                page.row("Sorgente","Nome, attivazione e consenso HTTP"){general(id)}
                page.row("Lettura e contenuti","Formato, riconoscimento VOD, gruppi e aggiornamento"){settings(id,page){config->PlaylistAdvancedEditor(pages).reading(config,currentName(id),
                    {query,offset->repository.groups(PersonalCatalogQuery(source=id,content=null,text=query,offset=offset,limit=50))}){repository.setSettings(id,it)}}}
                page.row("Accesso ai server","Header, cookie e loghi per ogni funzione"){settings(id,page){config->PlaylistAdvancedEditor(pages).access(config,currentName(id)){repository.setSettings(id,it)}}}
                page.section("Guida e gestione")
                page.row("Guida e archivio","XMLTV, orari e associazioni dei canali"){repository.state.value.firstOrNull {it.id==id}?.let(guideEditor::show)}
                page.row("Stato e ultima importazione","Ultimo controllo, informazioni del servizio e motivi degli scarti"){report(id,page)}
                if(item.location==PlaylistLocation.URL)page.action("Aggiorna lista",true){refresh(page,id)}
            } else page.text(item.error.ifBlank {"L’archivio salvato non è leggibile. Puoi eliminare questa voce e importare di nuovo la lista."})
            page.row("Elimina lista","Rimuove anche preferiti, guida e avanzamenti associati"){confirmDelete(id)}
        }
    }
    private fun currentName(id:String)=repository.state.value.firstOrNull {it.id==id}?.name.orEmpty()
    private fun settings(id:String,page:PlaylistPages.Page,show:(PlaylistSettings)->Unit) {pages.run(page,"Carico le impostazioni…") {
        val config=repository.settings(id);if(pages.isCurrent(page))show(config)
    }}
    private fun general(id:String) {
        val item=repository.state.value.firstOrNull {it.id==id} ?: return
        pages.push("general","Sorgente",item.name){page->
            val name=page.field("Nome della lista",item.name,100)
            val enabled=page.toggle("Lista attiva",item.enabled)
            val http=page.toggle("Consenti HTTP per questa lista",item.allowHttp)
            page.text("Con HTTP, indirizzi e dati di accesso viaggiano senza cifratura. Il consenso vale per lista, stream e licenze.")
            fun changesPending():Boolean {
                val changed=name.text.toString()!=item.name || enabled.isChecked!=item.enabled || http.isChecked!=item.allowHttp
                if(changed)page.message("Salva le modifiche prima di sostituire la sorgente.",true)
                return changed
            }
            if(item.location==PlaylistLocation.URL)page.row("Modifica URL","Verifica il nuovo indirizzo prima di sostituire la copia salvata") {
                if(!changesPending())pages.run(page,"Carico la sorgente…") {val url=repository.address(id);val config=repository.settings(id)
                    if(pages.isCurrent(page))importForm(item.name,url,id,kind=item.defaultKind,allowHttp=item.allowHttp,initialSettings=config)
                }
            } else page.row("Sostituisci file","Scegli e verifica un nuovo file M3U"){if(!changesPending())chooseFile(id)}
            page.protect {listOf(name.text.toString(),enabled.isChecked,http.isChecked).joinToString("\u0000")}
            page.action("Annulla"){pages.pop()}
            page.action("Salva modifiche",true){
                if(name.text.isNullOrBlank()){page.message("Assegna un nome alla lista.",true);name.requestFocus();return@action}
                pages.run(page,"Salvo la lista…") {
                    val latest=repository.state.value.firstOrNull {it.id==id} ?: throw IOException("La lista non è più disponibile.")
                    if(name.text.toString()!=latest.name)repository.rename(id,name.text.toString())
                    if(enabled.isChecked!=latest.enabled)repository.setEnabled(id,enabled.isChecked)
                    if(http.isChecked!=latest.allowHttp)repository.setHttpAllowed(id,http.isChecked)
                    pages.saved(page)
                }
            }
        }
    }
    private fun report(id:String,parent:PlaylistPages.Page) {
        scope.launch {try {
            val item=repository.state.value.firstOrNull {it.id==id} ?: return@launch
            val attributes=repository.descriptor(id).attributes;val result=repository.importReport(id);val pending=repository.classificationNeedsRefresh(id);val conflicts=repository.classificationConflicts(id)
            if(!pages.isCurrent(parent))return@launch
            pages.push("report","Stato e ultima importazione",item.name){page->
                page.text("${item.count} voci · HTTP ${if(item.allowHttp)"consentito" else "disattivato"}")
                page.text(if(item.checked>0)"Ultimo controllo: "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(item.checked)) else "Lista non ancora aggiornata")
                if(item.nextRetry>System.currentTimeMillis())page.text("Prossimo tentativo: "+DateFormat.getDateTimeInstance().format(Date(item.nextRetry)))
                if(item.error.isNotBlank())page.text(item.error,muted=false)
                if(pending)page.text(if(item.location==PlaylistLocation.URL)"Per completare il riconoscimento dei contenuti, aggiorna la lista e rileggi le indicazioni originali."
                    else "Per completare il riconoscimento dei contenuti, usa Sostituisci file e rileggi le indicazioni originali.")
                if(conflicts>0 && conflicts!=result?.diagnosticCounts?.get(it.lumetv.core.PlaylistDiagnosticCode.CONTENT_CONFLICT))page.text("$conflicts voci con indicazioni di contenuto discordanti. Prevalgono i tipi espliciti o le regole manuali; tra gruppi discordanti prevale Dirette.")
                fun clean(value:String)=value.filterNot {it.isISOControl() && it!='\n'}.take(1000)
                attributes["billed-msg"]?.takeIf(String::isNotBlank)?.let {page.text(clean(it))}
                attributes["billed-till"]?.takeIf(String::isNotBlank)?.let {page.text("Scadenza comunicata dal servizio: "+clean(it).take(100))}
                attributes["max-conn"]?.toIntOrNull()?.takeIf {it in 1..1000}?.let {page.text("Connessioni indicate dal servizio: $it")}
                page.section("Ultima importazione")
                if(result==null)page.text("Il rapporto non è disponibile. Aggiorna la lista o sostituisci il file per recuperare i metadati.")
                else {page.text(importCounts(result.count,result.excluded,result.duplicates,false))
                    result.diagnosticCounts.forEach {(code,n)->page.text("$n · ${code.message}")}
                    result.warnings.take(10).forEach {page.text(it)}
                }
            }
        }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){parent.message(PlaylistPages.safeMessage(error),true)}}
    }
    private fun refresh(page:PlaylistPages.Page,id:String) {
        if(repository.state.value.firstOrNull {it.id==id}?.refreshing==true)return
        page.message("Aggiorno la lista…")
        scope.launch {try {val changed=repository.refresh(id);val error=repository.state.value.firstOrNull {it.id==id}?.error.orEmpty()
            page.message(error.ifBlank {if(changed)"Lista aggiornata." else "Copia salvata disponibile. Il prossimo controllo rispetta l’intervallo previsto."},error.isNotBlank())}
            catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){page.message(PlaylistPages.safeMessage(error),true)}}
    }
    private fun confirmDelete(id:String) {
        pages.push("delete","Elimina lista",currentName(id)){page->
            page.text("Verranno rimossi la lista, i suoi preferiti, la guida e gli avanzamenti dei video. L’eventuale riproduzione di questa lista verrà interrotta.")
            page.action("Annulla"){pages.pop()}
            page.action("Elimina definitivamente",true){pages.run(page,"Elimino la lista…") {
                repository.delete(id);selectedId=null;if(page.alive){pages.backTo("lists");pages.current?.message("Lista eliminata.")}
            }}
        }
    }

    private fun importForm(name:String="",url:String="",replacing:String?=null,file:File?=null,kind:MediaKind=MediaKind.TV,
        allowHttp:Boolean=false,initialSettings:PlaylistSettings=PlaylistSettings(),replace:Boolean=false) {
        pages.push("import",if(replacing==null)"Aggiungi lista" else "Sostituisci lista",replace=replace){page->
            var config=initialSettings;var draft:PlaylistDraft?=null;var verifying:Job?=null;var saving=false
            val title=page.field("Nome della lista",name,100)
            val address=if(file==null)page.field("Indirizzo HTTP o HTTPS della lista",url,8192).apply {inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI} else null
            if(file!=null)page.text("File pronto per la verifica. La copia precedente resta disponibile fino al salvataggio.")
            val type=page.choice("Tipo dei canali senza indicazione nella lista",listOf("TV","Radio"),if(kind==MediaKind.RADIO)1 else 0)
            val insecure=page.toggle("Consenti HTTP per questa lista",allowHttp)
            page.text("Con HTTP, indirizzi e dati di accesso viaggiano senza cifratura.")
            val reading=page.row("Lettura e contenuti","Formato, codifica e tipo dei contenuti") {}
            val access=page.row("Accesso ai server","Header per download, video e altre funzioni") {}
            val progress=ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {isIndeterminate=true;visibility=GONE;indeterminateTintList=android.content.res.ColorStateList.valueOf(TvStyle.accent)}
            page.body.addView(progress,LinearLayout.LayoutParams(-1,activity.dp(6)))
            val preview=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=GONE};page.body.addView(preview)
            page.text("La verifica legge la lista; i singoli canali verranno aperti quando li scegli.",14f)
            lateinit var primary:TextView
            fun invalidate(){draft?.close();draft=null;preview.removeAllViews();preview.visibility=GONE;primary.text="Verifica lista"}
            reading.setOnClickListener {PlaylistAdvancedEditor(pages).reading(config){next->if(next!=config){config=next;invalidate()}}}
            access.setOnClickListener {PlaylistAdvancedEditor(pages).access(config){next->if(next!=config){config=next;invalidate()}}}
            address?.addTextChangedListener(PlaylistPages.watcher {invalidate()})
            type.setOnCheckedChangeListener {_,_->invalidate()};insecure.setOnCheckedChangeListener {_,_->invalidate()}
            page.protect {listOf(title.text.toString(),address?.text.toString(),pages.selected(type),insecure.isChecked,config.json()).joinToString("\u0000")}
            page.onDispose={if(!saving){verifying?.cancel();draft?.close();file?.delete()}}
            val cancel=page.action("Annulla"){
                if(verifying?.isActive==true){verifying?.cancel();page.message("Verifica interrotta. Puoi modificare i dati e riprovare.")}
                else pages.pop()
            }
            primary=page.action("Verifica lista",true){
                if(title.text.isNullOrBlank()){page.message("Assegna un nome alla lista.",true);title.requestFocus();return@action}
                pages.hideKeyboard()
                val checked=draft
                if(checked!=null) {
                    saving=true;var committed=false
                    pages.run(page,"Importo la lista…") {try {
                        val id=repository.save(title.text.toString(),checked,replacing){phase->activity.runOnUiThread {
                            if(page.alive)page.message(if(phase==it.lumetv.app.data.PlaylistSavePhase.PREPARING)"Organizzo i canali e conservo le associazioni…" else "Salvo la lista…")
                        }}
                        committed=true;draft=null;checked.close();file?.delete()
                        if(pages.isCurrent(page)){if(replacing!=null && pages.current?.key=="import"){pages.backTo("source-$replacing");pages.current?.message("Lista sostituita.")}
                            else source(id,replace=true)}
                    }finally {saving=false;checked.close();if(!committed)invalidate();if(!page.alive)file?.delete()}}
                } else {
                    page.message("Verifico la lista…");progress.visibility=VISIBLE;primary.isEnabled=false;cancel.text="Interrompi verifica"
                    val controls=listOfNotNull<View>(title,address,insecure,reading,access)+(0 until type.childCount).map(type::getChildAt)
                    controls.forEach {it.isEnabled=false}
                    verifying=scope.launch {try {
                        val update:(PlaylistImportProgress)->Unit={state->activity.runOnUiThread {if(page.alive)page.message(String.format(Locale.ITALY,"Verifico… %.1f MiB · %,d voci",state.bytes/1048576.0,state.entries))}}
                        val selectedKind=if(pages.selected(type)==1)MediaKind.RADIO else MediaKind.TV
                        val result=if(file==null)repository.prepareUrl(address?.text.toString(),selectedKind,insecure.isChecked,config,update) else repository.prepareFile(file,selectedKind,insecure.isChecked,config,update)
                        if(!page.alive){result.close();return@launch}
                        draft=result;preview.visibility=VISIBLE;preview.removeAllViews()
                        preview.addView(TvStyle.text(activity,"Anteprima della lista",20f,bold=true))
                        val report=result.report
                        preview.addView(TvStyle.text(activity,importCounts(report.count,report.excluded,report.duplicates,true),15f,pages.secondary))
                        report.sample.forEach {preview.addView(TvStyle.text(activity,it,16f))}
                        report.warnings.take(5).forEach {preview.addView(TvStyle.text(activity,it,14f,pages.secondary))}
                        primary.text=if(replacing==null)"Importa lista" else "Sostituisci lista"
                        page.message("Verifica completata. Controlla l’anteprima e salva la lista.")
                        preview.post {preview.requestRectangleOnScreen(android.graphics.Rect(0,0,preview.width,preview.height.coerceAtMost(activity.dp(200))),true)}
                    }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){page.message(PlaylistPages.safeMessage(error),true)}finally {
                        verifying=null;if(page.alive){primary.isEnabled=true;cancel.text="Annulla";progress.visibility=GONE;controls.forEach {it.isEnabled=true}}
                    }}
                }
            }
        }
    }
    private fun importCounts(count:Int,excluded:Int,duplicates:Int,preview:Boolean):String {
        val entries=if(count==1)"1 voce ${if(preview)"importabile" else "importata"}" else "$count voci ${if(preview)"importabili" else "importate"}"
        return "$entries · $excluded ${if(excluded==1)"esclusa" else "escluse"} · $duplicates ${if(duplicates==1)"duplicata" else "duplicate"}"
    }
    private fun chooseFile(replacing:String?) {
        replacingFile=replacing
        val intent=android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION") val handlers=activity.packageManager.queryIntentActivities(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        filePickerPackage=handlers.firstOrNull {!it.activityInfo.packageName.contains("frameworkpackagestubs")}?.activityInfo?.packageName
        if(filePickerPackage==null){replacingFile=null;pages.current?.message("Questo dispositivo non offre un selettore di documenti. Aggiungi la lista tramite URL HTTP o HTTPS.",true);return}
        pages.hideKeyboard()
        try {picker.launch(arrayOf("*/*"))}catch(_:android.content.ActivityNotFoundException){replacingFile=null;pages.current?.message("Selettore file non disponibile. Puoi usare un indirizzo HTTP o HTTPS.",true)}
    }
    private fun copyFile(uri:android.net.Uri,replacing:String?) {
        if(!isShowing)show()
        val opened=AtomicReference<java.io.InputStream?>();var task:Job?=null;var transferred=false;var file:File?=null
        val page=pages.push("copy","Importa file"){page->
            page.text("Copio il file nell’archivio privato prima della verifica.")
            page.action("Annulla"){pages.pop()};page.onDispose={if(!transferred){opened.getAndSet(null)?.close();task?.cancel();file?.delete()}}
        }
        task=scope.launch {try {
            val name=withContext(Dispatchers.IO){activity.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0)?.substringBeforeLast('.') else null}?.takeIf(String::isNotBlank) ?: "La mia lista"}
            file=withContext(Dispatchers.IO) {
                val target=File.createTempFile("import-",".m3u",File(activity.cacheDir,"personal-imports").apply {mkdirs()})
                try {
                    val input=activity.contentResolver.openInputStream(uri) ?: throw IOException("Il file non è leggibile. Selezionalo nuovamente.");opened.set(input)
                    LimitedPlaylistInput(input).use {source->target.outputStream().use {output->val buffer=ByteArray(32*1024)
                        while(true){coroutineContext.ensureActive();val n=source.read(buffer);if(n<0)break;output.write(buffer,0,n)}
                    }};target
                }catch(error:Exception){target.delete();throw error}finally {opened.set(null)}
            }
            if(!pages.isCurrent(page))return@launch
            val previous=repository.state.value.firstOrNull {it.id==replacing};val config=previous?.let {repository.settings(it.id)} ?: PlaylistSettings()
            transferred=true;val ready=file;file=null
            importForm(previous?.name ?: name,replacing=replacing,file=ready,kind=previous?.defaultKind ?: MediaKind.TV,allowHttp=previous?.allowHttp ?: false,initialSettings=config,replace=true)
        }catch(cancelled:CancellationException){throw cancelled}catch(error:Exception){page.message(PlaylistPages.safeMessage(error),true)}finally {file?.delete()}}
    }

    fun chooseSource(current:String,includeAll:Boolean,kind:MediaKind?,choose:(String)->Unit) {
        showWindow();pages.reset()
        pages.push("source-picker","Scegli la lista",navigation=true){page->
            val choices=buildList {if(includeAll)add("all" to "Tutte le liste");add("builtin" to "Catalogo Lume")
                repository.state.value.filter {it.enabled}.forEach {add(it.id to it.name)}}
            choices.forEach {(id,label)->page.row(label,if(id==current)"Selezionata" else ""){hide();choose(id)}}
        }
    }
}
