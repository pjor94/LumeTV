package it.lumetv.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import it.lumetv.app.R
import it.lumetv.app.ui.TvStyle.dp
import kotlin.math.max
import kotlin.math.min

internal object TvVideoStyle {
    fun title(context: Context, size: Float, lines: Int = 2) =
        TvStyle.text(context, "", size, bold = true).apply {
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            ViewCompat.setAccessibilityHeading(this, true)
        }

    fun action(context: Context, label: String, icon: Int = 0, primary: Boolean = false,
        quiet: Boolean = false, choose: () -> Unit) =
        TvStyle.text(context, label, 15f, bold = true).apply {
            val foreground = if (primary) TvStyle.background else TvStyle.white
            val surface = when {
                primary -> TvStyle.white
                quiet -> Color.TRANSPARENT
                else -> 0xFF242C38.toInt()
            }
            val colors = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(if (primary) 0xFF484F59.toInt() else TvStyle.muted, foreground)
            )
            setTextColor(colors)
            gravity = Gravity.CENTER
            minHeight = context.dp(42)
            val padding = context.dp(if (quiet) 4 else if (label.isEmpty()) 13 else 15)
            setPadding(padding, context.dp(8), padding, context.dp(8))
            isFocusable = true
            isClickable = true
            maxLines = 1
            TvStyle.buttonSemantics(this)
            val radius = context.dp(10).toFloat()
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),
                    TvStyle.rounded(if (primary) TvStyle.pressed else TvStyle.raised, radius, TvStyle.white, context.dp(2)))
                addState(intArrayOf(android.R.attr.state_focused),
                    TvStyle.rounded(if (primary) surface else 0xFF344052.toInt(), radius,
                        if (primary) TvStyle.accent else TvStyle.white, context.dp(2)))
                addState(intArrayOf(), TvStyle.rounded(surface, radius))
            }
            if (icon != 0) {
                val size = context.dp(18)
                val drawable = ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
                    setBounds(0, 0, size, size)
                    setTintList(colors)
                }
                compoundDrawablePadding = if (label.isEmpty()) 0 else context.dp(7)
                setCompoundDrawablesRelative(drawable, null, null, null)
            }
            setOnClickListener { choose() }
            setOnFocusChangeListener { view, focused -> TvStyle.focusMotion(view, focused, 1.025f) }
        }
}

/** The title artwork sits behind the shared Android TV menu and the foreground controls. */
internal class TvVideoBackdrop(context: Context) : View(context) {
    enum class Page { HOME, FILM, SERIES }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val horizontal = Paint()
    private val vertical = Paint()
    private val top = Paint()
    private val imageBounds = RectF()
    private var page: Page? = null
    private var image: Bitmap? = null
    private var previous: Bitmap? = null
    private var blend = 1f
    private var homeVisibility = 1f
    private var transition: ValueAnimator? = null
    private val background = TvStyle.background

    init {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(next: Page?) {
        if (page == next) return
        page = next
        transition?.cancel()
        transition = null
        image = null
        previous = null
        blend = 1f
        homeVisibility = 1f
        visibility = if (next == null) GONE else VISIBLE
        updateScrims()
        invalidate()
    }

    fun artwork(bitmap: Bitmap?, animated: Boolean) {
        if (page == null || bitmap === image) return
        transition?.cancel()
        transition = null
        previous = image
        image = bitmap
        if (animated && TvStyle.animationsEnabled(context) && (previous != null || bitmap != null)) {
            blend = 0f
            transition = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 170
                addUpdateListener {
                    blend = it.animatedValue as Float
                    if (blend == 1f) previous = null
                    invalidate()
                }
                start()
            }
        } else {
            previous = null
            blend = 1f
            invalidate()
        }
    }

    fun homeVisibility(fraction: Float) {
        if (page == Page.HOME && homeVisibility != fraction) {
            homeVisibility = fraction
            invalidate()
        }
    }

    private fun artworkBottom() = height * when (page) {
        Page.HOME -> .73f
        Page.SERIES -> .70f
        else -> 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateScrims()

    private fun updateScrims() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val bottom = artworkBottom()
        horizontal.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(background, background, 0xE60A0B0E.toInt(), 0x7C0A0B0E, Color.TRANSPARENT),
            floatArrayOf(0f, .27f, .43f, .62f, .90f), Shader.TileMode.CLAMP)
        vertical.shader = LinearGradient(0f, bottom * .51f, 0f, bottom,
            intArrayOf(Color.TRANSPARENT, 0xB30A0B0E.toInt(), background),
            floatArrayOf(0f, .70f, 1f), Shader.TileMode.CLAMP)
        top.shader = LinearGradient(0f, 0f, 0f, height * .25f,
            intArrayOf(0xF50A0B0E.toInt(), 0xB30A0B0E.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)
        if (homeVisibility == 0f || image == null && previous == null) return
        val w = width.toFloat()
        val h = height.toFloat()
        val bottom = artworkBottom()
        previous?.let { drawArtwork(canvas, it, w, h, bottom, homeVisibility * (1f - blend)) }
        image?.let { drawArtwork(canvas, it, w, h, bottom, homeVisibility * blend) }
        canvas.drawRect(0f, 0f, w, bottom, horizontal)
        canvas.drawRect(0f, 0f, w, bottom, vertical)
        canvas.drawRect(0f, 0f, w, bottom, top)
    }

    private fun drawArtwork(canvas: Canvas, bitmap: Bitmap, w: Float, h: Float, bottom: Float, opacity: Float) {
        if (opacity <= 0f) return
        val landscape = bitmap.width >= bitmap.height * 1.3f &&
            bitmap.width >= context.dp(320)
        val state = canvas.save()
        if (landscape) {
            val left = w * .24f
            val scale = max((w - left) / bitmap.width, bottom / bitmap.height)
            val drawnWidth = bitmap.width * scale
            val drawnHeight = bitmap.height * scale
            val x = w - drawnWidth
            val y = (bottom - drawnHeight) * .38f
            canvas.clipRect(left, 0f, w, bottom)
            imageBounds.set(x, y, w, y + drawnHeight)
        } else {
            val maxHeight = h * if (page == Page.SERIES) .35f else .53f
            val scale = min(w * .26f / bitmap.width, maxHeight / bitmap.height)
            val drawnWidth = bitmap.width * scale
            val drawnHeight = bitmap.height * scale
            val center = w * .76f
            val y = h * if (page == Page.FILM) .22f else .17f
            imageBounds.set(center - drawnWidth / 2, y, center + drawnWidth / 2, y + drawnHeight)
        }
        bitmapPaint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, imageBounds, bitmapPaint)
        canvas.restoreToCount(state)
    }
}

/** A single contextual header in the scrollable Video shelves. */
internal class TvVideoHero(context: Context, openDetails: () -> Unit) : LinearLayout(context) {
    private val compact = resources.configuration.screenHeightDp < 510 || resources.configuration.fontScale > 1.15f
    private val copyWidth = context.dp((resources.configuration.screenWidthDp * .46f).toInt().coerceAtLeast(250))
    private val title = TvVideoStyle.title(context, if (compact) 35f else 50f, if (compact) 1 else 2).apply {
        tag = "video_featured_title"
        maxWidth = copyWidth
    }
    private val meta = TvStyle.text(context, "", 15f, TvStyle.muted, bold = true).apply {
        maxWidth = copyWidth; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val plot = TvStyle.text(context, "", if (compact) 15f else 16f).apply {
        maxWidth = copyWidth; maxLines = if (compact) 1 else 2; ellipsize = TextUtils.TruncateAt.END
    }
    val details = TvVideoStyle.action(context, "Dettagli", R.drawable.ic_tv_video_info, choose = openDetails)

    init {
        tag = "video_featured"
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(4), context.dp(if (compact) 4 else 8), 0, context.dp(7))
        minimumHeight = context.dp(if (compact) 152 else 185)
        addView(title, LayoutParams(-2, -2))
        addView(meta, LayoutParams(-2, -2).apply { topMargin = context.dp(if (compact) 5 else 8) })
        addView(plot, LayoutParams(-2, -2).apply { topMargin = context.dp(if (compact) 5 else 7) })
        addView(details, LayoutParams(-2, -2).apply { topMargin = context.dp(if (compact) 7 else 10) })
    }

    fun bind(name: String, metadata: String, description: String) {
        title.text = name
        meta.text = metadata
        meta.visibility = if (metadata.isBlank()) GONE else VISIBLE
        plot.text = description
        plot.visibility = if (description.isBlank()) GONE else VISIBLE
        details.contentDescription = "Dettagli di $name"
    }
}
