package it.lumetv.app.ui

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.CoroutineScope

/** The same recycled, bounded list is used by video, group and guide selectors. */
internal class ContinuousPicker<T>(context:Context,scope:CoroutineScope,private val mobile:Boolean,
    private val label:(T)->String,private val choose:(T)->Unit) {
    val view=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
    private val count=TvStyle.text(context,"Carico…",14f,TvStyle.muted)
    private var closed=false
    private var pendingPosition:Int?=null
    private var selectedPosition:Int?=null
    private var pendingFocus:Int?=null
    private val adapter:RecyclerView.Adapter<Holder> = object:RecyclerView.Adapter<Holder>() {
        init {setHasStableIds(true)}
        override fun getItemCount()=catalog.size
        override fun getItemId(position:Int)=position.toLong()
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val row=OptionsRowView(context,mobile,""){}.apply {
                titleView.maxLines=3
                layoutParams=RecyclerView.LayoutParams(-1,-2)
            }
            return Holder(row)
        }
        override fun onBindViewHolder(holder:Holder,position:Int) {
            catalog.ensure(position)
            val item=catalog.peek(position)
            holder.row.bind(item?.let(label) ?: if(catalog.failedAt(position))"Riprova a caricare" else "Caricamento…")
            holder.row.setOnClickListener {
                val current=holder.bindingAdapterPosition
                if(current!=RecyclerView.NO_POSITION) {
                    val selected=catalog.peek(current)
                    if(selected!=null){selectedPosition=current;choose(selected)} else catalog.retry(current)
                }
            }
        }
    }
    private class Holder(val row:OptionsRowView):RecyclerView.ViewHolder(row)
    val list:RecyclerView=RecyclerView(context).apply {
        layoutManager=LinearLayoutManager(context);adapter=this@ContinuousPicker.adapter
        itemAnimator=null;clipToPadding=false
        addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
            val manager=layoutManager as LinearLayoutManager
            catalog.viewport(manager.findFirstVisibleItemPosition().coerceAtLeast(0),manager.findLastVisibleItemPosition().coerceAtLeast(0))
        }})
    }
    val catalog:ContinuousCatalog<T> = ContinuousCatalog<T>(scope,50) {change->list.post {
        if(!closed) {
            if(change.sizeChanged)adapter.notifyDataSetChanged()
            else if(change.start<catalog.size)adapter.notifyItemRangeChanged(change.start,change.count.coerceAtMost(catalog.size-change.start))
            count.text=when {catalog.size>0->"${catalog.size} risultati";catalog.hasError->"Impossibile caricare. Premi qui per riprovare.";else->"Nessun risultato"}
            count.isFocusable=catalog.hasError && catalog.size==0;count.isClickable=count.isFocusable
            count.minimumHeight=context.dp(if(count.isFocusable)48 else 32)
            count.background=if(count.isFocusable)TvStyle.focusBackground(context) else null
            if(catalog.size>0)pendingPosition?.let {position->
                pendingPosition=null
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position.coerceAtMost(catalog.size-1),0)
            }
            pendingFocus?.let {position->list.post {
                if(!closed && list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()==true)pendingFocus=null
            }}
        }
    }}
    init {
        count.minimumHeight=context.dp(32)
        count.setOnClickListener {catalog.retry()}
        view.addView(count,LinearLayout.LayoutParams(-1,-2))
        view.addView(list,LinearLayout.LayoutParams(-1,0,1f))
    }
    fun bind(key:Any,position:Int=0,restoreFocus:Boolean=false,load:suspend(Int)->Pair<List<T>,Int>) {
        if(catalog.bind(key,position,load)) {
            pendingPosition=position
            pendingFocus=selectedPosition.takeIf {restoreFocus && !mobile}
            count.text="Carico…";count.isClickable=false;count.isFocusable=false
            adapter.notifyDataSetChanged();list.scrollToPosition(position)
        }
    }
    fun close(){closed=true;catalog.close()}
}
