package it.lumetv.app.ui

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.text.InputFilter
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search and pagination are shared by TV focus and touch; only a page is materialized. */
internal class PersonalCatalogDialogs(private val activity:ComponentActivity,private val scope:CoroutineScope,
    private val track:(AlertDialog)->Unit) {
    private val secondary=androidx.core.graphics.ColorUtils.blendARGB(TvStyle.muted,TvStyle.white,.4f)
    private fun button(label:String,action:()->Unit)=TvStyle.button(activity,label,action=action).apply {
        setTextColor(secondary)
        setOnFocusChangeListener {view,focused->setTextColor(if(focused)TvStyle.background else secondary);TvStyle.focusMotion(view,focused,1.035f)}
    }
    fun library(repository:PersonalPlaylistRepository,choose:(Channel)->Unit) {
        var source:String?=null;var group:String?=null
        paged("Video delle mie liste",filters={column,reload->
            val sourceButton=button("Tutte le liste") {}
            val groupButton=button("Tutti i gruppi") {}
            sourceButton.setOnClickListener {
                val sources=repository.state.value.filter {it.enabled}
                track(AlertDialog.Builder(activity).setTitle("Lista dei video").setItems((listOf("Tutte le liste")+sources.map {it.name}).toTypedArray()) {_,n->
                    source=sources.getOrNull(n-1)?.id;group=null;sourceButton.text=sources.getOrNull(n-1)?.name ?: "Tutte le liste";groupButton.text="Tutti i gruppi";reload()
                }.setNegativeButton("Chiudi",null).show())
            }
            groupButton.setOnClickListener {
                val groups=repository.cached().filter {it.isVod && (source==null || it.sourceId==source)}.flatMap {it.groups}.distinct().sorted()
                paged("Gruppo dei video",load={q,o->val items=(listOf("Tutti i gruppi")+groups).filter {it.contains(q,true)};items.drop(o).take(50) to items.size},label={it}) {
                    group=it.takeUnless {it=="Tutti i gruppi"};groupButton.text=it;reload()
                }
            }
            column.addView(sourceButton,LinearLayout.LayoutParams(-1,-2));column.addView(groupButton,LinearLayout.LayoutParams(-1,-2))
        },load={q,o->repository.library(source,q,group,o).let {it.rows to it.total}},label={it.name},choose=choose)
    }
    fun <T> paged(title:String,filters:((LinearLayout,()->Unit)->Unit)?=null,
        load:suspend (String,Int)->Pair<List<T>,Int>,label:(T)->String,choose:(T)->Unit) {
        val column=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(12))}
        val search=EditText(activity).apply {hint="Cerca";contentDescription="Cerca in $title";setTextColor(TvStyle.white);setHintTextColor(secondary);setSingleLine();minimumHeight=activity.dp(48);this.filters=arrayOf(InputFilter.LengthFilter(300));isSaveEnabled=false}
        val rows=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val status=TvStyle.text(activity,"",14f,TvStyle.white)
        val navigation=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        var offset=0;var job:Job?=null;var generation=0;var alive=true
        lateinit var dialog:AlertDialog
        lateinit var previous:android.widget.TextView;lateinit var next:android.widget.TextView
        fun reload(reset:Boolean=false){
            if(reset)offset=0
            val request=++generation;job?.cancel();status.text="Carico…";rows.removeAllViews()
            job=scope.launch {
                try {
                    delay(100);val (page,total)=load(search.text.toString().trim(),offset)
                    if(!alive || request!=generation)return@launch
                    status.text=if(total==0)"Nessun risultato" else "${offset+1}–${offset+page.size} di $total"
                    page.forEach {item->rows.addView(button(label(item)) {dialog.dismiss();choose(item)}.apply {
                        minimumHeight=activity.dp(48);maxLines=3;gravity=Gravity.START or Gravity.CENTER_VERTICAL
                    },LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(4)})}
                    previous.isEnabled=offset>0;next.isEnabled=offset+page.size<total
                }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                catch(_:Exception){if(alive)status.text="Impossibile caricare. Chiudi e riprova."}
            }
        }
        filters?.invoke(column){reload(true)}
        column.addView(search,LinearLayout.LayoutParams(-1,-2))
        column.addView(status,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8)})
        previous=button("Pagina precedente"){offset=(offset-50).coerceAtLeast(0);reload()}
        next=button("Pagina successiva"){offset+=50;reload()}
        navigation.addView(previous,LinearLayout.LayoutParams(0,-2,1f));navigation.addView(next,LinearLayout.LayoutParams(0,-2,1f))
        column.addView(navigation)
        column.addView(ScrollView(activity).apply {addView(rows)},LinearLayout.LayoutParams(-1,activity.dp(minOf(350,activity.resources.configuration.screenHeightDp/2))))
        dialog=AlertDialog.Builder(activity).setTitle(title).setView(column).setNegativeButton("Chiudi",null).show()
        track(dialog)
        dialog.window?.decorView?.addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:android.view.View){}
            override fun onViewDetachedFromWindow(view:android.view.View){view.removeOnAttachStateChangeListener(this);alive=false;job?.cancel()}
        })
        search.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){reload(true)};override fun afterTextChanged(s:Editable?){}})
        reload()
    }
}
