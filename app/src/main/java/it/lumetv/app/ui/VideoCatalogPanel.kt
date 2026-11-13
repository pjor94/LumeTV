package it.lumetv.app.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.R
import it.lumetv.app.data.*
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle
import it.lumetv.core.Channel
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaybackTarget
import it.lumetv.core.PlaylistContentKind
import it.lumetv.core.VodKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A media library inside the app shell. The existing player still owns every playback session. */
@OptIn(FlowPreview::class)
internal class VideoCatalogPanel(private val activity:ComponentActivity,private val scope:CoroutineScope,
    private val repository:PersonalPlaylistRepository,private val artwork:VideoArtworkRepository,
    private val toggleFavorite:(Channel)->Unit,private val choose:(Channel)->Unit,private val restartCurrent:(Channel)->Unit,
    private val mobile:Boolean,private val host:CatalogPanelHost,saved:Bundle?=null) {
    private val jobs=SupervisorJob(scope.coroutineContext[Job])
    private val work=CoroutineScope(scope.coroutineContext+jobs)
    private val history=PersonalPlaybackStore.get(activity)
    private var base=readQuery(saved).copy(details=false)
    private var disposed=false
    private var binding=false
    private var searchJob:Job?=null
    private var previewJob:Job?=null
    private var filtersOpen=saved?.getBoolean("video_filters")==true
    private var compact=mobile && activity.resources.configuration.screenHeightDp<480
    private val gridMemory=linkedMapOf<String,Bundle>()
    private val screens=mutableListOf<Screen>()
    private val title=TvStyle.text(activity,"Video",if(mobile)28f else 26f,bold=true).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END;ViewCompat.setAccessibilityHeading(this,true)}
    val view=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(if(mobile)TvStyle.background else android.graphics.Color.TRANSPARENT);tag=TAG
        setPadding(activity.dp(if(mobile)20 else 0),activity.dp(if(mobile)8 else 6),activity.dp(if(mobile)20 else 0),0)}
    private val body=FrameLayout(activity)
    private val backButton=if(mobile)MobileStyle.icon(activity,R.drawable.ic_mobile_back,"Indietro"){if(!back())host.back()}.apply {tag="video_back";visibility=View.GONE} else null
    private val searchButton=button("Cerca"){search.visibility=if(search.visibility==View.VISIBLE)View.GONE else View.VISIBLE;if(search.visibility==View.VISIBLE)search.requestFocus() else hideKeyboard()}
    private val filterButton=button("Filtri"){filtersOpen=!filtersOpen;navigation()}
    private val search:EditText=CatalogPanelStyle.search(activity,"Cerca un video") {
        if(!binding) {
            base=base.copy(text=search.text.toString().trim());searchJob?.cancel()
            searchJob=work.launch {delay(280);val request=filteredQuery();if(base.text.isBlank() && request.kind==null && request.shelf==VideoShelf.ALL && base.group==null)home(reset=true) else grid(request,queryLabel(request),replace=true)}
        }
    }.apply {visibility=View.GONE;setOnEditorActionListener {_,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){hideKeyboard();true}else false}}
    private val filters=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=if(filtersOpen)View.VISIBLE else View.GONE}
    private val tabs=HorizontalScrollView(activity).apply {isHorizontalScrollBarEnabled=false}
    private val sourceButton=button("Tutte le liste"){chooseSource()}
    private val groupButton=button("Categorie"){chooseGroup()}
    private val orderButton=button("Ordine della lista"){chooseOrder()}
    private val scopeLabel=TvStyle.text(activity,"",13f,TvStyle.muted).apply {maxLines=2;visibility=View.GONE}
    private val status=TvStyle.text(activity,"",14f,TvStyle.muted).apply {visibility=View.GONE;minimumHeight=activity.dp(32);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
    private val preview=LinearLayout(activity).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE;setPadding(activity.dp(4),activity.dp(4),activity.dp(4),activity.dp(6))}
    private val previewTitle=TvStyle.text(activity,"",23f,bold=true).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
    private val previewCopy=TvStyle.text(activity,"",13f,TvStyle.muted).apply {maxLines=2;ellipsize=TextUtils.TruncateAt.END}
    private val previewImage=ImageView(activity).apply {scaleType=ImageView.ScaleType.CENTER_CROP;visibility=View.GONE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;clipToOutline=true;background=TvStyle.rounded(TvStyle.surface,activity.dp(12).toFloat())}
    private inner class Screen(val kind:String,val root:View,val query:VideoQuery=base,val label:String="Video",val item:VideoItem?=null) {
        var load:Job?=null
        var watchLoad:Job?=null
        var refresh:()->Unit={}
        var dispose:()->Unit={}
        var save:(Bundle)->Unit={}
        var focus:View?=null
        var restore:()->Unit={}
        var historyChanged:()->Unit={}
        var onHide:()->Unit={}
        var onShow:()->Unit={}
    }
    init {
        saved?.getBundle("video_grid_memory")?.let {memory->memory.keySet().take(12).forEach {key->memory.getBundle(key)?.let {gridMemory[key]=it}}}
        val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        backButton?.let {header.addView(it,LinearLayout.LayoutParams(activity.dp(48),activity.dp(48)))}
        header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(searchButton,LinearLayout.LayoutParams(-2,activity.dp(48)));header.addView(filterButton,LinearLayout.LayoutParams(-2,activity.dp(48)))
        view.addView(header,LinearLayout.LayoutParams(-1,-2))
        view.addView(search,LinearLayout.LayoutParams(-1,activity.dp(48)).apply {topMargin=activity.dp(6)})
        val controls=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        controls.addView(sourceButton);controls.addView(groupButton);controls.addView(orderButton)
        controls.addView(button("Azzera filtri"){searchJob?.cancel();base=VideoQuery(details=false);binding=true;search.setText("");binding=false;home(reset=true)})
        filters.addView(HorizontalScrollView(activity).apply {isHorizontalScrollBarEnabled=false;addView(controls)},LinearLayout.LayoutParams(-1,activity.dp(48)))
        view.addView(filters)
        val categories=LinearLayout(activity)
        listOf("Tutti" to null,"Film" to VodKind.MOVIE,"Serie" to VodKind.EPISODE).forEach {(label,kind)->categories.addView(button(label){searchJob?.cancel();filtersOpen=false;if(kind==null && base.text.isBlank() && base.group==null)home(reset=true) else grid(base.copy(kind=kind),label,replace=true)})}
        categories.addView(button("La mia lista"){searchJob?.cancel();filtersOpen=false;grid(base.copy(shelf=VideoShelf.SAVED),"La mia lista",replace=true)})
        tabs.addView(categories);view.addView(tabs,LinearLayout.LayoutParams(-1,activity.dp(48)))
        view.addView(scopeLabel);view.addView(status)
        val copy=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;addView(previewTitle);addView(previewCopy,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(6)})}
        preview.addView(copy,LinearLayout.LayoutParams(0,-2,1f));preview.addView(previewImage,LinearLayout.LayoutParams(activity.dp(112),activity.dp(52)).apply {leftMargin=activity.dp(20)})
        view.addView(preview,LinearLayout.LayoutParams(-1,activity.dp(68)))
        view.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        view.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->val next=mobile && activity.resources.configuration.screenHeightDp<480;if(compact!=next){compact=next;navigation()}}
        binding=true;search.setText(base.text);binding=false;labels()
        home(saved=saved?.getBundle("video_home"))
        work.launch {
            @Suppress("DEPRECATION") val routes=saved?.getParcelableArrayList<Bundle>("video_routes").orEmpty()
            for(route in routes) {
                if(route.getString("kind")=="grid")grid(readQuery(route),route.getString("label","Video"),saved=route)
                else if(route.getString("kind")=="detail") {
                    val item=repository.videos(VideoQuery(source=route.getString("source"),id=route.getString("id"),collapseSeries=false,limit=1)).rows.firstOrNull()
                    if(item!=null) {
                        val key=route.getString("series").orEmpty()
                        val restored=if(key.isNotEmpty())repository.videos(VideoQuery(source=item.source,series=key,limit=1)).rows.firstOrNull() ?: item else item
                        details(restored,route)
                    }
                }
            }
        }
        work.launch {repository.videoCatalogVersion.drop(1).collect {
            if(repository.videoSources.value.isEmpty()){host.close();return@collect}
            if(base.source!=null && base.source !in repository.videoSources.value){base=base.copy(source=null,group=null);home(reset=true)}
            else if(host.isOpen)refresh()
        }}
        work.launch {repository.videoFavoritesVersion.drop(1).collect {if(host.isOpen)refresh()}}
        work.launch {history.version.drop(1).debounce(350).collect {if(host.isOpen)screens.lastOrNull()?.historyChanged?.invoke()}}
        work.launch {repository.videoProgress.collect {progress->if(progress!=null && host.isOpen)message("Preparo i video… ${progress.processed} / ${progress.total}")}}
    }
    private fun button(label:String,action:()->Unit)=CatalogPanelStyle.button(activity,mobile,label,action)
    private fun hideKeyboard(){(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken,0)}
    private fun selectors()=PersonalCatalogPanels(activity,scope,host)
    private fun message(value:String,error:Boolean=false) {
        status.text=value;status.visibility=if(value.isBlank())View.GONE else View.VISIBLE
        status.isClickable=error;status.isFocusable=error;status.setOnClickListener {refresh()}
    }
    private fun labels() {
        sourceButton.text=repository.state.value.firstOrNull {it.id==base.source}?.name ?: "Tutte le liste";sourceButton.contentDescription="Lista dei video: ${sourceButton.text}"
        groupButton.text=base.group ?: "Categorie";groupButton.contentDescription="Categoria: ${groupButton.text}"
        orderButton.text=when(base.order){VideoOrder.PLAYLIST->"Ordine della lista";VideoOrder.TITLE->"Titolo A–Z";VideoOrder.ADDED->"Aggiunti di recente"}
        scopeLabel.text=listOfNotNull(base.source?.let {sourceButton.text.toString()},base.group,base.text.takeIf(String::isNotBlank)?.let {"Ricerca: $it"}).joinToString(" · ")
        scopeLabel.visibility=if(scopeLabel.text.isBlank())View.GONE else View.VISIBLE
    }
    private fun push(screen:Screen) {
        screens.lastOrNull()?.let {it.focus=it.root.findFocus();it.onHide();it.load?.cancel();it.watchLoad?.cancel();it.root.visibility=View.GONE}
        screens+=screen;body.addView(screen.root,FrameLayout.LayoutParams(-1,-1));navigation()
    }
    private fun removeTop(){val old=screens.removeAt(screens.lastIndex)
        if(old.kind=="grid"){gridMemory[old.query.toString()]=Bundle().also(old.save);while(gridMemory.size>12)gridMemory.remove(gridMemory.keys.first())}
        old.load?.cancel();old.watchLoad?.cancel();old.onHide();old.dispose();body.removeView(old.root)}
    private fun navigation() {
        val current=screens.lastOrNull() ?: return
        backButton?.visibility=if(screens.size>1)View.VISIBLE else View.GONE
        title.text=if(current.kind=="home")"Video" else current.label
        val detail=current.kind=="detail" || current.query.series!=null
        searchButton.visibility=if(detail)View.GONE else View.VISIBLE;filterButton.visibility=if(detail)View.GONE else View.VISIBLE
        tabs.visibility=if(detail || compact && !filtersOpen)View.GONE else View.VISIBLE
        filters.visibility=if(!detail && filtersOpen)View.VISIBLE else View.GONE
        if(detail)search.visibility=View.GONE
        preview.visibility=if(!mobile && current.kind=="home")View.VISIBLE else View.GONE
        labels();message("");ViewCompat.setAccessibilityPaneTitle(view,title.text)
    }
    fun back():Boolean {
        hideKeyboard()
        if(search.visibility==View.VISIBLE){search.visibility=View.GONE;searchButton.requestFocus();return true}
        if(screens.size<=1)return false
        removeTop();val previous=screens.last();previous.root.visibility=View.VISIBLE;navigation();previous.restore();previous.onShow();previous.refresh()
        previous.root.post {previous.focus?.takeIf {it.isShown}?.requestFocus()};return true
    }
    fun refresh(){if(!disposed)screens.lastOrNull()?.let {it.onShow();it.refresh()}}
    private fun home(reset:Boolean=false,saved:Bundle?=null) {
        if(disposed)return
        while(screens.size>1)removeTop()
        if(screens.isNotEmpty() && !reset){screens.first().onShow();screens.first().refresh();return}
        while(screens.isNotEmpty())removeTop()
        val list=RecyclerView(activity).apply {tag="video_home";layoutManager=LinearLayoutManager(activity);itemAnimator=null;clipChildren=false;clipToPadding=false;setPadding(0,0,0,activity.dp(16))}
        var focusedKey=saved?.getString("focus_key")
        lateinit var screen:Screen
        lateinit var adapter:VideoShelfAdapter
        val pending=mutableMapOf<String,Job>()
        var loadedKey:Any?=null;var restored=saved==null;var seenHistory=history.version.value
        fun key():Any=listOf(repository.videoCacheKey(base),repository.videoFavoritesVersion.value)
        fun fetch(row:VideoShelfRow) {
            if(screen!==screens.lastOrNull() || pending[row.key]?.isActive==true)return
            pending[row.key]=work.launch {
                yield()
                try {
                    val page=repository.videoPreview(row.query)
                    if(screen===screens.lastOrNull()) {
                        val next=adapter.rows.toMutableList();val index=next.indexOfFirst {it.key==row.key}
                        if(index>=0){if(page.rows.isEmpty())next.removeAt(index) else next[index]=row.copy(page=page,loading=false);adapter.submit(next)}
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){/* Other collections remain usable; reopening retries this shelf. */}
            }
        }
        adapter=VideoShelfAdapter(activity,mobile,artwork,{item->restored=true;focusedKey=item.key;details(item)},{row->restored=true;grid(row.query,row.title)},{item->focusedKey=item.key;preview(item)},::fetch,interact={restored=true})
        if(!mobile)adapter.restoreFocusKey=focusedKey
        adapter.restore(saved);list.adapter=adapter
        screen=Screen("home",list);push(screen)
        fun restoreScroll(complete:Boolean=false) {
            if(restored || screen!==screens.lastOrNull())return
            val manager=list.layoutManager as LinearLayoutManager
            val key=saved?.getString("anchor_key")
            val position=adapter.rows.indexOfFirst {it.key==key}
            if(position>=0 || complete && saved?.containsKey("anchor_position")==true && adapter.rows.isNotEmpty()) {
                restored=true;manager.scrollToPositionWithOffset(if(position>=0)position else saved!!.getInt("anchor_position").coerceIn(0,adapter.rows.lastIndex),saved?.getInt("anchor_top") ?: 0)
            } else if(complete) {
                @Suppress("DEPRECATION") val state=saved?.getParcelable<android.os.Parcelable>("scroll")
                restored=true;manager.onRestoreInstanceState(state)
            }
        }
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){
            override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){if(dy!=0)restored=true}
            override fun onScrollStateChanged(view:RecyclerView,state:Int){if(state==RecyclerView.SCROLL_STATE_DRAGGING)restored=true}
        })
        list.addOnItemTouchListener(object:RecyclerView.SimpleOnItemTouchListener(){
            override fun onInterceptTouchEvent(view:RecyclerView,event:android.view.MotionEvent):Boolean {
                if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN)restored=true
                return false
            }
        })
        screen.save={out->
            val manager=list.layoutManager as LinearLayoutManager;val position=manager.findFirstVisibleItemPosition().coerceAtLeast(0)
            out.putParcelable("scroll",manager.onSaveInstanceState());out.putString("focus_key",focusedKey)
            out.putString("anchor_key",adapter.rows.getOrNull(position)?.key);out.putInt("anchor_position",position)
            out.putInt("anchor_top",manager.findViewByPosition(position)?.let {manager.getDecoratedTop(it)-list.paddingTop} ?: 0)
            adapter.save(list,out)
        }
        screen.restore={restoreScroll();if(!mobile){adapter.restoreFocusKey=focusedKey.takeUnless {screen.focus?.isShown==true};adapter.restoreFocusShelf=adapter.focusedShelf}}
        screen.onHide={pending.values.forEach(Job::cancel);adapter.pause(list)}
        screen.onShow={adapter.resume(list)}
        screen.dispose={pending.values.forEach(Job::cancel);list.adapter=null}
        screen.historyChanged={
            screen.watchLoad?.cancel();screen.watchLoad=work.launch {
                try {
                    val query=base.copy(shelf=VideoShelf.CONTINUE,collapseSeries=false)
                    val page=repository.videoPreview(query)
                    if(screen===screens.lastOrNull()) {
                        val rows=adapter.rows.map {row->row.copy(page=repository.videoPositions(row.page))}.toMutableList();val index=rows.indexOfFirst {it.key=="continue"}
                        val row=VideoShelfRow("continue","Continua a guardare",query,page,true)
                        when {page.rows.isNotEmpty() && index>=0->rows[index]=row;page.rows.isNotEmpty()->rows.add(0,row);index>=0->rows.removeAt(index)}
                        adapter.submit(rows);seenHistory=history.version.value
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
            }
        }
        screen.refresh=refresh@ {
            val epoch=key()
            if(loadedKey==epoch) {if(seenHistory!=history.version.value)screen.historyChanged();screen.onShow();return@refresh}
            screen.load?.cancel();screen.load=work.launch {
                try {
                    if(adapter.rows.isEmpty())message("Carico le raccolte…")
                    val rows=adapter.rows.toMutableList()
                    fun publish() {
                        val order=listOf("continue","saved","movies","series","all")
                        adapter.submit(rows.sortedBy {order.indexOf(it.key).takeIf {i->i>=0} ?: 5});restoreScroll()
                    }
                    suspend fun add(id:String,label:String,q:VideoQuery,wide:Boolean=false) {
                        val cached=repository.cachedVideos(q,true)
                        if(cached!=null && cached.rows.isNotEmpty()) {rows.removeAll {it.key==id};rows+=VideoShelfRow(id,label,q,cached,wide);publish();message("")}
                        val page=repository.videoPreview(q)
                        rows.removeAll {it.key==id}
                        if(page.rows.isNotEmpty())rows+=VideoShelfRow(id,label,q,page,wide)
                        if(screen===screens.lastOrNull()){publish();if(rows.isNotEmpty())message("")}
                    }
                    add("continue","Continua a guardare",base.copy(shelf=VideoShelf.CONTINUE,collapseSeries=false),true)
                    add("saved","La mia lista",base.copy(shelf=VideoShelf.SAVED))
                    add("movies","Film",base.copy(kind=VodKind.MOVIE))
                    add("series","Serie",base.copy(kind=VodKind.EPISODE))
                    add("all","Dalle tue liste",base)
                    rows.removeAll {it.key.startsWith("group:")}
                    if(base.group==null)for(group in repository.videoGroups(base)) {
                        val query=base.copy(group=group)
                        val cached=repository.cachedVideos(query,true)
                        rows+=VideoShelfRow("group:$group",group,query,cached ?: VideoPage(emptyList(),0,false),loading=cached==null)
                    }
                    if(!disposed && screen===screens.lastOrNull() && key()==epoch) {
                        publish();loadedKey=epoch;seenHistory=history.version.value
                        message(if(rows.isEmpty())"Nessun video disponibile con questi filtri. Apri Filtri per modificare la selezione." else "")
                        restoreScroll(complete=true)
                        if(focusedKey==null)rows.firstOrNull()?.page?.rows?.firstOrNull()?.let(::preview)
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){if(screen===screens.lastOrNull())message("Impossibile aprire i video. Premi qui per riprovare.",true)}
            }
        }
        screen.refresh()
    }
    private fun grid(input:VideoQuery,label:String,replace:Boolean=false,saved:Bundle?=null,seriesItem:VideoItem?=null) {
        val query=input.copy(details=false)
        if(disposed)return
        if(replace)while(screens.size>1)removeTop()
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="video_grid"}
        val count=TvStyle.text(activity,"Carico i video…",13f,TvStyle.muted)
        root.addView(count,LinearLayout.LayoutParams(-1,activity.dp(30)))
        val actualSeries=seriesItem ?: query.series?.let {VideoItem("",query.source.orEmpty(),label,VodKind.EPISODE,series=it,seriesTitle=label)}
        if(actualSeries!=null)root.addView(button(if(query.season==null)"Tutte le stagioni" else "Stagione ${query.season}") {
            selectors().paged("Scegli la stagione",load={q,o->val(page,total)=repository.videoSeasons(actualSeries,q,(o-if(q.isBlank())1 else 0).coerceAtLeast(0));((if(o==0 && q.isBlank())listOf(-1) else emptyList())+page) to total+if(q.isBlank())1 else 0},label={if(it==-1)"Tutte le stagioni" else if(it==0)"Altri episodi" else "Stagione $it"}) {season->
                removeTop();screens.last().root.visibility=View.VISIBLE;grid(query.copy(season=season.takeIf {it>=0}),label,seriesItem=actualSeries)
            }
        })
        val list=RecyclerView(activity).apply {tag="video_grid_results";layoutManager=GridLayoutManager(activity,if(mobile)3 else 6);itemAnimator=null;clipChildren=false;clipToPadding=false;isFocusable=true}
        val restored=saved ?: gridMemory[query.toString()]
        var focusedKey=restored?.getString("focus_key")
        var rotationKey=if(restored?.containsKey("rotation_anchor_set")==true)restored.getString("rotation_key") else focusedKey
        var resizing=false;var viewportWidth=0;var viewportHeight=0;var resizeEpoch=0
        val posters=VideoPosterAdapter(activity,mobile,artwork,if(mobile)106 else 128,false,
            choose={item->focusedKey=item.key;rotationKey=item.key;details(item)},
            focus={item->if(!resizing){focusedKey=item.key;rotationKey=item.key};preview(item)})
        if(!mobile)posters.restoreFocusKey=focusedKey
        val screen=Screen("grid",root,query,label,actualSeries)
        var position=restored?.getInt("position") ?: 0;var top=restored?.getInt("top") ?: 0
        var resizeAnchor=position
        val catalog=ContinuousCatalog<VideoItem>(work,50,initialDelay=0) {change->list.post {
            if(screen in screens && !disposed) {
                if(change.sizeChanged)posters.notifyDataSetChanged() else if(change.start<posters.itemCount)posters.notifyItemRangeChanged(change.start,change.count.coerceAtMost(posters.itemCount-change.start))
                if(change.sizeChanged && posters.itemCount>0){
                    val target=posters.catalog?.indexOf {it.key==rotationKey} ?: position.coerceAtMost(posters.itemCount-1)
                    (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(target,if(target==position)top else 0);position=0;top=0
                }
                // Range updates retain their positions. Reapplying an old layout state here can
                // override a newer scroll or rotation anchor while a prefetched block completes.
                count.text=if(posters.itemCount>0)"${posters.itemCount} ${if(query.series!=null)if(posters.itemCount==1)"episodio" else "episodi" else if(posters.itemCount==1)"titolo" else "titoli"}" else if(catalogError(list))"Impossibile caricare i video. Premi qui per riprovare." else "Nessun video con questi filtri."
                count.isFocusable=posters.itemCount==0 && catalogError(list);count.isClickable=count.isFocusable
                count.minimumHeight=activity.dp(if(count.isFocusable)48 else 30)
            }
        }}
        posters.catalog=catalog;list.adapter=posters
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(v:RecyclerView,dx:Int,dy:Int){
            val manager=list.layoutManager as GridLayoutManager
            val first=manager.findFirstVisibleItemPosition().coerceAtLeast(0);val last=manager.findLastVisibleItemPosition().coerceAtLeast(first)
            // Keep the pre-rotation title while Android remeasures the old card shape.
            if(!resizing && list.width==viewportWidth && list.height==viewportHeight && posters.wide==(mobile && activity.resources.configuration.screenHeightDp<480))
                resizeAnchor=catalog.indexOf {it.key==rotationKey}?.takeIf {it in first..last} ?: first
            catalog.viewport(first,last)
        }
            override fun onScrollStateChanged(view:RecyclerView,state:Int){if(state==RecyclerView.SCROLL_STATE_DRAGGING){rotationKey=null;resizeEpoch++;resizing=false}}
        })
        list.addOnLayoutChangeListener {_,left,topEdge,right,bottom,oldLeft,oldTop,oldRight,oldBottom->
            val short=mobile && activity.resources.configuration.screenHeightDp<480
            if((right-left!=oldRight-oldLeft || bottom-topEdge!=oldBottom-oldTop || posters.wide!=short) && right>left) {
                val manager=list.layoutManager as GridLayoutManager
                val anchor=catalog.indexOf {it.key==rotationKey} ?: resizeAnchor
                val epoch=++resizeEpoch;resizing=true;viewportWidth=right-left;viewportHeight=bottom-topEdge
                val available=(right-left)/activity.resources.displayMetrics.density
                val minimum=if(short)156 else if(mobile)if(activity.resources.configuration.fontScale>1.2f)138 else 112 else if(activity.resources.configuration.fontScale>1.2f)156 else 136
                val columns=(available/minimum).toInt().coerceIn(2,8);val width=(available/columns).toInt()-14
                manager.spanCount=columns;posters.width=width;posters.wide=short;posters.notifyDataSetChanged()
                list.post {
                    if(epoch==resizeEpoch && screen in screens && list.isShown)manager.scrollToPositionWithOffset(anchor,0)
                    list.postOnAnimation {if(epoch==resizeEpoch)resizing=false}
                }
            }
        }
        count.setOnClickListener {catalog.retry()};count.isFocusable=false;count.isClickable=false
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f));push(screen)
        if(!mobile && search.visibility!=View.VISIBLE && host.isOpen)list.requestFocus()
        screen.onHide={catalog.pause();posters.pause(list)}
        screen.onShow={catalog.resume();posters.resume(list)}
        screen.dispose={catalog.close();list.adapter=null}
        screen.save={out->val manager=list.layoutManager as GridLayoutManager;val p=manager.findFirstVisibleItemPosition().coerceAtLeast(0);out.putInt("position",p);out.putString("focus_key",focusedKey);out.putBoolean("rotation_anchor_set",true);out.putString("rotation_key",rotationKey);out.putInt("top",manager.findViewByPosition(p)?.let {manager.getDecoratedTop(it)-list.paddingTop} ?: 0)}
        screen.restore={if(!mobile)posters.restoreFocusKey=focusedKey}
        var decoration:Any?=null
        screen.refresh={
            if(catalog.size>0){val manager=list.layoutManager as GridLayoutManager;position=manager.findFirstVisibleItemPosition().coerceAtLeast(0);top=manager.findViewByPosition(position)?.let {manager.getDecoratedTop(it)-list.paddingTop} ?: 0}
            val seed=repository.cachedVideos(query.copy(offset=position/50*50,limit=50))?.let {it.rows to it.total}
            val changed=catalog.bind(query to repository.videoCacheKey(query),position,seed){offset->repository.videos(query.copy(offset=offset,limit=50)).let {it.rows to it.total}}
            val nextDecoration=repository.videoFavoritesVersion.value to history.version.value
            if(changed)posters.notifyDataSetChanged() else {
                catalog.resume()
                if(decoration!=nextDecoration){screen.watchLoad?.cancel();screen.watchLoad=work.launch {catalog.refreshLoaded()}}
            }
            decoration=nextDecoration
        }
        screen.historyChanged={screen.refresh()}
        screen.refresh();message("")
    }
    private fun catalogError(list:RecyclerView)=(list.adapter as? VideoPosterAdapter)?.catalog?.hasError==true
    private fun details(item:VideoItem,saved:Bundle?=null) {
        if(disposed)return
        val scroll=ScrollView(activity).apply {tag="video_detail";isFillViewport=true}
        val content=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(activity.dp(4),activity.dp(12),activity.dp(4),activity.dp(20))}
        scroll.addView(content,FrameLayout.LayoutParams(-1,-2))
        val screen=Screen("detail",scroll,label=if(item.isSeries)"Serie" else "Dettagli",item=item);push(screen)
        val query=if(item.isSeries)VideoQuery(source=item.source,series=item.series,limit=1) else VideoQuery(source=item.source,id=item.id,collapseSeries=false,limit=1)
        var value=repository.cachedVideos(query)?.rows?.firstOrNull() ?: item
        var target=value;var targetReady=!item.isSeries;var stamp:Any?=null;var actionStamp:Any?=null
        var imageJob:Job?=null;var imageBinding="";var firstFocus=true
        var scrollPosition=saved?.getInt("detail_scroll") ?: 0;var scrollRestored=false
        val row=LinearLayout(activity).apply {gravity=Gravity.TOP;orientation=LinearLayout.HORIZONTAL}
        val poster=FrameLayout(activity).apply {background=TvStyle.rounded(TvStyle.surface,activity.dp(12).toFloat());clipToOutline=true}
        val image=ImageView(activity).apply {scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        poster.addView(image,FrameLayout.LayoutParams(-1,-1));val width=if(mobile)108 else 170
        row.addView(poster,LinearLayout.LayoutParams(activity.dp(width),activity.dp(width*3/2)).apply {rightMargin=activity.dp(if(mobile)16 else 28)})
        val copy=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val name=TvStyle.text(activity,"",if(mobile)24f else 31f,bold=true).apply {maxLines=5;ViewCompat.setAccessibilityHeading(this,true)}
        val meta=TvStyle.text(activity,"",14f,TvStyle.muted)
        val source=TvStyle.text(activity,"",13f,TvStyle.muted)
        copy.addView(name);copy.addView(meta,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(10)})
        copy.addView(source,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(10)})
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f));content.addView(row)
        val plot=TvStyle.text(activity,"",16f).apply {maxLines=20;visibility=View.GONE}
        content.addView(plot,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(22)})
        val actions=LinearLayout(activity).apply {orientation=if(mobile)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL}
        val actionArea:View=if(mobile)actions else HorizontalScrollView(activity).apply {isHorizontalScrollBarEnabled=false;addView(actions)}
        content.addView(actionArea,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(20)})
        val resume=TvStyle.text(activity,"",14f,TvStyle.muted).apply {visibility=View.GONE}
        content.addView(resume,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(10)})
        fun bind() {
            if(screen!==screens.lastOrNull())return
            name.text=value.displayTitle;meta.text=metadata(value)
            source.text=repository.state.value.firstOrNull {it.id==value.source}?.name.orEmpty()
            plot.text=value.description;plot.visibility=if(value.description.isBlank())View.GONE else View.VISIBLE
            val binding=artwork.bindingKey(value,false,activity.dp(width*3/2))
            if(binding!=imageBinding || imageJob==null && image.drawable==null) {
                imageJob?.cancel();if(binding!=imageBinding)image.setImageDrawable(null);imageBinding=binding
                imageJob=artwork.request(value,targetPx=activity.dp(width*3/2)){bitmap->if(screen in screens && bitmap!=null)image.setImageBitmap(bitmap)}
            }
            val nextAction=listOf(value.saved,targetReady,target.resume>0,value.isSeries)
            if(actionStamp!=nextAction) {
                val hadFocus=actions.hasFocus();actions.removeAllViews()
                val resumeLabel=if(target.resume>0)"Riprendi" else "Guarda"
                val play=button(resumeLabel){play(target,false)}.apply {tag="video_play";contentDescription="$resumeLabel ${value.displayTitle}";isEnabled=targetReady}
                actions.addView(play,LinearLayout.LayoutParams(if(mobile)-1 else -2,activity.dp(48)))
                if(target.resume>0)actions.addView(button("Dall’inizio"){play(target,true)}.apply {isEnabled=targetReady},LinearLayout.LayoutParams(if(mobile)-1 else -2,activity.dp(48)))
                actions.addView(button(if(value.saved)"Rimuovi dalla mia lista" else "Aggiungi alla mia lista") {
                    work.launch {try {if(value.isSeries)repository.toggleSavedSeries(value) else repository.channel(value.id,value.source)?.let(toggleFavorite);screen.refresh()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message("Impossibile aggiornare La mia lista. Riprova.",true)}}
                },LinearLayout.LayoutParams(if(mobile)-1 else -2,activity.dp(48)))
                if(value.isSeries)actions.addView(button("Stagioni ed episodi"){grid(VideoQuery(source=value.source,series=value.series,collapseSeries=false),"Episodi",seriesItem=value)},LinearLayout.LayoutParams(if(mobile)-1 else -2,activity.dp(48)))
                for(index in 0 until actions.childCount-1) {
                    val spacing=actions.getChildAt(index).layoutParams as LinearLayout.LayoutParams
                    if(mobile)spacing.bottomMargin=activity.dp(4) else spacing.rightMargin=activity.dp(12)
                }
                actionStamp=nextAction
                if(!mobile && targetReady && (hadFocus || firstFocus))play.post {if(screen===screens.lastOrNull() && host.isOpen){play.requestFocus();firstFocus=false}}
            }
            resume.text=if(target.resume>0)"Riprendi da ${videoTime(target.resume)}"+if(value.isSeries)" · ${target.title}" else "" else ""
            resume.visibility=if(resume.text.isBlank())View.GONE else View.VISIBLE
            if(!scrollRestored && value.detailsLoaded)scroll.post {scroll.scrollTo(0,scrollPosition);scrollRestored=true}
        }
        screen.onHide={imageJob?.cancel();imageJob=null}
        screen.onShow={bind()}
        screen.dispose={imageJob?.cancel()};screen.save={it.putInt("detail_scroll",scroll.scrollY)}
        screen.refresh=refresh@ {
            val nextStamp=listOf(repository.videoCacheKey(query),repository.videoFavoritesVersion.value,history.version.value)
            if(stamp==nextStamp){bind();return@refresh}
            val cached=repository.cachedVideos(query)?.rows?.firstOrNull()
            if(cached!=null){value=cached;if(!value.isSeries)target=value;bind()}
            screen.load?.cancel();screen.load=work.launch {
                try {
                    val current=cached ?: repository.videoDetails(item) ?: run {content.visibility=View.GONE;message("Il titolo non è più disponibile. Torna ai video per aggiornare la selezione.");return@launch}
                    val selected=if(current.isSeries)repository.videos(VideoQuery(source=current.source,series=current.series,shelf=VideoShelf.CONTINUE,collapseSeries=false,limit=1,details=false)).rows.firstOrNull()
                        ?: repository.videos(VideoQuery(source=current.source,series=current.series,collapseSeries=false,limit=1,details=false)).rows.firstOrNull() ?: current else current
                    if(screen===screens.lastOrNull()) {
                        value=current;target=selected;targetReady=true;stamp=nextStamp;content.visibility=View.VISIBLE;message("");bind()
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message("Impossibile aprire il titolo. Premi qui per riprovare.",true)}
            }
        }
        screen.historyChanged={screen.refresh()}
        bind();screen.refresh()
    }
    private fun metadata(item:VideoItem)=listOfNotNull(
        if(item.isSeries)"${item.episodes} ${if(item.episodes==1)"episodio" else "episodi"}" else item.year?.toString(),
        (item.duration/60_000).takeIf {it>0 && !item.isSeries}?.let {"$it min"},item.genres.take(3).joinToString(" · ").takeIf(String::isNotBlank),
        if(!item.isSeries && item.season!=null && item.episode!=null)"Stagione ${item.season} · Episodio ${item.episode}" else null).joinToString(" · ")
    private fun play(item:VideoItem,start:Boolean) {
        work.launch {try {
            val channel=repository.channel(item.id,item.source) ?: run {message("Il video non è più disponibile.");return@launch}
            if(start)withContext(Dispatchers.IO){history.write(item.source,PlaybackTarget(item.id,PlaybackMode.VOD),0,item.watchedDuration.takeIf {it>0} ?: item.duration)}
            hideKeyboard();screens.lastOrNull()?.onHide?.invoke();host.park();if(start)restartCurrent(channel);choose(channel)
        }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message("Impossibile aprire il video. Riprova.",true)}}
    }
    private var previewKey=""
    private fun preview(item:VideoItem) {
        if(mobile || screens.lastOrNull()?.kind!="home")return
        previewTitle.text=item.displayTitle;previewCopy.text=item.description.takeIf(String::isNotBlank) ?: metadata(item)
        val key=artwork.bindingKey(item,true,activity.dp(112))
        if(key==previewKey)return
        previewKey=key;previewJob?.cancel();previewImage.setImageDrawable(null);previewImage.visibility=View.GONE
        previewJob=work.launch {
            delay(240)
            if(!item.detailsLoaded)repository.videoDetails(item)?.takeIf {previewKey==key}?.let {full->previewCopy.text=full.description.takeIf(String::isNotBlank) ?: metadata(full)}
            artwork.request(item,true,activity.dp(112)){bitmap->if(bitmap!=null && previewKey==key && screens.lastOrNull()?.kind=="home"){previewImage.setImageBitmap(bitmap);previewImage.visibility=View.VISIBLE}}.join()
        }
    }
    private fun filteredQuery()=(screens.lastOrNull()?.takeIf {it.kind=="grid"}?.query ?: base).copy(source=base.source,group=base.group,text=base.text,order=base.order)
    private fun queryLabel(query:VideoQuery)=when {query.text.isNotBlank()->"Risultati";query.group!=null->query.group;query.shelf==VideoShelf.SAVED->"La mia lista";query.shelf==VideoShelf.CONTINUE->"Continua a guardare";query.kind==VodKind.MOVIE->"Film";query.kind==VodKind.EPISODE->"Serie";else->"Tutti i video"}
    private fun chooseSource() {
        selectors().paged("Lista dei video",load={text,offset->val rows=listOf("")+repository.state.value.filter {it.id in repository.videoSources.value}.map {it.id};val filtered=rows.filter {id->(repository.state.value.firstOrNull {it.id==id}?.name ?: "Tutte le liste").contains(text,true)};filtered.drop(offset).take(50) to filtered.size},label={id->repository.state.value.firstOrNull {it.id==id}?.name ?: "Tutte le liste"}) {
            searchJob?.cancel();filtersOpen=false;val wasGrid=screens.lastOrNull()?.kind=="grid";base=base.copy(source=it.ifEmpty {null},group=null);if(wasGrid){val query=filteredQuery();grid(query,queryLabel(query),replace=true)}else home(reset=true)
        }
    }
    private fun chooseGroup() {
        selectors().category(repository,PersonalCatalogQuery(source=base.source,content=PlaylistContentKind.VOD)){value->searchJob?.cancel();filtersOpen=false;base=base.copy(group=value.takeUnless {it=="Tutte le categorie"});val query=filteredQuery();if(base.group==null && query.kind==null && query.shelf==VideoShelf.ALL && query.text.isBlank())home(reset=true) else grid(query,queryLabel(query),replace=true);labels()}
    }
    private fun chooseOrder() {
        val choices=listOf(VideoOrder.PLAYLIST to "Ordine della lista",VideoOrder.TITLE to "Titolo A–Z",VideoOrder.ADDED to "Aggiunti di recente")
        selectors().paged("Ordina i video",load={text,offset->val rows=choices.filter {it.second.contains(text,true)};rows.drop(offset).take(50) to rows.size},label={it.second}) {searchJob?.cancel();filtersOpen=false;base=base.copy(order=it.first);val query=filteredQuery();grid(query,queryLabel(query),replace=true);labels()}
    }
    fun save(out:Bundle) {
        writeQuery(out,base);out.putBoolean("video_filters",filtersOpen)
        out.putBundle("video_grid_memory",Bundle().apply {gridMemory.forEach {(key,value)->putBundle(key,value)}})
        screens.firstOrNull()?.let {screen->out.putBundle("video_home",Bundle().also(screen.save))}
        out.putParcelableArrayList("video_routes",ArrayList(screens.drop(1).map {screen->Bundle().apply {
            putString("kind",screen.kind);putString("label",screen.label);writeQuery(this,screen.query);screen.save(this)
            screen.item?.let {putString("source",it.source);putString("id",it.id);putString("series",it.series)}
        }}))
    }
    fun close(){disposed=true;while(screens.isNotEmpty())removeTop();jobs.cancel();artwork.close()}
    companion object {
        const val TAG="lume_video_catalog"
        private fun readQuery(saved:Bundle?)=VideoQuery(source=saved?.getString("video_source"),group=saved?.getString("video_group"),text=saved?.getString("video_query").orEmpty(),
            kind=saved?.getString("video_kind")?.let {runCatching {VodKind.valueOf(it)}.getOrNull()},shelf=saved?.getString("video_shelf")?.let {runCatching {VideoShelf.valueOf(it)}.getOrNull()} ?: VideoShelf.ALL,
            order=saved?.getString("video_order")?.let {runCatching {VideoOrder.valueOf(it)}.getOrNull()} ?: VideoOrder.PLAYLIST,series=saved?.getString("video_series"),season=saved?.getInt("video_season")?.takeIf {saved.containsKey("video_season")},collapseSeries=saved?.getBoolean("video_collapse",true) ?: true)
        private fun writeQuery(out:Bundle,q:VideoQuery) {out.putString("video_source",q.source);out.putString("video_group",q.group);out.putString("video_query",q.text);out.putString("video_kind",q.kind?.name);out.putString("video_shelf",q.shelf.name);out.putString("video_order",q.order.name);out.putString("video_series",q.series);q.season?.let {out.putInt("video_season",it)};out.putBoolean("video_collapse",q.collapseSeries)}
    }
}
