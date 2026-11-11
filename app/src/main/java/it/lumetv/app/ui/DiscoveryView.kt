package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.InputFilter
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.R
import it.lumetv.app.data.*
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Programme-first discovery, using the same catalog, EPG, logos and fullscreen player. */
@SuppressLint("ViewConstructor")
class DiscoveryView(
    context: Context,
    private val logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    private val toggleFavorite: (Channel) -> Unit,
    private val reminders: ProgrammeReminders,
    private val choose: (Channel) -> Unit,
    private val openCatalog: () -> Unit,
    private val exitUp: () -> Unit,
) : LinearLayout(context) {
    private val controller = DiscoveryController()
    private val prefs = context.getSharedPreferences("discovery", Context.MODE_PRIVATE)
    private val date = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val subtitle = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val results = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val hint = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val nowControl = control(context.getString(R.string.discovery_now)) { changePeriod(DiscoveryPeriod.NOW) }
    private val soonControl = control(context.getString(R.string.discovery_soon)) { changePeriod(DiscoveryPeriod.SOON) }
    private val tonightControl = control(context.getString(R.string.discovery_tonight)) { changePeriod(DiscoveryPeriod.TONIGHT) }
    private val favoritesControl = control(context.getString(R.string.discovery_favorites)) {
        controller.filterFavorites(!controller.favoritesOnly); render(true)
    }
    private val searchControl = control(context.getString(R.string.discovery_search)) { toggleSearch() }
    private val controls get() = listOf(nowControl, soonControl, tonightControl, favoritesControl, searchControl)
    private val searchInput = EditText(context)
    private val searchRow = LinearLayout(context)
    private val preview = LinearLayout(context)
    private val logo = ChannelLogoView(context, logos, compact = true)
    private val channel = TvStyle.text(context, "", 13f)
    private val stateLabel = TvStyle.text(context, "", 10f, TvStyle.accent, true)
    private val title = TvStyle.text(context, "", 27f, bold = true)
    private val schedule = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val description = TvStyle.text(context, "", 15f, 0xFFD2D7E0.toInt())
    private val descriptionScroll = ScrollView(context)
    private val action = control("") { activate() }
    private val favoriteAction = control("★") { controller.selected?.let { toggleFavorite(it.channel) } }
    private val list = RecyclerView(context)
    private val rows = ProgrammeAdapter()
    private val empty = LinearLayout(context)
    private val emptyTitle = TvStyle.text(context, "", 21f, bold = true)
    private val emptyCopy = TvStyle.text(context, "", 14f, TvStyle.muted)
    private val openChannels = control(context.getString(R.string.discovery_open_channels), openCatalog)
    private var pendingFocus = false
    private var focusGeneration = 0
    private var boundKey: String? = null
    private var searchVisible = false
    private var writingQuery = false
    private var guideStatus = ""
    private var dialog: AlertDialog? = null
    private var restoringDialog = false
    private val queryChanged = Runnable { controller.search(searchInput.text.toString()); render(false) }
    val isEditing get() = searchInput.hasFocus()
    val hasDetails get() = dialog?.isShowing == true

    init {
        orientation = VERTICAL
        clipChildren = false; clipToPadding = false
        setPadding(0, context.dp(18), 0, 0)
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TvStyle.text(context, context.getString(R.string.discovery_title) + ".", 30f, bold = true), LayoutParams(0, -2, 1f))
        heading.addView(date)
        addView(heading)
        addView(subtitle, LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val periods = LinearLayout(context).apply {
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            background = TvStyle.rounded(0xFF191E27.toInt(), context.dp(24).toFloat())
        }
        listOf(nowControl, soonControl, tonightControl).forEach { periods.addView(it, LayoutParams(-2, -1)) }
        toolbar.addView(periods, LayoutParams(-2, context.dp(38)))
        toolbar.addView(View(context), LayoutParams(0, 1, 1f))
        toolbar.addView(favoritesControl, LayoutParams(-2, context.dp(36)))
        toolbar.addView(searchControl, LayoutParams(-2, context.dp(36)).apply { leftMargin = context.dp(7) })
        addView(toolbar, LayoutParams(-1, -2).apply { topMargin = context.dp(15) })
        searchInput.apply {
            id = View.generateViewId()
            hint = context.getString(R.string.discovery_search_hint)
            contentDescription = hint
            setTextColor(TvStyle.white); setHintTextColor(TvStyle.muted); textSize = 16f
            isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(120))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(context.dp(14), context.dp(7), context.dp(14), context.dp(7))
            background = TvStyle.rounded(0xFF1A2230.toInt(), context.dp(10).toFloat(), 0xFF627B99.toInt(), context.dp(1))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!writingQuery) { this@DiscoveryView.removeCallbacks(queryChanged); this@DiscoveryView.postDelayed(queryChanged, 180) }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    this@DiscoveryView.removeCallbacks(queryChanged); controller.search(text.toString()); hideKeyboard(); render(true); true
                } else false
            }
        }
        searchRow.addView(searchInput, LayoutParams(0, context.dp(40), 1f))
        searchRow.addView(control("×") { closeSearch() }.apply { contentDescription = context.getString(R.string.discovery_close_search) },
            LayoutParams(context.dp(40), context.dp(40)).apply { leftMargin = context.dp(8) })
        searchRow.visibility = GONE
        addView(searchRow, LayoutParams(-1, -2).apply { topMargin = context.dp(9) })
        results.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        addView(results, LayoutParams(-1, -2).apply { topMargin = context.dp(14); bottomMargin = context.dp(10) })
        val body = FrameLayout(context)
        val columns = LinearLayout(context)
        preview.apply { orientation = VERTICAL; setPadding(0, context.dp(5), context.dp(25), 0) }
        val information = LinearLayout(context).apply { orientation = VERTICAL }
        val brand = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(logo, LayoutParams(context.dp(46), context.dp(32)).apply { rightMargin = context.dp(10) })
        channel.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        brand.addView(channel, LayoutParams(0, -2, 1f))
        information.addView(brand)
        stateLabel.letterSpacing = .08f
        information.addView(stateLabel, LayoutParams(-1, -2).apply { topMargin = context.dp(13) })
        title.apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        information.addView(title, LayoutParams(-1, -2).apply { topMargin = context.dp(7) })
        schedule.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; fontFeatureSettings = "tnum" }
        information.addView(schedule, LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        progress.apply {
            max = 1000; progressTintList = ColorStateList.valueOf(TvStyle.accent)
            progressBackgroundTintList = ColorStateList.valueOf(TvStyle.raised)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        information.addView(progress, LayoutParams(-1, context.dp(3)).apply { topMargin = context.dp(10) })
        description.apply { setLineSpacing(context.dp(2).toFloat(), 1f) }
        information.addView(description, LayoutParams(-1, -2).apply { topMargin = context.dp(13) })
        descriptionScroll.apply {
            addView(information); isFocusable = true
            isVerticalScrollBarEnabled = true; overScrollMode = OVER_SCROLL_NEVER
            contentDescription = context.getString(R.string.discovery_description)
        }
        preview.addView(descriptionScroll, LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        action.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 14f; minWidth = context.dp(150); maxWidth = context.dp(270) }
        actions.addView(action, LayoutParams(-2, context.dp(40)))
        actions.addView(favoriteAction, LayoutParams(context.dp(44), context.dp(40)).apply { leftMargin = context.dp(8) })
        preview.addView(actions, LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        columns.addView(preview, LayoutParams(0, -1, .95f))
        list.apply {
            layoutManager = LinearLayoutManager(context); adapter = rows; itemAnimator = null
            isFocusable = false; clipToPadding = false
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            isVerticalScrollBarEnabled = false
        }
        columns.addView(list, LayoutParams(0, -1, 1.05f))
        body.addView(columns, FrameLayout.LayoutParams(-1, -1))
        empty.apply { orientation = VERTICAL; gravity = Gravity.CENTER; visibility = GONE }
        emptyTitle.gravity = Gravity.CENTER; emptyCopy.gravity = Gravity.CENTER
        empty.addView(emptyTitle)
        empty.addView(emptyCopy, LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        empty.addView(openChannels, LayoutParams(-2, context.dp(40)).apply { topMargin = context.dp(20) })
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        addView(body, LayoutParams(-1, 0, 1f))
        hint.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        addView(hint, LayoutParams(-1, context.dp(20)).apply { topMargin = context.dp(9) })
        restoreState(null)
    }

    private fun control(label: String, action: () -> Unit) = TvStyle.text(context, label, 12f, TvStyle.muted, true).apply {
        id = View.generateViewId(); isFocusable = true; isClickable = true; gravity = Gravity.CENTER
        TvStyle.buttonSemantics(this)
        background = TvStyle.focusBackground(context)
        setPadding(context.dp(14), context.dp(5), context.dp(14), context.dp(5))
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            setTextColor(if (focused) TvStyle.background else TvStyle.muted)
            styleControls()
        }
    }

    fun submit(channels: List<Channel>, programmes: Map<String, List<Programme>>) {
        val focus = list.hasFocus() || pendingFocus
        controller.submit(channels, programmes, channels.filter(favorite).map { it.id }.toSet(), System.currentTimeMillis())
        render(focus)
    }
    fun tick() { controller.tick(System.currentTimeMillis()); render(list.hasFocus() || pendingFocus) }
    fun setStatus(value: String) { guideStatus = value; updateHint() }
    fun showMessage(value: String) { hint.text = value }
    fun refreshLogos() { controller.selected?.let { logo.bind(it.channel) }; rows.refreshLogos() }
    fun selectChannel(id: String) {
        if (controller.selectChannel(id)) { render(true) }
        else showMessage(context.getString(R.string.discovery_channel_missing))
    }
    fun page(direction: Int) { controller.move(direction * (list.height / context.dp(62)).coerceAtLeast(1)); render(true) }

    fun saveState(out: Bundle) {
        out.putString("discovery_period", controller.period.name)
        out.putBoolean("discovery_favorites", controller.favoritesOnly)
        out.putString("discovery_query", controller.query)
        out.putString("discovery_selected", controller.selectedKey)
        out.putBoolean("discovery_search", searchVisible)
        savePreferences()
    }
    fun restoreState(saved: Bundle?) {
        val period = saved?.getString("discovery_period") ?: prefs.getString("period", DiscoveryPeriod.NOW.name)
        controller.restore(runCatching { DiscoveryPeriod.valueOf(period.orEmpty()) }.getOrDefault(DiscoveryPeriod.NOW),
            saved?.getBoolean("discovery_favorites") ?: prefs.getBoolean("favorites_only", false),
            saved?.getString("discovery_query") ?: prefs.getString("query", "").orEmpty(),
            saved?.getString("discovery_selected") ?: prefs.getString("selected", null))
        searchVisible = saved?.getBoolean("discovery_search") ?: controller.query.isNotEmpty()
        writingQuery = true; searchInput.setText(controller.query); writingQuery = false
        searchRow.visibility = if (searchVisible) VISIBLE else GONE
    }
    private fun savePreferences() {
        prefs.edit().putString("period", controller.period.name).putBoolean("favorites_only", controller.favoritesOnly)
            .putString("query", controller.query).putString("selected", controller.selectedKey).apply()
    }
    fun deactivate() {
        focusGeneration++; pendingFocus = false; removeCallbacks(queryChanged); hideKeyboard()
        dismissDetails(); savePreferences()
    }
    fun dismissDetails() { restoringDialog = true; dialog?.dismiss(); dialog = null; restoringDialog = false }
    fun handleBack(): Boolean {
        if (searchVisible) { closeSearch(); return true }
        return false
    }

    private fun changePeriod(period: DiscoveryPeriod) { controller.changePeriod(period); render(true) }
    private fun toggleSearch() {
        if (searchVisible) { closeSearch(); return }
        searchVisible = true; searchRow.visibility = VISIBLE; styleControls(); searchInput.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
    }
    private fun closeSearch() {
        removeCallbacks(queryChanged); hideKeyboard(); searchVisible = false
        writingQuery = true; searchInput.setText(""); writingQuery = false
        controller.search(""); searchRow.visibility = GONE; render(false); searchControl.requestFocus()
    }
    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    private fun render(focus: Boolean) {
        date.text = SimpleDateFormat("EEE d MMM", Locale.ITALY).format(Date())
        subtitle.setText(when (controller.period) {
            DiscoveryPeriod.NOW -> R.string.discovery_subtitle_now
            DiscoveryPeriod.SOON -> R.string.discovery_subtitle_soon
            DiscoveryPeriod.TONIGHT -> R.string.discovery_subtitle_tonight
        })
        results.text = resources.getQuantityString(R.plurals.discovery_programme_count, controller.items.size, controller.items.size)
        val noResults = controller.items.isEmpty()
        empty.visibility = if (noResults) VISIBLE else GONE
        preview.visibility = if (noResults) INVISIBLE else VISIBLE
        list.visibility = if (noResults) INVISIBLE else VISIBLE
        emptyTitle.setText(if (controller.query.isNotBlank()) R.string.discovery_empty_search else R.string.discovery_empty)
        emptyCopy.setText(when {
            controller.query.isNotBlank() -> R.string.discovery_empty_search_copy
            controller.favoritesOnly -> R.string.discovery_empty_favorites_copy
            else -> R.string.discovery_empty_copy
        })
        rows.submit(controller.items)
        updatePreview(); styleControls(); updateHint()
        if (focus && isShown) focusSelection()
    }

    private fun updatePreview() {
        val item = controller.selected ?: return
        val now = System.currentTimeMillis(); val p = item.programme
        logo.bind(item.channel)
        channel.text = listOfNotNull(item.channel.number.takeIf { it != 9999 }?.toString(), item.channel.name,
            "★".takeIf { favorite(item.channel) }).joinToString(" · ")
        stateLabel.setText(if (item.isLive(now)) R.string.discovery_live else R.string.discovery_next)
        title.text = p.title
        schedule.text = "${time(p.start)}–${time(p.end)} · ${status(item, now)}"
        progress.visibility = if (item.isLive(now)) VISIBLE else INVISIBLE
        progress.progress = item.progress(now)
        val copy = p.description.ifBlank { context.getString(R.string.discovery_no_description) }
        if (boundKey != item.key || description.text.toString() != copy) {
            description.text = copy; descriptionScroll.scrollTo(0, 0); boundKey = item.key
        }
        action.setText(when {
            item.isLive(now) -> R.string.discovery_watch
            reminders.contains(item) -> R.string.discovery_reminder_active
            else -> R.string.discovery_remind
        })
        action.contentDescription = "${action.text}, ${p.title}, ${item.channel.name}"
        favoriteAction.contentDescription = context.getString(if (favorite(item.channel)) R.string.discovery_remove_favorite else R.string.discovery_add_favorite, item.channel.name)
    }

    private fun styleControls() {
        listOf(nowControl to (controller.period == DiscoveryPeriod.NOW), soonControl to (controller.period == DiscoveryPeriod.SOON),
            tonightControl to (controller.period == DiscoveryPeriod.TONIGHT), favoritesControl to controller.favoritesOnly,
            searchControl to searchVisible).forEach { (view, selected) ->
            val focused = view.hasFocus()
            view.background = TvStyle.focusBackground(context, selected)
            view.setTextColor(if (focused) TvStyle.background else if (selected) TvStyle.white else TvStyle.muted)
            view.isSelected = selected
        }
        action.background = TvStyle.focusBackground(context, primary = true)
        action.setTextColor(TvStyle.background)
        favoriteAction.background = TvStyle.focusBackground(context)
        val isFavorite = controller.selected?.channel?.let(favorite) == true
        favoriteAction.text = if (isFavorite) "★" else "☆"
        favoriteAction.setTextColor(if (favoriteAction.hasFocus()) TvStyle.background else if (isFavorite) TvStyle.accent else TvStyle.muted)
        openChannels.background = TvStyle.focusBackground(context, true)
        openChannels.setTextColor(if (openChannels.hasFocus()) TvStyle.background else TvStyle.white)
    }
    private fun updateHint() {
        hint.text = guideStatus.ifBlank { context.getString(if (controller.selected?.isLive(System.currentTimeMillis()) != false)
            R.string.discovery_hint_live else R.string.discovery_hint_future) }
    }
    private fun activate(selection: DiscoveryProgramme? = controller.selected) {
        val item = selection ?: return
        val now = System.currentTimeMillis()
        if (item.isLive(now)) choose(item.channel)
        else if (item.programme.start > now) {
            val added = reminders.toggle(item, now)
            updatePreview(); rows.submit(controller.items)
            showMessage(context.getString(if (added) R.string.discovery_reminder_saved else R.string.discovery_reminder_removed))
        } else { controller.tick(now); render(true) }
    }
    private fun details() {
        val item = controller.selected ?: return
        dismissDetails()
        val copy = TvStyle.text(context, item.programme.description.ifBlank { context.getString(R.string.discovery_no_description) }, 16f)
            .apply { setPadding(context.dp(24), context.dp(14), context.dp(24), context.dp(14)) }
        val scroll = ScrollView(context).apply { addView(copy); isFocusable = true }
        dialog = AlertDialog.Builder(context).setTitle(item.programme.title)
            .setMessage("${item.channel.name} · ${time(item.programme.start)}–${time(item.programme.end)}")
            .setView(scroll).setPositiveButton(action.text) { _, _ -> activate(item) }
            .setNegativeButton(R.string.discovery_close, null).create().also { window ->
                window.setOnDismissListener { dialog = null; if (!restoringDialog && isShown) focusSelection() }
                window.show()
            }
    }

    fun focusSelection() {
        if (controller.items.isEmpty()) { pendingFocus = false; openChannels.requestFocusFromTouch(); return }
        val generation = ++focusGeneration
        val index = controller.items.indexOfFirst { it.key == controller.selectedKey }.coerceAtLeast(0)
        val holder = list.findViewHolderForAdapterPosition(index)
        if (holder?.itemId == rows.getItemId(index) && holder.itemView.isShown && holder.itemView.top >= list.paddingTop && holder.itemView.bottom <= list.height - list.paddingBottom) {
            pendingFocus = false; holder.itemView.requestFocusFromTouch(); return
        }
        pendingFocus = true
        list.doOnNextLayout {
            if (isShown && generation == focusGeneration) {
                pendingFocus = false
                list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocusFromTouch()
            }
        }
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, context.dp(3))
        list.requestLayout()
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!isShown || dialog?.isShowing == true || event.action != KeyEvent.ACTION_DOWN) return false
        val key = event.keyCode
        if (isEditing) return false
        if (key == KeyEvent.KEYCODE_INFO && controller.selected != null) { details(); return true }
        if (key == KeyEvent.KEYCODE_SEARCH) { toggleSearch(); return true }
        if (list.hasFocus() || pendingFocus) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (controller.items.firstOrNull()?.key == controller.selectedKey) {
                        pendingFocus = false; focusGeneration++; periodControl().requestFocus()
                    } else { controller.move(-1); render(true) }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { controller.move(1); render(true); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { pendingFocus = false; focusGeneration++; action.requestFocus(); return true }
            }
        }
        val control = controls.firstOrNull { it.hasFocus() }
        if (control != null) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val next = (controls.indexOf(control) + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, controls.lastIndex)
                    controls[next].requestFocus(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> { exitUp(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { focusSelection(); return true }
            }
        }
        if (action.hasFocus() || favoriteAction.hasFocus()) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (action.hasFocus()) favoriteAction.requestFocus() else focusSelection(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { action.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (descriptionScroll.canScrollVertically(-1) || descriptionScroll.canScrollVertically(1)) descriptionScroll.requestFocus()
                    else periodControl().requestFocus()
                    return true
                }
            }
        }
        if (descriptionScroll.hasFocus()) {
            when {
                key == KeyEvent.KEYCODE_DPAD_RIGHT -> { focusSelection(); return true }
                key == KeyEvent.KEYCODE_DPAD_UP && !descriptionScroll.canScrollVertically(-1) -> { periodControl().requestFocus(); return true }
                key == KeyEvent.KEYCODE_DPAD_DOWN && !descriptionScroll.canScrollVertically(1) -> { action.requestFocus(); return true }
            }
        }
        if (openChannels.hasFocus() && key == KeyEvent.KEYCODE_DPAD_UP) { periodControl().requestFocus(); return true }
        return false
    }

    private fun periodControl() = when (controller.period) { DiscoveryPeriod.NOW -> nowControl; DiscoveryPeriod.SOON -> soonControl; DiscoveryPeriod.TONIGHT -> tonightControl }
    private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(value))
    private fun status(item: DiscoveryProgramme, now: Long): String = when {
        item.endingSoon(now) -> context.getString(R.string.discovery_ending)
        item.isLive(now) -> context.getString(R.string.discovery_remaining, item.remainingMinutes(now))
        else -> context.getString(R.string.discovery_starts_in, item.startsInMinutes(now))
    }

    private inner class ProgrammeAdapter : RecyclerView.Adapter<ProgrammeAdapter.Holder>() {
        private var items = emptyList<DiscoveryProgramme>()
        private var selected: String? = null
        init { setHasStableIds(true) }
        inner class Holder(val row: LinearLayout, val logo: ChannelLogoView, val title: TextView, val meta: TextView, val status: TextView) : RecyclerView.ViewHolder(row)
        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].key.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }
        fun submit(next: List<DiscoveryProgramme>) {
            val old = items; val oldSelected = selected; val newSelected = controller.selectedKey
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(a: Int, b: Int) = old[a].key == next[b].key
                override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b] && (old[a].key == oldSelected) == (next[b].key == newSelected)
            })
            items = next; selected = newSelected; diff.dispatchUpdatesTo(this)
            if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, "status")
        }
        fun refreshLogos() { if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, "logo") }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; isLongClickable = true
                descendantFocusability = FOCUS_BLOCK_DESCENDANTS
                setPadding(context.dp(11), context.dp(9), context.dp(11), context.dp(9))
                val extra = ((resources.configuration.fontScale - 1f).coerceAtLeast(0f) * 38).toInt()
                layoutParams = RecyclerView.LayoutParams(-1, context.dp(58 + extra)).apply { bottomMargin = context.dp(6) }
            }
            val logo = ChannelLogoView(context, logos, compact = true)
            row.addView(logo, LayoutParams(context.dp(36), context.dp(30)).apply { rightMargin = context.dp(10) })
            val copy = LinearLayout(context).apply { orientation = VERTICAL }
            val title = TvStyle.text(context, "", 14f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            TvStyle.buttonSemantics(row)
            val meta = TvStyle.text(context, "", 12f, TvStyle.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; fontFeatureSettings = "tnum" }
            copy.addView(title); copy.addView(meta, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            row.addView(copy, LayoutParams(0, -2, 1f))
            val status = TvStyle.text(context, "", 12f, TvStyle.accent).apply { gravity = Gravity.END; maxLines = 2 }
            row.addView(status, LayoutParams(context.dp(76), -2).apply { leftMargin = context.dp(8) })
            val holder = Holder(row, logo, title, meta, status)
            row.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    val position = holder.bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        controller.select(items[position].key); selected = controller.selectedKey
                        updatePreview(); updateHint(); restyleVisible()
                    }
                } else styleRow(holder)
            }
            row.setOnClickListener {
                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { controller.select(items[it].key); activate() }
            }
            row.setOnLongClickListener {
                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { toggleFavorite(items[it].channel) }; true
            }
            return holder
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.logo.bind(item.channel); holder.title.text = item.programme.title
            holder.meta.text = "${item.channel.name} · ${time(item.programme.start)}–${time(item.programme.end)}"
            bindStatus(holder, item)
        }
        private fun bindStatus(holder: Holder, item: DiscoveryProgramme) {
            holder.status.text = if (reminders.contains(item)) "✓" else status(item, System.currentTimeMillis())
            holder.row.contentDescription = "${item.channel.name}, ${item.programme.title}, ${time(item.programme.start)}–${time(item.programme.end)}, ${status(item, System.currentTimeMillis())}" +
                if (reminders.contains(item)) ", ${context.getString(R.string.discovery_reminder_active)}" else ""
            styleRow(holder)
        }
        private fun styleRow(holder: Holder) {
            val position = holder.bindingAdapterPosition
            val selected = items.getOrNull(position)?.key == controller.selectedKey
            holder.row.isSelected = selected
            holder.row.background = StateListDrawable().apply {
                val radius = context.dp(12).toFloat()
                addState(intArrayOf(android.R.attr.state_pressed), TvStyle.rounded(TvStyle.raised, radius, TvStyle.accent, context.dp(1)))
                addState(intArrayOf(), TvStyle.rounded(if (selected) 0xFF26364A.toInt() else 0xFF171C24.toInt(), radius,
                    if (holder.row.hasFocus() || selected) TvStyle.accent else Color.TRANSPARENT, context.dp(1)))
            }
        }
        private fun restyleVisible() {
            for (i in 0 until list.childCount) (list.getChildViewHolder(list.getChildAt(i)) as? Holder)?.let(::styleRow)
        }
        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) super.onBindViewHolder(holder, position, payloads)
            else {
                if ("logo" in payloads) holder.logo.bind(items[position].channel)
                if ("status" in payloads) bindStatus(holder, items[position])
            }
        }
        override fun onViewRecycled(holder: Holder) { holder.logo.unbind(); super.onViewRecycled(holder) }
    }
}
