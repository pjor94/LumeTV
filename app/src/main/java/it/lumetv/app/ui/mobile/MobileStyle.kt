package it.lumetv.app.ui.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.DrawableRes
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp

/** Shared Lume colors, with touch-sized controls and native press feedback. */
object MobileStyle {
    fun ripple(context: Context, selected: Boolean = false, radius: Int = 12) = RippleDrawable(
        ColorStateList.valueOf(0x3399CAFF),
        TvStyle.rounded(if (selected) TvStyle.raised else Color.TRANSPARENT, context.dp(radius).toFloat()),
        TvStyle.rounded(Color.WHITE, context.dp(radius).toFloat()))

    fun icon(context: Context, @DrawableRes drawable: Int, label: String, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable)
        imageTintList = ColorStateList.valueOf(TvStyle.white)
        contentDescription = label
        minimumWidth = context.dp(48); minimumHeight = context.dp(48)
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        background = ripple(context)
        setOnClickListener { action() }
    }

    fun button(context: Context, label: String, selected: Boolean = false, action: () -> Unit): TextView =
        TvStyle.text(context, label, 14f, if (selected) TvStyle.accent else TvStyle.white, selected).apply {
            gravity = Gravity.CENTER
            minHeight = context.dp(48); minWidth = context.dp(48)
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            isFocusable = true; isClickable = true; isSelected = selected
            background = ripple(context, selected)
            TvStyle.buttonSemantics(this)
            setOnClickListener { action() }
        }
}
