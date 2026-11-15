package it.lumetv.app.ui

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import it.lumetv.app.companion.CompanionTvController
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Same in-window navigation and D-pad vocabulary as the existing TV settings. */
@android.annotation.SuppressLint("ViewConstructor")
internal class CompanionTvScreen(private val activity:ComponentActivity,private val scope:CoroutineScope,
    private val controller:CompanionTvController,private val closed:()->Unit):FrameLayout(activity) {
    private val pages=PlaylistPages(activity,scope,false,::hide)
    private var previous:View?=null
    private val accessibility=linkedMapOf<View,Int>()
    private var ticker:Job?=null
    private var key=""
    private var countdown:TextView?=null
    private var managing=false
    private var manual=false
    var isShowing=false;private set
    init {
        visibility=GONE;setBackgroundColor(TvStyle.background);isClickable=true;isSaveEnabled=false
        addView(CatalogBackdrop(activity),LayoutParams(-1,-1));addView(pages,LayoutParams(-1,-1))
        controller.onChanged={if(isShowing)render()}
        controller.onPaired={if(isShowing && !managing)hide()}
        pages.onChanged={pages.current?.root?.findViewWithTag<View>("playlist_page_back")?.setOnClickListener {handleBack()}}
    }
    fun show() {
        if(isShowing)return
        previous=activity.currentFocus
        (parent as? ViewGroup)?.let {parent->for(i in 0 until parent.childCount)parent.getChildAt(i).takeIf {it!==this}?.let {
            accessibility[it]=it.importantForAccessibility;it.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }}
        managing=false;manual=false;controller.enabled(true)
        isShowing=true;visibility=VISIBLE;bringToFront();key="";render()
        ticker=scope.launch {while(isShowing){delay(1000);render()}}
    }
    fun hide() {
        if(!isShowing)return
        isShowing=false;ticker?.cancel();ticker=null;controller.finishPairing();pages.reset();visibility=GONE
        accessibility.forEach {(v,value)->v.importantForAccessibility=value};accessibility.clear()
        previous?.takeIf {it.isShown}?.requestFocus();closed()
    }
    fun handleBack():Boolean {
        if(!isShowing)return false
        if(managing){managing=false;manual=false;key="";render()} else if(manual)showMethod(false) else hide()
        return true
    }
    override fun focusSearch(focused:View?,direction:Int):View? =
        android.view.FocusFinder.getInstance().findNextFocus(this,focused,direction) ?: focused
    private fun render() {
        if(!managing && controller.store.enabled && controller.endpoint!=null && controller.invite()==null &&
            controller.pending()==null && !controller.handingOver())controller.beginPairing(confirmOnTv=manual)
        val invite=controller.invite();val pending=controller.pending();val peers=controller.store.peers()
        val handingOver=controller.handingOver()
        val nextKey=listOf(managing,manual,controller.store.enabled,controller.endpoint,controller.message,invite?.code,pending?.ticket,handingOver,peers).joinToString()
        countdown?.text=invitationCountdown()
        if(nextKey==key)return
        key=nextKey;countdown=null
        pages.push("companion-tv",if(managing)"Gestisci collegamenti" else "Collega telefono",
            if(managing)"Telefoni autorizzati su questa TV." else "Collega il telecomando di Lume sulla stessa rete di casa.",replace=true) {page->
            page.root.tag="companion_tv_screen"
            if(managing) {
                if(peers.isEmpty())page.text("Nessun telefono autorizzato.",18f)
                peers.forEach {peer->page.row("Rimuovi ${peer.name}","Revoca subito il controllo di questa TV.") {controller.revoke(peer.id);render()}}
                page.section("Collegamento")
                page.row("Disattiva collegamento","I telefoni autorizzati restano memorizzati.") {controller.enabled(false);hide()}
                return@push
            }
            if(pending!=null) {
                page.text("Consenti a questo telefono?",25f,false)
                page.text(pending.name,24f,false).apply {tag="companion_pending_name";maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;contentDescription=pending.name}
                page.text("Potrà usare i menu e controllare la riproduzione di Lume su questa TV.")
                val allow=page.row("Consenti","Autorizza questo telefono.") {controller.decide(pending.ticket,true);render()}.apply {tag="companion_approve"}
                page.row("Rifiuta","Chiudi questa richiesta.") {controller.decide(pending.ticket,false);render()}.tag="companion_deny"
                page.initialFocus=allow
            } else if(handingOver) {
                page.text("Telefono autorizzato",25f,false)
                page.text("Apro il telecomando sul telefono… Questa schermata si chiuderà automaticamente.",18f)
            } else if(invite!=null) {
                if(manual) {
                    page.text("Codice di collegamento",18f)
                    page.text(invite.code.chunked(4).joinToString(" "),25f,false).tag="companion_pair_code"
                    controller.endpoint?.let {page.text("${controller.name}\nIP: ${it.host} · Porta: ${it.port}",16f).tag="companion_address"}
                    page.text("Sul telefono: Telecomando → Altri metodi.")
                    page.text("Con il codice dovrai confermare il telefono su questa TV.")
                    page.row("Mostra QR") {showMethod(false)}
                } else {
                    page.body.addView(TvStyle.text(activity,"Apri Telecomando sul telefono",22f,bold=true).apply {
                        ViewCompat.setAccessibilityHeading(this,true)
                    },LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(12)})
                    page.text("Inquadra il QR: il telecomando si apre automaticamente.",18f)
                    page.text("Chi inquadra questo QR può controllare Lume su questa TV.",15f)
                    page.row("Altri metodi","Mostra codice e indirizzo della TV.") {showMethod(true)}.tag="companion_tv_methods"
                }
                countdown=page.text(invitationCountdown(),13f)
                page.row("Gestisci collegamenti","Telefoni autorizzati e disattivazione.") {managing=true;controller.finishPairing();key="";render()}.tag="companion_manage"
                if(!manual)qrColumns(page,invite.encode())
            } else {
                page.text(if(controller.endpoint==null)controller.message.ifBlank {"Preparo il QR…"} else "Preparo il QR…",18f)
                page.text("Il QR comparirà qui appena la TV sarà pronta.")
                page.row("Gestisci collegamenti") {managing=true;key="";render()}.tag="companion_manage"
            }
        }
        pages.focus()
    }
    private fun showMethod(value:Boolean) {
        manual=value;controller.finishPairing()
        controller.beginPairing(confirmOnTv=manual);key="";render()
    }
    private fun invitationCountdown()="${if(manual)"Il codice" else "Il QR"} si rinnova automaticamente tra ${controller.remaining()/1000} secondi."
    private fun qrColumns(page:PlaylistPages.Page,value:String) {
        // Keep the whole page on the left, including Back and the scrollable secondary actions.
        val content=(0 until page.root.childCount).map {page.root.getChildAt(it)}
        page.root.removeAllViews()
        page.subtitleView.maxLines=Int.MAX_VALUE
        val text=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;tag="companion_pair_text"
            content.forEach {addView(it,it.layoutParams)}
        }
        val image=ImageView(activity).apply {
            tag="companion_qr";scaleType=ImageView.ScaleType.CENTER
            contentDescription="QR per collegare questo telefono";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val area=FrameLayout(activity).apply {addView(image,LayoutParams(0,0,Gravity.CENTER))}
        val resize=View.OnLayoutChangeListener {view,_,_,_,_,_,_,_,_->
            val size=minOf(view.width,view.height)
            if(size>0 && image.layoutParams.width!=size) {
                image.layoutParams=LayoutParams(size,size,Gravity.CENTER)
                image.setImageBitmap(qr(value,size))
            }
        }
        area.addOnLayoutChangeListener(resize)
        val columns=LinearLayout(activity).apply {
            addView(text,LinearLayout.LayoutParams(0,-1,1f).apply {rightMargin=activity.dp(32)})
            addView(area,LinearLayout.LayoutParams(0,-1,1f))
        }
        page.root.addView(columns,LinearLayout.LayoutParams(-1,-1))
        page.onDispose={area.removeOnLayoutChangeListener(resize);image.setImageDrawable(null)}
    }
    private fun qr(value:String,size:Int):Bitmap {
        // Match physical pixels exactly: resampling a QR can blur its module boundaries.
        val matrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,size,size,mapOf(EncodeHintType.MARGIN to 4))
        return Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).apply {
            density=Bitmap.DENSITY_NONE
            setPixels(IntArray(size*size){i->if(matrix[i%size,i/size])android.graphics.Color.BLACK else android.graphics.Color.WHITE},0,size,0,0,size,size)
        }
    }
}
