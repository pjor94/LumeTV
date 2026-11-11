package it.lumetv.app.ui.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.media3.ui.PlayerView
import it.lumetv.app.R
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@SuppressLint("ViewConstructor")
class MobileMiniPlayer(context: Context, private val open: () -> Unit, toggle: () -> Unit, stop: () -> Unit) : LinearLayout(context) {
    private val preview = FrameLayout(context)
    private val title = TvStyle.text(context, "", 14f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val status = TvStyle.text(context, "", 12f, TvStyle.accent).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val play = MobileStyle.icon(context, R.drawable.ic_mobile_pause, "Pausa", toggle)

    init {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12), context.dp(6), context.dp(4), context.dp(6))
        background = TvStyle.rounded(TvStyle.surface, context.dp(12).toFloat())
        minimumHeight = context.dp(68)
        preview.setOnClickListener { open() }
        addView(preview, LayoutParams(context.dp(80), context.dp(48)).apply { rightMargin = context.dp(10) })
        val copy = LinearLayout(context).apply {
            orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = context.dp(48)
            isClickable = true; isFocusable = true
            background = MobileStyle.ripple(context)
            setOnClickListener { open() }
            addView(title); addView(status, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
        }
        addView(copy, LayoutParams(0, -2, 1f))
        addView(play, LayoutParams(context.dp(48), context.dp(48)))
        addView(MobileStyle.icon(context, R.drawable.ic_mobile_close, "Interrompi la riproduzione", stop), LayoutParams(context.dp(48), context.dp(48)))
        visibility = GONE
    }

    fun bind(state: PlaybackSnapshot, video: PlayerView) {
        visibility = if (state.channel == null) GONE else VISIBLE
        title.text = state.channel?.name.orEmpty()
        status.text = state.error?.let { "Riproduzione interrotta · Apri" } ?: when {
            state.buffering -> "Caricamento…"
            !state.wantsToPlay -> "In pausa"
            else -> state.nowPlaying.ifBlank { if (state.audioOnly) "In ascolto" else "In diretta" }
        }
        play.setImageResource(if (state.wantsToPlay) R.drawable.ic_mobile_pause else R.drawable.ic_mobile_play)
        play.contentDescription = if (state.wantsToPlay) "Pausa" else "Riprendi"
        preview.visibility = if (state.channel != null && !state.audioOnly) VISIBLE else GONE
        if (preview.visibility == VISIBLE && video.parent !== preview) {
            (video.parent as? ViewGroup)?.removeView(video)
            preview.removeAllViews()
            preview.addView(video, FrameLayout.LayoutParams(-1, -1))
            // A separate hit area keeps the full player's tap handler out of the preview.
            preview.addView(View(context).apply { isClickable = true; contentDescription = "Apri la diretta"; setOnClickListener { open() } }, FrameLayout.LayoutParams(-1, -1))
        }
    }
}
