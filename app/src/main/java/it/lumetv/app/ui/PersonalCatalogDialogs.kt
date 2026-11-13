package it.lumetv.app.ui

import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.text.InputFilter
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PersonalCatalogQuery
import it.lumetv.core.PlaylistContentKind
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import kotlinx.coroutines.CoroutineScope

/** Search and continuous scrolling share a bounded cache on TV and touch. */
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
                group(repository,PersonalCatalogQuery(source=source,content=PlaylistContentKind.VOD)) {
                    group=it.takeUnless {it=="Tutti i gruppi"};groupButton.text=it;reload()
                }
            }
            column.addView(sourceButton,LinearLayout.LayoutParams(-1,-2));column.addView(groupButton,LinearLayout.LayoutParams(-1,-2))
        },load={q,o->repository.library(source,q,group,o).let {it.rows to it.total}},label={it.name},choose=choose)
    }
    fun group(repository:PersonalPlaylistRepository,query:it.lumetv.app.data.PersonalCatalogQuery,choose:(String)->Unit) {
        paged("Scegli il gruppo",load={text,offset->
            val all=text.isBlank()
            val (rows,total)=repository.groups(query.copy(text=text,offset=(offset-if(all)1 else 0).coerceAtLeast(0),limit=if(all && offset==0)49 else 50))
            ((if(all && offset==0)listOf("Tutti i gruppi") else emptyList())+rows) to total+if(all)1 else 0
        },label={it},choose=choose)
    }
    fun channels(repository:PersonalPlaylistRepository,source:String,kind:it.lumetv.core.MediaKind,favorites:()->Set<String>,choose:(Channel)->Unit) {
        var onlyFavorites=false
        paged("Canali della lista",filters={column,reload->
            column.addView(button("Tutti i canali"){ }.apply {setOnClickListener {
                onlyFavorites=!onlyFavorites;text=if(onlyFavorites)"Solo preferiti" else "Tutti i canali";reload()
            }})
        },load={q,o->repository.catalog(PersonalCatalogQuery(source=source,kind=kind,text=q,offset=o,limit=50,ids=if(onlyFavorites)favorites() else null)).let {it.rows to it.total}},label={it.name},choose=choose)
    }
    fun <T> paged(title:String,filters:((LinearLayout,()->Unit)->Unit)?=null,
        load:suspend (String,Int)->Pair<List<T>,Int>,label:(T)->String,choose:(T)->Unit) {
        val column=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(12))}
        val search=EditText(activity).apply {hint="Cerca";contentDescription="Cerca in $title";setTextColor(TvStyle.white);setHintTextColor(secondary);setSingleLine();minimumHeight=activity.dp(48);this.filters=arrayOf(InputFilter.LengthFilter(300));isSaveEnabled=false}
        lateinit var dialog:AlertDialog
        val mobile=activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK != android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        val picker=ContinuousPicker(activity,scope,mobile,label){item->dialog.dismiss();choose(item)}
        var revision=0
        fun reload(){val query=search.text.toString().trim();picker.bind(query to ++revision){offset->load(query,offset)}}
        filters?.invoke(column){reload()}
        column.addView(search,LinearLayout.LayoutParams(-1,-2))
        column.addView(picker.view,LinearLayout.LayoutParams(-1,activity.dp(minOf(390,activity.resources.configuration.screenHeightDp/2))))
        dialog=AlertDialog.Builder(activity).setTitle(title).setView(column).setNegativeButton("Chiudi",null).show()
        track(dialog)
        dialog.window?.decorView?.addOnAttachStateChangeListener(object:android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:android.view.View){}
            override fun onViewDetachedFromWindow(view:android.view.View){view.removeOnAttachStateChangeListener(this);picker.close()}
        })
        search.addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){reload()};override fun afterTextChanged(s:Editable?){}})
        reload()
    }
}
