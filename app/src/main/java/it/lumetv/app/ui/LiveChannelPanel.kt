package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.Programme
import it.lumetv.app.R
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A focus-contained channel browser above the existing, uninterrupted player. */
@SuppressLint("ViewConstructor")
class LiveChannelPanel(
    context: Context,
    logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    choose: (Channel) -> Unit,
    private val close: () -> Unit,
) : FrameLayout(context) {
    private val channels = LiveChannelAdapter(logos, favorite, choose)
    private val list = RecyclerView(context)
    private val count = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val clock = TvStyle.text(context, "", 12f, TvStyle.muted).apply { fontFeatureSettings = "tnum" }
    private val empty = TvStyle.text(context,
        "I tuoi canali, a portata di mano.\n\nTieni premuto OK su una scheda del catalogo per aggiungerla ai preferiti.", 15f, TvStyle.muted)
    private var allChannels = emptyList<Channel>()
    private var programmes = emptyMap<String, List<Programme>>()
    private var currentId: String? = null
    private var favoritesOnly = false
    private var focusGeneration = 0
    private var pendingFocusId: String? = null
    private val allTab = filter("Tutti", false)
    private val favoritesTab = filter("★  Preferiti", true)
    private val layout get() = list.layoutManager as LinearLayoutManager
    val isOpen get() = isVisible

    init {
        visibility = GONE
        isFocusable = false
        // Outside the floating surface, the picture retains its original brightness.
        setOnClickListener { close() }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(18), context.dp(20), context.dp(18), context.dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xE0181D26.toInt(), 0xBF121720.toInt())).apply {
                cornerRadius = context.dp(28).toFloat()
                setStroke(context.dp(1), 0x26FFFFFF)
            }
            elevation = context.dp(12).toFloat()
            // Consume pointer clicks on the surface without adding a remote focus target.
            setOnClickListener { }
            isFocusable = false
        }
        val eyebrow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        eyebrow.addView(TvStyle.text(context, "●  IN DIRETTA", 10f, TvStyle.accent, true).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(0, -2, 1f))
        eyebrow.addView(clock)
        panel.addView(eyebrow, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = context.dp(4); rightMargin = context.dp(4) })
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TvStyle.text(context, "Canali", 28f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(count)
        panel.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = context.dp(10); leftMargin = context.dp(4); rightMargin = context.dp(4)
        })
        val filters = LinearLayout(context).apply {
            clipChildren = false; clipToPadding = false
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            background = TvStyle.rounded(0x4006090E, context.dp(24).toFloat())
        }
        filters.addView(allTab, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = context.dp(4) })
        filters.addView(favoritesTab, LinearLayout.LayoutParams(0, -1, 1f))
        panel.addView(filters, LinearLayout.LayoutParams(-1, context.dp(44)).apply { topMargin = context.dp(14) })
        val body = FrameLayout(context)
        list.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = channels
            itemAnimator = null
            isFocusable = false
            clipToPadding = false
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(6))
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(context.dp(16))
        }
        body.addView(list, LayoutParams(-1, -1))
        empty.apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
        }
        body.addView(empty, LayoutParams(-1, -1))
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(12) })
        panel.addView(TvStyle.text(context, "←  Filtri    ↑↓  Scorri    OK  Guarda    →  Chiudi", 11f, TvStyle.muted).apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(8), context.dp(9), context.dp(8), context.dp(9))
            background = TvStyle.rounded(0xB30A0B0E.toInt(), context.dp(16).toFloat())
        },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        addView(panel, LayoutParams(context.dp(minOf(368, context.resources.configuration.screenWidthDp - 48)), -1, Gravity.START).apply {
            setMargins(context.dp(24), context.dp(24), 0, context.dp(24))
        })
    }

    private fun filter(label: String, favorites: Boolean) = TvStyle.button(context, label) {
        favoritesOnly = favorites
        updateFilters()
        showChannels(focusRows = true)
    }.apply {
        id = View.generateViewId()
        minHeight = 0
        setOnFocusChangeListener { view, focused ->
            updateFilters()
            TvStyle.focusMotion(view, focused, 1.015f)
        }
    }

    private fun updateFilters() {
        listOf(allTab to !favoritesOnly, favoritesTab to favoritesOnly).forEach { (tab, selected) ->
            tab.background = TvStyle.focusBackground(context, selected)
            tab.setTextColor(if (tab.hasFocus()) TvStyle.background else if (selected) TvStyle.white else TvStyle.muted)
            tab.isSelected = selected
            tab.contentDescription = "${tab.text}${if (selected) ", selezionato" else ""}"
        }
    }

    fun show(items: List<Channel>, programmes: Map<String, List<Programme>>, currentId: String?) {
        allChannels = items
        this.programmes = programmes
        this.currentId = currentId
        favoritesOnly = false
        visibility = VISIBLE
        updateFilters()
        showChannels(focusRows = true)
        refreshProgrammes()
    }

    fun dismiss() {
        focusGeneration++
        pendingFocusId = null
        list.stopScroll()
        visibility = GONE
        clearFocus()
    }

    fun update(items: List<Channel>, programmes: Map<String, List<Programme>>, currentId: String?) {
        if (!isOpen) return
        allChannels = items
        this.programmes = programmes
        this.currentId = currentId
        showChannels()
    }

    private fun showChannels(focusRows: Boolean = false) {
        val focusedId = list.findFocus()?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition }
            ?.takeIf { it != RecyclerView.NO_POSITION }?.let { channels.items.getOrNull(it)?.id }
        channels.submit(if (favoritesOnly) allChannels.filter(favorite) else allChannels, programmes, currentId)
        count.text = resources.getQuantityString(R.plurals.live_channel_count, channels.itemCount, channels.itemCount)
        empty.visibility = if (channels.items.isEmpty()) VISIBLE else GONE
        list.visibility = if (channels.items.isEmpty()) GONE else VISIBLE
        if (focusRows) focusChannel(currentId)
        else if (focusedId != null && channels.items.none { it.id == focusedId }) focusChannel(currentId)
    }

    private fun focusChannel(id: String?, anchor: Boolean = true) {
        val generation = ++focusGeneration
        pendingFocusId = null
        if (channels.items.isEmpty()) {
            (if (favoritesOnly) favoritesTab else allTab).requestFocus()
            return
        }
        val position = channels.items.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val holder = list.findViewHolderForAdapterPosition(position)
        // Reopening or leaving the filters often targets a row that is already laid out.
        // Focus it immediately, so the next remote event can keep navigating that row.
        if (holder?.itemId == channels.getItemId(position) && holder.itemView.isShown &&
            holder.itemView.top >= list.paddingTop && holder.itemView.bottom <= list.height - list.paddingBottom) {
            holder.itemView.requestFocus()
            return
        }
        pendingFocusId = channels.items[position].id
        // Layout the anchor before requesting focus; off-screen/current rows may not exist yet.
        list.doOnNextLayout {
            if (isOpen && generation == focusGeneration) {
                pendingFocusId = null
                list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
        if (anchor) layout.scrollToPositionWithOffset(position, context.dp(4))
        else layout.scrollToPosition(position)
    }

    fun refreshProgrammes() {
        if (!isOpen) return
        clock.text = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date())
        channels.refreshProgrammes()
    }

    fun refreshLogos() { if (isOpen) channels.refreshLogos() }

    fun handleDirection(keyCode: Int): Boolean {
        val pendingPosition = pendingFocusId?.let { id -> channels.items.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        val rowPosition = pendingPosition ?: list.findFocus()?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition }
            ?.takeIf { it != RecyclerView.NO_POSITION }
        // Navigate by stable channel positions, including when a fast repeat reaches a row
        // RecyclerView has not created yet. Native focus search can stall at viewport edges.
        if (rowPosition != null && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && rowPosition == 0) {
                focusGeneration++; pendingFocusId = null
                (if (favoritesOnly) favoritesTab else allTab).requestFocus()
            } else {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                focusChannel(channels.items[(rowPosition + direction).coerceIn(0, channels.items.lastIndex)].id, anchor = false)
            }
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                focusGeneration++; pendingFocusId = null
                if (favoritesTab.hasFocus()) allTab.requestFocus()
                else if (!allTab.hasFocus()) (if (favoritesOnly) favoritesTab else allTab).requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                focusGeneration++; pendingFocusId = null
                if (pendingPosition == null && allTab.hasFocus()) favoritesTab.requestFocus() else close()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (allTab.hasFocus() || favoritesTab.hasFocus()) { focusChannel(currentId); true } else false
            }
            else -> false
        }
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val candidate = super.focusSearch(focused, direction) ?: return focused
        var ancestor: android.view.ViewParent? = candidate.parent
        while (ancestor != null) {
            if (ancestor === this) return candidate
            ancestor = ancestor.parent
        }
        return focused
    }
}
