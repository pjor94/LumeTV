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
    var sourceChooser: ((String, it.lumetv.core.MediaKind?, (String) -> Unit) -> Unit)? = null
    var openVideoLibrary:()->Unit={}
    var onProgrammeSearch:(Channel)->Unit={}
    var onGuideWindow:(List<Channel>,Long,Long)->Unit={_,_,_->}
    private var requestedGuide=""
    private var guideDay=0
    var trackModal: ((AlertDialog) -> Unit)? = null
    private val sourceControls = LinearLayout(context)
    private val sourceButton = MobileStyle.button(context, "Lista: Catalogo Lume") {
        sourceChooser?.invoke(sourceId, if (section == MobileSection.RADIO) it.lumetv.core.MediaKind.RADIO else it.lumetv.core.MediaKind.TV) { selected ->
            sourceId = selected; sourceGroup = null; family = "Tutti"; guideChannelId = null
            context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().putString("catalog_source", selected).apply()
            buildFilters(); renderRows(true)
        }
    }
    private val groupButton = MobileStyle.button(context, "Tutti i gruppi") {
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
    private var guideStatus = ""
    private var pendingScroll: Parcelable? = null
    private val heading = TvStyle.text(context, "Canali", 28f, bold = true)
    private val summary = TvStyle.text(context, "Carico i tuoi canali…", 13f, TvStyle.muted)
    private val search = EditText(context)
    private val filters = LinearLayout(context)
    private val guideSelector = LinearLayout(context)
    private val guideChannel = MobileStyle.button(context, "Scegli un canale") { chooseGuideChannel() }
    private val navigation = LinearLayout(context)
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
        addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){if(section!=MobileSection.GUIDE)requestVisibleGuide(adapterItems())}})
    }
    private val content: LinearLayout

    init {
        orientation = VERTICAL
        setBackgroundColor(TvStyle.background)
        isFocusableInTouchMode = true
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(20), context.dp(4), context.dp(8), 0)
        }
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
        content.addView(heading)
        content.addView(summary, LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
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
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderRows(true) }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
            }
        }
        search.minimumHeight = context.dp(48)
        content.addView(search, LayoutParams(-1, -2).apply { topMargin = context.dp(16); bottomMargin = context.dp(8) })
        val filterScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(this@MobileBrowseView.filters)
        }
        content.addView(filterScroll, LayoutParams(-1, -2))
        sourceControls.addView(sourceButton, LayoutParams(0, -2, 1f).apply { rightMargin = context.dp(8) })
        sourceControls.addView(groupButton, LayoutParams(0, -2, 1f))
        sourceControls.addView(MobileStyle.button(context,"Video"){openVideoLibrary()},LayoutParams(-2,-2))
        content.addView(sourceControls, LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
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
        addView(content, LayoutParams(-1, 0, 1f))
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
        if (channels.none { !it.isRadio && it.id == guideChannelId }) guideChannelId = currentId?.takeIf { id -> channels.any { !it.isRadio && it.id == id } }
            ?: channels.firstOrNull { !it.isRadio }?.id
        renderRows()
        if (loaded) pendingScroll?.let { list.layoutManager?.onRestoreInstanceState(it); pendingScroll = null }
    }

    fun setSourceNames(names: Map<String, String>) { sourceNames = names; adapter.sourceNames = names }

    fun navigate(value: MobileSection) {
        hideKeyboard()
        if (value == MobileSection.CHANNELS && family in listOf("TV", "Radio")) family = "Tutti"
        if (value == MobileSection.FAVORITES && family !in listOf("Tutti", "TV", "Radio")) family = "Tutti"
        section = value
        search.setText("")
        buildNavigation(); buildFilters(); renderRows(true)
    }

    fun setGuideStatus(message: String) {
        if (message != guideStatus) { guideStatus = message; renderRows() }
    }
    fun showUpdate(version: String?) {
        updateNotice.text = version?.let { "Lume TV $it è disponibile · Aggiorna" }.orEmpty()
        updateNotice.visibility = if (version == null) GONE else VISIBLE
    }
    fun refreshLogos() { adapter.refresh() }
    val isSearching get() = search.hasFocus()
    fun tick() { renderRows() }
    fun closeSearch(): Boolean {
        if (!search.hasFocus() && search.text.isNullOrEmpty()) return false
        search.setText(""); hideKeyboard(); return true
    }

    fun saveState(out: Bundle) {
        out.putString("mobile_source", sourceId); out.putString("mobile_source_group", sourceGroup)
        out.putString("mobile_radio_filter", radioFilter)
        out.putString("mobile_section", section.name); out.putString("mobile_family", family)
        out.putString("mobile_period", period.name); out.putString("mobile_query", search.text.toString())
        out.putString("mobile_guide_channel", guideChannelId); out.putBoolean("mobile_evening", guideEvening)
        out.putBoolean("mobile_discovery_favorites", discoveryFavorites)
        out.putParcelable("mobile_scroll", list.layoutManager?.onSaveInstanceState())
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
        search.setText(state.getString("mobile_query", ""))
        pendingScroll = state.getParcelable("mobile_scroll")
        buildNavigation(); buildFilters(); renderRows()
    }

    private fun buildNavigation() {
        navigation.removeAllViews()
        MobileSection.entries.forEach { destination ->
            val selected = destination == section
            val item = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER
                setPadding(0, context.dp(7), 0, context.dp(7))
                minimumHeight = context.dp(60)
                isSelected = selected; isFocusable = true; isClickable = true
                contentDescription = destination.label
                TvStyle.buttonSemantics(this)
                background = MobileStyle.ripple(context, selected)
                addView(ImageView(context).apply {
                    setImageResource(destination.icon)
                    imageTintList = ColorStateList.valueOf(if (selected) TvStyle.accent else TvStyle.muted)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(context.dp(22), context.dp(22)))
                addView(TvStyle.text(context, destination.label, 12f, if (selected) TvStyle.accent else TvStyle.muted, selected).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(-2, -2).apply { topMargin = context.dp(5) })
                setOnClickListener { navigate(destination) }
            }
            navigation.addView(item, LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(3); rightMargin = context.dp(3) })
        }
        heading.text = when (section) { MobileSection.GUIDE -> "Guida TV"; MobileSection.DISCOVER -> "Cosa guardare"; else -> section.label }
        search.hint = when (section) {
            MobileSection.GUIDE, MobileSection.DISCOVER -> "Cerca un programma"
            MobileSection.RADIO -> "Cerca una radio o una città"
            else -> "Cerca un canale"
        }
        search.contentDescription = search.hint
        guideSelector.visibility = if (section == MobileSection.GUIDE) VISIBLE else GONE
    }

    private fun buildFilters() {
        filters.removeAllViews()
        fun chip(label: String, selected: Boolean, action: () -> Unit) {
            filters.addView(MobileStyle.button(context, label, selected) { action(); buildFilters(); renderRows(true) },
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

    private fun renderRows(resetScroll: Boolean = false) {
        if (loaded && sourceId != "builtin" && sourceId !in sourceNames) {
            sourceId = "builtin"; sourceGroup = null
            context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().putString("catalog_source", sourceId).apply()
            buildFilters()
        }
        if (sourceGroup != null && it.lumetv.core.CatalogSelection.channels(channels, sourceId)
                .none { it.isRadio == (section == MobileSection.RADIO) && sourceGroup in it.groups }) sourceGroup = null
        val catalog = if(section==MobileSection.FAVORITES)channels else it.lumetv.core.CatalogSelection.channels(channels,sourceId,sourceGroup).filterNot {it.isVod}
        sourceControls.visibility = if (sourceNames.isEmpty() || section == MobileSection.FAVORITES) GONE else VISIBLE
        sourceButton.text = "Lista: " + (sourceNames[sourceId] ?: "Catalogo Lume")
        sourceButton.maxLines = 2; sourceButton.ellipsize = TextUtils.TruncateAt.END
        groupButton.maxLines = 2; groupButton.ellipsize = TextUtils.TruncateAt.END
        groupButton.text = sourceGroup ?: "Tutti i gruppi"
        groupButton.visibility = if (sourceId == "builtin") GONE else VISIBLE
        if (catalog.none { !it.isRadio && it.id == guideChannelId }) guideChannelId = catalog.firstOrNull { !it.isRadio }?.id
        val query = search.text.toString().trim()
        val now = System.currentTimeMillis()
        val selectedChannel = catalog.firstOrNull { it.id == guideChannelId }
        val rows = when (section) {
            MobileSection.CHANNELS, MobileSection.FAVORITES -> catalog.filter { channel ->
                (section == MobileSection.FAVORITES || !channel.isRadio) &&
                    (section != MobileSection.FAVORITES || favorite(channel)) &&
                    (family == "Tutti" || family == "Recenti" && channel.id in recentIds || family == "TV" && !channel.isRadio || channel.family == family) &&
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
        guideChannel.text = selectedChannel?.name?.let { "$it · Cambia" } ?: "Scegli un canale"
        val count = when (section) {
            MobileSection.CHANNELS, MobileSection.FAVORITES -> if (rows.size == 1) "1 canale" else "${rows.size} canali"
            MobileSection.RADIO -> if (rows.size == 1) "1 radio" else "${rows.size} radio"
            else -> if (rows.size == 1) "1 programma" else "${rows.size} programmi"
        }
        summary.text = if (!loaded) "Carico i tuoi canali…" else count +
            if (guideStatus.isNotBlank() && section in listOf(MobileSection.GUIDE, MobileSection.DISCOVER)) " · $guideStatus" else ""
        adapter.items = rows
        adapter.notifyDataSetChanged()
        if (resetScroll) list.scrollToPosition(0)
        list.visibility = if (rows.isEmpty()) GONE else VISIBLE
        empty.visibility = if (rows.isEmpty()) VISIBLE else GONE
        val recentMode = section == MobileSection.RADIO && radioFilter == "Recenti" || section == MobileSection.CHANNELS && family == "Recenti"
        emptyTitle.text = when {
            !loaded -> "Un momento…"
            query.isNotBlank() -> "Nessun risultato"
            recentMode -> "Ancora nessun ascolto recente"
            section == MobileSection.RADIO && radioFilter == "Preferite" -> "Le tue radio preferite, qui"
            section == MobileSection.FAVORITES -> "I tuoi canali, qui"
            section == MobileSection.GUIDE || section == MobileSection.DISCOVER -> "Nessun programma disponibile"
            else -> "Nessun canale disponibile"
        }
        emptyCopy.text = when {
            !loaded -> "Sto aprendo il catalogo salvato sul dispositivo."
            query.isNotBlank() -> "Prova a cercare un altro nome."
            recentMode -> "Le emittenti riprodotte compariranno qui."
            section == MobileSection.RADIO && radioFilter == "Preferite" -> "Tocca il cuore accanto a una radio per ritrovarla qui."
            section == MobileSection.FAVORITES -> "Tocca il cuore accanto a un canale per ritrovarlo nei preferiti."
            sourceId != "builtin" && section in listOf(MobileSection.GUIDE, MobileSection.DISCOVER) -> "Questa lista personale non ha una guida programmi. La diretta resta accessibile dai Canali."
            section == MobileSection.GUIDE || section == MobileSection.DISCOVER -> guideStatus.ifBlank { "Il palinsesto può non essere disponibile per questa selezione. La diretta resta accessibile dai Canali." }
            else -> "Controlla la connessione e aggiorna il catalogo."
        }
        emptyAction.visibility = if (!loaded || query.isNotBlank() || recentMode || section == MobileSection.FAVORITES ||
            section == MobileSection.RADIO && radioFilter == "Preferite" || sourceId != "builtin" && section == MobileSection.DISCOVER) GONE else VISIBLE
        emptyAction.text = if (section == MobileSection.GUIDE && selectedChannel != null) "Guarda ${selectedChannel.name}" else "Aggiorna"
        if(section!=MobileSection.GUIDE){requestVisibleGuide(rows);return}
        val requested=listOfNotNull(selectedChannel)
        val from=if(section==MobileSection.GUIDE)guideStart(now) else now/1_800_000*1_800_000
        val until=from+if(section==MobileSection.GUIDE)86_400_000L else GuideSchedule.WINDOW
        val key=requested.joinToString {it.id}+from
        if(key!=requestedGuide){requestedGuide=key;onGuideWindow(requested,from,until)}
    }
    private fun adapterItems()=adapter.items
    private fun requestVisibleGuide(rows:List<MobileBrowseItem>) {
        val manager=list.layoutManager as LinearLayoutManager
        val first=manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last=manager.findLastVisibleItemPosition().coerceAtLeast(first+9)
        val requested=rows.drop(first).take((last-first+1).coerceAtMost(80)).map {it.channel}.distinctBy {it.id}
        val from=System.currentTimeMillis()/1_800_000*1_800_000
        val key=requested.joinToString {it.id}+from
        if(key!=requestedGuide){requestedGuide=key;onGuideWindow(requested,from,from+GuideSchedule.WINDOW)}
    }
    fun reloadGuideWindow(){requestedGuide="";renderRows()}
    private fun guideStart(now:Long):Long=if(guideEvening)GuideSchedule.evening(now) else if(guideDay==0 || sourceId=="builtin")now/1_800_000*1_800_000 else
        java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().plusDays(guideDay.toLong()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun dayLabel(offset:Int)=java.time.LocalDate.now().plusDays(offset.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM",java.util.Locale.ITALY))

    private fun chooseGuideChannel() {
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
        heading.visibility = if (compact) GONE else VISIBLE
        summary.visibility = if (compact) GONE else VISIBLE
        content.setPadding(context.dp(20), context.dp(if (compact) 0 else 16), context.dp(20), 0)
    }
}
