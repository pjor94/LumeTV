package it.lumetv.app.ui.mobile

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.R
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.DiscoveryEngine
import it.lumetv.app.data.DiscoveryPeriod
import it.lumetv.app.data.GuideSchedule
import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.data.RadioCatalog
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel

enum class MobileSection(val label: String, val icon: Int) {
    CHANNELS("Canali", R.drawable.ic_mobile_channels),
    RADIO("Radio", R.drawable.ic_mobile_radio),
    FAVORITES("Preferiti", R.drawable.ic_mobile_heart),
    GUIDE("Guida", R.drawable.ic_mobile_guide),
    DISCOVER("Scopri", R.drawable.ic_mobile_discover),
}

/** Phone navigation and projections of the same catalog/EPG used by the TV interface. */
@SuppressLint("ViewConstructor")
class MobileBrowseView(
    context: Context,
    logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    private val choose: (MobileBrowseItem) -> Unit,
    toggleFavorite: (Channel) -> Unit,
    private val refresh: () -> Unit,
    options: () -> Unit,
    showUpdates: () -> Unit,
) : LinearLayout(context) {
    var section = MobileSection.CHANNELS
        private set
    private var family = "Tutti"
    private var sourceId = context.getSharedPreferences("lume", Context.MODE_PRIVATE).getString("catalog_source", "builtin") ?: "builtin"
    private var sourceGroup: String? = null
    private var sourceNames = emptyMap<String, String>()
    private var videoAvailable=false
    private var videoSelected=false
    private var filtersExpanded=false
    private var restoringControls=false
    private val sectionStates=linkedMapOf<MobileSection,Bundle>()
    var sourceChooser: ((String, it.lumetv.core.MediaKind?, (String) -> Unit) -> Unit)? = null
    var openVideoLibrary:()->Unit={}
    var catalogPager:it.lumetv.app.ui.PersonalCatalog?=null
    var favoriteIds:()->Set<String> = {emptySet()}
    var choosePersonalGroup:((String,it.lumetv.core.MediaKind,(String)->Unit)->Unit)?=null
    var choosePersonalChannel:((String,(Channel)->Unit)->Unit)?=null
    private var selectedPersonalGuide:Channel?=null
    var onProgrammeSearch:(Channel)->Unit={}
    var onGuideWindow:(List<Channel>,Long,Long)->Unit={_,_,_->}
    private var requestedGuide=""
    private var guideDay=0
    var trackModal: ((AlertDialog) -> Unit)? = null
    private val sourceControls = LinearLayout(context)
    private val details=LinearLayout(context).apply {orientation=VERTICAL;visibility=GONE}
    private val detailsScroll=android.widget.ScrollView(context).apply {addView(details);visibility=GONE;isSaveEnabled=false}
    private val sourceButton = MobileStyle.button(context, "Lista: Catalogo Lume") {
        sourceChooser?.invoke(sourceId, if (section == MobileSection.RADIO) it.lumetv.core.MediaKind.RADIO else it.lumetv.core.MediaKind.TV) { selected ->
            sourceId = selected; sourceGroup = null; family = "Tutti"; guideChannelId = null;filtersExpanded=false
            context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().putString("catalog_source", selected).apply()
            buildFilters(); renderRows(true)
        }
    }
    private val groupButton = MobileStyle.button(context, "Tutti i gruppi") {
        if(sourceId!="builtin" && choosePersonalGroup!=null) {
            choosePersonalGroup?.invoke(sourceId,if(section==MobileSection.RADIO)it.lumetv.core.MediaKind.RADIO else it.lumetv.core.MediaKind.TV){selected->
                sourceGroup=selected.takeUnless {it=="Tutti i gruppi"};guideChannelId=null;filtersExpanded=false;renderRows(true)}
            return@button
        }
        val groups = it.lumetv.core.CatalogSelection.channels(channels, sourceId).filter { it.isRadio == (section == MobileSection.RADIO) }.flatMap { it.groups }.distinct().sorted()
        android.app.AlertDialog.Builder(context).setTitle("Scegli il gruppo")
            .setSingleChoiceItems((listOf("Tutti i gruppi") + groups).toTypedArray(), sourceGroup?.let { groups.indexOf(it) + 1 } ?: 0) { dialog, index ->
                sourceGroup = if (index == 0) null else groups[index - 1]; guideChannelId = null
                dialog.dismiss(); renderRows(true)
            }.setNegativeButton("Annulla", null).show().also { trackModal?.invoke(it) }
    }
    private var radioFilter = "Tutte"
    private var recentIds = emptyList<String>()
    private var period = DiscoveryPeriod.NOW
    private var discoveryFavorites = false
    private var guideEvening = false
    private var guideChannelId: String? = null
    private var channels = emptyList<Channel>()
    private var programmes = emptyMap<String, List<Programme>>()
    private var currentId: String? = null
    private var loaded = false
    private var catalogLoadingMessage:String?=null
    private var guideStatus = ""
    private var personalGuideMessages=emptyMap<String,String>()
    private var pendingScroll: Parcelable? = null
    private var pendingPosition:Int?=null
    private var pendingTop=0
    private val heading = TvStyle.text(context, "Canali", 28f, bold = true)
    private val summary = TvStyle.text(context, "Carico i tuoi canali…", 13f, TvStyle.muted)
    private val search = EditText(context)
    private val searchRow=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
    private val headingRow=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
    private val filterButton=MobileStyle.button(context,"Filtri") {filtersExpanded=!filtersExpanded;renderRows();adaptHeight()}
    private val clearButton=MobileStyle.icon(context,R.drawable.ic_mobile_close,"Azzera filtri"){clearFilters()}
    private val scopeLabel=TvStyle.text(context,"",13f,TvStyle.muted).apply {maxLines=2;ellipsize=TextUtils.TruncateAt.END}
    private lateinit var brandToolbar:LinearLayout
    private val compactOptions=MobileStyle.icon(context,R.drawable.ic_mobile_more,"Opzioni",options).apply {visibility=GONE}
    private val filters = LinearLayout(context)
    private val guideSelector = LinearLayout(context)
    private val guideChannel = MobileStyle.button(context, "Scegli un canale") { chooseGuideChannel() }
    private val navigation = LinearLayout(context).apply {tag="mobile_navigation"}
    private val emptyTitle = TvStyle.text(context, "", 21f, bold = true)
    private val emptyCopy = TvStyle.text(context, "", 15f, TvStyle.muted)
    private val emptyAction = MobileStyle.button(context, "Aggiorna") {
        val selected = channels.firstOrNull { it.id == guideChannelId }
        if (section == MobileSection.GUIDE && selected != null) choose(MobileBrowseItem(selected, channelRow = true))
        else refresh()
    }
    private val empty = LinearLayout(context)
    private val updateNotice = MobileStyle.button(context, "Nuova versione disponibile", action = showUpdates).apply { visibility = GONE }
    private val adapter = MobileChannelAdapter(logos, favorite, choose, toggleFavorite)
    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = this@MobileBrowseView.adapter
        itemAnimator = null
        clipToPadding = false
        setPadding(0, 0, 0, context.dp(12))
        addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
            val manager=layoutManager as LinearLayoutManager
            this@MobileBrowseView.adapter.catalog?.viewport((manager.findFirstVisibleItemPosition()-this@MobileBrowseView.adapter.items.size).coerceAtLeast(0),
                (manager.findLastVisibleItemPosition()-this@MobileBrowseView.adapter.items.size).coerceAtLeast(0))
            if(section!=MobileSection.GUIDE)requestVisibleGuide()
        }})
    }
    private val content: LinearLayout
    val videoContainer=FrameLayout(context)
    var onSectionSelected:()->Unit={}
    fun showVideo(value:Boolean){videoSelected=value;content.visibility=if(value)GONE else VISIBLE;updateNavigationSelection()}

    init {
        orientation = VERTICAL
        setBackgroundColor(TvStyle.background)
        isFocusableInTouchMode = true
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(20), context.dp(4), context.dp(8), 0)
        }
        brandToolbar=toolbar
        toolbar.addView(ImageView(context).apply {
            setImageResource(R.drawable.lume_wordmark)
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "Lume TV"
        }, LayoutParams(context.dp(100), context.dp(36)))
        toolbar.addView(View(context), LayoutParams(0, 1, 1f))
        toolbar.addView(MobileStyle.icon(context, R.drawable.ic_mobile_refresh, "Aggiorna canali e guida", refresh), LayoutParams(context.dp(48), context.dp(48)))
        toolbar.addView(MobileStyle.icon(context, R.drawable.ic_mobile_more, "Opzioni", options), LayoutParams(context.dp(48), context.dp(48)))
        addView(toolbar, LayoutParams(-1, -2))

        content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(context.dp(20), context.dp(16), context.dp(20), 0)
        }
        headingRow.addView(heading,LayoutParams(0,-2,1f))
        heading.maxLines=1;heading.ellipsize=android.text.TextUtils.TruncateAt.END
        summary.maxWidth=context.dp(150);summary.maxLines=2;summary.ellipsize=android.text.TextUtils.TruncateAt.END
        headingRow.addView(summary,LayoutParams(-2,-2).apply {leftMargin=context.dp(12)})
        content.addView(headingRow)
        content.addView(updateNotice, LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        search.apply {
            textSize = 16f
            setTextColor(TvStyle.white); setHintTextColor(TvStyle.muted)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(120))
            isSaveEnabled = false
            background = TvStyle.rounded(TvStyle.surface, context.dp(12).toFloat())
            setPadding(context.dp(14), context.dp(10), context.dp(14), context.dp(10))
            compoundDrawablePadding = context.dp(10)
            val icon = context.getDrawable(R.drawable.ic_mobile_search)!!.mutate().apply { setTint(TvStyle.muted) }
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {if(!restoringControls)renderRows(true)}
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
            }
        }
        search.minimumHeight = context.dp(48)
        searchRow.addView(search,LayoutParams(0,context.dp(48),1f))
        searchRow.addView(filterButton,LayoutParams(-2,context.dp(48)))
        searchRow.addView(clearButton,LayoutParams(context.dp(48),context.dp(48)))
        searchRow.addView(compactOptions,LayoutParams(context.dp(48),context.dp(48)))
        content.addView(searchRow, LayoutParams(-1, -2).apply { topMargin = context.dp(8); bottomMargin = context.dp(4) })
        content.addView(scopeLabel,LayoutParams(-1,-2))
        val filterScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(this@MobileBrowseView.filters)
        }
        details.addView(filterScroll, LayoutParams(-1, -2))
        sourceControls.orientation=VERTICAL
        sourceControls.addView(sourceButton, LayoutParams(-1,-2))
        sourceControls.addView(groupButton, LayoutParams(-1,-2))
        details.addView(sourceControls, LayoutParams(-1, -2))
        details.addView(MobileStyle.button(context,"Aggiorna catalogo",action=refresh),LayoutParams(-1,context.dp(48)))
        content.addView(detailsScroll, LayoutParams(-1, -2))
        guideSelector.gravity = Gravity.CENTER_VERTICAL
        guideChannel.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        guideSelector.addView(guideChannel, LayoutParams(0, -2, 1f))
        guideSelector.addView(MobileStyle.icon(context, R.drawable.ic_mobile_play, "Guarda il canale selezionato") {
            channels.firstOrNull { it.id == guideChannelId }?.let { choose(MobileBrowseItem(it, channelRow = true)) }
        }, LayoutParams(context.dp(48), context.dp(48)))
        content.addView(guideSelector, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
        val body = FrameLayout(context)
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty.apply {
            orientation = VERTICAL; gravity = Gravity.CENTER
            setPadding(context.dp(12), context.dp(16), context.dp(12), context.dp(24))
            emptyTitle.gravity = Gravity.CENTER
            emptyCopy.gravity = Gravity.CENTER
            addView(emptyTitle, LayoutParams(-1, -2))
            addView(emptyCopy, LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
            addView(emptyAction, LayoutParams(-2, -2).apply { topMargin = context.dp(12) })
        }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        content.addView(body, LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(8) })
        videoContainer.addView(content,FrameLayout.LayoutParams(-1,-1))
        addView(videoContainer, LayoutParams(-1, 0, 1f))
        addView(View(context).apply { setBackgroundColor(TvStyle.raised) }, LayoutParams(-1, context.dp(1)))
        navigation.apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(4))
        }
        addView(navigation, LayoutParams(-1, -2))
        buildNavigation()
        buildFilters()
        adaptHeight()
        requestFocus()
    }

    fun submit(channels: List<Channel>, programmes: Map<String, List<Programme>>, currentId: String?, loaded: Boolean = true, recentIds: List<String> = emptyList()) {
        this.channels = channels; this.programmes = programmes; this.currentId = currentId; this.loaded = loaded
        this.recentIds = recentIds
        if (selectedPersonalGuide?.id!=guideChannelId && channels.none { !it.isRadio && it.id == guideChannelId }) guideChannelId = currentId?.takeIf { id -> channels.any { !it.isRadio && it.id == id } }
            ?: channels.firstOrNull { !it.isRadio }?.id
        renderRows()
        restorePendingScroll()
    }

    fun setSourceNames(names: Map<String, String>) { sourceNames = names; adapter.sourceNames = names }

    fun navigate(value: MobileSection) {
        onSectionSelected()
        hideKeyboard()
        if(value==section)return
        val previous=Bundle();writeState(previous);sectionStates[section]=previous
        sectionStates[value]?.let {restoreState(it);return}
        if (value == MobileSection.CHANNELS && family in listOf("TV", "Radio")) family = "Tutti"
        if (value == MobileSection.FAVORITES && family !in listOf("Tutti", "TV", "Radio")) family = "Tutti"
        section = value
        restoringControls=true;search.setText("");restoringControls=false
        buildNavigation(); buildFilters(); renderRows(true)
    }

    fun setGuideStatus(message: String) {
        if (message != guideStatus) { guideStatus = message; renderRows() }
    }
    fun setVideoAvailable(value:Boolean){
        if(videoAvailable==value)return
        val returnToChannels=videoSelected && !value
        videoAvailable=value
        if(returnToChannels)navigate(MobileSection.CHANNELS)
        buildNavigation()
    }
    fun setCatalogLoadingMessage(message:String?){if(catalogLoadingMessage!=message){catalogLoadingMessage=message;if(!loaded)renderRows()}}
    fun setPersonalGuideMessages(messages:Map<String,String>){if(personalGuideMessages!=messages){personalGuideMessages=messages;renderRows()}}
    private fun clearFilters() {
        family="Tutti";radioFilter="Tutte";sourceGroup=null;guideDay=0;guideEvening=false;discoveryFavorites=false
        restoringControls=true;search.setText("");restoringControls=false
        catalogPager?.resetPosition();pendingPosition=0;pendingTop=0
        buildFilters();renderRows(true)
    }
    fun showUpdate(version: String?) {
        updateNotice.text = version?.let { "Lume TV $it è disponibile · Aggiorna" }.orEmpty()
        updateNotice.visibility = if (version == null) GONE else VISIBLE
    }
    fun refreshLogos() { adapter.refresh() }
    fun reloadCatalog(){renderRows(true)}
    fun catalogChanged(change:it.lumetv.app.ui.ContinuousCatalog.Change){post {renderRows(catalogChange=change);restorePendingScroll()}}
    private fun restorePendingScroll(){
        if(!loaded || adapter.itemCount==0 || adapter.catalog?.let {it.loading && it.size==0}==true)return
        pendingPosition?.let {position->
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position.coerceIn(0,adapter.itemCount-1),pendingTop)
            pendingPosition=null;pendingScroll=null;return
        }
        pendingScroll?.let {list.layoutManager?.onRestoreInstanceState(it);pendingScroll=null}
    }
    val isSearching get() = search.hasFocus()
    fun tick() { renderRows() }
    fun closeSearch(): Boolean {
        if (!search.hasFocus() && search.text.isNullOrEmpty()) return false
        search.setText(""); hideKeyboard(); return true
    }

    fun saveState(out: Bundle) {
        writeState(out)
        val history=Bundle();sectionStates.forEach {(key,value)->history.putBundle(key.name,value)}
        out.putBundle("mobile_sections",history)
    }
    private fun writeState(out:Bundle) {
        out.putString("mobile_source", sourceId); out.putString("mobile_source_group", sourceGroup)
        out.putString("mobile_radio_filter", radioFilter)
        out.putString("mobile_section", section.name); out.putString("mobile_family", family)
        out.putString("mobile_period", period.name); out.putString("mobile_query", search.text.toString())
        out.putString("mobile_guide_channel", guideChannelId); out.putBoolean("mobile_evening", guideEvening)
        out.putBoolean("mobile_discovery_favorites", discoveryFavorites)
        out.putBoolean("mobile_filters_expanded",filtersExpanded)
        out.putParcelable("mobile_scroll", list.layoutManager?.onSaveInstanceState())
        val manager=list.layoutManager as LinearLayoutManager
        val position=manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        out.putInt("mobile_list_position",position)
        out.putInt("mobile_list_top",manager.findViewByPosition(position)?.let {manager.getDecoratedTop(it)-list.paddingTop} ?: 0)
    }

    @Suppress("DEPRECATION")
    fun restoreState(state: Bundle?) {
        if (state == null) return
        sourceId = state.getString("mobile_source") ?: sourceId; sourceGroup = state.getString("mobile_source_group")
        section = MobileSection.entries.firstOrNull { it.name == state.getString("mobile_section") } ?: MobileSection.CHANNELS
        period = DiscoveryPeriod.entries.firstOrNull { it.name == state.getString("mobile_period") } ?: DiscoveryPeriod.NOW
        family = state.getString("mobile_family")?.takeIf { it in listOf("Tutti", "TV", "Radio", "Rai", "Mediaset", "Recenti") } ?: "Tutti"
        if (section == MobileSection.CHANNELS && family in listOf("TV", "Radio")) family = "Tutti"
        if (section == MobileSection.FAVORITES && family !in listOf("Tutti", "TV", "Radio")) family = "Tutti"
        radioFilter = state.getString("mobile_radio_filter")?.takeIf { it in listOf("Tutte", "Preferite", "Recenti") } ?: "Tutte"
        guideChannelId = state.getString("mobile_guide_channel")
        guideEvening = state.getBoolean("mobile_evening"); discoveryFavorites = state.getBoolean("mobile_discovery_favorites")
        filtersExpanded=state.getBoolean("mobile_filters_expanded")
        state.getBundle("mobile_sections")?.let {history->MobileSection.entries.forEach {key->history.getBundle(key.name)?.let {sectionStates[key]=it}}}
        restoringControls=true;search.setText(state.getString("mobile_query", ""));restoringControls=false
        pendingScroll = state.getParcelable("mobile_scroll")
        pendingPosition=state.getInt("mobile_list_position").takeIf {state.containsKey("mobile_list_position")}
        pendingTop=state.getInt("mobile_list_top")
        buildNavigation(); buildFilters(); renderRows();restorePendingScroll();adaptHeight()
    }

    private fun buildNavigation() {
        navigation.removeAllViews()
        fun item(label:String,icon:Int,action:()->Unit) {
            val selected = if(label=="Video")videoSelected else !videoSelected && label==section.label
            val item = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                setPadding(0, context.dp(7), 0, context.dp(7))
                minimumHeight = context.dp(60)
                isSelected = selected; isFocusable = true; isClickable = true
                tag=label;contentDescription = label
                TvStyle.buttonSemantics(this)
                background = MobileStyle.ripple(context, selected)
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(if (selected) TvStyle.accent else TvStyle.muted)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(context.dp(22), context.dp(22)))
                addView(TvStyle.text(context, label, 12f, if (selected) TvStyle.accent else TvStyle.muted, selected).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(-2, -2).apply { topMargin = context.dp(5) })
                setOnClickListener { action() }
            }
            val margin=context.dp(if(videoAvailable)1 else 3)
            navigation.addView(item, LayoutParams(0, -2, 1f).apply { leftMargin=margin;rightMargin=margin })
        }
        MobileSection.entries.forEach { destination ->
            item(destination.label,destination.icon){navigate(destination)}
            if(destination==MobileSection.CHANNELS && videoAvailable)item("Video",R.drawable.ic_mobile_play){if(videoAvailable)openVideoLibrary()}
        }
        heading.text = when (section) { MobileSection.GUIDE -> "Guida TV"; MobileSection.DISCOVER -> "Cosa guardare"; else -> section.label }
        search.hint = when (section) {
            MobileSection.GUIDE, MobileSection.DISCOVER -> "Cerca un programma"
            MobileSection.RADIO -> "Cerca una radio o una città"
            else -> "Cerca un canale"
        }
        search.contentDescription = search.hint
        guideSelector.visibility = if (section == MobileSection.GUIDE) VISIBLE else GONE
        adaptHeight()
    }

    private fun updateNavigationSelection() {
        // Keep the actual controls attached: Video's host restores focus to its originating tab.
        for(index in 0 until navigation.childCount) {
            val item=navigation.getChildAt(index) as LinearLayout
            val selected=if(item.tag=="Video")videoSelected else !videoSelected && item.tag==section.label
            if(item.isSelected==selected)continue
            item.isSelected=selected;item.background=MobileStyle.ripple(context,selected)
            val color=if(selected)TvStyle.accent else TvStyle.muted
            (item.getChildAt(0) as ImageView).imageTintList=ColorStateList.valueOf(color)
            (item.getChildAt(1) as TextView).apply {setTextColor(color);typeface=android.graphics.Typeface.create(if(selected)"sans-serif-medium" else "sans-serif",android.graphics.Typeface.NORMAL)}
        }
    }

    private fun buildFilters() {
        filters.removeAllViews()
        fun chip(label: String, selected: Boolean, action: () -> Unit) {
            filters.addView(MobileStyle.button(context, label, selected) { action();filtersExpanded=false; buildFilters(); renderRows(true) },
                LayoutParams(-2, -2).apply { rightMargin = context.dp(6) })
        }
        when (section) {
            MobileSection.CHANNELS, MobileSection.FAVORITES -> (if (section == MobileSection.FAVORITES) listOf("Tutti", "TV", "Radio") else if (sourceId == "builtin") listOf("Tutti", "Rai", "Mediaset", "Recenti") else listOf("Tutti", "Recenti")).forEach { name ->
                chip(name, family == name) { family = name }
            }
            MobileSection.RADIO -> listOf("Tutte", "Preferite", "Recenti").forEach { name ->
                chip(name, radioFilter == name) { radioFilter = name }
            }
            MobileSection.GUIDE -> {
                chip("Da adesso", !guideEvening && guideDay==0) { guideEvening = false;guideDay=0 }
                chip("Stasera", guideEvening) { guideEvening = true;guideDay=0 }
                if(sourceId!="builtin")chip(if(guideDay==0)"Scegli giorno" else dayLabel(guideDay),guideDay!=0) {
                    val days=(-7..2).toList()
                    AlertDialog.Builder(context).setTitle("Giorno della guida").setItems(days.map(::dayLabel).toTypedArray()){_,n->guideDay=days[n];guideEvening=false;buildFilters();renderRows(true)}.setNegativeButton("Chiudi",null).show()
                }
                if(sourceId!="builtin")chip("Cerca nella guida",false){channels.firstOrNull {it.id==guideChannelId}?.let(onProgrammeSearch)}
            }
            MobileSection.DISCOVER -> {
                listOf("Ora", "Tra poco", "Stasera").forEachIndexed { index, label ->
                    chip(label, period.ordinal == index) { period = DiscoveryPeriod.entries[index] }
                }
                chip("Solo preferiti", discoveryFavorites) { discoveryFavorites = !discoveryFavorites }
            }
        }
    }

    private fun renderRows(resetScroll: Boolean = false,catalogChange:it.lumetv.app.ui.ContinuousCatalog.Change?=null) {
        if (loaded && sourceId != "builtin" && sourceId !in sourceNames) {
            sourceId = "builtin"; sourceGroup = null
            context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().putString("catalog_source", sourceId).apply()
            buildFilters()
        }
        if (sourceId=="builtin" && sourceGroup != null && it.lumetv.core.CatalogSelection.channels(channels, sourceId)
                .none { it.isRadio == (section == MobileSection.RADIO) && sourceGroup in it.groups }) sourceGroup = null
        val paged=sourceId!="builtin" || section==MobileSection.FAVORITES
        val queryText=search.text.toString().trim()
        val kind=when {section==MobileSection.RADIO || section==MobileSection.FAVORITES && family=="Radio"->it.lumetv.core.MediaKind.RADIO
            section!=MobileSection.FAVORITES || family=="TV"->it.lumetv.core.MediaKind.TV;else->null}
        val restricted=when {section==MobileSection.FAVORITES || section==MobileSection.RADIO && radioFilter=="Preferite"->favoriteIds()
            section==MobileSection.RADIO && radioFilter=="Recenti" || section==MobileSection.CHANNELS && family=="Recenti"->recentIds.toSet();else->null}
        val catalogReset=if(paged)catalogPager?.bind(it.lumetv.app.data.PersonalCatalogQuery(source=sourceId.takeUnless {section==MobileSection.FAVORITES},kind=kind,
            content=if(section==MobileSection.FAVORITES)null else it.lumetv.core.PlaylistContentKind.LIVE,
            text=if(section in listOf(MobileSection.GUIDE,MobileSection.DISCOVER))"" else queryText,group=sourceGroup.takeUnless {section==MobileSection.FAVORITES},ids=restricted))
        else {catalogPager?.hide();false}
        if(catalogReset==true && pendingPosition==null){pendingPosition=catalogPager?.offset ?: 0;pendingTop=0}
        val continuous=paged && catalogPager!=null && section in listOf(MobileSection.CHANNELS,MobileSection.RADIO,MobileSection.FAVORITES) &&
            !(section==MobileSection.CHANNELS && family=="Recenti" || section==MobileSection.RADIO && radioFilter=="Recenti")
        val catalog = if(paged && catalogPager!=null) {
            (if(section==MobileSection.FAVORITES)channels.filterNot {it.isPersonal} else emptyList())+catalogPager!!.rows
        } else if(section==MobileSection.FAVORITES)channels else it.lumetv.core.CatalogSelection.channels(channels,sourceId,sourceGroup).filterNot {it.isVod}
        sourceControls.visibility = if (sourceNames.isEmpty() || section == MobileSection.FAVORITES) GONE else VISIBLE
        sourceButton.text = "Lista: " + (sourceNames[sourceId] ?: "Catalogo Lume")
        sourceButton.maxLines = 2; sourceButton.ellipsize = TextUtils.TruncateAt.END
        groupButton.maxLines = 2; groupButton.ellipsize = TextUtils.TruncateAt.END
        groupButton.text = sourceGroup ?: "Tutti i gruppi"
        groupButton.visibility = if (sourceId == "builtin") GONE else VISIBLE
        val activeFilters=(if(queryText.isNotEmpty())1 else 0)+(if(sourceGroup!=null)1 else 0)+
            (if(section==MobileSection.RADIO && radioFilter!="Tutte" || section==MobileSection.CHANNELS && family!="Tutti" || section==MobileSection.FAVORITES && family!="Tutti")1 else 0)
        filterButton.text=if(activeFilters==0)"Filtri" else "Filtri ($activeFilters)"
        filterButton.isSelected=filtersExpanded || activeFilters>0
        filterButton.background=MobileStyle.ripple(context,filterButton.isSelected)
        clearButton.visibility=if(activeFilters>0)VISIBLE else GONE
        val scope=if(section==MobileSection.FAVORITES)"Tutte le liste" else sourceNames[sourceId] ?: "Catalogo Lume"
        scopeLabel.text=listOfNotNull(scope,sourceGroup,queryText.takeIf {it.isNotEmpty()}?.let {"Ricerca: $it"},
            family.takeIf {section in listOf(MobileSection.CHANNELS,MobileSection.FAVORITES) && it!="Tutti"},radioFilter.takeIf {section==MobileSection.RADIO && it!="Tutte"}).joinToString(" · ")
        scopeLabel.contentDescription=scopeLabel.text
        adaptHeight()
        if (selectedPersonalGuide?.id!=guideChannelId && catalog.none { !it.isRadio && it.id == guideChannelId }) guideChannelId = catalog.firstOrNull { !it.isRadio }?.id
        val query = search.text.toString().trim()
        val now = System.currentTimeMillis()
        val selectedChannel = selectedPersonalGuide?.takeIf {it.id==guideChannelId && it.sourceId==sourceId} ?: catalog.firstOrNull { it.id == guideChannelId }
        val rows = when (section) {
            MobileSection.CHANNELS, MobileSection.FAVORITES -> catalog.filter { channel ->
                (section == MobileSection.FAVORITES || !channel.isRadio) &&
                    (section != MobileSection.FAVORITES || favorite(channel)) &&
                    (family == "Tutti" || family == "Recenti" && channel.id in recentIds || family == "TV" && !channel.isRadio || family == "Radio" && channel.isRadio || channel.family == family) &&
                    (query.isBlank() || channel.name.contains(query, true) || channel.number.toString() == query)
            }.let { rows -> if (family == "Recenti") rows.sortedBy { recentIds.indexOf(it.id) } else rows }
                .map { MobileBrowseItem(it, ProgrammeWindow.at(programmes[it.epgId].orEmpty(), now).current, channelRow = true) }
            MobileSection.RADIO -> catalog.filter { it.isRadio &&
                (radioFilter != "Preferite" || favorite(it)) && (radioFilter != "Recenti" || it.id in recentIds) &&
                (query.isBlank() || RadioCatalog.matches(it, query))
            }.let { rows -> if (radioFilter == "Recenti") rows.sortedBy { recentIds.indexOf(it.id) } else rows }
                .map { MobileBrowseItem(it, channelRow = true) }
            MobileSection.GUIDE -> selectedChannel?.let { channel ->
                val start = guideStart(now)
                GuideSchedule.normalize(programmes[channel.epgId].orEmpty()).filter {
                    it.end > start && it.start<start+86_400_000L && (query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true))
                }.map { MobileBrowseItem(channel, it) }
            }.orEmpty()
            MobileSection.DISCOVER -> DiscoveryEngine.select(catalog.filterNot(Channel::isRadio), programmes,
                channels.filter(favorite).map { it.id }.toSet(), period, discoveryFavorites, query, now)
                .map { MobileBrowseItem(it.channel, it.programme) }
        }
        val fixedRows=if(continuous)rows.filterNot {it.channel.isPersonal} else rows
        adapter.catalog=if(continuous)catalogPager?.data else null
        adapter.programmeFor={channel->ProgrammeWindow.at(programmes[channel.epgId].orEmpty(),System.currentTimeMillis()).current}
        val rowCount=fixedRows.size+(adapter.catalog?.size ?: 0)
        guideChannel.text = selectedChannel?.name?.let { "$it · Cambia" } ?: "Scegli un canale"
        val count = when (section) {
            MobileSection.CHANNELS, MobileSection.FAVORITES -> if (rowCount == 1) "1 canale" else "$rowCount canali"
            MobileSection.RADIO -> if (rowCount == 1) "1 radio" else "$rowCount radio"
            else -> if (rows.size == 1) "1 programma" else "${rows.size} programmi"
        }
        val initialLoading=paged && catalogPager?.loading==true && rowCount==0
        val catalogError=paged && catalogPager?.error?.isNotBlank()==true && rowCount==0
        summary.text = if (!loaded || initialLoading) catalogLoadingMessage ?: "Carico i tuoi canali…" else count +
            if (sourceId=="builtin" && guideStatus.isNotBlank() && section in listOf(MobileSection.GUIDE, MobileSection.DISCOVER)) " · $guideStatus" else ""
        adapter.items = fixedRows
        if(continuous && catalogChange!=null && !catalogChange.sizeChanged) {
            val start=fixedRows.size+catalogChange.start
            val amount=catalogChange.count.coerceAtMost(adapter.itemCount-start)
            if(amount>0)adapter.notifyItemRangeChanged(start,amount)
        } else adapter.notifyDataSetChanged()
        if (resetScroll && !continuous)list.scrollToPosition(0)
        list.visibility = if (rowCount==0) GONE else VISIBLE
        empty.visibility = if (rowCount==0) VISIBLE else GONE
        val recentMode = section == MobileSection.RADIO && radioFilter == "Recenti" || section == MobileSection.CHANNELS && family == "Recenti"
        emptyTitle.text = when {
            catalogError -> "Impossibile aprire la lista"
            !loaded || initialLoading -> if(catalogLoadingMessage!=null)"Aggiorno il catalogo…" else "Un momento…"
            query.isNotBlank() -> "Nessun risultato"
            recentMode -> "Ancora nessun ascolto recente"
            section == MobileSection.RADIO && radioFilter == "Preferite" -> "Le tue radio preferite, qui"
            section == MobileSection.FAVORITES -> "I tuoi canali, qui"
            section == MobileSection.GUIDE || section == MobileSection.DISCOVER -> "Nessun programma disponibile"
            else -> "Nessun canale disponibile"
        }
        emptyCopy.text = when {
            catalogError -> "Riprova a caricare i canali."
            !loaded || initialLoading -> catalogLoadingMessage ?: "Sto aprendo il catalogo salvato sul dispositivo."
            query.isNotBlank() -> "Prova a cercare un altro nome."
            recentMode -> "Le emittenti riprodotte compariranno qui."
            section == MobileSection.RADIO && radioFilter == "Preferite" -> "Tocca il cuore accanto a una radio per ritrovarla qui."
            section == MobileSection.FAVORITES -> "Tocca il cuore accanto a un canale per ritrovarlo nei preferiti."
            sourceId != "builtin" && section in listOf(MobileSection.GUIDE, MobileSection.DISCOVER) -> personalGuideMessages[sourceId] ?: "Nessun programma in questa fascia. Controlla la guida della lista e aggiorna."
            section == MobileSection.GUIDE || section == MobileSection.DISCOVER -> guideStatus.ifBlank { "Il palinsesto può non essere disponibile per questa selezione. La diretta resta accessibile dai Canali." }
            else -> "Controlla la connessione e aggiorna il catalogo."
        }
        emptyAction.visibility = if(catalogError)VISIBLE else if (!loaded || initialLoading || query.isNotBlank() || recentMode || section == MobileSection.FAVORITES ||
            section == MobileSection.RADIO && radioFilter == "Preferite" || sourceId != "builtin" && section == MobileSection.DISCOVER) GONE else VISIBLE
        emptyAction.text = if(catalogError)"Riprova" else if (section == MobileSection.GUIDE && selectedChannel != null) "Guarda ${selectedChannel.name}" else "Aggiorna"
        emptyAction.setOnClickListener {if(catalogError)catalogPager?.data?.retry() else if(section==MobileSection.GUIDE && selectedChannel!=null)choose(MobileBrowseItem(selectedChannel,channelRow=true)) else refresh()}
        if(section!=MobileSection.GUIDE){list.post {requestVisibleGuide()};return}
        val requested=listOfNotNull(selectedChannel)
        val from=if(section==MobileSection.GUIDE)guideStart(now) else now/1_800_000*1_800_000
        val until=from+if(section==MobileSection.GUIDE)86_400_000L else GuideSchedule.WINDOW
        val key=requested.joinToString {it.id}+from
        if(key!=requestedGuide){requestedGuide=key;onGuideWindow(requested,from,until)}
    }
    private fun requestVisibleGuide() {
        val manager=list.layoutManager as LinearLayoutManager
        val first=manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last=manager.findLastVisibleItemPosition().coerceAtLeast(first+9)
        val requested=adapter.visibleRows(first,last.coerceAtMost(first+79)).map {it.channel}.distinctBy {it.id}
        val from=System.currentTimeMillis()/1_800_000*1_800_000
        val key=requested.joinToString {it.id}+from
        if(key!=requestedGuide){requestedGuide=key;onGuideWindow(requested,from,from+GuideSchedule.WINDOW)}
    }
    fun reloadGuideWindow(){requestedGuide="";renderRows()}
    private fun guideStart(now:Long):Long=if(guideEvening)GuideSchedule.evening(now) else if(guideDay==0 || sourceId=="builtin")now/1_800_000*1_800_000 else
        java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().plusDays(guideDay.toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun dayLabel(offset:Int)=java.time.LocalDate.now().plusDays(offset.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM",java.util.Locale.ITALY))

    private fun chooseGuideChannel() {
        if(sourceId!="builtin" && choosePersonalChannel!=null) {
            choosePersonalChannel?.invoke(sourceId){channel->selectedPersonalGuide=channel;guideChannelId=channel.id;renderRows(true)}
            return
        }
        val channels = it.lumetv.core.CatalogSelection.channels(channels, sourceId, sourceGroup).filter {!it.isRadio && !it.isVod}
        if (channels.isEmpty()) return
        AlertDialog.Builder(context).setTitle("Guida del canale")
            .setSingleChoiceItems(channels.map { (if (it.displayNumber == null) "" else "${it.number} · ") + it.name }.toTypedArray(),
                channels.indexOfFirst { it.id == guideChannelId }) { dialog, index ->
                guideChannelId = channels[index].id; dialog.dismiss(); renderRows(true)
            }.setNegativeButton("Chiudi", null).show()
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus(); requestFocus()
    }

    fun setMiniPlayer(view: View) {
        if (view.parent === this) return
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, childCount - 2, LayoutParams(-1, -2).apply {
            leftMargin = context.dp(12); rightMargin = context.dp(12); bottomMargin = context.dp(8)
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adaptHeight()
    }

    private fun adaptHeight() {
        val compact = resources.configuration.screenHeightDp < 480
        brandToolbar.visibility=if(compact)GONE else VISIBLE
        headingRow.visibility=if(compact)GONE else VISIBLE
        compactOptions.visibility=if(compact)VISIBLE else GONE
        scopeLabel.maxLines=if(compact)1 else 2
        details.visibility=if(filtersExpanded)VISIBLE else GONE
        detailsScroll.visibility=details.visibility
        detailsScroll.layoutParams?.let {val height=if(compact)context.dp(minOf(160,resources.configuration.screenHeightDp/2)) else LayoutParams.WRAP_CONTENT;if(it.height!=height){it.height=height;detailsScroll.layoutParams=it}}
        for(index in 0 until navigation.childCount) {
            val item=navigation.getChildAt(index) as? LinearLayout ?: continue
            item.minimumHeight=context.dp(if(compact)48 else 60)
            if(item.childCount>1)item.getChildAt(1).visibility=if(compact)GONE else VISIBLE
        }
        content.setPadding(context.dp(20), context.dp(if (compact) 0 else 16), context.dp(20), 0)
    }
}
