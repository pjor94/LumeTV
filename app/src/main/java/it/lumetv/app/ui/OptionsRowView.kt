package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import it.lumetv.app.R
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle

/** The native Options row, shared by settings and personal playlist pages. */
@SuppressLint("ViewConstructor")
internal class OptionsRowView(context:Context,private val mobile:Boolean,label:String,summary:String="",action:()->Unit):LinearLayout(context) {
    val titleView=TvStyle.text(context,label,if(mobile)16f else 18f,bold=true)
    val summaryView=TvStyle.text(context,summary,14f,TvStyle.muted)
    val label:String get()=titleView.text.toString()
    var onFocused:(()->Unit)?=null
    init {
        id=generateViewId();gravity=Gravity.CENTER_VERTICAL
        minimumHeight=context.dp(if(mobile)72 else 64)
        setPadding(context.dp(if(mobile)8 else 18),context.dp(if(mobile)14 else 10),context.dp(if(mobile)8 else 18),context.dp(if(mobile)14 else 10))
        isClickable=true;isFocusable=true;isSaveEnabled=false
        if(!mobile){nextFocusLeftId=id;nextFocusRightId=id}
        descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        background=if(mobile)MobileStyle.ripple(context) else TvStyle.focusBackground(context)
        TvStyle.buttonSemantics(this)
        val copy=LinearLayout(context).apply {
            orientation=VERTICAL
            addView(titleView,LayoutParams(-1,-2))
            addView(summaryView,LayoutParams(-1,-2).apply {topMargin=context.dp(if(mobile)6 else 5)})
        }
        titleView.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        summaryView.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(copy,LayoutParams(0,-2,1f))
        if(mobile)addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_mobile_chevron)
            imageTintList=ColorStateList.valueOf(TvStyle.muted)
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        },LayoutParams(context.dp(24),context.dp(24)).apply {leftMargin=context.dp(16)})
        setOnClickListener {action()}
        setOnFocusChangeListener {view,focused->
            if(!mobile){
                titleView.setTextColor(if(focused)TvStyle.background else TvStyle.white)
                summaryView.setTextColor(if(focused)TvStyle.raised else TvStyle.muted)
                TvStyle.focusMotion(view,focused,1.015f)
            }
            if(focused)onFocused?.invoke()
        }
        bind(label,summary)
    }
    fun bind(label:String,summary:String="") {
        titleView.text=label;summaryView.text=summary
        summaryView.visibility=if(summary.isBlank())GONE else VISIBLE
        contentDescription=if(summary.isBlank())label else "$label: $summary"
        if(hasFocus())onFocused?.invoke()
    }
    companion object {
        fun divider(context:Context)=View(context).apply {setBackgroundColor(TvStyle.surface);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    }
}
