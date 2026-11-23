package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import it.lumetv.app.R
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import it.lumetv.core.PlaybackMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Programme details follow catalog focus. The preview follows the existing playback session. */
@SuppressLint("ViewConstructor") // The player host and navigation callbacks are supplied programmatically.
internal class TvChannelDetails(
    context: Context,
    logos: ChannelLogoRepository,
    watchChannel: (Channel) -> Unit,
    showSchedule: (Channel) -> Unit,
    toggleFavorite: (Channel) -> Unit,
    openPlayback: () -> Unit,
    playbackActions: () -> Unit,
) : LinearLayout(context) {
    private val configuration = resources.configuration
    private val compact = configuration.screenHeightDp < 450
    private val narrow = configuration.screenWidthDp < 740
    private val largeText = configuration.fontScale > 1.15f
    private val tightSpacing = compact || largeText
    private val time = SimpleDateFormat("HH:mm", Locale.ITALY)
    private var selection: Channel? = null
    private var playing: Channel? = null
    private var artworkId: String? = null
    private val detail = LinearLayout(context).apply { orientation = VERTICAL }
    private val identityLogo = ChannelLogoView(context, logos, compact = true, fallbackIcon = true)
    private val identity = label(13f, TvStyle.white, bold = true)
    private val title = TvVideoStyle.title(context, when {
        compact && largeText -> 18f
        compact -> 20f
        tightSpacing -> 23f
        else -> 26f
    }, lines = if (configuration.screenHeightDp < 600) 1 else 2).apply { tag = "tv_channel_programme" }
    private val interval = label(if (narrow) 12f else 13f, bold = true).apply { fontFeatureSettings = "tnum" }
    private val remaining = label(if (narrow) 11f else 12f).apply { gravity = Gravity.END }
    private val timing = row().apply {
        addView(interval, LayoutParams(0, -2, 1f))
        addView(remaining, LayoutParams(-2, -2).apply { marginStart = context.dp(5) })
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        tag = "tv_channel_progress"
        max = 1000
        progressTintList = ColorStateList.valueOf(TvStyle.accent)
        progressBackgroundTintList = ColorStateList.valueOf(0x3CB4C9E2)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val nextTitle = label(if (narrow) 11f else 12f, TvStyle.white, bold = true).apply { tag = "tv_channel_next" }
    private val nextTime = label(11f).apply { fontFeatureSettings = "tnum"; isVisible = !compact }
    private val nextRow = row().apply {
        gravity = Gravity.TOP
        addView(label(if (narrow) 11f else 12f).apply { setText(R.string.tv_channel_next) },
            LayoutParams(-2, -2).apply { marginEnd = context.dp(if (narrow) 6 else 10) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(nextTitle, LayoutParams(-1, -2))
            addView(nextTime, LayoutParams(-1, -2).apply { topMargin = context.dp(2) })
        }, LayoutParams(0, -2, 1f))
    }
    private val watch = action("Guarda", R.drawable.ic_tv_video_play, primary = true) { selection?.let(watchChannel) }
    private val schedule = action(
        if (narrow) "" else "Palinsesto", R.drawable.ic_mobile_guide
    ) { selection?.let(showSchedule) }.apply { tag = "tv_channel_schedule" }
    private val favorite = action("", R.drawable.ic_mobile_heart) { selection?.let(toggleFavorite) }

    private val preview = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(size * 9 / 16, MeasureSpec.EXACTLY))
        }
    }.apply {
        tag = "tv_channel_preview"
        val radius = context.dp(11).toFloat()
        background = TvStyle.rounded(0xFF192333.toInt(), radius)
        foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                TvStyle.rounded(Color.TRANSPARENT, radius, TvStyle.white, context.dp(2)))
            addState(intArrayOf(), TvStyle.rounded(Color.TRANSPARENT, radius, 0x35C2D1E7, context.dp(1)))
        }
        clipToOutline = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        TvStyle.buttonSemantics(this)
        setOnClickListener { openPlayback() }
        setOnLongClickListener { playbackActions(); true }
    }
    val playerSlot = FrameLayout(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val placeholder = ChannelLogoView(context, logos, fallbackIcon = true)
    private val previewState = TvStyle.text(context, "", 11f, TvStyle.white, true).apply {
        tag = "tv_channel_preview_state"
        setPadding(context.dp(8), context.dp(4), context.dp(8), context.dp(4))
        background = TvStyle.rounded(0xE90E1724.toInt(), context.dp(5).toFloat())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val previewName = label(if (narrow) 11f else 12f, TvStyle.white, bold = true).apply {
        tag = "tv_channel_preview_name"
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private val expand = ImageView(context).apply {
        setImageResource(R.drawable.ic_mobile_expand)
        setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
        background = TvStyle.rounded(0xDF0E1724.toInt(), context.dp(5).toFloat())
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        tag = "tv_channel_details"
        orientation = VERTICAL
        clipChildren = false
        addView(preview, LayoutParams(-1, -2))
        preview.addView(playerSlot, FrameLayout.LayoutParams(-1, -1))
        preview.addView(placeholder, FrameLayout.LayoutParams(context.dp(if (compact) 64 else 84),
            context.dp(if (compact) 44 else 58), Gravity.CENTER))
        preview.addView(row().apply {
            setPadding(context.dp(8), context.dp(7), context.dp(9), context.dp(9))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xC5080E19.toInt(), Color.TRANSPARENT))
            addView(previewState, LayoutParams(-2, -2))
            addView(previewName, LayoutParams(0, -2, 1f).apply { marginStart = context.dp(5) })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        preview.addView(expand, FrameLayout.LayoutParams(context.dp(28), context.dp(28),
            Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = context.dp(7)
            bottomMargin = context.dp(7)
        })

        val logoSize = context.dp(if (compact) 20 else 29)
        detail.addView(row().apply {
            addView(identityLogo, LayoutParams(logoSize, logoSize).apply { marginEnd = context.dp(8) })
            addView(identity, LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, -2))
        detail.addView(title, detailParams(if (compact) 0 else if (largeText) 2 else 4))
        detail.addView(timing, detailParams(if (compact) 2 else if (largeText) 3 else 6))
        detail.addView(progress, LayoutParams(-1, context.dp(4)).apply { topMargin = context.dp(if (compact) 3 else 4) })
        detail.addView(nextRow, detailParams(if (compact) 4 else if (largeText) 6 else 10))
        detail.addView(row().apply {
            clipChildren = false
            addView(watch, LayoutParams(0, -2, 1f))
            val iconWidth = context.dp(if (narrow) 38 else 44)
            val spacing = context.dp(if (narrow) 5 else 8)
            val scheduleWeight = if (narrow) 0f else if (largeText) 1.2f else 1f
            addView(schedule, LayoutParams(if (narrow) iconWidth else 0, -2, scheduleWeight).apply {
                marginStart = spacing
            })
            addView(favorite, LayoutParams(iconWidth, -2).apply { marginStart = spacing })
        }, detailParams(if (compact) 5 else if (largeText) 7 else 12))
        addView(detail, detailParams(if (compact) 4 else 11))
        bindPlayback(PlaybackSnapshot())
    }

    fun bindProgramme(channel: Channel?, window: ProgrammeWindow, isFavorite: Boolean) {
        if (selection != channel) {
            if (channel == null) identityLogo.unbind() else identityLogo.bind(channel)
        }
        selection = channel
        detail.isVisible = channel != null
        val programme = window.current
        val upcoming = window.next
        identity.text = listOfNotNull(channel?.displayNumber?.let { "%02d".format(Locale.ITALY, it) },
            channel?.name).joinToString(" · ")
        title.text = when {
            programme != null -> programme.title
            upcoming != null -> "Programma in onda non indicato"
            else -> "Guida non disponibile"
        }
        timing.isVisible = programme != null
        interval.text = programme?.let { formatTime(it.start) + "–" + formatTime(it.end) }.orEmpty()
        remaining.text = programme?.let {
            window.remainingMinutes.toString() + if (narrow) " min" else " min alla fine"
        }.orEmpty()
        progress.isVisible = programme != null
        progress.progress = window.progress
        nextRow.isVisible = upcoming != null
        nextTitle.text = upcoming?.title.orEmpty()
        nextTitle.contentDescription = upcoming?.let { it.title + ", " + formatTime(it.start) + "–" + formatTime(it.end) }
        nextTime.text = upcoming?.let { formatTime(it.start) + "–" + formatTime(it.end) }.orEmpty()
        watch.contentDescription = channel?.let { "Guarda " + it.name }
        schedule.isVisible = programme != null || upcoming != null
        schedule.contentDescription = channel?.let { "Palinsesto di " + it.name }
        favorite.isSelected = isFavorite
        favorite.compoundDrawablesRelative[0]?.setTint(if (isFavorite) TvStyle.accent else TvStyle.white)
        favorite.contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti"
        if (playing == null) bindArtwork(channel)
    }

    fun focusActions(): Boolean = if (watch.isShown) watch.requestFocus() else preview.requestFocus()

    fun hasEntryFocus(): Boolean = watch.hasFocus() || preview.hasFocus()

    fun bindPlayback(state: PlaybackSnapshot) {
        val channel = state.channel
        playing = channel
        val video = channel != null && !state.audioOnly && !channel.isRadio && state.error == null
        playerSlot.visibility = if (video) VISIBLE else INVISIBLE
        placeholder.isVisible = !video || !state.ready
        if (placeholder.isVisible) bindArtwork(channel ?: selection)
        val label = when {
            channel == null -> ""
            state.error != null -> "Interrotta"
            !state.wantsToPlay -> "In pausa"
            state.buffering -> "Caricamento…"
            channel.isRadio || state.audioOnly -> "In ascolto"
            state.mode == PlaybackMode.ARCHIVE -> "Archivio"
            state.mode == PlaybackMode.VOD -> "Video"
            else -> "In visione"
        }
        if (!TextUtils.equals(previewState.text, label)) previewState.text = label
        previewState.isVisible = channel != null
        val name = channel?.name ?: if (compact) "Nessuna diretta" else "Nessuna diretta in visione"
        if (!TextUtils.equals(previewName.text, name)) previewName.text = name
        expand.visibility = if (channel == null) INVISIBLE else VISIBLE
        preview.isFocusable = channel != null
        preview.isClickable = channel != null
        preview.isLongClickable = channel != null
        val description = channel?.let { "Apri " + it.name + " a schermo intero. " + label }
        if (!TextUtils.equals(preview.contentDescription, description)) preview.contentDescription = description
        preview.importantForAccessibility = if (channel == null) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun refreshLogo() {
        artworkId = null
        selection?.let(identityLogo::bind)
        if (placeholder.isVisible) bindArtwork(playing ?: selection)
    }

    private fun bindArtwork(channel: Channel?) {
        if (artworkId == channel?.id) return
        artworkId = channel?.id
        if (channel == null) placeholder.unbind() else placeholder.bind(channel)
    }

    private fun label(size: Float, color: Int = TvStyle.muted, bold: Boolean = false): TextView =
        TvStyle.text(context, "", size, color, bold).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun action(label: String, icon: Int, primary: Boolean = false, choose: () -> Unit) =
        TvVideoStyle.action(context, label, icon, primary, choose = choose).apply {
            textSize = if (narrow) 12f else 13f
            minHeight = context.dp(if (compact) 39 else 43)
            minWidth = 0
            val inset = context.dp(if (narrow) 4 else 7)
            setPadding(inset, 0, inset, 0)
            compoundDrawablePadding = context.dp(if (narrow) 4 else 6)
            if (narrow) compoundDrawablesRelative[0]?.setBounds(0, 0, context.dp(15), context.dp(15))
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun formatTime(value: Long): String = time.format(Date(value))
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun detailParams(top: Int) = LayoutParams(-1, -2).apply { topMargin = context.dp(top) }
}
