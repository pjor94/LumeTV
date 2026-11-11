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
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel

enum class MobileSection(val label: String, val icon: Int) {
    CHANNELS("Canali", R.drawable.ic_mobile_channels),
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

    fun submit(channels: List<Channel>, programmes: Map<String, List<Programme>>, currentId: String?, loaded: Boolean = true) {
        this.channels = channels; this.programmes = programmes; this.currentId = currentId; this.loaded = loaded
        if (channels.none { it.id == guideChannelId }) guideChannelId = currentId?.takeIf { id -> channels.any { it.id == id } }
            ?: channels.firstOrNull()?.id
        renderRows()
        if (loaded) pendingScroll?.let { list.layoutManager?.onRestoreInstanceState(it); pendingScroll = null }
    }

    fun navigate(value: MobileSection) {
        hideKeyboard()
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
        out.putString("mobile_section", section.name); out.putString("mobile_family", family)
        out.putString("mobile_period", period.name); out.putString("mobile_query", search.text.toString())
        out.putString("mobile_guide_channel", guideChannelId); out.putBoolean("mobile_evening", guideEvening)
        out.putBoolean("mobile_discovery_favorites", discoveryFavorites)
        out.putParcelable("mobile_scroll", list.layoutManager?.onSaveInstanceState())
    }

    @Suppress("DEPRECATION")
    fun restoreState(state: Bundle?) {
        if (state == null) return
        section = MobileSection.entries.firstOrNull { it.name == state.getString("mobile_section") } ?: MobileSection.CHANNELS
        period = DiscoveryPeriod.entries.firstOrNull { it.name == state.getString("mobile_period") } ?: DiscoveryPeriod.NOW
        family = state.getString("mobile_family")?.takeIf { it in listOf("Tutti", "Rai", "Mediaset") } ?: "Tutti"
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
        search.hint = if (section == MobileSection.GUIDE || section == MobileSection.DISCOVER) "Cerca un programma" else "Cerca un canale"
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
            MobileSection.CHANNELS, MobileSection.FAVORITES -> listOf("Tutti", "Rai", "Mediaset").forEach { name ->
                chip(name, family == name) { family = name }
            }
            MobileSection.GUIDE -> {
                chip("Da adesso", !guideEvening) { guideEvening = false }
                chip("Stasera", guideEvening) { guideEvening = true }
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
        val query = search.text.toString().trim()
        val now = System.currentTimeMillis()
        val selectedChannel = channels.firstOrNull { it.id == guideChannelId }
        val rows = when (section) {
            MobileSection.CHANNELS, MobileSection.FAVORITES -> channels.filter { channel ->
                (section != MobileSection.FAVORITES || favorite(channel)) && (family == "Tutti" || channel.family == family) &&
                    (query.isBlank() || channel.name.contains(query, true) || channel.number.toString() == query)
            }.map { MobileBrowseItem(it, ProgrammeWindow.at(programmes[it.epgId].orEmpty(), now).current, channelRow = true) }
            MobileSection.GUIDE -> selectedChannel?.let { channel ->
                val start = if (guideEvening) GuideSchedule.evening(now) else now
                GuideSchedule.normalize(programmes[channel.epgId].orEmpty()).filter {
                    it.end > start && (query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true))
                }.map { MobileBrowseItem(channel, it) }
            }.orEmpty()
            MobileSection.DISCOVER -> DiscoveryEngine.select(channels, programmes,
                channels.filter(favorite).map { it.id }.toSet(), period, discoveryFavorites, query, now)
                .map { MobileBrowseItem(it.channel, it.programme) }
        }
        guideChannel.text = selectedChannel?.name?.let { "$it · Cambia" } ?: "Scegli un canale"
        val count = when (section) {
            MobileSection.CHANNELS, MobileSection.FAVORITES -> if (rows.size == 1) "1 canale" else "${rows.size} canali"
            else -> if (rows.size == 1) "1 programma" else "${rows.size} programmi"
        }
        summary.text = if (!loaded) "Carico i tuoi canali…" else count +
            if (guideStatus.isNotBlank() && section in listOf(MobileSection.GUIDE, MobileSection.DISCOVER)) " · $guideStatus" else ""
        adapter.items = rows
        adapter.notifyDataSetChanged()
        if (resetScroll) list.scrollToPosition(0)
        list.visibility = if (rows.isEmpty()) GONE else VISIBLE
        empty.visibility = if (rows.isEmpty()) VISIBLE else GONE
        emptyTitle.text = when {
            !loaded -> "Un momento…"
            query.isNotBlank() -> "Nessun risultato"
            section == MobileSection.FAVORITES -> "I tuoi canali, qui"
            section == MobileSection.GUIDE || section == MobileSection.DISCOVER -> "Nessun programma disponibile"
            else -> "Nessun canale disponibile"
        }
        emptyCopy.text = when {
            !loaded -> "Sto aprendo il catalogo salvato sul dispositivo."
            query.isNotBlank() -> "Prova a cercare un altro nome."
            section == MobileSection.FAVORITES -> "Tocca il cuore accanto a un canale per ritrovarlo nei preferiti."
            section == MobileSection.GUIDE || section == MobileSection.DISCOVER -> guideStatus.ifBlank { "Il palinsesto può non essere disponibile per questa selezione. La diretta resta accessibile dai Canali." }
            else -> "Controlla la connessione e aggiorna il catalogo."
        }
        emptyAction.visibility = if (!loaded || query.isNotBlank() || section == MobileSection.FAVORITES) GONE else VISIBLE
        emptyAction.text = if (section == MobileSection.GUIDE && selectedChannel != null) "Guarda ${selectedChannel.name}" else "Aggiorna"
    }

    private fun chooseGuideChannel() {
        if (channels.isEmpty()) return
        AlertDialog.Builder(context).setTitle("Guida del canale")
            .setSingleChoiceItems(channels.map { (if (it.number == 9999) "" else "${it.number} · ") + it.name }.toTypedArray(),
                channels.indexOfFirst { it.id == guideChannelId }) { dialog, index ->
                guideChannelId = channels[index].id; dialog.dismiss(); renderRows(true)
            }.setNegativeButton("Chiudi", null).show()
    }

    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus(); requestFocus()
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
