package it.lumetv.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.*
import it.lumetv.app.ui.TvStyle.dp

internal data class VideoShelfRow(val key:String,val title:String,val query:VideoQuery,val page:VideoPage,val wide:Boolean=false,val loading:Boolean=false)

/** Only visible shelves create poster views and image requests; previews own at most 16 rows each. */
internal class VideoShelfAdapter(private val activity:androidx.activity.ComponentActivity,private val mobile:Boolean,
    private val artwork:VideoArtworkRepository,private val choose:(VideoItem)->Unit,
    private val open:(VideoShelfRow)->Unit,private val focus:(VideoItem)->Unit,private val need:(VideoShelfRow)->Unit={},private val interact:()->Unit={}) : RecyclerView.Adapter<VideoShelfAdapter.Holder>() {
    var rows:List<VideoShelfRow> = emptyList()
    var restoreFocusKey:String?=null
    var restoreFocusShelf:String?=null
    var focusedShelf:String?=null;private set
    private val positions=linkedMapOf<String,android.os.Parcelable?>()
    private val pool=RecyclerView.RecycledViewPool()
    inner class Holder(val root:LinearLayout,val heading:android.widget.TextView,val more:android.widget.TextView,val list:RecyclerView):RecyclerView.ViewHolder(root) {var key="";var posters:VideoPosterAdapter?=null}
    init {setHasStableIds(true)}
    override fun getItemCount()=rows.size
    override fun getItemId(position:Int):Long {var hash=-3750763034362895579L;rows[position].key.forEach {hash=(hash xor it.code.toLong())*1099511628211L};return hash}
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        val context=activity
        val root=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false;layoutParams=RecyclerView.LayoutParams(-1,-2)}
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(context.dp(4),context.dp(14),0,context.dp(3))}
        val heading=TvStyle.text(context,"",if(mobile)20f else 22f,bold=true).apply {maxLines=2;androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)}
        val more=CatalogPanelStyle.button(context,mobile,"Mostra tutto"){}
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f));header.addView(more,LinearLayout.LayoutParams(-2,context.dp(48)))
        root.addView(header)
        val list=RecyclerView(context).apply {layoutManager=LinearLayoutManager(context,RecyclerView.HORIZONTAL,false);itemAnimator=null;clipChildren=false;clipToPadding=false;setPadding(0,context.dp(4),context.dp(8),context.dp(6));setRecycledViewPool(pool)}
        root.addView(list,LinearLayout.LayoutParams(-1,-2))
        val holder=Holder(root,heading,more,list);val focusMotion=more.onFocusChangeListener
        more.setOnFocusChangeListener {view,focused->
            focusMotion?.onFocusChange(view,focused)
            if(focused){restoreFocusKey=null;restoreFocusShelf=null;focusedShelf=holder.key;interact()}
        }
        return holder
    }
    override fun onBindViewHolder(holder:Holder,position:Int) {
        val row=rows[position];val sameShelf=holder.key==row.key && holder.posters!=null && holder.list.adapter===holder.posters
        remember(holder);holder.key=row.key;holder.heading.text=row.title
        holder.more.contentDescription="Mostra tutto: ${row.title}";holder.more.setOnClickListener {open(row)}
        val adapter=if(sameShelf)holder.posters!! else VideoPosterAdapter(activity,mobile,artwork,if(row.wide)206 else if(mobile)112 else 132,
            wide=row.wide,mayRestore={key->restoreFocusKey==key && (restoreFocusShelf==null || restoreFocusShelf==holder.key)},choose=choose,
            focus={item->restoreFocusKey=null;restoreFocusShelf=null;focusedShelf=holder.key;interact();focus(item)})
        if(!holder.list.hasFocus())adapter.restoreFocusKey=restoreFocusKey.takeIf {restoreFocusShelf==null || restoreFocusShelf==row.key}
        holder.posters=adapter
        if(sameShelf && !row.loading && adapter.loadingCount==0)adapter.submit(row.page.rows) else {
            adapter.items=row.page.rows;adapter.loadingCount=if(row.loading)4 else 0
            if(sameShelf)adapter.notifyDataSetChanged()
        }
        if(!sameShelf) {
            holder.list.adapter=adapter
            positions[row.key]?.let {holder.list.layoutManager?.onRestoreInstanceState(it)}
        }
        holder.more.isEnabled=row.page.rows.isNotEmpty()
        if(row.loading)holder.list.post {if(holder.key==row.key && holder.root.isShown)need(row)}
    }
    fun submit(next:List<VideoShelfRow>) {
        val old=rows;rows=next
        val diff=androidx.recyclerview.widget.DiffUtil.calculateDiff(object:androidx.recyclerview.widget.DiffUtil.Callback(){
            override fun getOldListSize()=old.size
            override fun getNewListSize()=next.size
            override fun areItemsTheSame(a:Int,b:Int)=old[a].key==next[b].key
            override fun areContentsTheSame(a:Int,b:Int)=old[a]==next[b]
            override fun getChangePayload(a:Int,b:Int):Any="metadata"
        });diff.dispatchUpdatesTo(this)
    }
    fun pause(home:RecyclerView){for(i in 0 until home.childCount)(home.getChildViewHolder(home.getChildAt(i)) as? Holder)?.let {it.posters?.pause(it.list)}}
    fun resume(home:RecyclerView){for(i in 0 until home.childCount)(home.getChildViewHolder(home.getChildAt(i)) as? Holder)?.let {holder->
        holder.posters?.restoreFocusKey=restoreFocusKey.takeIf {restoreFocusShelf==null || restoreFocusShelf==holder.key}
        holder.posters?.resume(holder.list);rows.getOrNull(holder.bindingAdapterPosition)?.takeIf {it.loading}?.let(need)
    }}
    private fun remember(holder:Holder){if(holder.key.isNotBlank()){positions[holder.key]=holder.list.layoutManager?.onSaveInstanceState();while(positions.size>24)positions.remove(positions.keys.first())}}
    override fun onViewRecycled(holder:Holder){remember(holder);holder.list.adapter=null;super.onViewRecycled(holder)}
    fun save(list:RecyclerView,out:Bundle) {
        for(i in 0 until list.childCount)(list.getChildViewHolder(list.getChildAt(i)) as? Holder)?.let(::remember)
        out.putString("focus_shelf",focusedShelf)
        out.putBundle("shelves",Bundle().apply {positions.forEach {(key,value)->putParcelable(key,value)}})
    }
    @Suppress("DEPRECATION") fun restore(saved:Bundle?){restoreFocusShelf=saved?.getString("focus_shelf");focusedShelf=restoreFocusShelf;saved?.getBundle("shelves")?.let {b->b.keySet().take(24).forEach {positions[it]=b.getParcelable(it)}}}
}
