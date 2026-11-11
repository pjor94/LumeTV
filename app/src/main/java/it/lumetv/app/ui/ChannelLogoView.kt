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
import it.lumetv.app.R
import kotlinx.coroutines.Job

/** A fixed optical footprint, shared by cards, guide rows and the live banner. */
@SuppressLint("ViewConstructor") // Programmatic UI; the repository is supplied by the activity.
class ChannelLogoView(context: Context, private val logos: ChannelLogoRepository, private val compact: Boolean = false) : FrameLayout(context) {
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        val horizontal = context.dp(if (compact) 4 else 10)
        val vertical = context.dp(if (compact) 4 else 8)
        setPadding(horizontal, vertical, horizontal, vertical)
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
        val changed = channel?.id != value.id
        channel = value
        if (changed) { artwork = null; placeholder() }
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
        if (channel?.isRadio == true) {
            image.setImageResource(R.drawable.ic_mobile_radio)
            image.imageTintList = android.content.res.ColorStateList.valueOf(TvStyle.accent)
            image.visibility = VISIBLE
            background = TvStyle.rounded(TvStyle.surface, context.dp(12).toFloat())
            clipToOutline = true
        } else {
            image.setImageDrawable(null)
            image.visibility = INVISIBLE
            background = null
            clipToOutline = false
        }
    }

    private fun surface(light: Boolean) {
        image.imageTintList = null
        clipToOutline = true
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (light) intArrayOf(0xFFF3F5F8.toInt(), 0xFFDDE3EB.toInt())
            else intArrayOf(0xFF303846.toInt(), 0xFF222832.toInt())).apply {
            cornerRadius = context.dp(if (compact) 8 else 12).toFloat()
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
