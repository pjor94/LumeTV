package it.lumetv.app.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import it.lumetv.app.data.*
import it.lumetv.app.ui.TvStyle.dp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Shared in-window pages. Android owns only the document chooser. */
@android.annotation.SuppressLint("ViewConstructor")
internal class PersonalBackupScreen(
    private val activity:ComponentActivity,private val scope:CoroutineScope,
    repository:PersonalPlaylistRepository,private val mobile:Boolean,
    private val restored:()->Unit,private val closed:()->Unit,
) : FrameLayout(activity) {
    private val manager=PersonalBackupRepository(activity,repository)
    private val pages=PlaylistPages(activity,scope,mobile){hide()}
    private val repository=repository
    private var job:Job?=null
    private var committing=false
    private var pendingExport:File?=null
    private var encryptedInput:File?=null
    private var preview:BackupPreview?=null
    private var previousFocus:View?=null
    private var savePackage:String?=null
    private var openPackage:String?=null
    private val hiddenAccessibility=linkedMapOf<View,Int>()
    var isShowing=false;private set
    val isBusy get()=job!=null
    var onIdle:()->Unit={}
    private val saveDocument=activity.registerForActivityResult(object:ActivityResultContracts.CreateDocument("application/octet-stream") {
        override fun createIntent(context:android.content.Context,input:String)=super.createIntent(context,input).apply {savePackage?.let(::setPackage)}
    }) {uri->
        if(uri==null){pendingExport?.delete();pendingExport=null;pages.current?.message("Esportazione annullata.");pages.focus()}
        else exportDocument(uri)
    }
    private val openDocument=activity.registerForActivityResult(object:ActivityResultContracts.OpenDocument() {
        override fun createIntent(context:android.content.Context,input:Array<String>)=super.createIntent(context,input).apply {openPackage?.let(::setPackage)}
    }) {uri->
        if(uri!=null && isShowing)readDocument(uri) else pages.focus()
    }
    init {
        visibility=GONE;isClickable=true;isSaveEnabled=false;setBackgroundColor(TvStyle.background)
        if(!mobile)addView(CatalogBackdrop(activity),LayoutParams(-1,-1))
        addView(pages,LayoutParams(-1,-1))
        pages.onChanged={pages.current?.root?.findViewWithTag<View>("playlist_page_back")?.setOnClickListener {handleBack()}}
        activity.lifecycle.addObserver(object:DefaultLifecycleObserver {
            override fun onDestroy(owner:LifecycleOwner) {
                job?.cancel();preview?.close();encryptedInput?.delete()
                if(!activity.isChangingConfigurations)pendingExport?.delete()
                pages.reset()
            }
        })
    }
    fun show() {
        if(isShowing)return
        previousFocus=activity.currentFocus
        (parent as? ViewGroup)?.let {parent->for(i in 0 until parent.childCount)parent.getChildAt(i).takeIf {it!==this}?.let {
            hiddenAccessibility[it]=it.importantForAccessibility;it.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }}
        isShowing=true;visibility=VISIBLE;bringToFront();overview()
    }
    fun hide() {
        if(job!=null){pages.current?.message("Attendi il completamento dell’operazione.");return}
        pages.hideKeyboard();pages.reset();preview?.close();preview=null;encryptedInput?.delete();encryptedInput=null
        pendingExport?.delete();pendingExport=null;isShowing=false;visibility=GONE
        hiddenAccessibility.forEach {(view,value)->view.importantForAccessibility=value};hiddenAccessibility.clear()
        closed();previousFocus?.takeIf {it.isShown}?.requestFocus();previousFocus=null
    }
    fun handleBack():Boolean {
        if(!isShowing)return false
        if(ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime())==true){pages.hideKeyboard();return true}
        if(job!=null) {
            if(committing)pages.current?.message("Completo il ripristino. I dati già presenti vengono conservati.")
            else {job?.cancel();pages.current?.message("Annullamento in corso…")}
            return true
        }
        return pages.back()
    }
    fun focusSelection(){if(isShowing && !hasFocus())pages.focus()}
    override fun focusSearch(focused:View?,direction:Int):View? =
        FocusFinder.getInstance().findNextFocus(this,focused,direction) ?: focused
    override fun onWindowFocusChanged(hasWindowFocus:Boolean){super.onWindowFocusChanged(hasWindowFocus);if(hasWindowFocus)focusSelection()}
    fun saveState(out:Bundle) {
        out.putBoolean("backup_open",isShowing)
        out.putString("backup_export",pendingExport?.name)
    }
    fun restoreState(saved:Bundle?) {
        pendingExport=manager.exported(saved?.getString("backup_export"))
        if(saved?.getBoolean("backup_open")==true)show()
    }
    private fun overview() {
        pages.reset()
        pages.push("backup","Backup e ripristino","Un file locale per conservare i tuoi dati.",navigation=true){page->
            page.row("Crea backup","Salva liste, preferiti e riprese con una password."){
                savePackage=documentPackage(android.content.Intent.ACTION_CREATE_DOCUMENT,"application/octet-stream")
                if(savePackage==null)missingDocuments(page) else exportForm()
            }
            page.row("Ripristina backup","Scegli un file, verifica l’anteprima e seleziona i dati da recuperare.") {
                openPackage=documentPackage(android.content.Intent.ACTION_OPEN_DOCUMENT,"*/*")
                if(openPackage==null){missingDocuments(page);return@row}
                try {openDocument.launch(arrayOf("*/*"))}
                catch(_:ActivityNotFoundException){page.message("La selezione dei file non è disponibile. Apri Lume su un dispositivo con un gestore documenti.",true)}
            }
        }
    }
    private fun documentPackage(action:String,type:String):String? {
        val intent=android.content.Intent(action).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType(type)
        @Suppress("DEPRECATION")
        return activity.packageManager.queryIntentActivities(intent,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull {!it.activityInfo.packageName.contains("frameworkpackagestubs")}?.activityInfo?.packageName
    }
    private fun missingDocuments(page:PlaylistPages.Page) {
        page.message("Serve un gestore documenti compatibile per scegliere e salvare i file. Installane uno sul dispositivo e riprova.",true)
    }
    private fun password(page:PlaylistPages.Page,label:String,tag:String):EditText =
        page.field(label,"",128).apply {
            this.tag=tag
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod=PasswordTransformationMethod.getInstance()
        }
    private fun exportForm() {
        pages.push("backup-export","Crea backup"){page->
            page.text("Il file include le configurazioni delle liste, che possono contenere URL e credenziali. La password protegge tutto il backup.")
            val password=password(page,"Password del backup","backup_password")
            val confirmation=password(page,"Ripeti password","backup_confirm")
            page.text("Da 8 a 128 caratteri. Conserva la password: servirà per aprire il file.",14f)
            page.onDispose={password.text.clear();confirmation.text.clear()}
            page.initialFocus=password
            page.action("Crea e scegli dove salvare",true) {
                val secret=password.text.toString().toCharArray()
                val repeated=confirmation.text.toString().toCharArray()
                try {
                    BackupCipher.requirePassword(secret)
                    if(!secret.contentEquals(repeated))throw IOException("Le password non coincidono. Ripetile e riprova.")
                    password.text.clear();confirmation.text.clear()
                    run(page,"Creo il backup…") {
                        try {
                            val file=manager.export(secret,progress(page));pendingExport=file
                            try {saveDocument.launch("Lume-backup-"+SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(Date())+".lumebackup")}
                            catch(_:ActivityNotFoundException){file.delete();pendingExport=null;throw IOException("La selezione dei file non è disponibile su questo dispositivo.")}
                        }finally {secret.fill('\u0000')}
                    }
                }catch(error:IOException){secret.fill('\u0000');page.message(error.message.orEmpty(),true)}
                finally {repeated.fill('\u0000')}
            }.tag="backup_create"
        }
    }
    private fun exportDocument(uri:Uri) {
        val file=pendingExport;pendingExport=null
        if(file==null || !isShowing){file?.delete();return}
        val page=pages.current ?: return
        run(page,"Salvo il file di backup…") {
            try {
                withContext(Dispatchers.IO) {
                    val output=activity.contentResolver.openOutputStream(uri,"wt") ?: throw IOException("Non riesco ad aprire la destinazione. Scegli un’altra cartella.")
                    output.use {destination->file.inputStream().use {source->
                        val buffer=ByteArray(64*1024)
                        while(true){coroutineContext.ensureActive();val count=source.read(buffer);if(count<0)break;destination.write(buffer,0,count)}
                    }}
                }
                pages.push("backup-saved","Backup salvato",replace=true){done->
                    done.text("Il file contiene le liste personali, i preferiti, i canali nascosti, gli avanzamenti e le preferenze di riproduzione.")
                    done.text("Per recuperare i dati, apri Ripristina backup e usa la stessa password.")
                    done.action("Fine",true){hide()}
                }
            }catch(error:Exception) {
                withContext(kotlinx.coroutines.NonCancellable+Dispatchers.IO){runCatching {if(DocumentsContract.isDocumentUri(activity,uri))DocumentsContract.deleteDocument(activity.contentResolver,uri)}}
                throw error
            }finally {file.delete()}
        }
    }
    private fun readDocument(uri:Uri) {
        val page=pages.current ?: return
        run(page,"Leggo il file di backup…") {
            val file=withContext(Dispatchers.IO) {
                val input=activity.contentResolver.openInputStream(uri)
                    ?: throw IOException("Il file non è disponibile. Selezionalo di nuovo.")
                input.use {manager.copyEncrypted(it)}
            }
            encryptedInput?.delete();encryptedInput=file
            unlock(file)
        }
    }
    private fun unlock(file:File) {
        pages.push("backup-unlock","Apri backup"){page->
            page.text("Inserisci la password usata per creare questo file. Dopo la verifica potrai scegliere cosa ripristinare.")
            val password=password(page,"Password del backup","backup_password");page.initialFocus=password
            page.onDispose={password.text.clear();if(encryptedInput===file){file.delete();encryptedInput=null}}
            page.action("Verifica backup",true) {
                val secret=password.text.toString().toCharArray()
                run(page,"Verifico il backup…") {
                    try {
                        val value=manager.prepare(file,secret,progress(page))
                        password.text.clear();preview=value
                        showPreview(value)
                    }finally {secret.fill('\u0000')}
                }
            }.tag="backup_verify"
        }
    }
    private fun showPreview(value:BackupPreview) {
        pages.push("backup-preview","Scegli cosa ripristinare",replace=true){page->
            page.text("Backup del "+DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(value.created)),14f)
            val present=repository.state.value.map {it.id}.toSet()
            val lists=page.toggle("Liste personali ("+value.playlists.size+")",value.playlists.isNotEmpty()).apply {tag="backup_lists";isEnabled=value.playlists.isNotEmpty()}
            val sourceToggles=value.playlists.map {source->
                val id=source.stored.source.id;val existing=id in present
                val title=source.stored.source.name+" · "+quantity(source.stored.source.count,"voce","voci")+if(existing)" · già presente, conservata" else ""
                id to page.toggle(title,true).apply {
                    tag="backup_source_"+id;isEnabled=!existing
                    val params=layoutParams as LinearLayout.LayoutParams;params.leftMargin=activity.dp(16)
                }
            }
            lists.setOnCheckedChangeListener {_,checked->sourceToggles.forEach {(id,toggle)->toggle.isEnabled=checked && id !in present}}
            page.text("Aggiunge le liste selezionate che mancano. Le liste già presenti mantengono contenuti e impostazioni; i nomi uguali vengono distinti.",14f)
            val personal=page.toggle("Preferiti, La mia lista e canali nascosti",true).apply {tag="backup_personal"}
            page.text(quantity(value.favorites,"preferito","preferiti")+" · "+value.savedSeries+" serie · "+quantity(value.hidden,"canale nascosto","canali nascosti")+". Unisce i dati mantenendo l’ordine attuale.",14f)
            val watch=page.toggle("Riprese, Visto/Non visto e Continua a guardare",value.watches.isNotEmpty()).apply {tag="backup_watch";isEnabled=value.watches.isNotEmpty()}
            page.text(quantity(value.watches.size,"avanzamento","avanzamenti")+". I punti e gli stati già presenti sul dispositivo hanno la precedenza.",14f)
            val preferences=page.toggle("Preferenze di riproduzione",true).apply {tag="backup_preferences"}
            page.text("Applica audio, sottotitoli, qualità e cambio canale del backup.",14f)
            page.onDispose={value.close();if(preview===value)preview=null}
            page.action("Ripristina dati selezionati",true) {
                val categories=buildSet {
                    if(lists.isChecked)add(BackupCategory.PLAYLISTS)
                    if(personal.isChecked)add(BackupCategory.PERSONAL)
                    if(watch.isChecked)add(BackupCategory.WATCH)
                    if(preferences.isChecked)add(BackupCategory.PREFERENCES)
                }
                if(categories.isEmpty()){page.message("Scegli almeno una categoria da ripristinare.",true);return@action}
                val selection=BackupSelection(categories,sourceToggles.filter {it.second.isChecked}.map {it.first}.toSet())
                run(page,"Ripristino i dati selezionati…",commit=true) {
                    val result=manager.restore(value,selection,progress(page));restored()
                    pages.push("backup-restored","Ripristino completato",replace=true){done->
                        done.text("Liste aggiunte: "+result.playlists+" · Preferiti aggiunti: "+result.favorites+" · Avanzamenti recuperati: "+result.watch)
                        if(result.keptPlaylists>0)done.text(quantity(result.keptPlaylists,"lista già presente conservata.","liste già presenti conservate."))
                        if(result.preferences)done.text("Preferenze di riproduzione applicate.")
                        if(result.skipped>0)done.text("Elementi non importati: "+result.skipped+". Sono già presenti, non collegabili alle liste disponibili o oltre il limite degli avanzamenti.",14f)
                        done.action("Fine",true){hide()}
                    }
                }
            }.tag="backup_restore"
        }
    }
    private fun progress(page:PlaylistPages.Page):(String)->Unit = {message->
        activity.runOnUiThread {if(pages.isCurrent(page))page.message(message)}
    }
    private fun quantity(value:Int,singular:String,plural:String)=value.toString()+" "+if(value==1)singular else plural
    private fun run(page:PlaylistPages.Page,message:String,commit:Boolean=false,operation:suspend ()->Unit) {
        if(job!=null || page.busy || !page.alive)return
        pages.hideKeyboard();page.busy(true,message);committing=commit
        job=scope.launch {
            try {operation()}
            catch(cancelled:CancellationException){page.message("Operazione annullata. I dati già presenti sono conservati.")}
            catch(error:Exception){page.message(if(error.javaClass==IOException::class.java)error.message.orEmpty() else "Operazione non riuscita. I dati già presenti sono conservati.",true)}
            finally {committing=false;job=null;if(page.alive){page.busy(false);page.clearMessage(message)};onIdle()}
        }
    }
}
