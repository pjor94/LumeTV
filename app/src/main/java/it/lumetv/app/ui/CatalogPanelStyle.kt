package it.lumetv.app.ui

import android.content.Context
import android.text.InputFilter
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import it.lumetv.app.ui.mobile.MobileStyle
import it.lumetv.app.ui.TvStyle.dp

internal object CatalogPanelStyle {
    fun column(context:Context,mobile:Boolean,title:String,back:()->Unit)=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL
        if(mobile)setPadding(context.dp(16),context.dp(8),context.dp(16),context.dp(8))
        else setPadding(context.dp(32),context.dp(20),context.dp(32),context.dp(16))
        background=if(mobile)TvStyle.rounded(TvStyle.background,0f) else TvStyle.rounded(TvStyle.background,context.dp(24).toFloat())
        ViewCompat.setAccessibilityPaneTitle(this,title)
        val heading=TvStyle.text(context,title,if(mobile)24f else 28f,bold=true).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;ViewCompat.setAccessibilityHeading(this,true)}
        val row=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        row.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(button(context,mobile,"Indietro",back),LinearLayout.LayoutParams(-2,context.dp(48)))
        addView(row,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=context.dp(8)})
    }
    fun button(context:Context,mobile:Boolean,label:String,action:()->Unit)=
        (if(mobile)MobileStyle.button(context,label,action=action) else TvStyle.button(context,label,action=action)).apply {
            minimumHeight=context.dp(48);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        }
    fun search(context:Context,hint:String,changed:()->Unit)=EditText(context).apply {
        this.hint=hint;contentDescription=hint;textSize=16f;setTextColor(TvStyle.white);setHintTextColor(TvStyle.muted)
        setSingleLine();minimumHeight=context.dp(48);filters=arrayOf(InputFilter.LengthFilter(300));isSaveEnabled=false
        imeOptions=EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background=TvStyle.rounded(TvStyle.surface,context.dp(12).toFloat());setPadding(context.dp(14),context.dp(8),context.dp(14),context.dp(8))
        addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){changed()};override fun afterTextChanged(s:Editable?) {}})
    }
}
