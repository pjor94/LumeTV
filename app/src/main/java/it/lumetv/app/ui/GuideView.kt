package it.lumetv.app.ui

import android.app.Dialog
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.GuideNavigator
import it.lumetv.app.data.GuideSchedule
import it.lumetv.app.data.GuideSlot
import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.R
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Native TV guide. Time and channel selection do not depend on asynchronously laid-out views. */
@SuppressLint("ViewConstructor")
class GuideView(
    context: Context,
    private val logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    private val choose: (Channel) -> Unit,
    private val toggleFavorite: (Channel) -> Unit,
    private val exitUp: () -> Unit,
) : LinearLayout(context) {
    private val navigation = GuideNavigator()
    private val eyebrow = TvStyle.text(context, "", 11f, TvStyle.accent, true)
    private val title = TvStyle.text(context, "", 27f, bold = true)
    private val timing = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val date = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val hint = TvStyle.text(context, "", 11f, TvStyle.muted)
    private val guideStatus = TvStyle.text(context, "", 10f, TvStyle.muted)
    private val all = control("Tutti") { changeFilter(false) }
    private val favorites = control("★ Preferiti") { changeFilter(true) }
    private val nowButton = control("Adesso") { jump(System.currentTimeMillis()) }
    private val evening = control("Stasera") { jump(GuideSchedule.evening(System.currentTimeMillis())) }
    private val earlier = control("‹", "Ora precedente") { navigation.pageTime(-1); render(true) }
    private val later = control("›", "Ora successiva") { navigation.pageTime(1); render(true) }
    private val watch = control("Guarda il canale") { navigation.channel?.let(choose) }
    private val controls get() = listOf(all, favorites, nowButton, evening, earlier, later)
    private var currentId: String? = null
    private var gridActive = false
    private var rendering = false
    private var focusGeneration = 0
    private var dialog: Dialog? = null
    private var suppressDialogFocus = false
    private var statusMessage = ""
    private val timeline = GuideTimelineView(context, logos, navigation,
        onSelected = { channel, slot ->
            if (!rendering) {
                gridActive = true
                navigation.select(channel, slot)
                updateDetails()
            }
        },
        onActivate = { channel, slot ->
            navigation.select(channel, slot)
            val show = slot.programme
            val now = System.currentTimeMillis()
            if (show == null || show.start <= now && show.end > now) choose(channel)
            else showDetails(channel, show)
        },
        onFavorite = { channel -> toggleFavorite(channel) },
        onRowsChanged = { count ->
            navigation.resizeRows(count)
            post { if (isShown) render(gridActive) }
        })

    init {
        orientation = VERTICAL
        setPadding(0, context.dp(12), 0, 0)
        clipChildren = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        val detail = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val copy = LinearLayout(context).apply { orientation = VERTICAL }
        eyebrow.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; letterSpacing = 0.05f }
        title.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        timing.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; fontFeatureSettings = "tnum" }
        copy.addView(eyebrow)
        copy.addView(title, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
        copy.addView(timing, LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        detail.addView(copy, LayoutParams(0, -2, 1f))
        detail.addView(watch, LayoutParams(-2, context.dp(34)).apply { leftMargin = context.dp(20) })
        addView(detail, LayoutParams(-1, context.dp(72 + ((resources.configuration.fontScale - 1f).coerceAtLeast(0f) * 48).toInt())))
        val toolbar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val filters = LinearLayout(context).apply {
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            background = TvStyle.rounded(0xB2181D26.toInt(), context.dp(22).toFloat())
        }
        filters.addView(all, LayoutParams(-2, -1))
        filters.addView(favorites, LayoutParams(-2, -1))
        toolbar.addView(filters, LayoutParams(-2, context.dp(36)))
        toolbar.addView(View(context), LayoutParams(0, 1, 1f))
        date.apply { maxLines = 1; fontFeatureSettings = "tnum"; gravity = Gravity.END }
        toolbar.addView(date, LayoutParams(-2, -2).apply { rightMargin = context.dp(12) })
        listOf(nowButton, evening, earlier, later).forEach { button ->
            toolbar.addView(button, LayoutParams(-2, context.dp(34)).apply { leftMargin = context.dp(4) })
        }
        addView(toolbar, LayoutParams(-1, context.dp(36)).apply { topMargin = context.dp(7) })
        addView(timeline, LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(12) })
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        hint.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        guideStatus.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END }
        footer.addView(hint, LayoutParams(0, -2, 1.25f))
        footer.addView(guideStatus, LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(12) })
        addView(footer, LayoutParams(-1, context.dp(24)).apply { topMargin = context.dp(7) })
    }

    fun submit(channels: List<Channel>, programmes: Map<String, List<Programme>>, currentId: String?) {
        this.currentId = currentId
        val focusedGrid = gridActive && isShown
        navigation.submit(channels, programmes, channels.filter(favorite).map { it.id }.toSet(),
            currentId, System.currentTimeMillis())
        render(focusedGrid)
    }

    fun focusSelection() {
        if (navigation.channels.isEmpty()) { gridActive = false; favorites.requestFocusFromTouch(); return }
        gridActive = true
        requestGridFocus()
    }

    fun tick() { navigation.tick(System.currentTimeMillis()); render(gridActive && isShown) }
    fun refreshLogos() = timeline.refreshLogos()
    fun showMessage(message: String) { guideStatus.text = message }
    fun setStatus(message: String) { statusMessage = message; updateDetails() }
    fun selectChannel(id: String) { navigation.selectChannel(id); render(true) }
    fun pageChannels(direction: Int) { if (navigation.moveChannel(direction, page = true)) render(true) }

    fun dismissDetails() {
        suppressDialogFocus = true
        dialog?.dismiss(); dialog = null
        suppressDialogFocus = false
    }

    fun deactivate() { gridActive = false; focusGeneration++; dismissDetails() }

    fun saveState(out: Bundle) {
        out.putString("guide_channel", navigation.channelId)
        navigation.programmeStart?.let { out.putLong("guide_programme", it) }
        out.putLong("guide_anchor", navigation.anchor)
        out.putLong("guide_window", navigation.windowStart)
        out.putBoolean("guide_favorites", navigation.favoritesOnly)
        out.putInt("guide_row", navigation.rowStart)
    }

    fun restoreState(saved: Bundle?) {
        if (saved == null) return
        navigation.restore(saved.getString("guide_channel"),
            if (saved.containsKey("guide_programme")) saved.getLong("guide_programme") else null,
            saved.getLong("guide_anchor"), saved.getLong("guide_window"), saved.getBoolean("guide_favorites"),
            saved.getInt("guide_row"))
    }

    /** Called before the host's global keys. Only consume navigation inside this guide. */
    fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !isShown || dialog?.isShowing == true) return false
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_INFO) {
            val channel = navigation.channel
            val programme = navigation.programme
            if (channel != null && programme != null) { showDetails(channel, programme); return true }
        }
        if (gridActive && (timeline.hasFocus() || !controls.any { it.hasFocus() } && !watch.hasFocus())) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!navigation.moveChannel(-1)) {
                        gridActive = false; focusGeneration++
                        (if (navigation.favoritesOnly) favorites else all).requestFocus()
                    } else render(true)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (navigation.moveChannel(1)) render(true); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (navigation.moveProgramme(-1)) render(true); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (navigation.moveProgramme(1)) render(true); return true }
            }
        }
        val focused = (controls + watch).indexOfFirst { it.hasFocus() }
        if (focused >= 0) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { focusSelection(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    gridActive = false; focusGeneration++
                    exitUp(); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val available = (controls + watch).filter { it.isShown && it.isEnabled }
                    val position = available.indexOfFirst { it.hasFocus() }
                    val target = (position + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, available.lastIndex)
                    available.getOrNull(target)?.requestFocus()
                    return true
                }
            }
        }
        return false
    }

    private fun control(label: String, description: String = label, action: () -> Unit) =
        TvStyle.text(context, label, 12f, TvStyle.muted, true).apply {
            id = View.generateViewId()
            isFocusable = true; isClickable = true
            gravity = Gravity.CENTER; contentDescription = description
            setPadding(context.dp(13), context.dp(5), context.dp(13), context.dp(5))
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                if (focused) { gridActive = false; focusGeneration++ }
                updateControls()
                timeline.updateFocus()
            }
        }

    private fun changeFilter(value: Boolean) { navigation.filterFavorites(value); render(true) }
    private fun jump(time: Long) { navigation.jump(time); render(true) }

    private fun render(focus: Boolean) {
        rendering = true
        updateDetails()
        timeline.bind(currentId)
        updateControls()
        rendering = false
        if (focus && isShown) focusSelection()
    }

    private fun requestGridFocus() {
        val generation = ++focusGeneration
        val target = timeline.selectedView
        if (target != null && target.isLaidOut) { target.requestFocus(); return }
        timeline.doOnNextLayout {
            if (isShown && gridActive && generation == focusGeneration) timeline.selectedView?.requestFocus()
        }
        timeline.requestLayout()
    }

    private fun updateControls() {
        listOf(all to !navigation.favoritesOnly, favorites to navigation.favoritesOnly).forEach { (view, selected) ->
            styleControl(view, selected)
            view.isSelected = selected
        }
        earlier.isEnabled = navigation.canGoEarlier; earlier.alpha = if (earlier.isEnabled) 1f else 0.35f
        later.isEnabled = navigation.canGoLater; later.alpha = if (later.isEnabled) 1f else 0.35f
        listOf(nowButton, evening, earlier, later, watch).forEach { styleControl(it, it === nowButton) }
    }

    private fun styleControl(view: TextView, selected: Boolean) {
        val focused = view.hasFocus()
        view.setTextColor(if (focused) TvStyle.background else if (selected) TvStyle.white else TvStyle.muted)
        view.background = TvStyle.rounded(if (focused) TvStyle.white else if (selected) TvStyle.raised else Color.TRANSPARENT,
            context.dp(20).toFloat())
    }

    private fun updateDetails() {
        val now = System.currentTimeMillis()
        val channel = navigation.channel
        val show = navigation.programme
        val live = show != null && show.start <= now && show.end > now
        val state = when { show == null -> "GUIDA NON DISPONIBILE"; live -> "IN ONDA"; show.start > now -> "A SEGUIRE"; else -> "TERMINATO" }
        eyebrow.text = channel?.let { it.name + "  ·  " + state } ?: "GUIDA TV"
        title.text = show?.title ?: if (navigation.channels.isEmpty() && navigation.favoritesOnly) "La guida dei tuoi preferiti."
            else if (channel != null) "Programmazione non disponibile" else "La tua prossima serata."
        timing.text = when {
            show != null -> programmeTime(show) + "  ·  " + when {
                live -> ((show.end - now + 59_999) / GuideSchedule.MINUTE).toString() + " min rimanenti"
                show.start > now -> dayPrefix(show.start, now) + "alle " + time(show.start)
                else -> "Programma terminato"
            }
            channel != null -> "OK per guardare il canale in diretta"
            navigation.favoritesOnly -> "Aggiungi un preferito tenendo premuto OK su un canale."
            else -> "Carico i canali…"
        }
        watch.visibility = if (channel == null) GONE else VISIBLE
        watch.text = if (live) context.getString(R.string.guide_watch_channel, channel?.name.orEmpty())
            else context.getString(R.string.guide_watch_now)
        watch.contentDescription = watch.text
        val zone = ZoneId.systemDefault()
        val dateWindow = navigation.windowStart.takeIf { it > 0 } ?: GuideSchedule.halfHour(now)
        val first = Instant.ofEpochMilli(dateWindow).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(dateWindow + GuideSchedule.WINDOW - 1).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        date.text = when { first != last -> when {
                first == today && last == today.plusDays(1) -> "Oggi → domani"
                first == today.minusDays(1) && last == today -> "Ieri → oggi"
                else -> first.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALY)) + " → " + last.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALY))
            }; first == today -> "Oggi, " + first.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALY))
            first == today.plusDays(1) -> "Domani, " + first.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALY))
            else -> first.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALY)) }
        hint.setText(when {
            show == null -> R.string.guide_hint_missing
            live -> R.string.guide_hint_live
            else -> R.string.guide_hint_future
        })
        guideStatus.text = statusMessage.ifBlank { navigation.covered.toString() + " di " + navigation.channels.size + " canali con guida" }
    }

    private fun showDetails(channel: Channel, show: Programme) {
        dismissDetails()
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(context.dp(28), context.dp(24), context.dp(28), context.dp(24))
            background = TvStyle.rounded(0xFF1B2431.toInt(), context.dp(24).toFloat(), 0x26FFFFFF, context.dp(1))
        }
        panel.addView(TvStyle.text(context, channel.name + "  ·  " + SimpleDateFormat("EEE d MMM", Locale.ITALY).format(Date(show.start)), 12f, TvStyle.accent, true))
        panel.addView(TvStyle.text(context, show.title, 27f, bold = true), LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        panel.addView(TvStyle.text(context, programmeTime(show) + "  ·  " + ((show.end - show.start) / GuideSchedule.MINUTE) + " min", 14f, TvStyle.muted),
            LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        if (show.description.isNotBlank()) {
            val description = TvStyle.text(context, show.description, 16f).apply { setLineSpacing(context.dp(3).toFloat(), 1f) }
            val scroll = object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(context.dp(160), MeasureSpec.AT_MOST))
                }
            }.apply { addView(description); isFocusable = true }
            panel.addView(scroll, LayoutParams(-1, -2).apply { topMargin = context.dp(18) })
        }
        val current = ProgrammeWindow.at(navigation.shows(channel), System.currentTimeMillis()).current
        if (show.start > System.currentTimeMillis() && current != null) panel.addView(
            TvStyle.text(context, "Ora su " + channel.name + ": " + current.title, 13f, TvStyle.muted),
            LayoutParams(-1, -2).apply { topMargin = context.dp(16) })
        val actions = LinearLayout(context)
        lateinit var details: Dialog
        val play = TvStyle.button(context, "Guarda " + channel.name + " ora") {
            suppressDialogFocus = true; details.dismiss(); suppressDialogFocus = false; choose(channel)
        }
        val back = TvStyle.button(context, "Torna alla guida") { details.dismiss() }
        actions.addView(play, LayoutParams(-2, context.dp(42)))
        actions.addView(back, LayoutParams(-2, context.dp(42)).apply { leftMargin = context.dp(12) })
        panel.addView(actions, LayoutParams(-1, -2).apply { topMargin = context.dp(24) })
        details = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(panel)
            setOnDismissListener {
                dialog = null
                if (!suppressDialogFocus && isShown) focusSelection()
            }
        }
        dialog = details
        details.show()
        details.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(context.dp(minOf(620, context.resources.configuration.screenWidthDp - 92)), -2)
            setDimAmount(0.55f)
        }
        back.requestFocus()
    }

    private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(value))
    private fun programmeTime(show: Programme) = time(show.start) + "–" + time(show.end)
    private fun dayPrefix(value: Long, now: Long): String {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(value).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) { today -> "Inizia "; today.plusDays(1) -> "Domani "; else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM ", Locale.ITALY)) }
    }
}

/** Only the visible rows are materialized; every row shares one origin and scale. */
@SuppressLint("ViewConstructor")
private class GuideTimelineView(
    context: Context,
    private val logos: ChannelLogoRepository,
    private val navigation: GuideNavigator,
    private val onSelected: (Channel, GuideSlot) -> Unit,
    private val onActivate: (Channel, GuideSlot) -> Unit,
    private val onFavorite: (Channel) -> Unit,
    private val onRowsChanged: (Int) -> Unit,
) : FrameLayout(context) {
    private val axisHeight = context.dp(26)
    private val laneLeft = context.dp(164)
    private val gap = context.dp(5)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeRect = RectF()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALY)
    private val axisTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private var axisTicks = emptyList<Pair<Long, String>>()
    private var nowLabel = ""
    private val cells = mutableListOf<Cell>()
    private val logoViews = mutableListOf<ChannelLogoView>()
    private var currentId: String? = null
    private var binding = false
    var selectedView: View? = null
        private set
    private data class Cell(val view: LinearLayout, val title: TextView, val timing: TextView,
                            val channel: Channel, val slot: GuideSlot)

    init { setWillNotDraw(false); clipChildren = true; isFocusable = false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fontExtra = ((resources.configuration.fontScale - 1f).coerceAtLeast(0f) * context.dp(34)).toInt()
        val count = ((h - axisHeight + gap) / (context.dp(44) + fontExtra + gap)).coerceIn(1, 5)
        onRowsChanged(count)
    }

    fun bind(currentId: String?) {
        if (width <= laneLeft || height <= axisHeight) { this.currentId = currentId; return }
        binding = true
        this.currentId = currentId
        timeFormat.timeZone = TimeZone.getDefault()
        nowLabel = timeFormat.format(Date())
        axisTicks = generateSequence(GuideSchedule.halfHour(navigation.windowStart)) { it + GuideSchedule.HALF_HOUR }
            .takeWhile { it <= navigation.windowStart + GuideSchedule.WINDOW }
            .map { it to timeFormat.format(Date(it)) }.toList()
        logoViews.forEach { it.unbind() }
        removeAllViews(); cells.clear(); logoViews.clear(); selectedView = null
        val rows = navigation.channels.drop(navigation.rowStart).take(navigation.rowCount)
        if (rows.isEmpty()) {
            val empty = TvStyle.text(context, "Nessun canale in questa vista", 17f, TvStyle.muted).apply { gravity = Gravity.CENTER }
            addView(empty, LayoutParams(-1, -1))
        }
        val rowHeight = ((height - axisHeight - gap * (navigation.rowCount - 1)) / navigation.rowCount).coerceAtLeast(1)
        val laneWidth = width - laneLeft
        rows.forEachIndexed { index, channel ->
            val top = axisHeight + index * (rowHeight + gap)
            val brand = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(5), 0, context.dp(5), 0)
                background = TvStyle.rounded(if (channel.id == navigation.channelId) 0xFF1C2634.toInt() else Color.TRANSPARENT, context.dp(9).toFloat())
                contentDescription = channel.name + ", guarda la diretta"
                setOnClickListener { onActivate(channel, GuideSlot(navigation.anchor, navigation.anchor + 1, null)) }
                setOnLongClickListener { onFavorite(channel); true }
            }
            brand.addView(TvStyle.text(context, if (channel.number == 9999) "" else "%02d".format(channel.number), 10f, TvStyle.muted).apply { fontFeatureSettings = "tnum" },
                LinearLayout.LayoutParams(context.dp(20), -2))
            val logo = ChannelLogoView(context, logos, compact = true).apply { bind(channel) }
            logoViews += logo
            brand.addView(logo, LinearLayout.LayoutParams(context.dp(32), context.dp(32)).apply { rightMargin = context.dp(7) })
            brand.addView(TvStyle.text(context, channel.name + if (channel.id == currentId) " ●" else "", 14f, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(brand, LayoutParams(laneLeft - context.dp(8), rowHeight).apply { topMargin = top })
            navigation.slots(channel).forEach { slot ->
                val left = laneLeft + ((slot.start - navigation.windowStart).toDouble() / GuideSchedule.WINDOW * laneWidth).toInt()
                val right = laneLeft + ((slot.end - navigation.windowStart).toDouble() / GuideSchedule.WINDOW * laneWidth).toInt()
                val cellWidth = (right - left - context.dp(3)).coerceAtLeast(1)
                val view = LinearLayout(context).apply {
                    id = View.generateViewId(); orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                    isFocusableInTouchMode = true; isClickable = true; isLongClickable = true
                    setPadding(context.dp(9), context.dp(5), context.dp(9), context.dp(5))
                }
                val show = slot.programme
                val label = TvStyle.text(context, show?.title ?: "Programmazione non disponibile", if (show != null) 16f else 13f,
                    if (show != null) TvStyle.white else TvStyle.muted, show != null).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }
                val timing = TvStyle.text(context, show?.let { time(it.start) + "–" + time(it.end) } ?: "OK per la diretta", 10f, TvStyle.muted).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; fontFeatureSettings = "tnum"
                }
                if (cellWidth < context.dp(42) || show == null && cellWidth < context.dp(100)) {
                    view.setPadding(0, 0, 0, 0)
                    label.text = if (show == null) "—" else "·"; label.gravity = Gravity.CENTER
                    timing.visibility = GONE
                }
                view.addView(label, LinearLayout.LayoutParams(-1, -2))
                view.addView(timing, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(3) })
                val cell = Cell(view, label, timing, channel, slot)
                cells += cell
                view.contentDescription = channel.name + ", " + (show?.title ?: "Programmazione non disponibile") +
                    (show?.let { ", " + time(it.start) + "–" + time(it.end) } ?: ", OK per la diretta")
                view.setOnClickListener { onActivate(channel, slot) }
                view.setOnLongClickListener { onFavorite(channel); true }
                view.setOnFocusChangeListener { _, focused ->
                    if (focused && !binding) {
                        onSelected(channel, slot)
                        selectedView = view
                    }
                    updateFocus()
                }
                addView(view, LayoutParams(cellWidth, rowHeight).apply { leftMargin = left; topMargin = top })
                if (channel.id == navigation.channelId && navigation.selected(slot)) selectedView = view
            }
        }
        binding = false
        updateFocus()
        invalidate()
    }

    fun updateFocus() {
        val now = System.currentTimeMillis()
        cells.forEach { cell ->
            val focused = cell.view.hasFocus()
            val selected = cell.channel.id == navigation.channelId && navigation.selected(cell.slot)
            val live = cell.slot.programme?.let { it.start <= now && it.end > now } == true
            cell.view.background = TvStyle.rounded(when {
                focused -> TvStyle.white
                selected -> 0xFF354961.toInt()
                live -> 0xFF243244.toInt()
                cell.slot.programme == null -> 0xFF141A23.toInt()
                else -> 0xFF1D232D.toInt()
            }, context.dp(9).toFloat(), if (focused || selected) TvStyle.accent else Color.TRANSPARENT,
                context.dp(if (focused) 2 else 1))
            cell.title.setTextColor(if (focused) TvStyle.background else if (cell.slot.programme == null) TvStyle.muted else TvStyle.white)
            cell.timing.setTextColor(if (focused) 0xFF495363.toInt() else TvStyle.muted)
        }
    }

    fun refreshLogos() = logoViews.forEachIndexed { index, logo ->
        navigation.channels.drop(navigation.rowStart).getOrNull(index)?.let(logo::bind)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        val laneWidth = width - laneLeft
        if (laneWidth <= 0 || navigation.windowStart == 0L) return
        paint.typeface = axisTypeface
        paint.textSize = context.dp(11).toFloat() * resources.configuration.fontScale
        paint.color = TvStyle.muted
        canvas.drawText("CANALI", context.dp(5).toFloat(), context.dp(16).toFloat(), paint)
        val nowX = laneLeft + ((now - navigation.windowStart).toDouble() / GuideSchedule.WINDOW * laneWidth).toFloat()
        val showNow = now >= navigation.windowStart && now < navigation.windowStart + GuideSchedule.WINDOW
        val badgeWidth = maxOf(context.dp(40).toFloat(), paint.measureText(nowLabel) + context.dp(12))
        val badgeX = nowX.coerceIn(laneLeft + badgeWidth / 2, width - badgeWidth / 2)
        axisTicks.forEach { (tick, text) ->
            val x = laneLeft + ((tick - navigation.windowStart).toDouble() / GuideSchedule.WINDOW * laneWidth).toFloat()
            val textWidth = paint.measureText(text)
            val textX = minOf(x, width - textWidth)
            if (x >= laneLeft && (!showNow || textX + textWidth < badgeX - badgeWidth / 2 - context.dp(5) || textX > badgeX + badgeWidth / 2 + context.dp(5))) {
                paint.color = TvStyle.muted
                canvas.drawText(text, textX, context.dp(16).toFloat(), paint)
            }
        }
        if (showNow) {
            paint.color = TvStyle.accent
            badgeRect.set(badgeX - badgeWidth / 2, context.dp(1).toFloat(), badgeX + badgeWidth / 2, context.dp(21).toFloat())
            canvas.drawRoundRect(badgeRect,
                context.dp(6).toFloat(), context.dp(6).toFloat(), paint)
            paint.color = TvStyle.background
            val text = nowLabel
            canvas.drawText(text, badgeX - paint.measureText(text) / 2, context.dp(15).toFloat(), paint)
        }
    }

    private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(value))
}
