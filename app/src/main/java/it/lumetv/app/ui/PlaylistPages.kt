package it.lumetv.app.ui

import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** In-window navigation. Retained parent views keep edits, scroll and D-pad focus when a child closes. */
internal class PlaylistPages(val activity:ComponentActivity,val scope:CoroutineScope,val mobile:Boolean,
    private val close:()->Unit):FrameLayout(activity) {
    private val stack=mutableListOf<Page>()
    val current:Page? get()=stack.lastOrNull()
    var onChanged:()->Unit={}
    val secondary=TvStyle.muted

    inner class Page internal constructor(val key:String,val title:String,val subtitle:String,val navigation:Boolean) {
        val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;isSaveEnabled=false
            if(!mobile)setPadding(activity.dp(46),activity.dp(26),activity.dp(46),activity.dp(20)) else setPadding(0,0,0,activity.dp(12))}
        val heading=TvStyle.text(activity,title,if(mobile)28f else 35f,bold=true).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;ViewCompat.setAccessibilityHeading(this,true)}
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;isSaveEnabled=false
            if(mobile)setPadding(activity.dp(20),activity.dp(8),activity.dp(20),activity.dp(24))
            else if(navigation)setPadding(activity.dp(4),activity.dp(4),activity.dp(4),activity.dp(4))}
        val scroll=ScrollView(activity).apply {isSaveEnabled=false;addView(body,LayoutParams(-1,-2));clipToPadding=false;clipChildren=true}
        private val detailTitle=TvStyle.text(activity,title,25f,bold=true).apply {maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;ViewCompat.setAccessibilityHeading(this,true)}
        private val detailText=TvStyle.text(activity,subtitle,16f,TvStyle.muted)
        private val status=TvStyle.text(activity,"",15f,secondary).apply {visibility=GONE;maxLines=6;ViewCompat.setAccessibilityLiveRegion(this,ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)}
        private val discard=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;visibility=GONE}
        private val actions=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
        private val actionViews=mutableListOf<TextView>()
        private val busyControls=linkedMapOf<View,Boolean>()
        var alive=true;private set
        var busy=false;private set
        var onRefresh:(()->Unit)?=null
        var onDispose:()->Unit={}
        var initialFocus:View?=null
        private var backGuard:(()->Boolean)?=null
        private var lastFocus:View?=null
        init {
            val header=if(mobile)MobileStyle.pageHeader(activity,heading,"Indietro"){back()}.apply {getChildAt(0).tag="playlist_page_back"}
                else LinearLayout(activity).apply {
                    gravity=Gravity.CENTER_VERTICAL
                    addView(heading,LinearLayout.LayoutParams(0,-2,1f))
                    addView(button("Indietro"){back()}.apply {tag="playlist_page_back"},LinearLayout.LayoutParams(-2,-2).apply {leftMargin=activity.dp(20)})
                }
            root.addView(header,LinearLayout.LayoutParams(-1,-2))
            if(subtitle.isNotBlank() && (mobile || !navigation))root.addView(TvStyle.text(activity,subtitle,if(mobile)14f else 15f,secondary).apply {maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply {
                topMargin=activity.dp(8);if(mobile){leftMargin=activity.dp(20);rightMargin=activity.dp(20);bottomMargin=activity.dp(8)}
            })
            if(!mobile && navigation) {
                val columns=LinearLayout(activity)
                columns.addView(scroll,LinearLayout.LayoutParams(0,-1,1f).apply {rightMargin=activity.dp(32)})
                val details=LinearLayout(activity).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(0,activity.dp(12),activity.dp(4),activity.dp(8))
                    addView(detailTitle,LinearLayout.LayoutParams(-1,-2))
                    addView(detailText,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(12)})
                }
                columns.addView(ScrollView(activity).apply {isSaveEnabled=false;isFocusable=false;descendantFocusability=FOCUS_BLOCK_DESCENDANTS;addView(details,LayoutParams(-1,-2))},LinearLayout.LayoutParams(0,-1,1f))
                root.addView(columns,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=activity.dp(20)})
            } else root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply {topMargin=activity.dp(if(mobile)0 else 20)})
            if(mobile)listOf(status,discard,actions).forEach {it.setPadding(activity.dp(20),0,activity.dp(20),0)}
            root.addView(status,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8)})
            root.addView(discard,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8)})
            root.addView(actions,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8)})
            if(!mobile && navigation)root.addView(TvStyle.text(activity,"↑ ↓  Scegli     OK  Apri     Indietro  Torna",12f,TvStyle.muted),LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(12)})
        }
        fun text(value:String,size:Float=15f,muted:Boolean=true):TextView=TvStyle.text(activity,value,size,if(muted)secondary else TvStyle.white).also {
            body.addView(it,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(12)})
        }
        fun section(value:String,first:Boolean=false) {body.addView(TvStyle.text(activity,value,14f,TvStyle.accent,bold=true).apply {ViewCompat.setAccessibilityHeading(this,true)},LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(if(first || body.childCount==0)8 else 24);bottomMargin=activity.dp(8)})}
        fun addRow(container:LinearLayout,label:String,detail:String="",action:()->Unit):OptionsRowView {
            val row=OptionsRowView(activity,mobile,label,detail,action).apply {
                titleView.maxLines=2;titleView.ellipsize=android.text.TextUtils.TruncateAt.END
                summaryView.maxLines=3;summaryView.ellipsize=android.text.TextUtils.TruncateAt.END
                onFocused={if(!mobile && navigation){detailTitle.text=this.label;detailText.text=summaryView.text}}
            }
            container.addView(row,LinearLayout.LayoutParams(-1,-2).apply {if(!mobile)bottomMargin=activity.dp(2)})
            if(mobile)container.addView(OptionsRowView.divider(activity),LinearLayout.LayoutParams(-1,activity.dp(1)).apply {leftMargin=activity.dp(8);rightMargin=activity.dp(8)})
            if(!mobile && navigation && initialFocus==null)initialFocus=row
            return row
        }
        fun row(label:String,detail:String="",action:()->Unit)=addRow(body,label,detail,action)
        fun field(label:String,value:String,limit:Int=100,multiline:Boolean=false):EditText {
            body.addView(TvStyle.text(activity,label,14f,secondary),LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(12);bottomMargin=activity.dp(4)})
            return EditText(activity).apply {
                contentDescription=label;setText(value);textSize=16f;setTextColor(TvStyle.white);setHintTextColor(secondary)
                minimumHeight=activity.dp(48);filters=arrayOf(InputFilter.LengthFilter(limit));isSaveEnabled=false
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                if(multiline){inputType=inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE;minLines=2;maxLines=5;gravity=Gravity.TOP}else setSingleLine()
                if(android.os.Build.VERSION.SDK_INT>=26)importantForAutofill=IMPORTANT_FOR_AUTOFILL_NO
                addTextChangedListener(watcher {message("");discard.visibility=GONE})
                body.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(8)})
            }
        }
        fun toggle(label:String,checked:Boolean):CheckBox=CheckBox(activity).apply {
            text=label;textSize=16f;isChecked=checked;minimumHeight=activity.dp(48);isSaveEnabled=false
            setTextColor(controlText());buttonTintList=controlTint();background=if(mobile)MobileStyle.ripple(activity) else TvStyle.focusBackground(activity)
            setPadding(activity.dp(6),activity.dp(10),activity.dp(6),activity.dp(10))
            body.addView(this,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(8)})
        }
        fun choice(label:String,labels:List<String>,selected:Int):RadioGroup {
            section(label)
            return RadioGroup(activity).apply {
                orientation=LinearLayout.VERTICAL;isSaveEnabled=false
                labels.forEachIndexed {index,value->addView(RadioButton(activity).apply {
                    id=View.generateViewId();tag=index;text=value;textSize=16f;minimumHeight=activity.dp(48);isSaveEnabled=false
                    setTextColor(controlText());buttonTintList=controlTint();background=if(mobile)MobileStyle.ripple(activity) else TvStyle.focusBackground(activity)
                    setPadding(activity.dp(6),activity.dp(8),activity.dp(6),activity.dp(8))
                },RadioGroup.LayoutParams(-1,-2))}
                check(getChildAt(selected.coerceIn(0,labels.lastIndex)).id)
                body.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(8)})
            }
        }
        fun action(label:String,primary:Boolean=false,block:()->Unit):TextView=button(label,primary,block).also {
            actionViews+=it;actions.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply {if(actionViews.size>1)leftMargin=activity.dp(8)})
        }
        fun message(value:String,error:Boolean=false) {if(!alive)return;status.text=value;status.visibility=if(value.isBlank())GONE else VISIBLE
            status.setTextColor(if(error)TvStyle.white else secondary)}
        internal fun clearMessage(expected:String){if(status.text.toString()==expected)message("")}
        fun busy(value:Boolean,message:String="") {
            busy=value
            if(value) {
                fun disable(view:View) {
                    if(view.isClickable || view is EditText){busyControls[view]=view.isEnabled;view.isEnabled=false}
                    if(view is ViewGroup)for(i in 0 until view.childCount)disable(view.getChildAt(i))
                }
                disable(body)
                actionViews.forEach {busyControls[it]=it.isEnabled;it.isEnabled=false;it.alpha=.5f}
            } else {busyControls.forEach {(view,enabled)->view.isEnabled=enabled};busyControls.clear();actionViews.forEach {it.alpha=1f}}
            if(message.isNotBlank())this.message(message)
        }
        fun protect(fingerprint:()->String) {
            val original=fingerprint()
            backGuard={if(fingerprint()==original)false else {
                hideKeyboard();discard.removeAllViews();discard.visibility=VISIBLE
                discard.addView(TvStyle.text(activity,"Hai modifiche non salvate.",15f,TvStyle.white))
                val buttons=LinearLayout(activity)
                val keep=button("Continua a modificare"){discard.visibility=GONE}
                buttons.addView(keep,LinearLayout.LayoutParams(0,-2,1f))
                buttons.addView(button("Scarta modifiche"){pop(this,true)},LinearLayout.LayoutParams(0,-2,1f))
                discard.addView(buttons);keep.requestFocus();true
            }}
        }
        internal fun canLeave():Boolean {
            if(busy){message("Attendi il completamento dell’operazione.");return false}
            return backGuard?.invoke()!=true
        }
        internal fun rememberFocus(){lastFocus=root.findFocus()}
        internal fun focus(){root.post {if(alive && current===this && root.findFocus()==null){when {
            lastFocus?.isShown==true->lastFocus?.requestFocus()
            initialFocus?.isShown==true->initialFocus?.requestFocus()
            else->root.findViewWithTag<View>("playlist_page_back")?.requestFocus()
        }}}}
        internal fun dispose(){alive=false;onDispose()}
    }

    fun button(label:String,primary:Boolean=false,action:()->Unit):TextView =
        (if(mobile)MobileStyle.button(activity,label,primary,action) else TvStyle.button(activity,label,primary,action)).apply {
            minimumHeight=activity.dp(48);maxLines=3
            if(!mobile){setTextColor(controlText());setOnFocusChangeListener {view,focused->TvStyle.focusMotion(view,focused,1f)}}
        }
    fun push(key:String,title:String,subtitle:String="",replace:Boolean=false,navigation:Boolean=false,build:(Page)->Unit):Page {
        hideKeyboard();current?.rememberFocus()
        if(replace && stack.isNotEmpty()){val old=stack.removeAt(stack.lastIndex);old.dispose()}
        val page=Page(key,title,subtitle,navigation);stack+=page
        build(page);display(page);return page
    }
    fun pop(page:Page?=current,force:Boolean=false) {
        if(page==null || current!==page || !force && !page.canLeave())return
        if(stack.size==1){close();return}
        stack.removeAt(stack.lastIndex);page.dispose();display(stack.last());current?.onRefresh?.invoke()
    }
    fun back():Boolean {if(stack.isEmpty())return false;pop();return true}
    fun backTo(key:String) {if(stack.none {it.key==key})return;while(current?.key!=key){val page=stack.removeAt(stack.lastIndex);page.dispose()};display(stack.last());current?.onRefresh?.invoke()}
    fun reset(){stack.asReversed().forEach {it.dispose()};stack.clear();removeAllViews()}
    fun refresh(){current?.onRefresh?.invoke()}
    fun focus(){current?.focus()}
    fun isCurrent(page:Page)=current===page && page.alive
    fun contains(key:String)=stack.any {it.key==key}
    fun hideKeyboard(){(activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken,0)}
    fun selected(group:RadioGroup)=group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Int ?: 0
    fun run(page:Page,message:String,operation:suspend ()->Unit) {
        if(page.busy || !page.alive)return
        page.busy(true,message)
        scope.launch {try {operation()}catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){page.message(safeMessage(error),true)}finally {if(page.alive){page.busy(false);page.clearMessage(message)}}}
    }
    fun saved(page:Page,message:String="Modifiche salvate."){if(isCurrent(page)){pop(page,true);current?.message(message)}}
    private fun display(page:Page){removeAllViews();addView(page.root,LayoutParams(-1,-1));ViewCompat.setAccessibilityPaneTitle(this,page.title);onChanged();page.focus()}
    private fun controlText()=if(mobile)ColorStateList.valueOf(TvStyle.white) else ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(TvStyle.background,TvStyle.white))
    private fun controlTint()=if(mobile)ColorStateList.valueOf(TvStyle.accent) else ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(TvStyle.background,TvStyle.accent))

    fun <T> paged(title:String,subtitle:String="",key:String="picker",load:suspend(String,Int)->Pair<List<T>,Int>,label:(T)->String,choose:(T)->Unit) {
        push(key,title,subtitle){page->
            val query=page.field("Cerca","",300)
            val picker=ContinuousPicker(activity,scope,mobile,label){item->if(!page.busy){hideKeyboard();choose(item)}}
            if(mobile)picker.view.setPadding(activity.dp(20),0,activity.dp(20),activity.dp(24))
            val container=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
            val index=page.root.indexOfChild(page.scroll)
            page.scroll.removeView(page.body);page.root.removeView(page.scroll)
            container.addView(page.body,LinearLayout.LayoutParams(-1,-2))
            container.addView(picker.view,LinearLayout.LayoutParams(-1,0,1f))
            page.root.addView(container,index,LinearLayout.LayoutParams(-1,0,1f))
            var revision=0
            fun reload(preserve:Boolean=false){
                val text=query.text.toString().trim()
                picker.bind(text to ++revision,if(preserve)picker.catalog.anchor else 0,restoreFocus=preserve){offset->load(text,offset)}
            }
            query.addTextChangedListener(watcher {reload()})
            page.onRefresh={reload(true)};page.onDispose=picker::close;reload()
        }
    }
    companion object {
        fun watcher(changed:()->Unit)=object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){changed()};override fun afterTextChanged(s:Editable?) {}}
        fun safeMessage(error:Exception):String=when(error) {
            is it.lumetv.core.PlaylistFormatException,is it.lumetv.app.data.PlaylistFetchException,is it.lumetv.app.data.SourceException->error.message.orEmpty()
            is java.io.IOException->if(error.javaClass==java.io.IOException::class.java)error.message ?: "Operazione non riuscita. Riprova." else "Connessione non riuscita. Controlla l’indirizzo e riprova."
            else->"Operazione non riuscita. La copia precedente è stata conservata."
        }
    }
}
