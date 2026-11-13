package it.lumetv.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Retained routes inside the Activity window; closing a choice restores the originating control. */
internal class CatalogPanelHost(context:Context,private val trapFocus:Boolean=true):FrameLayout(context) {
    data class Route(val view:View,val dispose:()->Unit,var focus:View?=null)
    private val routes=mutableListOf<Route>()
    private var origin:View?=null
    private val accessibility=mutableMapOf<View,Int>()
    var parked=false;private set
    val isOpen get()=visibility==VISIBLE && routes.isNotEmpty()
    val currentView get()=routes.lastOrNull()?.view
    init {visibility=GONE;isClickable=true;isFocusable=false;tag=TAG}
    fun push(view:View,dispose:()->Unit={}) {
        hideKeyboard()
        if(routes.isEmpty())origin=rootView.findFocus()
        else routes.last().let {it.focus=it.view.findFocus();it.view.visibility=GONE}
        routes+=Route(view,dispose)
        addView(view,LayoutParams(-1,-1));visibility=VISIBLE;parked=false
        protectBackground(true)
        view.post {val targets=ArrayList<View>();view.addFocusables(targets,View.FOCUS_FORWARD);targets.firstOrNull {it.isShown}?.requestFocus()}
    }
    fun back():Boolean {
        if(!isOpen)return false
        hideKeyboard()
        val old=routes.removeAt(routes.lastIndex);old.dispose();removeView(old.view)
        if(routes.isEmpty()) {visibility=GONE;protectBackground(false);origin?.takeIf {it.isShown}?.requestFocus();origin=null}
        else routes.last().let {route->route.view.visibility=VISIBLE;route.view.post {route.focus?.takeIf {it.isShown}?.requestFocus()}}
        return true
    }
    fun close() {hideKeyboard();routes.asReversed().forEach {it.dispose()};routes.clear();removeAllViews();visibility=GONE;parked=false;protectBackground(false);origin?.takeIf {it.isShown}?.requestFocus();origin=null}
    fun park() {if(routes.isNotEmpty()){hideKeyboard();routes.last().let {it.focus=it.view.findFocus()};visibility=GONE;parked=true;protectBackground(false)}}
    fun resume():Boolean {
        if(!parked || routes.isEmpty())return false
        parked=false;visibility=VISIBLE
        protectBackground(true)
        routes.last().let {route->route.view.post {route.focus?.takeIf {it.isShown}?.requestFocus()}}
        return true
    }
    private fun hideKeyboard(){(context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(windowToken,0)}
    private fun protectBackground(hide:Boolean) {
        if(hide) {
            val root=parent as? ViewGroup ?: return
            for(i in 0 until root.childCount)root.getChildAt(i).takeIf {it!==this}?.let {view->
                accessibility.putIfAbsent(view,view.importantForAccessibility);view.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        } else {accessibility.forEach {(view,value)->view.importantForAccessibility=value};accessibility.clear()}
    }
    override fun focusSearch(focused:View,direction:Int):View? {
        val candidate=super.focusSearch(focused,direction) ?: return focused
        if(!trapFocus)return candidate
        var parent=candidate.parent
        while(parent!=null){if(parent===this)return candidate;parent=parent.parent}
        return focused
    }
    companion object {
        private const val TAG="lume_catalog_panels"
        fun get(activity:ComponentActivity):CatalogPanelHost {
            val content=activity.findViewById<ViewGroup>(android.R.id.content)
            val root=(content.getChildAt(0) as? FrameLayout) ?: content
            val existing=(0 until root.childCount).map {root.getChildAt(it)}.filterIsInstance<CatalogPanelHost>().firstOrNull {it.tag==TAG}
            return existing ?: CatalogPanelHost(activity).also {host->
                root.addView(host,ViewGroup.LayoutParams(-1,-1))
                activity.lifecycle.addObserver(object:DefaultLifecycleObserver {override fun onDestroy(owner:LifecycleOwner){host.close();root.removeView(host)}})
            }
        }
    }
}
