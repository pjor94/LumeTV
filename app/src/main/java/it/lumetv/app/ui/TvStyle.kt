package it.lumetv.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.TextView

object TvStyle {
    val background = Color.rgb(10, 11, 14)
    val surface = Color.rgb(24, 26, 31)
    val raised = Color.rgb(43, 46, 53)
    val white = Color.rgb(245, 246, 248)
    val accent = Color.rgb(153, 202, 255)
    val muted = Color.rgb(162, 167, 179)
    val subtle = Color.rgb(113, 120, 134)
    val pressed = Color.rgb(207, 212, 221)
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun rounded(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT, width: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius; if (width > 0) setStroke(width, stroke)
    }
    fun focusBackground(context: Context, selected: Boolean = false, primary: Boolean = false) = StateListDrawable().apply {
        val radius = context.dp(24).toFloat()
        addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_pressed), rounded(pressed, radius))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(if (primary) pressed else raised, radius))
        addState(intArrayOf(android.R.attr.state_focused), rounded(white, radius,
            if (primary) accent else Color.TRANSPARENT, if (primary) context.dp(2) else 0))
        addState(intArrayOf(), rounded(if (primary) white else if (selected) raised else Color.TRANSPARENT, radius))
    }
    fun buttonSemantics(view: View) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
    }
    fun text(context: Context, text: String, size: Float, color: Int = white, bold: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        if (size >= 28f) letterSpacing = -0.035f
    }
    fun focusMotion(view: View, focused: Boolean, scale: Float = 1.04f) {
        // Retarget the current presentation rather than queueing focus animations.
        val animations = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
        else Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        view.animate().cancel()
        val target = if (focused) scale else 1f
        if (!animations) { view.scaleX = target; view.scaleY = target }
        else view.animate().scaleX(target).scaleY(target).setDuration(180)
            .setInterpolator(DecelerateInterpolator(1.5f)).start()
    }
    fun button(context: Context, label: String, selected: Boolean = false, action: () -> Unit) = text(context, label, 14f, if (selected) white else muted, selected).apply {
        gravity = Gravity.CENTER
        isFocusable = true; isClickable = true
        isSelected = selected
        buttonSemantics(this)
        minHeight = context.dp(42)
        setPadding(context.dp(18), context.dp(9), context.dp(18), context.dp(9))
        background = focusBackground(context, selected)
        setOnClickListener { action() }
        setOnFocusChangeListener { view, focused ->
            setTextColor(if (focused) TvStyle.background else if (isSelected) white else muted)
            focusMotion(view, focused, 1.035f)
        }
    }
    fun setButtonSelected(button:TextView,selected:Boolean) {
        if(button.isSelected==selected)return
        button.isSelected=selected
        button.typeface=Typeface.create(if(selected)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        button.background=focusBackground(button.context,selected)
        button.setTextColor(if(button.isFocused)background else if(selected)white else muted)
    }
}
