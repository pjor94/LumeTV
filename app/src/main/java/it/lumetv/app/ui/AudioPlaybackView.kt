package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.lumetv.app.R
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle

/** The same radio information, with touch targets or D-pad focus as appropriate. */
@SuppressLint("ViewConstructor")
class AudioPlaybackView(
    context: Context,
    logos: ChannelLogoRepository,
    television: Boolean,
    back: () -> Unit,
    togglePause: () -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    favorite: () -> Unit,
    stop: () -> Unit,
    retry: () -> Unit,
    returnToVideo: (() -> Unit)? = null,
) : FrameLayout(context) {
    private val logo = ChannelLogoView(context, logos)
    private val audioFallback = ImageView(context).apply {
        setImageResource(R.drawable.ic_mobile_audio)
        imageTintList = ColorStateList.valueOf(TvStyle.accent)
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val name = TvStyle.text(context, "", if (television) 34f else 28f, bold = true).apply {
        gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val track = TvStyle.text(context, "", if (television) 19f else 16f, TvStyle.muted).apply {
        gravity = Gravity.CENTER; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
    }
    private val status = TvStyle.text(context, "", 14f, TvStyle.accent).apply { gravity = Gravity.CENTER; maxLines = 3 }
    private val play: View
    private val heart: View
    private val retryButton = if (television) TvStyle.button(context, "Riprova", action = retry)
        else MobileStyle.button(context, "Riprova", true, retry)
    private val videoButton = MobileStyle.button(context, "Torna al video") { returnToVideo?.invoke() }

    init {
        setBackgroundColor(TvStyle.background)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(24))
        }
        val scroll = ScrollView(context).apply { isFillViewport = true; addView(column, LayoutParams(-1, -1)) }
        addView(scroll, LayoutParams(-1, -1))
        fun button(label: String, drawable: Int, action: () -> Unit): View =
            if (television) TvStyle.button(context, label, action = action)
            else MobileStyle.icon(context, drawable, label, action)
        val backButton = if (television) TvStyle.button(context, "Catalogo", action = back)
            else MobileStyle.button(context, "Torna al catalogo", action = back)
        column.addView(backButton, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = context.dp(24) })
        val artwork = FrameLayout(context).apply {
            addView(audioFallback, LayoutParams(-1, -1))
            addView(logo, LayoutParams(-1, -1))
        }
        column.addView(artwork, LinearLayout.LayoutParams(context.dp(if (television) 112 else 96), context.dp(if (television) 88 else 80)))
        column.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(22) })
        column.addView(track, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(10) })
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12); bottomMargin = context.dp(24) })
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        val previousButton = button("Stazione precedente", R.drawable.ic_mobile_previous, previous)
        play = button("Pausa", R.drawable.ic_mobile_pause, togglePause)
        val nextButton = button("Stazione successiva", R.drawable.ic_mobile_next, next)
        if (television) {
            (previousButton as TextView).text = "Precedente"
            (nextButton as TextView).text = "Successiva"
        }
        controls.addView(previousButton, LinearLayout.LayoutParams(-2, context.dp(56)).apply { rightMargin = context.dp(16) })
        controls.addView(play, LinearLayout.LayoutParams(context.dp(if (television) 120 else 64), context.dp(64)))
        controls.addView(nextButton, LinearLayout.LayoutParams(-2, context.dp(56)).apply { leftMargin = context.dp(16) })
        column.addView(controls)
        val secondary = LinearLayout(context).apply { gravity = Gravity.CENTER }
        heart = button("Aggiungi ai preferiti", R.drawable.ic_mobile_heart, favorite)
        secondary.addView(heart, LinearLayout.LayoutParams(-2, context.dp(48)).apply { rightMargin = context.dp(16) })
        secondary.addView(button("Interrompi", R.drawable.ic_mobile_close, stop), LinearLayout.LayoutParams(-2, context.dp(48)))
        column.addView(secondary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(18) })
        column.addView(retryButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(12) })
        column.addView(videoButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(12) })
        videoButton.visibility = GONE
    }

    fun bind(state: PlaybackSnapshot, preferred: Boolean) {
        val channel = state.channel ?: return
        logo.bind(channel)
        audioFallback.visibility = if (channel.isRadio) GONE else VISIBLE
        name.text = channel.name
        track.text = state.nowPlaying.ifBlank { if (channel.isRadio) channel.group.ifBlank { "Radio in diretta" } else "Solo audio · TV in diretta" }
        status.text = state.error ?: when {
            state.buffering -> state.message.ifBlank { "Caricamento…" }
            !state.wantsToPlay -> "In pausa"
            state.playing -> "In diretta"
            else -> "Apro la diretta…"
        }
        val label = if (state.wantsToPlay) "Pausa" else "Riprendi"
        play.contentDescription = label
        if (play is TextView) play.text = label
        if (play is ImageButton) play.setImageResource(if (state.wantsToPlay) R.drawable.ic_mobile_pause else R.drawable.ic_mobile_play)
        heart.isSelected = preferred
        heart.contentDescription = if (preferred) "Rimuovi ${channel.name} dai preferiti" else "Aggiungi ${channel.name} ai preferiti"
        if (heart is TextView) heart.text = if (preferred) "Preferito" else "Preferiti"
        if (heart is ImageButton) heart.imageTintList = ColorStateList.valueOf(if (preferred) TvStyle.accent else TvStyle.white)
        retryButton.visibility = if (state.error != null) VISIBLE else GONE
        videoButton.visibility = if (!channel.isRadio && state.audioOnly) VISIBLE else GONE
    }

    fun focusPlayback() { play.requestFocus() }
}
