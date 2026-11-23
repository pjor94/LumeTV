package it.lumetv.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import it.lumetv.app.ui.TvStyle.dp

internal object LiveOverlayStyle {
    val secondary = 0xFFBCCBE0.toInt()
    private val focus = 0xFF9AD8FF.toInt()

    fun material(context: Context, radius: Int = 20) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xF01C2B40.toInt(), 0xED131F31.toInt(), 0xE90D1727.toInt()),
    ).apply {
        cornerRadius = context.dp(radius).toFloat()
        setStroke(context.dp(1), 0x4D9EB8DA)
    }

    fun focusBackground(context: Context, radius: Int = 12, restingColor: Int = 0x0CFFFFFF) = StateListDrawable().apply {
        val corners = context.dp(radius).toFloat()
        addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_pressed),
            TvStyle.rounded(0xFF355575.toInt(), corners, focus, context.dp(2)))
        addState(intArrayOf(android.R.attr.state_focused),
            TvStyle.rounded(0xFF23425F.toInt(), corners, focus, context.dp(2)))
        addState(intArrayOf(android.R.attr.state_selected),
            TvStyle.rounded(0xFF2A3D57.toInt(), corners, 0x449EB8DA, context.dp(1)))
        addState(intArrayOf(), TvStyle.rounded(restingColor, corners))
    }

    fun button(context: Context, label: String, action: () -> Unit): TextView =
        TvStyle.button(context, label, action = action).apply {
            setTextColor(TvStyle.white)
            background = focusBackground(context, radius = 20)
            setOnFocusChangeListener { view, focused -> TvStyle.focusMotion(view, focused, 1.015f) }
        }

    fun reveal(view: View, fromX: Int = 0, fromY: Int = 0) {
        val entering = view.visibility != View.VISIBLE
        view.animate().cancel()
        view.visibility = View.VISIBLE
        if (!TvStyle.animationsEnabled(view.context)) {
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            return
        }
        if (entering) {
            view.alpha = 0f
            view.translationX = fromX.toFloat()
            view.translationY = fromY.toFloat()
        }
        view.animate().alpha(1f).translationX(0f).translationY(0f)
            .setDuration(190).setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    fun conceal(view: View, toX: Int = 0, toY: Int = 0, animated: Boolean = true, onHidden: () -> Unit = {}) {
        view.animate().cancel()
        if (view.visibility != View.VISIBLE || !animated || !TvStyle.animationsEnabled(view.context)) {
            view.visibility = View.GONE
            onHidden()
            return
        }
        view.animate().alpha(0f).translationX(toX.toFloat()).translationY(toY.toFloat())
            .setDuration(140).setInterpolator(DecelerateInterpolator())
            .withEndAction { view.visibility = View.GONE; onHidden() }.start()
    }
}
