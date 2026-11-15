package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.R
import it.lumetv.app.data.ChannelRepository
import it.lumetv.app.data.HiddenChannel
import it.lumetv.app.data.PersonalCatalogQuery
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared in-window editor. Only favorite IDs are retained; catalog metadata stays in bounded windows. */
@SuppressLint("ViewConstructor", "NotifyDataSetChanged", "ClickableViewAccessibility")
class ChannelManagementScreen(private val activity:ComponentActivity,private val scope:CoroutineScope,
    private val repository:ChannelRepository,private val mobile:Boolean):FrameLayout(activity) {
    private enum class Mode(val label:String){FAVORITES("Preferiti"),ALL("Tutti"),HIDDEN("Nascosti")}
    private val store=repository.personalization
    private var mode=Mode.FAVORITES
    private var kind=MediaKind.TV
    private var refreshJob:Job?=null
    private var generation=0
    private var refreshing=false
    private var restoring=false
    private var previousFocus:View?=null
    private val hiddenAccessibility=linkedMapOf<View,Int>()
    private var ids=emptyList<String>()
    private var baseline=emptyList<String>()
    private var baselineOwners=emptyMap<String,String>()
    private var hidden=emptyList<HiddenChannel>()
    private var expectedRevision=0L
    private var moving:Channel?=null
    private var movingIndex=-1
    private var dragging=false
    private var dragHolder:RecyclerView.ViewHolder?=null
    private var moveKeyUp:Int?=null
    private var preview:MutableList<String>?=null
    private var indices=emptyMap<String,Int>()
    private var detailPosition=0
    private var pendingFocus:Int?=null
    private var notice:String?=null
    var isShowing=false;private set
    private val column=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL
        setPadding(activity.dp(if(mobile)20 else 46),activity.dp(if(mobile)4 else 24),activity.dp(if(mobile)20 else 46),activity.dp(12))}
    private val modes=LinearLayout(activity)
    private val kinds=LinearLayout(activity)
    private val search=EditText(activity)
    private val status=TvStyle.text(activity,"",14f,TvStyle.muted).apply {
        ViewCompat.setAccessibilityLiveRegion(this,ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    private val message=TvStyle.text(activity,"",18f,TvStyle.muted).apply {gravity=Gravity.CENTER;setPadding(activity.dp(12),activity.dp(12),activity.dp(12),activity.dp(12))}
    private val body=FrameLayout(activity)
    private val details=ScrollView(activity).apply {visibility=GONE;isSaveEnabled=false}
    private val moveActions=LinearLayout(activity).apply {gravity=Gravity.CENTER;visibility=GONE}
    private val adapter:Rows=Rows()
    private val data:ContinuousCatalog<Channel> = ContinuousCatalog(scope,initialDelay=0){change->post {
        if(!isShowing)return@post
        if(change.sizeChanged)adapter.notifyDataSetChanged() else if(preview==null && change.start<adapter.itemCount)
            adapter.notifyItemRangeChanged(change.start,change.count.coerceAtMost(adapter.itemCount-change.start))
        else adapter.notifyDataSetChanged()
        updateStatus()
        pendingFocus?.takeIf {data.size>0}?.let {position->pendingFocus=null;focusRow(position.coerceAtMost(data.size-1))}
    }}
    private val list=object:RecyclerView(activity){
        override fun dispatchTouchEvent(event:MotionEvent):Boolean {
            if(event.actionMasked==MotionEvent.ACTION_CANCEL && moving!=null)cancelMove(cancelGesture=false)
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        layoutManager=LinearLayoutManager(activity);adapter=this@ChannelManagementScreen.adapter
        itemAnimator=null;isSaveEnabled=false;clipToPadding=false
        if(!mobile)setPadding(activity.dp(8),0,activity.dp(8),activity.dp(4))
        addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
            val lm=layoutManager as LinearLayoutManager
            val first=lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            val last=lm.findLastVisibleItemPosition().coerceAtLeast(first)
            // Moving a row must not pin its original, possibly distant block in memory.
            val positions=(first..last).filter {it!=movingIndex}.map {originalPosition(it)}
            data.viewport(positions.minOrNull() ?: first,positions.maxOrNull() ?: last)
        }})
    }
    private val touch=ItemTouchHelper(object:ItemTouchHelper.Callback(){
        override fun isLongPressDragEnabled()=false
        override fun isItemViewSwipeEnabled()=false
        override fun getMovementFlags(rv:RecyclerView,holder:RecyclerView.ViewHolder)=makeMovementFlags(if(canMove())ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0,0)
        override fun onMove(rv:RecyclerView,from:RecyclerView.ViewHolder,to:RecyclerView.ViewHolder):Boolean {
            if(preview==null)beginMove(from.bindingAdapterPosition)
            return moveTo(to.bindingAdapterPosition)
        }
        override fun onSwiped(holder:RecyclerView.ViewHolder,direction:Int)=Unit
        override fun clearView(rv:RecyclerView,holder:RecyclerView.ViewHolder){super.clearView(rv,holder);if(mobile && dragging && moving!=null && holder===dragHolder)finishMove()}
    })
    init {
        visibility=GONE;isClickable=true;isSaveEnabled=false;setBackgroundColor(TvStyle.background)
        ViewCompat.setAccessibilityPaneTitle(this,"Canali e preferiti")
        val heading=TvStyle.text(activity,"Canali e preferiti",if(mobile)26f else 32f,bold=true).apply {
            maxLines=2;ellipsize=TextUtils.TruncateAt.END;ViewCompat.setAccessibilityHeading(this,true)
        }
        val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL
            addView(heading,LinearLayout.LayoutParams(0,-2,1f))
            addView(button("Indietro"){handleBack()},LinearLayout.LayoutParams(-2,-2))}
        column.addView(header,LinearLayout.LayoutParams(-1,-2))
        column.addView(HorizontalScrollView(activity).apply {isHorizontalScrollBarEnabled=false;addView(modes)},LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8)})
        val controls=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL
            addView(kinds,LinearLayout.LayoutParams(-2,-2))
            search.apply {
                textSize=16f;hint="Cerca un canale";contentDescription=hint;setSingleLine(true)
                setTextColor(TvStyle.white);setHintTextColor(TvStyle.muted);minimumHeight=activity.dp(48)
                background=TvStyle.rounded(TvStyle.surface,activity.dp(12).toFloat());isSaveEnabled=false
                setPadding(activity.dp(12),activity.dp(10),activity.dp(12),activity.dp(10))
                filters=arrayOf(InputFilter.LengthFilter(120))
                addTextChangedListener(object:TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                    override fun afterTextChanged(s:Editable?)=Unit
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!restoring && isShowing){notice=null;refresh()}}
                })
                setOnEditorActionListener {_,_,_->hideKeyboard();true}
            }
            addView(search,LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=activity.dp(8)})
        }
        column.addView(controls,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(4)})
        body.addView(list,LayoutParams(-1,-1));body.addView(message,LayoutParams(-1,-1));body.addView(details,LayoutParams(-1,-1))
        column.addView(body,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=activity.dp(8)})
        listOf("Su" to -1,"Giù" to 1).forEach {(label,direction)->moveActions.addView(button(label){moveTo(movingIndex+direction)},LinearLayout.LayoutParams(0,-2,1f))}
        moveActions.addView(button("Salva"){finishMove()},LinearLayout.LayoutParams(0,-2,1f))
        moveActions.addView(button("Annulla"){cancelMove()},LinearLayout.LayoutParams(0,-2,1f))
        column.addView(moveActions,LinearLayout.LayoutParams(-1,-2))
        column.addView(status,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8)})
        addView(column,LayoutParams(-1,-1));touch.attachToRecyclerView(list);buildControls()
        scope.launch {store.revision.collect {if(isShowing)refresh()}}
        scope.launch {repository.personal.version.collect {if(isShowing)refresh()}}
    }
    private fun button(label:String,selected:Boolean=false,action:()->Unit)=if(mobile)MobileStyle.button(activity,label,selected,action) else TvStyle.button(activity,label,selected,action)
    private fun buildControls() {
        val focused=activity.currentFocus?.tag
        modes.removeAllViews();kinds.removeAllViews()
        Mode.entries.forEach {value->modes.addView(button(value.label,mode==value){if(mode!=value){mode=value;notice=null;buildControls();refresh()}}.apply {tag="channel-mode-${value.name}"},LinearLayout.LayoutParams(-2,-2).apply {rightMargin=activity.dp(6)})}
        listOf(MediaKind.TV to "TV",MediaKind.RADIO to "Radio").forEach {(value,label)->kinds.addView(button(label,kind==value){if(kind!=value){kind=value;notice=null;buildControls();refresh()}}.apply {tag="channel-kind-${value.name}"})}
        if(isShowing && !mobile && focused is String)findViewWithTag<View>(focused)?.requestFocus()
    }
    fun show() {
        if(isShowing)return
        previousFocus=activity.currentFocus
        (parent as? ViewGroup)?.let {p->for(i in 0 until p.childCount)p.getChildAt(i).takeUnless {it===this}?.let {v->hiddenAccessibility[v]=v.importantForAccessibility;v.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}}
        isShowing=true;visibility=VISIBLE;bringToFront();refresh()
        modes.getChildAt(mode.ordinal)?.requestFocus()
    }
    fun hide() {
        if(!isShowing)return
        hideKeyboard();cancelMove(false);generation++;refreshJob?.cancel();data.close();details.visibility=GONE
        isShowing=false;visibility=GONE
        hiddenAccessibility.forEach {(view,value)->view.importantForAccessibility=value};hiddenAccessibility.clear()
        previousFocus?.takeIf {it.isShown}?.requestFocus();previousFocus=null
    }
    fun handleBack():Boolean {
        if(!isShowing)return false
        when {
            moving!=null->cancelMove()
            ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime())==true->hideKeyboard()
            details.visibility==VISIBLE->{details.visibility=GONE;focusRow(detailPosition)}
            else->hide()
        }
        return true
    }
    fun cancelPendingMove(){if(moving!=null)cancelMove()}
    fun focusSelection(){if(isShowing && !hasFocus())modes.getChildAt(mode.ordinal)?.requestFocus()}
    override fun onWindowFocusChanged(hasWindowFocus:Boolean){super.onWindowFocusChanged(hasWindowFocus);if(hasWindowFocus)focusSelection()}
    fun saveState(out:Bundle){out.putBoolean("channel_editor_open",isShowing);out.putString("channel_editor_mode",mode.name);out.putString("channel_editor_kind",kind.name);out.putString("channel_editor_search",search.text.toString())}
    fun restoreState(saved:Bundle?) {
        mode=Mode.entries.firstOrNull {it.name==saved?.getString("channel_editor_mode")} ?: Mode.FAVORITES
        kind=MediaKind.entries.firstOrNull {it.name==saved?.getString("channel_editor_kind")} ?: MediaKind.TV
        restoring=true;search.setText(saved?.getString("channel_editor_search").orEmpty());restoring=false
        buildControls();if(saved?.getBoolean("channel_editor_open")==true)show()
    }
    private fun refresh() {
        if(pendingFocus==null)list.findContainingViewHolder(activity.currentFocus ?: list)?.bindingAdapterPosition?.takeIf {it>=0}?.let {pendingFocus=it}
        generation++;val epoch=generation;refreshJob?.cancel();cancelMove(false);data.close();details.visibility=GONE
        refreshing=true;ids=emptyList();hidden=emptyList();adapter.notifyDataSetChanged();updateStatus()
        refreshJob=scope.launch {
            delay(80)
            try {
                val revision=store.revision.value;val version=repository.personal.version.value
                val query=PersonalCatalogQuery(kind=kind,text=search.text.toString().trim(),includeBuiltin=true,
                    ids=if(mode==Mode.FAVORITES)repository.favorites() else null,favoriteOrder=mode==Mode.FAVORITES)
                if(mode==Mode.HIDDEN)hidden=store.hidden.filter {it.kind==kind && (query.text.isBlank() || it.name.contains(query.text,true))}
                else if(mode==Mode.FAVORITES && query.text.isBlank()) {
                    baselineOwners=repository.favoriteLocations(kind);baseline=baselineOwners.keys.toList();ids=repository.favoriteSequence(kind)
                }
                if(epoch!=generation || !isShowing)return@launch
                if(revision!=store.revision.value || version!=repository.personal.version.value){refresh();return@launch}
                expectedRevision=revision
                refreshing=false
                if(mode!=Mode.HIDDEN) {
                    val sequence=ids
                    data.bind(epoch) {offset->
                        if(mode==Mode.FAVORITES && query.text.isBlank()) {
                            val window=sequence.subList(offset.coerceAtMost(sequence.size),(offset+data.blockSize).coerceAtMost(sequence.size))
                            val rows=repository.catalog(query.copy(ids=window.toSet(),favoriteOrder=false,offset=0,limit=data.blockSize)).rows.associateBy {it.id}
                            window.mapNotNull(rows::get) to sequence.size
                        } else repository.catalog(query.copy(offset=offset,limit=data.blockSize)).let {it.rows to it.total}
                    }
                }
                adapter.notifyDataSetChanged();updateStatus()
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(epoch==generation){refreshing=false;message.text="Impossibile caricare i canali. Premi qui per riprovare.";message.visibility=VISIBLE;message.isFocusable=true;message.setOnClickListener {refresh()}}}
        }
    }
    private fun updateStatus() {
        val count=adapter.itemCount
        val loading=refreshing || data.loading && count==0
        message.visibility=if(count==0)VISIBLE else GONE
        message.isFocusable=data.hasError;message.isClickable=data.hasError
        message.text=when {
            loading->"Carico i canali…"
            data.hasError->"Impossibile caricare i canali. Premi qui per riprovare."
            search.text.isNotBlank()->"Nessun risultato. Prova un altro nome."
            mode==Mode.HIDDEN->"Nessun canale nascosto."
            mode==Mode.FAVORITES->"Aggiungi i preferiti dal cuore accanto a un canale, oppure dalla vista Tutti."
            else->"Nessun canale disponibile."
        }
        message.setOnClickListener {data.retry()}
        status.text=store.storageError ?: notice ?: when {
            moving!=null->if(mobile)if(dragging)"Rilascia per salvare. Indietro annulla." else "Sposta il canale, poi salva. Indietro annulla." else "↑ ↓ Sposta · OK Salva · Indietro Annulla"
            mode==Mode.HIDDEN->"$count nascosti · Ripristina per mostrarli di nuovo."
            mode==Mode.FAVORITES && search.text.isNotBlank()->"$count preferiti · Azzera la ricerca per riordinarli."
            mode==Mode.FAVORITES->if(mobile)"$count preferiti · Trascina la maniglia per riordinare." else "$count preferiti · OK per gestire o spostare."
            else->"$count canali · ${if(mobile)"Tocca un canale" else "OK"} per aggiungere ai preferiti o nascondere."
        }
    }
    private fun originalPosition(position:Int)=preview?.getOrNull(position)?.let {indices[it]} ?: position
    private fun channelAt(position:Int):Channel?=if(moving!=null && preview?.getOrNull(position)==moving?.id)moving else data.peek(originalPosition(position))
    private fun canMove()=mode==Mode.FAVORITES && search.text.isBlank() && !refreshing && ids.size>1 && !data.hasError
    private fun beginMove(position:Int,dragging:Boolean=false):Boolean {
        if(!canMove() || moving!=null)return false
        val channel=data.peek(position) ?: return false
        hideKeyboard();notice=null;details.visibility=GONE;moving=channel;movingIndex=position;this.dragging=dragging
        preview=ids.toMutableList();indices=ids.withIndex().associate {it.value to it.index}
        moveActions.visibility=if(dragging)GONE else VISIBLE;updateStatus();if(!dragging)focusRow(position);return true
    }
    private fun moveTo(position:Int):Boolean {
        val order=preview ?: return false
        if(position !in order.indices || position==movingIndex)return false
        val from=movingIndex;order.add(position,order.removeAt(from));movingIndex=position
        adapter.notifyItemMoved(from,position);updateStatus()
        if(!mobile)focusRow(position)
        return true
    }
    private fun finishMove() {
        val order=preview?.toList() ?: return
        val selected=movingIndex;cancelMove(false,false)
        try {
            notice=if(!store.reorder(order,baseline,expectedRevision,baselineOwners))"I canali sono cambiati. Riprova il riordino." else "Ordine dei preferiti salvato."
            pendingFocus=selected;refresh()
        }catch(_:Exception){notice=store.storageError ?: "Impossibile salvare l’ordine. Riprova.";updateStatus();adapter.notifyDataSetChanged()}
    }
    private fun cancelMove(redraw:Boolean=true,cancelGesture:Boolean=true) {
        val original=moving?.id?.let {indices[it]}
        val wasDragging=dragging;dragging=false;dragHolder=null
        moving=null;movingIndex=-1;preview=null;indices=emptyMap();moveActions.visibility=GONE
        if(wasDragging && cancelGesture) {
            val now=android.os.SystemClock.uptimeMillis()
            MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0f,0f,0).let {list.dispatchTouchEvent(it);it.recycle()}
        }
        if(redraw){adapter.notifyDataSetChanged();updateStatus();original?.let(::focusRow)}
    }
    private fun focusRow(position:Int) {
        if(adapter.itemCount==0)return
        list.scrollToPosition(position.coerceIn(0,adapter.itemCount-1))
        list.findViewHolderForAdapterPosition(position)?.itemView?.findViewWithTag<View>("channel_action")?.requestFocus()
        list.post {list.findViewHolderForAdapterPosition(position)?.itemView?.findViewWithTag<View>("channel_action")?.requestFocus()}
    }
    private fun sourceName(source:String)=repository.personal.state.value.firstOrNull {it.id==source}?.name ?: if(source.startsWith("user_"))"Lista non disponibile" else "Catalogo Lume"
    private fun actions(position:Int) {
        if(moving!=null)return
        val entry=hidden.getOrNull(position).takeIf {mode==Mode.HIDDEN}
        val channel=if(mode==Mode.HIDDEN)null else channelAt(position)
        if(mode!=Mode.HIDDEN && channel==null)return
        detailPosition=position;hideKeyboard()
        val content=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(8),activity.dp(12),activity.dp(8),activity.dp(12))}
        content.addView(TvStyle.text(activity,channel?.name ?: entry?.name.orEmpty(),24f,bold=true).apply {maxLines=3;ellipsize=TextUtils.TruncateAt.END;ViewCompat.setAccessibilityHeading(this,true)})
        content.addView(TvStyle.text(activity,sourceName(channel?.sourceId ?: entry?.source.orEmpty()),14f,TvStyle.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8);bottomMargin=activity.dp(16)})
        fun action(label:String,copy:String,perform:()->Unit){content.addView(OptionsRowView(activity,mobile,label,copy){
            try{perform()}catch(_:Exception){status.text=store.storageError ?: "Impossibile salvare. Riprova."}
        },LinearLayout.LayoutParams(-1,-2))}
        if(entry!=null)action("Ripristina canale","Torna nel catalogo, nella guida e nel cambio canale."){store.restore(entry.id);refresh()}
        if(channel!=null) {
            action(if(repository.isFavorite(channel))"Rimuovi dai preferiti" else "Aggiungi ai preferiti","I numeri originali restano invariati."){repository.toggleFavorite(channel);refresh()}
            if(canMove())action("Sposta","Scegli la posizione e salva il nuovo ordine."){beginMove(position)}
            action("Nascondi canale","Lo ritrovi nella vista Nascosti. Se è in riproduzione, la diretta continua."){store.hide(channel);refresh()}
        }
        action("Torna alla lista",""){details.visibility=GONE;focusRow(position)}
        details.removeAllViews();details.addView(content);details.visibility=VISIBLE
        content.post {(0 until content.childCount).map(content::getChildAt).firstOrNull {it.isFocusable}?.requestFocus()}
    }
    private fun hideKeyboard(){(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0);search.clearFocus()}
    override fun focusSearch(focused:View?,direction:Int):View?=FocusFinder.getInstance().findNextFocus(this,focused,direction) ?: focused
    fun handleKey(event:KeyEvent):Boolean {
        if(event.action==KeyEvent.ACTION_UP && event.keyCode==moveKeyUp){moveKeyUp=null;return true}
        if(!mobile && moving!=null && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)) {
            if(event.action==KeyEvent.ACTION_DOWN){moveKeyUp=event.keyCode;when(event.keyCode){
                KeyEvent.KEYCODE_DPAD_UP->moveTo(movingIndex-1)
                KeyEvent.KEYCODE_DPAD_DOWN->moveTo(movingIndex+1)
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->if(event.repeatCount==0)finishMove()
            }}
            return true
        }
        return false
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean=handleKey(event) || super.dispatchKeyEvent(event)
    private inner class Holder(val row:LinearLayout,val action:OptionsRowView,val grip:ImageButton):RecyclerView.ViewHolder(row)
    private inner class Rows:RecyclerView.Adapter<Holder>() {
        override fun getItemCount():Int=if(refreshing)0 else if(mode==Mode.HIDDEN)hidden.size else data.size
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):Holder {
            val row=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;layoutParams=RecyclerView.LayoutParams(-1,-2)}
            lateinit var holder:Holder
            val action=OptionsRowView(activity,mobile,""){if(holder.bindingAdapterPosition>=0)actions(holder.bindingAdapterPosition)}.apply {tag="channel_action"}
            val grip=MobileStyle.icon(activity,R.drawable.ic_channel_reorder,"Sposta canale"){beginMove(holder.bindingAdapterPosition)}
            grip.setOnTouchListener {_,event->if(event.action==MotionEvent.ACTION_DOWN && canMove() && beginMove(holder.bindingAdapterPosition,true)){dragHolder=holder;touch.startDrag(holder);true}else false}
            row.addView(action,LinearLayout.LayoutParams(0,-2,1f));row.addView(grip,LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))
            holder=Holder(row,action,grip);return holder
        }
        override fun onBindViewHolder(holder:Holder,position:Int) {
            val h=if(mode==Mode.HIDDEN)hidden.getOrNull(position) else null
            val c=if(mode==Mode.HIDDEN)null else channelAt(position)
            holder.action.titleView.maxLines=2;holder.action.titleView.ellipsize=TextUtils.TruncateAt.END
            holder.action.summaryView.maxLines=2;holder.action.summaryView.ellipsize=TextUtils.TruncateAt.END
            holder.action.bind(h?.name ?: c?.let {(it.displayNumber?.let {n->"$n · "} ?: "")+it.name} ?: if(data.failedAt(originalPosition(position)))"Riprova caricamento" else "Carico canale…",
                h?.let {sourceName(it.source)} ?: c?.let {sourceName(it.sourceId)+(if(repository.isFavorite(it))" · Preferito" else "")} ?: "")
            holder.action.setOnClickListener {val p=holder.bindingAdapterPosition;if(p>=0){if(data.failedAt(originalPosition(p)))data.retry(originalPosition(p)) else actions(p)}}
            holder.grip.visibility=if(mobile && canMove())VISIBLE else GONE
            holder.grip.contentDescription="Sposta ${c?.name.orEmpty()}"
            holder.action.isSelected=moving!=null && c?.id==moving?.id
            if(!mobile)holder.action.background=TvStyle.focusBackground(activity,holder.action.isSelected)
            if(mode!=Mode.HIDDEN)data.ensure(originalPosition(position))
        }
    }
}
