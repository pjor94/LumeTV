package it.lumetv.app.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
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
import it.lumetv.core.VodMediaType
import it.lumetv.core.VodTitleCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A media library inside the app shell. The existing player still owns every playback session. */
@OptIn(FlowPreview::class)
internal class VideoCatalogPanel(private val activity:ComponentActivity,private val scope:CoroutineScope,
    private val repository:PersonalPlaylistRepository,private val artwork:VideoArtworkRepository,
    private val toggleFavorite:(Channel)->Unit,private val choose:(Channel)->Unit,
    private val mobile:Boolean,private val host:CatalogPanelHost,saved:Bundle?=null,private val watchOnTv:((Channel)->Unit)?=null,
    private val backdrop:TvVideoBackdrop?=null,private val beforeTrailer:()->Unit={}) {
    private val jobs=SupervisorJob(scope.coroutineContext[Job])
    private val work=CoroutineScope(scope.coroutineContext+jobs)
    private val history=PersonalPlaybackStore.get(activity)
    private val online=repository.videoMetadata
    private var base=readQuery(saved).copy(details=false)
    private val arrivalsSource = MutableStateFlow(base.source)
    private var hasArrivals = false
    private var disposed=false
    private var binding=false
    private data class MetadataChoice(val candidate:VodTitleCandidate?)
    private var searchJob:Job?=null
    private var filtersOpen=saved?.getBoolean("video_filters")==true
    private var compact=mobile && activity.resources.configuration.screenHeightDp<480
    private val gridMemory=linkedMapOf<String,Bundle>()
    private val screens=mutableListOf<Screen>()
    private val title=TvStyle.text(activity,"Video",if(mobile)28f else 26f,bold=true).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END;ViewCompat.setAccessibilityHeading(this,true)}
    val view=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(if(mobile)TvStyle.background else android.graphics.Color.TRANSPARENT);tag=TAG
        clipChildren=false;clipToPadding=false
        setPadding(activity.dp(if(mobile)20 else 0),activity.dp(if(mobile)8 else 0),activity.dp(if(mobile)20 else 0),0)}
    private val body=FrameLayout(activity)
    private val backButton=(if(mobile)MobileStyle.icon(activity,R.drawable.ic_mobile_back,"Indietro"){if(!back())host.back()}
        else TvVideoStyle.action(activity,"Video",R.drawable.ic_mobile_back,quiet=true){if(!back())host.back()}).apply {
        tag="video_back";visibility=View.GONE
        if(!mobile)contentDescription="Torna ai video"
    }
    private val searchButton=if(mobile)button("Cerca",::toggleSearch) else TvVideoStyle.action(activity,"Cerca",R.drawable.ic_mobile_search,choose=::toggleSearch)
    private val filterButton=button("Filtri"){filtersOpen=!filtersOpen;navigation()}
    private val search:EditText=CatalogPanelStyle.search(activity,"Cerca un video") {
        if(!binding) {
            base=base.copy(text=search.text.toString().trim());searchJob?.cancel()
            searchJob=work.launch {delay(280);val request=filteredQuery();if(base.text.isBlank() && request.kind==null && request.shelf==VideoShelf.ALL && base.group==null)home(reset=true) else grid(request,queryLabel(request),replace=true)}
        }
    }.apply {visibility=View.GONE;setOnEditorActionListener {_,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){hideKeyboard();true}else false}}
    private val filters=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=if(filtersOpen)View.VISIBLE else View.GONE}
    private val tabs=HorizontalScrollView(activity).apply {isHorizontalScrollBarEnabled=false}
    private val categoryButtons=linkedMapOf<String,TextView>()
    private val sourceButton=button("Tutte le liste"){chooseSource()}
    private val groupButton=button("Categorie"){chooseGroup()}
    private val orderButton=button("Ordine della lista"){chooseOrder()}
    private val scopeLabel=TvStyle.text(activity,"",13f,TvStyle.muted).apply {maxLines=2;visibility=View.GONE}
    private val status=TvStyle.text(activity,"",14f,TvStyle.muted).apply {visibility=View.GONE;minimumHeight=activity.dp(32);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
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
        var interceptBack:()->Boolean={false}
    }
    init {
        saved?.getBundle("video_grid_memory")?.let {memory->memory.keySet().take(12).forEach {key->memory.getBundle(key)?.let {gridMemory[key]=it}}}
        val header=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
        header.addView(backButton,LinearLayout.LayoutParams(if(mobile)activity.dp(48) else -2,activity.dp(if(mobile)48 else 42)))
        if(mobile)header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        else header.addView(tabs,LinearLayout.LayoutParams(0,activity.dp(44),1f))
        header.addView(searchButton,LinearLayout.LayoutParams(-2,activity.dp(if(mobile)48 else 42)))
        header.addView(filterButton,LinearLayout.LayoutParams(-2,activity.dp(if(mobile)48 else 42)))
        view.addView(header,LinearLayout.LayoutParams(-1,-2))
        if(!mobile)view.addView(title,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(6);bottomMargin=activity.dp(5)})
        view.addView(search,LinearLayout.LayoutParams(-1,activity.dp(48)).apply {topMargin=activity.dp(6)})
        val controls=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        controls.addView(sourceButton);controls.addView(groupButton);controls.addView(orderButton)
        controls.addView(button("Azzera filtri"){searchJob?.cancel();base=VideoQuery(details=false);binding=true;search.setText("");binding=false;home(reset=true)})
        filters.addView(HorizontalScrollView(activity).apply {isHorizontalScrollBarEnabled=false;addView(controls)},LinearLayout.LayoutParams(-1,activity.dp(48)))
        view.addView(filters)
        val categories=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
        fun category(label:String,action:()->Unit){
            val control=button(label,action)
            categoryButtons[label]=control
            if(mobile)categories.addView(control)
            else categories.addView(control,LinearLayout.LayoutParams(-2,activity.dp(37)).apply {marginEnd=activity.dp(5)})
        }
        category("Tutti"){searchJob?.cancel();filtersOpen=false;if(base.text.isBlank() && base.group==null)home(reset=true) else grid(base,"Tutti",replace=true)}
        category(ARRIVALS_LABEL,::showArrivals)
        category(ALL_CATEGORIES,::chooseGroup)
        listOf("Film" to VodKind.MOVIE,"Serie" to VodKind.EPISODE).forEach {(label,kind)->category(label){searchJob?.cancel();filtersOpen=false;grid(base.copy(kind=kind),label,replace=true)}}
        category("La mia lista"){searchJob?.cancel();filtersOpen=false;grid(base.copy(shelf=VideoShelf.SAVED),"La mia lista",replace=true)}
        tabs.addView(categories)
        if(mobile)view.addView(tabs,LinearLayout.LayoutParams(-1,activity.dp(48)))
        view.addView(scopeLabel);view.addView(status)
        view.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        view.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->val next=mobile && activity.resources.configuration.screenHeightDp<480;if(compact!=next){compact=next;navigation()}}
        binding=true;search.setText(base.text);binding=false;labels()
        home(saved=saved?.getBundle("video_home"))
        work.launch {
            @Suppress("DEPRECATION") val routes=saved?.getParcelableArrayList<Bundle>("video_routes").orEmpty()
            var legacyEpisodes=-1
            for((index,route) in routes.withIndex()) {
                if(index==legacyEpisodes)continue
                if(route.getString("kind")=="grid")grid(readQuery(route),route.getString("label","Video"),saved=route)
                else if(route.getString("kind")=="detail") {
                    val item=repository.videos(VideoQuery(source=route.getString("source"),id=route.getString("id"),collapseSeries=false,limit=1)).rows.firstOrNull()
                    if(item!=null) {
                        val key=route.getString("series").orEmpty()
                        val restored=if(key.isNotEmpty())repository.videos(VideoQuery(source=item.source,series=key,limit=1)).rows.firstOrNull() ?: item else item
                        val oldSeriesRoute=routes.getOrNull(index+1)?.takeIf {next->
                            !mobile && restored.isSeries && next.getString("kind")=="grid" &&
                                readQuery(next).let {it.series==restored.series && it.source==restored.source}
                        }
                        val detailState=if(oldSeriesRoute!=null){
                            legacyEpisodes=index+1
                            Bundle(route).apply {putBundle("video_inline_episodes",Bundle(oldSeriesRoute).apply {
                                readQuery(oldSeriesRoute).season?.let {putInt("season",it)}
                                putBoolean("list_focus",true)
                            })}
                        } else route
                        details(restored,detailState)
                    }
                }
            }
        }
        work.launch {repository.videoCatalogVersion.drop(1).collect {
            if(repository.videoSources.value.isEmpty()){host.close();return@collect}
            if(base.source!=null && base.source !in repository.videoSources.value){base=base.copy(source=null,group=null);home(reset=true)}
            else if(host.isOpen)refresh()
        }}
        work.launch {
            combine(arrivalsSource, repository.videoCatalogVersion) { source, version -> source to version }
                .collectLatest { (source, version) ->
                    if (base.source != source || repository.videoCatalogVersion.value != version) return@collectLatest
                    val query = arrivalsQuery(source)
                    val cached = repository.cachedVideos(query, preview = true)
                    hasArrivals = cached?.rows?.isNotEmpty() == true
                    updateCategoryVisibility()
                    if (cached != null) return@collectLatest
                    val page = try {
                        repository.videoPreview(query)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@collectLatest
                    }
                    if (base.source == source && repository.videoCatalogVersion.value == version) {
                        hasArrivals = page.rows.isNotEmpty()
                        updateCategoryVisibility()
                    }
                }
        }
        work.launch {repository.videoFavoritesVersion.drop(1).collect {if(host.isOpen)refresh()}}
        work.launch {history.version.drop(1).debounce(350).collect {if(host.isOpen)screens.lastOrNull()?.historyChanged?.invoke()}}
        work.launch {repository.videoProgress.collect {progress->if(progress!=null && host.isOpen)message("Preparo i video… ${progress.processed} / ${progress.total}")}}
    }
    private fun button(label:String,action:()->Unit)=CatalogPanelStyle.button(activity,mobile,label,action)
    private fun toggleSearch() {
        search.visibility=if(search.visibility==View.VISIBLE)View.GONE else View.VISIBLE
        if(search.visibility==View.VISIBLE)search.requestFocus() else hideKeyboard()
        if(!mobile)labels()
    }
    private fun hideKeyboard(){(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken,0)}
    private fun selectors()=PersonalCatalogPanels(activity,scope,host)
    private fun message(value:String,error:Boolean=false) {
        status.text=value;status.visibility=if(value.isBlank())View.GONE else View.VISIBLE
        status.isClickable=error;status.isFocusable=error;status.setOnClickListener {refresh()}
    }
    private fun labels() {
        sourceButton.text=repository.state.value.firstOrNull {it.id==base.source}?.name ?: "Tutte le liste";sourceButton.contentDescription="Lista dei video: ${sourceButton.text}"
        groupButton.text=base.group?.let(::videoGroupLabel) ?: "Categorie";groupButton.contentDescription="Categoria: ${groupButton.text}"
        orderButton.text=when(base.order){VideoOrder.PLAYLIST->"Ordine della lista";VideoOrder.TITLE->"Titolo A–Z";VideoOrder.ADDED->"Aggiunti di recente"}
        val query=base.text.takeIf {it.isNotBlank() && (mobile || search.visibility!=View.VISIBLE)}
        scopeLabel.text=listOfNotNull(base.source?.let {sourceButton.text.toString()},base.group?.let(::videoGroupLabel),query?.let {"Ricerca: $it"}).joinToString(" · ")
        scopeLabel.visibility=if(scopeLabel.text.isBlank())View.GONE else View.VISIBLE
    }
    private fun push(screen:Screen) {
        screens.lastOrNull()?.let {it.focus=it.root.findFocus();it.onHide();it.load?.cancel();it.watchLoad?.cancel();it.root.visibility=View.GONE}
        screens+=screen;body.addView(screen.root,FrameLayout.LayoutParams(-1,-1));navigation()
    }
    private fun removeTop(){val old=screens.removeAt(screens.lastIndex)
        if(old.kind=="grid"){gridMemory[old.query.toString()]=Bundle().also(old.save);while(gridMemory.size>12)gridMemory.remove(gridMemory.keys.first())}
        old.load?.cancel();old.watchLoad?.cancel();old.onHide();old.dispose();body.removeView(old.root)}
    private fun updateCategoryVisibility() {
        if (!hasArrivals && categoryButtons[ARRIVALS_LABEL]?.hasFocus() == true) {
            categoryButtons[ALL_CATEGORIES]?.requestFocus()
        }
        categoryButtons.forEach { (label, control) ->
            val visible = when (label) {
                ARRIVALS_LABEL -> hasArrivals
                ALL_CATEGORIES -> true
                else -> !compact || filtersOpen
            }
            control.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }
    private fun navigation() {
        val current=screens.lastOrNull() ?: return
        title.text=if(current.kind=="home")"Video" else current.label
        val detail=current.kind=="detail" || current.query.series!=null
        backButton.visibility=if(if(mobile)screens.size>1 else detail)View.VISIBLE else View.GONE
        searchButton.visibility=if(detail)View.GONE else View.VISIBLE;filterButton.visibility=if(detail)View.GONE else View.VISIBLE
        tabs.visibility=if(detail)View.GONE else View.VISIBLE
        if (arrivalsSource.value != base.source) {
            hasArrivals = false
            arrivalsSource.value = base.source
        }
        updateCategoryVisibility()
        filters.visibility=if(!detail && filtersOpen)View.VISIBLE else View.GONE
        if(detail)search.visibility=View.GONE
        if(!mobile){
            title.visibility=if(!detail && current.kind=="grid")View.VISIBLE else View.GONE
            val selected=when {
                current.query.shelf==VideoShelf.ARRIVALS->ARRIVALS_LABEL
                current.query.shelf==VideoShelf.SAVED->"La mia lista"
                current.query.kind==VodKind.MOVIE->"Film"
                current.query.kind==VodKind.EPISODE->"Serie"
                else->"Tutti"
            }
            categoryButtons.forEach {(label,control)->TvStyle.setButtonSelected(control,label==selected)}
        }
        backdrop?.show(when(current.kind){
            "home"->TvVideoBackdrop.Page.HOME
            "detail"->if(current.item?.isSeries==true)TvVideoBackdrop.Page.SERIES else TvVideoBackdrop.Page.FILM
            else->null
        })
        labels()
        if(!mobile && detail)scopeLabel.visibility=View.GONE
        message("");ViewCompat.setAccessibilityPaneTitle(view,title.text)
    }
    fun back():Boolean {
        hideKeyboard()
        if(search.visibility==View.VISIBLE){search.visibility=View.GONE;if(!mobile)labels();searchButton.requestFocus();return true}
        if(screens.lastOrNull()?.interceptBack?.invoke()==true)return true
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
        val list=RecyclerView(activity).apply {
            tag="video_home";layoutManager=LinearLayoutManager(activity);itemAnimator=null
            clipChildren=false;clipToPadding=false
            // The TV shell already provides the bottom safe area.
            setPadding(0,0,0,activity.dp(if(mobile)16 else 6))
        }
        var focusedKey=saved?.getString("focus_key")
        var featured:VideoItem?=null
        val hero=if(mobile)null else TvVideoHero(activity){featured?.let {details(it)}}
        var featuredSignature:String?=null
        var featuredJob:Job?=null
        var offerEntryFocus=hero!=null && focusedKey==null
        lateinit var screen:Screen
        lateinit var adapter:VideoShelfAdapter
        val pending=mutableMapOf<String,Job>()
        var loadedKey:Any?=null;var restored=saved==null;var seenHistory=history.version.value
        fun key():Any=listOf(repository.videoCacheKey(base),repository.videoFavoritesVersion.value)
        fun highlight(item:VideoItem,immediate:Boolean=false) {
            val header=hero ?: return
            if(screen!==screens.lastOrNull())return
            featured=item
            val target=activity.dp(700)
            val signature=artwork.bindingKey(item,true,target)
            if(featuredSignature==signature)return
            val first=featuredSignature==null
            featuredSignature=signature
            featuredJob?.cancel()
            // The Details action and displayed title always describe the same selection.
            header.bind(item.displayTitle,metadata(item),item.description)
            featuredJob=work.launch {
                if(!immediate && !first)delay(185)
                val detail=try {if(item.detailsLoaded)item else repository.videoDetails(item) ?: item}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){item}
                val cached=if(online.enabled && online.configured)try {repository.videoLookup(detail)?.let {online.cached(it)}}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){null} else null
                if(featuredSignature==signature && screen===screens.lastOrNull()){
                    header.bind(detail.displayTitle,metadata(detail,cached),detail.description.ifBlank {cached?.overview.orEmpty()})
                    if(backdrop!=null)loadBackdrop(detail,target) { bitmap->
                        if(featuredSignature==signature && screen===screens.lastOrNull())backdrop.artwork(bitmap,true)
                    }
                }
            }
        }
        fun publishRows(rows:List<VideoShelfRow>,complete:Boolean=false) {
            val firstRow=rows.firstOrNull {it.page.rows.isNotEmpty()}
            if(firstRow!=null && hero!=null){
                val candidate=firstRow.page.rows.first()
                val selected=featured
                if(selected==null || complete && rows.none {row->row.loading || row.page.rows.any {it.key==selected.key}})
                    highlight(candidate,immediate=true)
                if(offerEntryFocus && host.isOpen){
                    offerEntryFocus=false
                    val focus=activity.currentFocus
                    if(!filtersOpen && search.visibility!=View.VISIBLE &&
                        (focus==null || focus===categoryButtons["Tutti"] || focus.tag=="Video")){
                        adapter.restoreFocusKey=candidate.key
                        adapter.restoreFocusShelf=firstRow.key
                    }
                }
            }else if(complete && featured!=null){
                featured=null;featuredSignature=null;featuredJob?.cancel()
                backdrop?.artwork(null,true)
            }
            adapter.submit(rows)
        }
        fun fetch(row:VideoShelfRow) {
            if(screen!==screens.lastOrNull() || pending[row.key]?.isActive==true)return
            pending[row.key]=work.launch {
                yield()
                try {
                    val page=repository.videoPreview(row.query)
                    if(screen===screens.lastOrNull()) {
                        val next=adapter.rows.toMutableList();val index=next.indexOfFirst {it.key==row.key}
                        if(index>=0){if(page.rows.isEmpty())next.removeAt(index) else next[index]=row.copy(page=page,loading=false);publishRows(next)}
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){/* Other collections remain usable; reopening retries this shelf. */}
            }
        }
        adapter=VideoShelfAdapter(activity,mobile,artwork,{item,row->
            restored=true;focusedKey=item.key
            if(!mobile && row.query.shelf==VideoShelf.CONTINUE && item.resume>0)play(item,false) else details(item)
        },{row->restored=true;grid(row.query,row.title)},{item->focusedKey=item.key;highlight(item)},::fetch,
            interact={restored=true;offerEntryFocus=false},featured=hero)
        if(!mobile)adapter.restoreFocusKey=focusedKey
        adapter.restore(saved);list.adapter=adapter
        screen=Screen("home",list);push(screen)
        fun restoreScroll(complete:Boolean=false) {
            if(restored || screen!==screens.lastOrNull())return
            val manager=list.layoutManager as LinearLayoutManager
            val key=saved?.getString("anchor_key")
            val position=adapter.positionOfKey(key)
            if(position>=0 || complete && saved?.containsKey("anchor_position")==true && adapter.itemCount>0) {
                restored=true;manager.scrollToPositionWithOffset(if(position>=0)position else saved!!.getInt("anchor_position").coerceIn(0,adapter.itemCount-1),saved?.getInt("anchor_top") ?: 0)
            } else if(complete) {
                @Suppress("DEPRECATION") val state=saved?.getParcelable<android.os.Parcelable>("scroll")
                restored=true;manager.onRestoreInstanceState(state)
            }
        }
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){
            override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
                if(dy!=0)restored=true
                if(backdrop!=null && screen===screens.lastOrNull()){
                    val manager=list.layoutManager as LinearLayoutManager
                    val header=if(adapter.hasFeatured)manager.findViewByPosition(0) else null
                    val fraction=if(header!=null && header.height>0)
                        (header.bottom.toFloat()/header.height).coerceIn(0f,1f)
                        else if(manager.findFirstVisibleItemPosition()>0)0f else 1f
                    backdrop.homeVisibility(fraction)
                }
            }
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
            out.putString("anchor_key",adapter.keyAt(position));out.putInt("anchor_position",position)
            out.putInt("anchor_top",manager.findViewByPosition(position)?.let {manager.getDecoratedTop(it)-list.paddingTop} ?: 0)
            adapter.save(list,out)
        }
        screen.restore={restoreScroll();if(!mobile){adapter.restoreFocusKey=focusedKey.takeUnless {screen.focus?.isShown==true};adapter.restoreFocusShelf=adapter.focusedShelf}}
        screen.onHide={pending.values.forEach(Job::cancel);featuredJob?.cancel();featuredSignature=null;adapter.pause(list)}
        screen.onShow={adapter.resume(list);featured?.let {highlight(it,immediate=true)}}
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
                        publishRows(rows,complete=true);seenHistory=history.version.value
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
                    fun publish(complete:Boolean=false) {
                        val order=listOf("continue","saved","arrivals","movies","series","all")
                        publishRows(rows.sortedBy {order.indexOf(it.key).takeIf {i->i>=0} ?: order.size},complete);restoreScroll()
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
                    if(base.group==null)add("arrivals",ARRIVALS_LABEL,arrivalsQuery().copy(text=base.text))
                    add("movies","Film",base.copy(kind=VodKind.MOVIE))
                    add("series","Serie",base.copy(kind=VodKind.EPISODE))
                    add("all","Dalle tue liste",base)
                    rows.removeAll {it.key.startsWith("group:")}
                    if(base.group==null)for(group in repository.videoGroups(base).filterNot {it==VIDEO_ARRIVALS_GROUP}) {
                        val query=base.copy(group=group)
                        val cached=repository.cachedVideos(query,true)
                        rows+=VideoShelfRow("group:$group",videoGroupLabel(group),query,cached ?: VideoPage(emptyList(),0,false),loading=cached==null)
                    }
                    if(!disposed && screen===screens.lastOrNull() && key()==epoch) {
                        publish(complete=true);loadedKey=epoch;seenHistory=history.version.value
                        message(if(rows.isEmpty())"Nessun video disponibile con questi filtri. Apri Filtri per modificare la selezione." else "")
                        restoreScroll(complete=true)
                        if(focusedKey==null)rows.firstOrNull()?.page?.rows?.firstOrNull()?.let {highlight(it)}
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){if(screen===screens.lastOrNull())message("Impossibile aprire i video. Premi qui per riprovare.",true)}
            }
        }
        screen.refresh()
    }
    private fun grid(input:VideoQuery,label:String,replace:Boolean=false,saved:Bundle?=null,seriesItem:VideoItem?=null,seasonPage:Pair<List<Int>,Int>?=null) {
        val query=input.copy(details=false)
        if(disposed)return
        if(replace)while(screens.size>1)removeTop()
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;tag="video_grid"}
        val count=TvStyle.text(activity,"Carico i video…",13f,TvStyle.muted)
        root.addView(count,LinearLayout.LayoutParams(-1,activity.dp(30)))
        val actualSeries=seriesItem ?: query.series?.let {VideoItem("",query.source.orEmpty(),label,VodKind.EPISODE,series=it,seriesTitle=label)}
        var availableSeasons=seasonPage
        var seasonsVersion=seasonPage?.let {repository.videoCatalogVersion.value}
        fun chooseSeason(season:Int?) {
            if(disposed || screens.lastOrNull()?.root!==root || season==query.season)return
            removeTop();screens.last().root.visibility=View.VISIBLE
            grid(query.copy(season=season),label,seriesItem=actualSeries,seasonPage=availableSeasons.takeIf {seasonsVersion==repository.videoCatalogVersion.value})
        }
        val seasons=actualSeries?.let {series->VideoSeasonSelector(activity,mobile,::chooseSeason) {
            selectors().paged("Scegli la stagione",load={q,o->repository.videoSeasons(series,q,o)},label={VideoSeasonSelector.label(it)}) {chooseSeason(it)}
        }.also {selector->root.addView(selector,LinearLayout.LayoutParams(-1,-2));seasonPage?.let {selector.submit(it,query.season)}}}
        val list=RecyclerView(activity).apply {tag="video_grid_results";layoutManager=GridLayoutManager(activity,if(mobile)3 else 6);itemAnimator=null;clipChildren=false;clipToPadding=false;isFocusable=true}
        val restored=saved ?: gridMemory[query.toString()]
        var focusedKey=restored?.getString("focus_key")
        var rotationKey=if(restored?.containsKey("rotation_anchor_set")==true)restored.getString("rotation_key") else focusedKey
        var resizing=false;var viewportWidth=0;var viewportHeight=0;var resizeEpoch=0
        val posters=VideoPosterAdapter(activity,mobile,artwork,if(mobile)106 else 128,false,
            choose={item->focusedKey=item.key;rotationKey=item.key;details(item)},
            focus={item->if(!resizing){focusedKey=item.key;rotationKey=item.key}})
        if(!mobile)posters.restoreFocusKey=focusedKey
        val screen=Screen("grid",root,query,label,actualSeries)
        var position=restored?.getInt("position") ?: 0;var top=restored?.getInt("top") ?: 0
        var resizeAnchor=position
        val catalog=ContinuousCatalog<VideoItem>(work,50,initialDelay=0) {change->list.post {
            if(screen in screens && !disposed) {
                val loadFailed = catalogError(list)
                if (screen === screens.lastOrNull() && query.shelf == VideoShelf.ARRIVALS &&
                    base.text.isBlank() && posters.itemCount == 0 && !loadFailed && posters.catalog?.loading == false) {
                    home(reset = true)
                    return@post
                }
                if(change.sizeChanged)posters.notifyDataSetChanged() else if(change.start<posters.itemCount)posters.notifyItemRangeChanged(change.start,change.count.coerceAtMost(posters.itemCount-change.start))
                if(change.sizeChanged && posters.itemCount>0){
                    val target=posters.catalog?.indexOf {it.key==rotationKey} ?: position.coerceAtMost(posters.itemCount-1)
                    (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(target,if(target==position)top else 0);position=0;top=0
                }
                // Range updates retain their positions. Reapplying an old layout state here can
                // override a newer scroll or rotation anchor while a prefetched block completes.
                count.text=when {
                    posters.itemCount>0->"${posters.itemCount} ${if(query.series!=null)if(posters.itemCount==1)"episodio" else "episodi" else if(posters.itemCount==1)"titolo" else "titoli"}"
                    loadFailed->"Impossibile caricare i video. Premi qui per riprovare."
                    else->"Nessun video con questi filtri."
                }
                count.isFocusable=posters.itemCount==0 && loadFailed;count.isClickable=count.isFocusable
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
            if(actualSeries!=null && seasonsVersion!=repository.videoCatalogVersion.value && screen.load?.isActive!=true) {
                val version=repository.videoCatalogVersion.value
                screen.load=work.launch {
                    try {
                        val page=repository.videoSeasons(actualSeries)
                        if(screen===screens.lastOrNull() && !disposed){availableSeasons=page;seasonsVersion=version;seasons?.submit(page,query.season)}
                    }catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){if(screen===screens.lastOrNull())seasons?.retry {screen.refresh()}}
                }
            }
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
        val inlineSeries=!mobile && item.isSeries
        val compactTv=!mobile && (activity.resources.configuration.screenHeightDp<510 || activity.resources.configuration.fontScale>1.15f)
        val scroll=if(inlineSeries)null else ScrollView(activity).apply {
            isFillViewport=true
            if(!mobile)isVerticalScrollBarEnabled=false
        }
        val content=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            clipChildren=false;clipToPadding=false
            setPadding(activity.dp(4),0,activity.dp(4),activity.dp(if(inlineSeries)3 else 20))
        }
        val presentation=if(!mobile && !inlineSeries)LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            clipChildren=false;clipToPadding=false
            minimumHeight=activity.dp((activity.resources.configuration.screenHeightDp-127).coerceAtLeast(350))
            content.addView(this,LinearLayout.LayoutParams(-1,-2))
        }else content
        presentation.setPadding(presentation.paddingLeft,activity.dp(when {
                mobile->12
                inlineSeries->7
                compactTv->28
                else->53
            }),presentation.paddingRight,presentation.paddingBottom)
        scroll?.addView(content,FrameLayout.LayoutParams(-1,-2))
        val root=(scroll ?: content).apply {tag="video_detail"}
        val screen=Screen("detail",root,label=if(item.isSeries)"Serie" else "Dettagli",item=item);push(screen)
        backdrop?.artwork(null,false)
        val query=if(item.isSeries)VideoQuery(source=item.source,series=item.series,limit=1) else VideoQuery(source=item.source,id=item.id,collapseSeries=false,limit=1)
        var value=repository.cachedVideos(query)?.rows?.firstOrNull() ?: item
        var target=value;var targetReady=!item.isSeries;var stamp:Any?=null;var actionStamp:Any?=null
        var nextEpisode:VideoItem?=null;var editing=false
        var seasons:Pair<List<Int>,Int>?=null
        var onlineState:VideoMetadataState?=null
        var trailerPlayer:TrailerPlayerDialog?=null
        var onlineInput:VideoLookup?=null
        var onlineJob:Job?=null
        var onlineStamp:Any?=null
        var startOnline:()->Unit={}
        var imageJob:Job?=null;var imageBinding=""
        val episodesState=saved?.getBundle("video_inline_episodes")
        var firstFocus=episodesState?.getBoolean("list_focus")!=true
        var scrollPosition=saved?.getInt("detail_scroll") ?: 0;var scrollRestored=false
        val image=if(mobile)ImageView(activity).apply {scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO} else null
        val copy=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        val copyWidth=activity.dp((activity.resources.configuration.screenWidthDp*.49f).toInt().coerceAtLeast(250))
        val name=if(mobile)TvStyle.text(activity,"",24f,bold=true).apply {maxLines=5;ViewCompat.setAccessibilityHeading(this,true)}
            else TvVideoStyle.title(activity,when {
                inlineSeries && compactTv->34f
                inlineSeries->48f
                compactTv->45f
                else->63f
            }).apply {maxWidth=activity.dp(activity.resources.configuration.screenWidthDp*58/100)}
        val meta=TvStyle.text(activity,"",if(mobile)14f else 15f,TvStyle.muted,bold=!mobile).apply {
            if(!mobile){maxWidth=copyWidth;maxLines=2;ellipsize=TextUtils.TruncateAt.END}
        }
        val source=TvStyle.text(activity,"",13f,TvStyle.muted)
        copy.addView(name,LinearLayout.LayoutParams(if(mobile)-1 else -2,-2))
        copy.addView(meta,LinearLayout.LayoutParams(if(mobile)-1 else -2,-2).apply {topMargin=activity.dp(10)})
        if(image!=null){
            val row=LinearLayout(activity).apply {gravity=Gravity.TOP;orientation=LinearLayout.HORIZONTAL}
            val poster=FrameLayout(activity).apply {background=TvStyle.rounded(TvStyle.surface,activity.dp(12).toFloat());clipToOutline=true}
            poster.addView(image,FrameLayout.LayoutParams(-1,-1))
            row.addView(poster,LinearLayout.LayoutParams(activity.dp(108),activity.dp(162)).apply {marginEnd=activity.dp(16)})
            copy.addView(source,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(10)})
            row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
            presentation.addView(row)
        }else presentation.addView(copy)
        val plot=TvStyle.text(activity,"",if(mobile)16f else 17f).apply {
            maxLines=if(mobile)20 else 3
            if(!mobile){maxWidth=copyWidth;ellipsize=TextUtils.TruncateAt.END;setLineSpacing(activity.dp(3).toFloat(),1f)}
            visibility=View.GONE
        }
        if(!inlineSeries)presentation.addView(plot,LinearLayout.LayoutParams(if(mobile)-1 else -2,-2).apply {topMargin=activity.dp(if(mobile)22 else 14)})
        val actions=LinearLayout(activity).apply {orientation=if(mobile)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL;clipChildren=false;clipToPadding=false}
        val extraActions=LinearLayout(activity).apply {clipChildren=false;clipToPadding=false}
        fun actionBar(row:LinearLayout)=HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled=false;isFocusable=false;clipChildren=false;clipToPadding=false
            setPadding(activity.dp(2),activity.dp(2),activity.dp(2),activity.dp(2));addView(row)
        }
        val actionArea:View=if(mobile)actions else actionBar(actions)
        presentation.addView(actionArea,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(if(mobile)20 else if(inlineSeries)10 else 15)})
        val extraArea=if(mobile)null else actionBar(extraActions).apply {
            visibility=if(saved?.getBoolean("detail_extra")==true)View.VISIBLE else View.GONE
            presentation.addView(this,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(5)})
        }
        fun closeExtra(restoreFocus:Boolean):Boolean{
            if(extraArea?.isShown!=true)return false
            extraArea.visibility=View.GONE
            if(restoreFocus)actions.findViewWithTag<View>("video_more_actions")?.requestFocus()
            return true
        }
        screen.interceptBack={closeExtra(true)}
        val resume=TvStyle.text(activity,"",14f,TvStyle.muted).apply {visibility=View.GONE;maxLines=4;ellipsize=TextUtils.TruncateAt.END}
        val progress=if(mobile || inlineSeries)null else ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal).apply {
            tag="video_detail_progress"
            max=1000
            progressTintList=ColorStateList.valueOf(TvStyle.accent)
            progressBackgroundTintList=ColorStateList.valueOf(TvStyle.raised)
            visibility=View.GONE
        }
        if(inlineSeries)resume.maxLines=1
        val watchRow=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE}
        if(progress!=null)watchRow.addView(progress,LinearLayout.LayoutParams(activity.dp(111),activity.dp(5)).apply {marginEnd=activity.dp(10)})
        watchRow.addView(resume,LinearLayout.LayoutParams(-2,-2))
        presentation.addView(watchRow,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(if(inlineSeries)5 else 10)})
        val episodes=if(inlineSeries)TvVideoEpisodes(activity,repository,artwork,work,episodesState,{details(it)}) {series,chooseSeason->
            selectors().paged("Scegli la stagione",load={text,offset->repository.videoSeasons(series,text,offset)},label={VideoSeasonSelector.label(it)},choose=chooseSeason)
        }.also {content.addView(it.view,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=activity.dp(9)})} else null
        val credits=TvStyle.text(activity,"",14f,TvStyle.muted).apply {tag="video_cast";visibility=View.GONE}
        val onlineStatus=TvStyle.text(activity,"",13f,TvStyle.muted).apply {
            tag="video_metadata_status";visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val correct=button("Correggi abbinamento") {
            onlineInput?.let {input->
                selectors().paged<MetadataChoice>("Associa il titolo",searchHint=if(input.query.type==VodMediaType.MOVIE)"Cerca un film su TMDB" else "Cerca una serie su TMDB",load={text,offset->
                    val rows=if(offset==0)listOf(MetadataChoice(null))+online.suggestions(input,text).map {MetadataChoice(it)} else emptyList()
                    rows to rows.size
                },label={choice->choice.candidate?.let {it.title+(it.year?.let {year->" ($year)"} ?: "")} ?: "Non associare questo titolo"}) {choice->
                    work.launch {try {online.choose(input,choice.candidate)}catch(cancelled:CancellationException){throw cancelled}
                        catch(_:Exception){message("Impossibile salvare l’associazione. Riprova.",true)}}
                }
            }
        }.apply {tag="video_metadata_match";visibility=View.GONE;maxLines=2;ellipsize=null}
        if(!inlineSeries){
            if(!mobile)content.addView(source,LinearLayout.LayoutParams(-2,-2).apply {topMargin=activity.dp(25)})
            content.addView(credits,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(20)})
            content.addView(onlineStatus,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(16)})
            content.addView(correct,LinearLayout.LayoutParams(if(mobile)-1 else -2,-2).apply {topMargin=activity.dp(4)})
        }
        fun showInformation(){
            val info=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL
                setPadding(activity.dp(8),activity.dp(12),activity.dp(8),activity.dp(20))
                setBackgroundColor(TvStyle.background)
            }
            val page=ScrollView(activity).apply {
                isFillViewport=true
                addView(info,FrameLayout.LayoutParams(-1,-2))
            }
            info.addView(TvVideoStyle.title(activity,32f).apply {text=value.displayTitle})
            if(inlineSeries){
                for(field in listOf(plot,source,credits,onlineStatus,correct)){
                    (field.parent as? android.view.ViewGroup)?.removeView(field)
                    if(field===plot){plot.maxLines=20;plot.maxWidth=Int.MAX_VALUE}
                    info.addView(field,LinearLayout.LayoutParams(if(field===correct)-2 else -1,-2).apply {topMargin=activity.dp(12)})
                }
            }else info.addView(TvStyle.text(activity,plot.text.toString(),17f),
                LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(12)})
            info.addView(TvVideoStyle.action(activity,"Indietro",R.drawable.ic_mobile_back){host.back()},
                LinearLayout.LayoutParams(-2,-2).apply {topMargin=activity.dp(15)})
            host.push(page)
        }
        fun bind() {
            if(screen!==screens.lastOrNull())return
            fun change(edit:suspend()->Unit) {
                if(editing)return
                editing=true;bind()
                work.launch {
                    try {edit();stamp=null;screen.refresh()}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){message("Impossibile aggiornare lo stato del video. Riprova.",true)}
                    finally {editing=false;bind()}
                }
            }
            val extra=onlineState?.details
            val trailers=TrailerPlaybackQueue.candidates(extra?.trailers.orEmpty())
            if(!online.enabled || onlineState?.status==VideoMetadataStatus.EXCLUDED)trailerPlayer?.dismiss()
            name.text=value.displayTitle
            val numbered=seasons?.let {it.second-if(0 in it.first)1 else 0} ?: 0
            meta.text=listOfNotNull(
                "${numbered} ${if(numbered==1)"stagione" else "stagioni"}".takeIf {inlineSeries && numbered>0},
                metadata(value,extra).takeIf(String::isNotBlank)
            ).joinToString(" · ")
            meta.visibility=if(!mobile && meta.text.isBlank())View.GONE else View.VISIBLE
            source.text=repository.state.value.firstOrNull {it.id==value.source}?.name.orEmpty()
            val description=value.description.ifBlank {
                extra?.overview?.takeIf(String::isNotBlank)?.let {if(extra.type==VodMediaType.TV && !value.isSeries)"Trama della serie\n$it" else it}.orEmpty()
            }
            plot.text=description;plot.visibility=if(description.isBlank())View.GONE else View.VISIBLE
            credits.text=listOfNotNull(extra?.directors?.takeIf {it.isNotEmpty()}?.let {
                (if(extra.type==VodMediaType.MOVIE)"Regia: " else "Ideazione: ")+it.joinToString(", ")
            },extra?.cast?.takeIf {it.isNotEmpty()}?.let {"Cast: "+it.take(8).joinToString(", ")}).joinToString("\n\n")
            credits.visibility=if(credits.text.isBlank())View.GONE else View.VISIBLE
            onlineStatus.text=when(onlineState?.status) {
                VideoMetadataStatus.LOADING->"Carico le informazioni…"
                VideoMetadataStatus.READY->"Informazioni da TMDB"+if(value.description.isBlank() && extra?.overviewLanguage=="en" && extra.overview.isNotBlank())" · Trama in inglese" else ""
                VideoMetadataStatus.AMBIGUOUS->"Più titoli corrispondono. Scegli quello corretto."
                VideoMetadataStatus.NOT_FOUND->"Titolo non trovato. Puoi cercarlo con un altro nome."
                VideoMetadataStatus.EXCLUDED->"Ricerca online disattivata per questo titolo."
                VideoMetadataStatus.ERROR->onlineState?.message.orEmpty()
                VideoMetadataStatus.UNAVAILABLE->if(extra!=null)"Informazioni salvate da TMDB" else ""
                else->""
            }
            onlineStatus.visibility=if(onlineStatus.text.isBlank())View.GONE else View.VISIBLE
            correct.text=if(onlineState?.status in setOf(VideoMetadataStatus.AMBIGUOUS,VideoMetadataStatus.NOT_FOUND,VideoMetadataStatus.EXCLUDED))"Associa il titolo" else "Correggi abbinamento"
            correct.visibility=if(onlineInput!=null && online.configured && online.enabled)View.VISIBLE else View.GONE
            val artworkTarget=activity.dp(if(mobile)162 else 720)
            val binding=artwork.bindingKey(value,!mobile,artworkTarget)
            if(binding!=imageBinding || imageJob==null) {
                imageJob?.cancel()
                if(binding!=imageBinding)image?.setImageDrawable(null)
                imageBinding=binding
                val current=value
                imageJob=if(image!=null)artwork.request(current,targetPx=artworkTarget){bitmap->
                    if(screen in screens && imageBinding==binding && bitmap!=null)image.setImageBitmap(bitmap)
                }else if(backdrop!=null)work.launch {
                    loadBackdrop(current,artworkTarget) {bitmap->
                        if(screen===screens.lastOrNull() && imageBinding==binding)backdrop.artwork(bitmap,true)
                    }
                }else null
            }
            val nextAction=listOf(value.saved,targetReady,target.id,target.season,target.episode,target.resume>0,target.completed,
                value.isSeries,value.hiddenFromContinue,nextEpisode?.id,editing,description.isNotBlank(),trailers)
            if(actionStamp!=nextAction) {
                val hadFocus=actions.hasFocus() || extraActions.hasFocus()
                val focusTag=(actions.findFocus() ?: extraActions.findFocus())?.tag
                actions.removeAllViews();extraActions.removeAllViews()
                fun action(label:String,tag:String,secondary:Boolean=false,icon:Int=0,choose:()->Unit):TextView{
                    val bar=if(!mobile && secondary)extraActions else actions
                    return (if(mobile)button(label,choose) else TvVideoStyle.action(activity,label,icon,tag=="video_play",choose=choose)).apply {
                        this.tag=tag;isEnabled=targetReady && !editing
                        if(mobile){minimumHeight=activity.dp(48);maxLines=3;ellipsize=null}
                        bar.addView(this,LinearLayout.LayoutParams(if(mobile)-1 else -2,-2).apply {
                            if(bar.childCount>0){
                                if(mobile)topMargin=activity.dp(4) else marginStart=activity.dp(9)
                            }
                        })
                    }
                }
                val resumeLabel=if(target.completed)"Riguarda" else if(target.resume>0)"Riprendi" else "Guarda"
                val episode=if(inlineSeries && (target.season ?: 0)>0 && target.episode!=null)" S${target.season} E${target.episode}" else ""
                val play=action(resumeLabel+episode,"video_play",icon=R.drawable.ic_tv_video_play){play(target,false)}.apply {
                    contentDescription="$resumeLabel ${if(inlineSeries && targetReady && !target.isSeries)target.title else value.displayTitle}"
                }
                trailers.firstOrNull()?.let {trailer->
                    action(trailer.label,"video_trailer",icon=R.drawable.ic_tv_video_play){
                        if(trailerPlayer?.isShowing!=true){
                            hideKeyboard();beforeTrailer()
                            TrailerPlayerDialog(activity,value.displayTitle,trailers,mobile).also {dialog->
                                trailerPlayer=dialog
                                dialog.setOnDismissListener {
                                    if(trailerPlayer===dialog)trailerPlayer=null
                                    if(screen===screens.lastOrNull() && activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                                        actions.findViewWithTag<View>("video_trailer")?.takeIf {it.isShown}?.requestFocus()
                                }
                                dialog.show()
                            }
                        }
                    }.apply {isEnabled=!editing;contentDescription="${trailer.label}: ${value.displayTitle}"}
                }
                if(mobile && watchOnTv!=null)action("Guarda sulla TV","video_watch_tv") {
                    work.launch {try {
                        val channel=repository.channel(target.id,target.source) ?: run {message("Il video non è più disponibile.");return@launch}
                        hideKeyboard();watchOnTv.invoke(channel)
                    }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message("Impossibile aprire Guarda sulla TV. Riprova.",true)}}
                }
                if(target.resume>0)action("Dall’inizio","video_restart",secondary=true){play(target,true)}
                nextEpisode?.let {next->action("Guarda episodio successivo","video_next",secondary=true){play(next,false)}.contentDescription="Guarda episodio successivo: ${next.title}"}
                val savedLabel=if(value.saved)"Rimuovi dalla mia lista" else "Aggiungi alla mia lista"
                action(if(mobile)savedLabel else if(value.saved)"Nella mia lista" else "La mia lista","video_saved",
                    icon=if(value.saved)R.drawable.ic_mobile_heart else R.drawable.ic_remote_plus) {
                    work.launch {try {if(value.isSeries)repository.toggleSavedSeries(value) else repository.channel(value.id,value.source)?.let(toggleFavorite);screen.refresh()}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message("Impossibile aggiornare La mia lista. Riprova.",true)}}
                }.apply {if(!mobile)contentDescription=savedLabel}
                if(value.isSeries && mobile)action("Stagioni ed episodi","video_episodes") {
                    grid(VideoQuery(source=value.source,series=value.series,season=VideoSeasonSelector.initialSeason(seasons?.first.orEmpty(),target),collapseSeries=false),
                        "Episodi",seriesItem=value,seasonPage=seasons)
                }
                if(!value.isSeries){
                    action(if(value.completed)"Segna come non visto" else "Segna come visto","video_watched",secondary=true) {
                        change {repository.setVideoCompleted(value,!value.completed)}
                    }
                    if(value.resume>0)action(if(value.hiddenFromContinue)"Mostra in Continua a guardare" else "Rimuovi da Continua a guardare","video_continue",secondary=true) {
                        change {repository.setContinueHidden(value,!value.hiddenFromContinue)}
                    }
                }
                if(!mobile && (inlineSeries || description.isNotBlank())){
                    action(if(inlineSeries)"Informazioni sulla serie" else "Trama completa","video_information",secondary=true,choose=::showInformation)
                }
                if(extraArea!=null && extraActions.childCount>0){
                    action("","video_more_actions",icon=R.drawable.ic_mobile_more){
                        if(!closeExtra(true)){
                            extraArea.visibility=View.VISIBLE
                            extraArea.post {if(extraArea.isShown)extraActions.getChildAt(0)?.requestFocus()}
                        }
                    }.contentDescription="Altre opzioni"
                }
                actionStamp=nextAction
                if(!mobile && targetReady && (hadFocus || firstFocus))play.post {if(screen===screens.lastOrNull() && host.isOpen){
                    val restoreTag=focusTag ?: saved?.getString("detail_focus")
                    val focus=restoreTag?.let {root.findViewWithTag<View>(it)}?.takeIf {it.isEnabled && it.isShown} ?: play
                    focus.requestFocus();firstFocus=false
                }}
            }
            val duration=target.watchedDuration.takeIf {it>0} ?: target.duration
            resume.text=listOfNotNull(when {
                target.resume>0 && progress!=null->videoRemaining(target.resume,duration) ?: "Riprendi da ${videoTime(target.resume)}"
                target.resume>0->"Riprendi da ${videoTime(target.resume)}"+(if(value.isSeries)" · ${target.title}" else "")
                value.isSeries && targetReady->if(target.completed)"Episodi già visti · ${target.title}" else "Da vedere · ${target.title}"
                value.completed->"Visto"
                else->null
            },"Rimosso da Continua a guardare".takeIf {value.hiddenFromContinue && !value.isSeries},nextEpisode?.let {"Prossimo · ${it.title}"}).joinToString("\n")
            resume.visibility=if(resume.text.isBlank())View.GONE else View.VISIBLE
            watchRow.visibility=resume.visibility
            progress?.let {bar->
                val playingProgress=duration>0 && target.resume>0
                bar.visibility=if(playingProgress)View.VISIBLE else View.GONE
                if(playingProgress)bar.progress=(target.resume.toDouble()/duration*1000).toInt().coerceIn(0,1000)
            }
            episodes?.bind(value,target,seasons,history.version.value)
            if(scroll!=null && !scrollRestored && value.detailsLoaded)scroll.post {scroll.scrollTo(0,scrollPosition);scrollRestored=true}
        }
        startOnline={
            val next=repository.videoCacheKey(query)
            if(onlineJob?.isActive!=true || onlineStamp!=next) {
                onlineJob?.cancel();onlineStamp=next
                onlineJob=work.launch {
                    try {
                        val input=repository.videoLookup(value);onlineInput=input
                        if(input==null){onlineState=null;bind()}
                        else online.observe(input).collect {state->
                            if(screen===screens.lastOrNull()){onlineState=state;bind()}
                        }
                    }catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){onlineState=VideoMetadataState(VideoMetadataStatus.ERROR,message="Informazioni online non disponibili. Riprova riaprendo la scheda.");bind()}
                }
            }
        }
        screen.onHide={trailerPlayer?.dismiss();imageJob?.cancel();imageJob=null;onlineJob?.cancel();onlineJob=null;episodes?.hide()}
        screen.onShow={episodes?.show();bind();startOnline()}
        screen.restore={episodes?.restoreFocus()}
        screen.dispose={trailerPlayer?.dismiss();imageJob?.cancel();onlineJob?.cancel();episodes?.close()}
        screen.save={out->
            scroll?.let {out.putInt("detail_scroll",it.scrollY)}
            extraArea?.let {out.putBoolean("detail_extra",it.visibility==View.VISIBLE)}
            (root.findFocus()?.tag as? String)?.let {out.putString("detail_focus",it)}
            episodes?.save()?.let {out.putBundle("video_inline_episodes",it)}
        }
        screen.refresh=refresh@ {
            val nextStamp=listOf(repository.videoCacheKey(query),repository.videoFavoritesVersion.value,history.version.value)
            if(stamp==nextStamp){bind();return@refresh}
            val cached=repository.cachedVideos(query)?.rows?.firstOrNull()
            if(cached!=null){value=cached;if(!value.isSeries)target=value;bind()}
            screen.load?.cancel();screen.load=work.launch {
                try {
                    val current=cached ?: repository.videoDetails(item) ?: run {content.visibility=View.GONE;message("Il titolo non è più disponibile. Torna ai video per aggiornare la selezione.");return@launch}
                    val selected=if(current.isSeries)repository.seriesPlaybackTarget(current) ?: current else current
                    val seasonPage=if(current.isSeries)repository.videoSeasons(current) else null
                    val following=if(!current.isSeries && current.seriesId.isNotBlank())repository.nextEpisode(current.source,current.id) else null
                    if(screen===screens.lastOrNull()) {
                        value=current;target=selected;seasons=seasonPage;nextEpisode=following;targetReady=true;stamp=nextStamp;content.visibility=View.VISIBLE;message("");bind()
                        startOnline()
                    }
                }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message("Impossibile aprire il titolo. Premi qui per riprovare.",true)}
            }
        }
        screen.historyChanged={screen.refresh()}
        bind();screen.refresh()
    }
    private fun metadata(item:VideoItem,extra:TmdbMetadata?=null)=listOfNotNull(
        (if(item.isSeries)extra?.year else item.year ?: extra?.year?.takeIf {item.kind==VodKind.MOVIE})?.toString(),
        if(item.isSeries)"${item.episodes} ${if(item.episodes==1)"episodio" else "episodi"}" else null,
        (item.duration/60_000).takeIf {it>0 && !item.isSeries}?.let {"$it min"}
            ?: extra?.runtimeMinutes?.takeIf {it>0 && !item.isSeries && item.kind==VodKind.MOVIE}?.let {"$it min"},
        item.genres.ifEmpty {extra?.genres.orEmpty()}.take(3).joinToString(" · ").takeIf(String::isNotBlank),
        if(!item.isSeries && item.season!=null && item.episode!=null)"Stagione ${item.season} · Episodio ${item.episode}" else null).joinToString(" · ")
    private fun play(item:VideoItem,start:Boolean) {
        work.launch {try {
            val channel=repository.channel(item.id,item.source) ?: run {message("Il video non è più disponibile.");return@launch}
            if(start)withContext(Dispatchers.IO){history.write(item.source,PlaybackTarget(item.id,PlaybackMode.VOD),0,item.watchedDuration.takeIf {it>0} ?: item.duration)}
            hideKeyboard();screens.lastOrNull()?.onHide?.invoke();host.park();choose(channel)
        }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){message("Impossibile aprire il video. Riprova.",true)}}
    }
    private suspend fun loadBackdrop(item:VideoItem,target:Int,display:(Bitmap?)->Unit) {
        var hasArtwork=false
        suspend fun load(wide:Boolean){
            val request=artwork.request(item,wide,target){bitmap->
                if(bitmap!=null){hasArtwork=true;display(bitmap)}
            }
            try {request.join()} finally {request.cancel()}
        }
        load(true)
        if(!hasArtwork)load(false)
        if(!hasArtwork)display(null)
    }
    private fun filteredQuery()=(screens.lastOrNull()?.takeIf {it.kind=="grid"}?.query ?: base).copy(source=base.source,group=base.group,text=base.text,order=base.order)
    private fun queryLabel(query:VideoQuery)=when {query.text.isNotBlank()->"Risultati";query.shelf==VideoShelf.ARRIVALS->ARRIVALS_LABEL;query.group!=null->videoGroupLabel(query.group);query.shelf==VideoShelf.SAVED->"La mia lista";query.shelf==VideoShelf.CONTINUE->"Continua a guardare";query.kind==VodKind.MOVIE->"Film";query.kind==VodKind.EPISODE->"Serie";else->"Tutti i video"}
    private fun arrivalsQuery(source:String?=base.source) = VideoQuery(
        source = source, kind = VodKind.MOVIE, shelf = VideoShelf.ARRIVALS, details = false
    )
    private fun showArrivals() {
        if (!hasArrivals) return
        searchJob?.cancel();filtersOpen=false
        search.visibility=View.GONE;hideKeyboard()
        base=base.copy(group=null,text="",order=VideoOrder.PLAYLIST)
        binding=true;search.setText("");binding=false
        grid(arrivalsQuery(),ARRIVALS_LABEL,replace=true)
    }
    private fun chooseSource() {
        selectors().paged("Lista dei video",load={text,offset->val rows=listOf("")+repository.state.value.filter {it.id in repository.videoSources.value}.map {it.id};val filtered=rows.filter {id->(repository.state.value.firstOrNull {it.id==id}?.name ?: "Tutte le liste").contains(text,true)};filtered.drop(offset).take(50) to filtered.size},label={id->repository.state.value.firstOrNull {it.id==id}?.name ?: "Tutte le liste"}) {
            searchJob?.cancel();filtersOpen=false;val wasGrid=screens.lastOrNull()?.kind=="grid";base=base.copy(source=it.ifEmpty {null},group=null);if(wasGrid){val query=filteredQuery();grid(query,queryLabel(query),replace=true)}else home(reset=true)
        }
    }
    private fun chooseGroup() {
        selectors().category(repository,PersonalCatalogQuery(source=base.source,content=PlaylistContentKind.VOD),::videoGroupLabel){value->
            searchJob?.cancel();filtersOpen=false
            base=base.copy(group=value.takeUnless {it==ALL_CATEGORIES})
            val request=filteredQuery()
            val query=if(request.shelf==VideoShelf.ARRIVALS)request.copy(shelf=VideoShelf.ALL,kind=null) else request
            if(base.group==null && query.kind==null && query.shelf==VideoShelf.ALL && query.text.isBlank())home(reset=true) else grid(query,queryLabel(query),replace=true)
            labels()
        }
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
    fun close(){disposed=true;while(screens.isNotEmpty())removeTop();backdrop?.show(null);jobs.cancel();artwork.close()}
    companion object {
        const val TAG="lume_video_catalog"
        private const val ARRIVALS_LABEL="Nuovi arrivi"
        private const val ALL_CATEGORIES="Tutte le categorie"
        private fun videoGroupLabel(group:String)=group.removePrefix("VOD · Film · ").removePrefix("VOD · ")
        private fun readQuery(saved:Bundle?)=VideoQuery(source=saved?.getString("video_source"),group=saved?.getString("video_group"),text=saved?.getString("video_query").orEmpty(),
            kind=saved?.getString("video_kind")?.let {runCatching {VodKind.valueOf(it)}.getOrNull()},shelf=saved?.getString("video_shelf")?.let {runCatching {VideoShelf.valueOf(it)}.getOrNull()} ?: VideoShelf.ALL,
            order=saved?.getString("video_order")?.let {runCatching {VideoOrder.valueOf(it)}.getOrNull()} ?: VideoOrder.PLAYLIST,series=saved?.getString("video_series"),season=saved?.getInt("video_season")?.takeIf {saved.containsKey("video_season")},collapseSeries=saved?.getBoolean("video_collapse",true) ?: true)
        private fun writeQuery(out:Bundle,q:VideoQuery) {out.putString("video_source",q.source);out.putString("video_group",q.group);out.putString("video_query",q.text);out.putString("video_kind",q.kind?.name);out.putString("video_shelf",q.shelf.name);out.putString("video_order",q.order.name);out.putString("video_series",q.series);q.season?.let {out.putInt("video_season",it)};out.putBoolean("video_collapse",q.collapseSeries)}
    }
}
