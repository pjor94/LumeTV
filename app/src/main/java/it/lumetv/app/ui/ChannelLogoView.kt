package it.lumetv.app.ui

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.LogoArtwork
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import kotlinx.coroutines.Job

/** A fixed optical footprint, shared by cards, guide rows and the live banner. */
@SuppressLint("ViewConstructor") // Programmatic UI; the repository is supplied by the activity.
class ChannelLogoView(context: Context, private val logos: ChannelLogoRepository) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var channel: Channel? = null
    private var request: Job? = null
    private var artwork: LogoArtwork? = null

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipToOutline = true
        addView(image, LayoutParams(-1, -1))
        placeholder()
    }

    fun bind(value: Channel) {
        if (channel?.id != value.id) { artwork = null; placeholder() }
        channel = value
        if (isAttachedToWindow) load(value)
    }

    fun unbind() {
        request?.cancel(); request = null
        channel = null; artwork = null
        placeholder()
    }

    private fun load(value: Channel) {
        request?.cancel()
        request = logos.request(value) { result ->
            if (channel?.id != value.id) return@request
            if (result != null && result !== artwork) {
                artwork = result
                image.setImageBitmap(result.bitmap)
                image.visibility = VISIBLE
                surface(result.lightSurface)
            }
        }
    }

    private fun placeholder() {
        image.setImageDrawable(null)
        image.visibility = INVISIBLE
        background = null
        clipToOutline = false
    }

    private fun surface(light: Boolean) {
        clipToOutline = true
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (light) intArrayOf(0xFFF3F5F8.toInt(), 0xFFDDE3EB.toInt())
            else intArrayOf(0xFF303846.toInt(), 0xFF222832.toInt())).apply {
            cornerRadius = context.dp(12).toFloat()
            setStroke(context.dp(1), if (light) 0x19000000 else 0x19FFFFFF)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        channel?.let(::load)
    }

    override fun onDetachedFromWindow() {
        request?.cancel(); request = null
        super.onDetachedFromWindow()
    }
}
