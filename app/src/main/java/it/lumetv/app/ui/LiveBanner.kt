package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ScaleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import it.lumetv.app.R
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.playback.PlaybackOptionsState
import it.lumetv.app.playback.sleepTimerLabel
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("ViewConstructor") // The activity supplies the shared logo repository.
internal class LiveBanner(context: Context, logos: ChannelLogoRepository) : FrameLayout(context) {
    private enum class Mode { HIDDEN, TUNE, MESSAGE, INFO, DETAILS }

    var onPlaybackOptions: () -> Unit = {}
    var onDetailsOpened: () -> Unit = {}
    var onInfoLayout: () -> Unit = {}
    private var mode = Mode.HIDDEN
    private var boundChannel: Channel? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALY)

    private val notice = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = LiveOverlayStyle.material(context, radius = 16)
        setPadding(context.dp(12), context.dp(10), context.dp(15), context.dp(10))
        visibility = GONE
    }
    private val noticeLogo = ChannelLogoView(context, logos, compact = true, fallbackIcon = true)
    private val noticeTitle = line(16f, bold = true)
    private val noticeProgramme = line(13f, color = LiveOverlayStyle.secondary).apply { maxLines = 2 }

    private val info = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = LiveOverlayStyle.material(context)
        setPadding(context.dp(18), context.dp(13), context.dp(16), context.dp(14))
        visibility = GONE
    }
    private val infoLogo = ChannelLogoView(context, logos, compact = true, fallbackIcon = true)
    private val identity = line(13f, color = LiveOverlayStyle.secondary, bold = true)
    private val title = line(25f, bold = true).apply {
        maxLines = 2
        letterSpacing = -0.025f
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val timing = line(13f, color = LiveOverlayStyle.secondary).apply { fontFeatureSettings = "tnum" }
    private val remaining = line(13f, color = LiveOverlayStyle.secondary).apply { fontFeatureSettings = "tnum" }
    private val timingRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val guideProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        val radius = context.dp(2).toFloat()
        progressDrawable = LayerDrawable(arrayOf(
            TvStyle.rounded(0x3BADC6E5, radius),
            ScaleDrawable(TvStyle.rounded(TvStyle.accent, radius), Gravity.START, 1f, -1f),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val next = line(13f, color = LiveOverlayStyle.secondary)
    private val metadata = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val detailsButton = LiveOverlayStyle.button(context, "Dettagli", ::openDetails)
    private val optionsButton = LiveOverlayStyle.button(context, "Opzioni") { onPlaybackOptions() }
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val synopsis = TvStyle.text(context, "", 16f, TvStyle.white).apply {
        setLineSpacing(context.dp(4).toFloat(), 1f)
    }
    private val synopsisHint = TvStyle.text(context, context.getString(R.string.live_description_scroll), 12f, LiveOverlayStyle.secondary)
    private val synopsisScroll = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) context.dp(172)
                else minOf(context.dp(172), MeasureSpec.getSize(heightMeasureSpec))
            val lineHeight = synopsis.lineHeight.coerceAtLeast(1)
            val available = cap - cap % lineHeight
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
        }
    }.apply {
        isFocusable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        overScrollMode = OVER_SCROLL_NEVER
        addView(synopsis, LayoutParams(-1, -2))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            post { synopsisHint.isVisible = isDetailsOpen && (canScrollVertically(-1) || canScrollVertically(1)) }
        }
    }

    val isInfoOpen: Boolean get() = mode == Mode.INFO || mode == Mode.DETAILS
    val isDetailsOpen: Boolean get() = mode == Mode.DETAILS
    val isActive: Boolean get() = mode != Mode.HIDDEN
    val showsProgramme: Boolean get() = mode == Mode.TUNE || mode == Mode.INFO
    val actionFocused: Boolean get() = mode == Mode.INFO && actions.hasFocus()
    val subtitleBoundary: View? get() = info.takeIf { isInfoOpen }

    init {
        notice.addView(noticeLogo, LinearLayout.LayoutParams(context.dp(42), context.dp(38)).apply { marginEnd = context.dp(11) })
        notice.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(noticeTitle)
            addView(noticeProgramme, below(3))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(notice, LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM))

        val heading = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(infoLogo, LinearLayout.LayoutParams(context.dp(28), context.dp(24)).apply { marginEnd = context.dp(8) })
            addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        }
        timingRow.addView(timing, LinearLayout.LayoutParams(0, -2, 1f))
        timingRow.addView(remaining, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(8) })
        metadata.addView(timingRow)
        metadata.addView(guideProgress, LinearLayout.LayoutParams(-1, context.dp(4)).apply { topMargin = context.dp(5) })
        metadata.addView(next, below(8))
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading)
            addView(title, below(4))
            addView(metadata, below(8))
        }
        actions.addView(detailsButton, LinearLayout.LayoutParams(-1, -2))
        actions.addView(optionsButton, below(5))
        info.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            addView(actions, LinearLayout.LayoutParams(context.dp(116), -2).apply { marginStart = context.dp(20) })
        })
        info.addView(synopsisScroll, below(12))
        info.addView(synopsisHint, below(6))
        addView(info, LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM))
        info.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onInfoLayout() }
        ViewCompat.setAccessibilityPaneTitle(info, "Programma in onda")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
        val compact = MeasureSpec.getSize(heightMeasureSpec) < context.dp(460)
        val inset = context.dp(if (compact) 20 else 27)
        val available = (containerWidth - inset * 2).coerceAtLeast(0)
        (notice.layoutParams as LayoutParams).apply {
            if (notice.isVisible) widthLimit(notice, minOf(context.dp(350), available))
            setMargins(inset, 0, inset, inset)
        }
        (info.layoutParams as LayoutParams).apply {
            width = minOf(available, context.dp(760), maxOf((containerWidth * .70).toInt(), context.dp(560)))
            setMargins(inset, 0, inset, inset)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun LayoutParams.widthLimit(view: View, limit: Int) {
        width = -2
        view.measure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        width = view.measuredWidth
    }

    fun showChannel(channel: Channel, programmes: List<Programme>, reading: Boolean, paused: Boolean) {
        if (isDetailsOpen && reading && boundChannel?.id == channel.id) return
        boundChannel = null
        mode = if (reading) Mode.INFO else Mode.TUNE
        if (reading) {
            noticeLogo.unbind()
            LiveOverlayStyle.conceal(notice, animated = false)
            metadata.visibility = VISIBLE
            actions.visibility = VISIBLE
            synopsisScroll.visibility = GONE
            synopsisHint.visibility = GONE
            info.descendantFocusability = FOCUS_AFTER_DESCENDANTS
        } else hideInfo(animated = false)
        bind(channel, programmes, paused)
        if (reading) {
            LiveOverlayStyle.reveal(info, fromY = context.dp(14))
            if (!actions.hasFocus()) (if (detailsButton.isVisible) detailsButton else optionsButton).requestFocus()
        } else LiveOverlayStyle.reveal(notice, fromY = context.dp(7))
    }

    fun bind(channel: Channel, programmes: List<Programme>, paused: Boolean) {
        if (!showsProgramme) return
        if (boundChannel != channel) {
            boundChannel = channel
            refreshLogo()
        }
        val label = listOfNotNull(channel.displayNumber?.toString(), channel.name).joinToString("  ·  ")
        val window = ProgrammeWindow.at(programmes, System.currentTimeMillis())
        val current = window.current
        if (mode == Mode.TUNE) {
            noticeLogo.visibility = VISIBLE
            noticeTitle.replaceText(label)
            noticeProgramme.replaceText(current?.title ?: if (paused) "In pausa" else "In diretta")
            return
        }
        val status = if (paused) "In pausa" else "In diretta"
        identity.replaceText(if (current == null) listOfNotNull(channel.displayNumber?.toString(), status).joinToString("  ·  ")
            else if (paused) "$label  ·  $status" else label)
        title.replaceText(current?.title ?: channel.name)
        timing.replaceText(current?.let { "${time(it.start)}–${time(it.end)}" } ?: "Programma attuale non disponibile")
        remaining.isVisible = current != null
        remaining.replaceText("${window.remainingMinutes} min alla fine")
        guideProgress.isVisible = current != null
        guideProgress.progress = window.progress
        next.isVisible = window.next != null
        next.replaceText(window.next?.let { "A seguire  ·  ${time(it.start)}  ${it.title}" }.orEmpty())
        val description = current?.description.orEmpty().trim()
        synopsis.replaceText(description)
        val detailsHadFocus = detailsButton.hasFocus()
        detailsButton.isVisible = description.isNotEmpty()
        if (detailsHadFocus && !detailsButton.isVisible) optionsButton.requestFocus()
    }

    fun refreshLogo() {
        boundChannel?.let { channel -> (if (isInfoOpen) infoLogo else noticeLogo).bind(channel) }
    }

    fun message(title: String, subtitle: String) {
        mode = Mode.MESSAGE
        boundChannel = null
        hideInfo(animated = false)
        noticeLogo.unbind()
        noticeLogo.visibility = GONE
        noticeTitle.text = title
        noticeProgramme.text = subtitle
        LiveOverlayStyle.reveal(notice, fromY = context.dp(7))
    }

    fun dismiss(animated: Boolean) {
        val wasInfo = isInfoOpen
        mode = Mode.HIDDEN
        boundChannel = null
        if (wasInfo || !animated) hideInfo(animated)
        LiveOverlayStyle.conceal(notice, toY = context.dp(7), animated = animated) { noticeLogo.unbind() }
    }

    private fun hideInfo(animated: Boolean) {
        info.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        info.clearFocus()
        LiveOverlayStyle.conceal(info, toY = context.dp(14), animated = animated) { infoLogo.unbind() }
    }

    private fun openDetails() {
        if (mode != Mode.INFO || !detailsButton.isVisible) return
        mode = Mode.DETAILS
        metadata.visibility = GONE
        actions.visibility = GONE
        synopsisScroll.visibility = VISIBLE
        synopsisScroll.scrollTo(0, 0)
        synopsisScroll.requestFocus()
        onDetailsOpened()
    }

    fun moveFocus(direction: Int): Boolean {
        if (isDetailsOpen) synopsisScroll.scrollBy(0, direction * synopsis.lineHeight * 2)
        else if (mode == Mode.INFO) {
            (if (direction < 0 && detailsButton.isVisible) detailsButton else optionsButton).requestFocus()
        } else return false
        return true
    }

    fun renderOptions(state: PlaybackOptionsState) {
        val timer = sleepTimerLabel(state.sleepRemaining)
        optionsButton.replaceText(if (state.sleepRemaining > 0) "Timer · ${timer.substringBefore(" rimanenti")}" else "Opzioni")
        optionsButton.contentDescription = if (state.sleepRemaining > 0) "Opzioni riproduzione, timer $timer" else "Opzioni riproduzione"
    }

    private fun below(gap: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(gap) }
    private fun line(size: Float, color: Int = TvStyle.white, bold: Boolean = false) =
        TvStyle.text(context, "", size, color, bold).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private fun TextView.replaceText(value: String) { if (text != value) text = value }
    private fun time(value: Long) = timeFormat.format(Date(value))
}
