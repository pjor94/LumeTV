package it.lumetv.app.ui.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.media3.ui.PlayerView
import it.lumetv.app.R
import it.lumetv.app.data.Programme
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.ui.AudioPlaybackView
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@SuppressLint("ViewConstructor")
class MobilePlaybackView(
    context: Context,
    logos: ChannelLogoRepository,
    back: () -> Unit,
    togglePause: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    channelPicker: () -> Unit,
    fullscreen: () -> Unit,
    toggleFavorite: () -> Unit,
    details: () -> Unit,
    retry: () -> Unit,
    audioMode: () -> Unit,
    pip: () -> Unit,
    stop: () -> Unit,
) : FrameLayout(context) {
    val video = PlayerView(context).apply {
        useController = false
        setBackgroundColor(Color.BLACK)
        contentDescription = "Diretta · Tocca per mostrare o nascondere i comandi"
        isClickable = true
        setOnClickListener { setControlsVisible(controls.visibility != VISIBLE) }
    }
    private val controls = FrameLayout(context)
    private val channelName = TvStyle.text(context, "", 16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val title = TvStyle.text(context, "", 21f, bold = true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    private val timing = TvStyle.text(context, "", 13f, TvStyle.accent).apply { fontFeatureSettings = "tnum" }
    private val description = TvStyle.text(context, "", 14f, TvStyle.white).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    private val favorite = MobileStyle.icon(context, R.drawable.ic_mobile_heart, "Aggiungi ai preferiti", toggleFavorite)
    private val expand = MobileStyle.icon(context, R.drawable.ic_mobile_expand, "Schermo intero", fullscreen)
    val pipButton = MobileStyle.icon(context, R.drawable.ic_mobile_pip, "Picture in Picture", pip)
    private val audioView = AudioPlaybackView(context, logos, false, back, togglePause, previous, next, toggleFavorite, stop, retry, audioMode)
    private val play = MobileStyle.icon(context, R.drawable.ic_mobile_pause, "Pausa", togglePause)
    private val centerControls = LinearLayout(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(TvStyle.accent)
        progressBackgroundTintList = ColorStateList.valueOf(TvStyle.raised)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val loading = LinearLayout(context)
    private val loadingMessage = TvStyle.text(context, "Apro la diretta…", 16f).apply { gravity = Gravity.CENTER }
    private val error = LinearLayout(context)
    private val errorMessage = TvStyle.text(context, "", 16f).apply { gravity = Gravity.CENTER }
    private var playing = false
    private var busy = false
    private var failed = false
    private var audio = false
    private var inPip = false
    private val hideControls = Runnable { if (playing && !busy && !failed) setControlsVisible(false) }

    init {
        setBackgroundColor(Color.BLACK)
        addView(video, LayoutParams(-1, -1))
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xDD000000.toInt(), Color.TRANSPARENT))
        }
        top.addView(MobileStyle.icon(context, R.drawable.ic_mobile_back, "Torna al catalogo", back), LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        top.addView(channelName, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(4) })
        top.addView(favorite, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        top.addView(pipButton, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        top.addView(expand, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        controls.addView(top, LayoutParams(-1, -2, Gravity.TOP))
        centerControls.gravity = Gravity.CENTER
        centerControls.addView(MobileStyle.icon(context, R.drawable.ic_mobile_previous, "Canale precedente", previous).apply {
            background = MobileStyle.ripple(context, selected = true, radius = 24)
        },
            LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { rightMargin = context.dp(32) })
        play.background = MobileStyle.ripple(context, selected = true, radius = 36)
        play.setPadding(context.dp(18), context.dp(18), context.dp(18), context.dp(18))
        centerControls.addView(play, LinearLayout.LayoutParams(context.dp(72), context.dp(72)))
        centerControls.addView(MobileStyle.icon(context, R.drawable.ic_mobile_next, "Canale successivo", next).apply {
            background = MobileStyle.ripple(context, selected = true, radius = 24)
        },
            LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { leftMargin = context.dp(32) })
        controls.addView(centerControls, LayoutParams(-1, -2, Gravity.CENTER))
        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(20), context.dp(44), context.dp(20), context.dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xF2000000.toInt()))
        }
        bottom.addView(timing)
        title.setOnClickListener { details() }
        title.isFocusable = true
        TvStyle.buttonSemantics(title)
        title.contentDescription = "Dettagli del programma in onda"
        title.minHeight = context.dp(48)
        title.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(title, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(description, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
        bottom.addView(progress, LinearLayout.LayoutParams(-1, context.dp(2)).apply { topMargin = context.dp(14); bottomMargin = context.dp(8) })
        val actions = LinearLayout(context)
        actions.addView(MobileStyle.button(context, "Canali", action = channelPicker), LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(MobileStyle.button(context, "Solo audio", action = audioMode), LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(actions, LinearLayout.LayoutParams(-1, -2))
        controls.addView(bottom, LayoutParams(-1, -2, Gravity.BOTTOM))
        addView(controls, LayoutParams(-1, -1))
        loading.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(context.dp(24), 0, context.dp(24), 0)
            addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(TvStyle.accent) },
                LinearLayout.LayoutParams(context.dp(36), context.dp(36)))
            addView(loadingMessage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(16) })
            visibility = GONE
        }
        addView(loading, LayoutParams(-1, -2, Gravity.CENTER))
        error.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(context.dp(24), context.dp(20), context.dp(24), context.dp(20))
            background = TvStyle.rounded(TvStyle.surface, context.dp(16).toFloat())
            addView(errorMessage, LinearLayout.LayoutParams(-1, -2))
            addView(MobileStyle.button(context, "Riprova", true, retry), LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
            visibility = GONE
        }
        addView(error, LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = context.dp(24); rightMargin = context.dp(24) })
        addView(audioView, LayoutParams(-1, -1))
        audioView.visibility = GONE
        updateConfiguration()
    }

    fun bind(channel: Channel, programmes: List<Programme>, preferred: Boolean) {
        channelName.text = channel.name
        val now = System.currentTimeMillis()
        val programme = ProgrammeWindow.at(programmes, now).current
        title.text = programme?.title ?: channel.name
        title.contentDescription = (programme?.title ?: channel.name) + " · Dettagli del programma"
        description.text = programme?.description.orEmpty()
        description.visibility = if (description.text.isBlank() || landscape()) GONE else VISIBLE
        timing.text = programme?.let { "In diretta · ${time(it.start)} – ${time(it.end)}" } ?: "In diretta"
        progress.visibility = if (programme == null) GONE else VISIBLE
        if (programme != null) progress.progress = ((now - programme.start).toDouble() /
            (programme.end - programme.start).coerceAtLeast(1) * 1000).toInt().coerceIn(0, 1000)
        favorite.imageTintList = ColorStateList.valueOf(if (preferred) TvStyle.accent else TvStyle.white)
        favorite.isSelected = preferred
        favorite.contentDescription = if (preferred) "Rimuovi ${channel.name} dai preferiti" else "Aggiungi ${channel.name} ai preferiti"
    }

    fun showLoading(message: String) {
        busy = true; failed = false
        error.visibility = GONE; loading.visibility = VISIBLE; centerControls.visibility = GONE
        loadingMessage.text = message
        setControlsVisible(true)
    }

    fun ready(isPlaying: Boolean) {
        busy = false; failed = false
        loading.visibility = GONE; error.visibility = GONE; centerControls.visibility = VISIBLE
        setPlaying(isPlaying)
    }

    fun setPlaying(value: Boolean) {
        playing = value
        play.setImageResource(if (value) R.drawable.ic_mobile_pause else R.drawable.ic_mobile_play)
        play.contentDescription = if (value) "Pausa" else "Riprendi la diretta"
        if (!value) setControlsVisible(true) else scheduleHide()
    }

    fun showError(message: String) {
        busy = false; failed = true; playing = false
        loading.visibility = GONE; centerControls.visibility = GONE
        errorMessage.text = message; error.visibility = VISIBLE
        setControlsVisible(true)
    }

    fun updateConfiguration() {
        expand.setImageResource(if (landscape()) R.drawable.ic_mobile_collapse else R.drawable.ic_mobile_expand)
        expand.contentDescription = if (landscape()) "Esci da schermo intero" else "Schermo intero"
        title.maxLines = if (landscape()) 1 else 3
        description.visibility = if (description.text.isBlank() || landscape()) GONE else VISIBLE
    }

    fun setControlsVisible(visible: Boolean) {
        removeCallbacks(hideControls)
        controls.visibility = if (inPip || audio) GONE else if (visible || !playing || busy || failed) VISIBLE else GONE
        if (controls.visibility == VISIBLE) scheduleHide()
    }

    fun stopTimers() { removeCallbacks(hideControls) }
    fun renderState(state: PlaybackSnapshot, preferred: Boolean) {
        audio = state.audioOnly
        audioView.visibility = if (audio && !inPip) VISIBLE else GONE
        if (audio) { audioView.bind(state, preferred); controls.visibility = GONE; loading.visibility = GONE; error.visibility = GONE }
        video.visibility = if (audio) GONE else VISIBLE
        if (inPip) { controls.visibility = GONE; loading.visibility = GONE; error.visibility = GONE }
    }
    fun restoreVideo() {
        if (video.parent !== this) {
            (video.parent as? ViewGroup)?.removeView(video)
            addView(video, 0, LayoutParams(-1, -1))
        }
    }
    fun setInPip(value: Boolean) {
        inPip = value
        setControlsVisible(!value)
        if (value) { loading.visibility = GONE; error.visibility = GONE; audioView.visibility = GONE }
    }
    private fun scheduleHide() {
        removeCallbacks(hideControls)
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (playing && !busy && !failed && !accessibility.isTouchExplorationEnabled) postDelayed(hideControls, 5_000)
    }
    private fun landscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(value))
    override fun onDetachedFromWindow() { stopTimers(); super.onDetachedFromWindow() }
}
