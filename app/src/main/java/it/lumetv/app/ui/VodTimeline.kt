package it.lumetv.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import it.lumetv.app.ui.TvStyle.dp

/** The played, buffered and remaining ranges share one time scale. */
class VodTimeline(context:Context):View(context) {
    var duration=0L;set(value){field=value;invalidate()}
    var position=0L;set(value){field=value;invalidate()}
    var buffered=0L;set(value){field=value;invalidate()}
    var preview:Long?=null;set(value){field=value;invalidate()}
    var onPreview:(Long)->Unit={}
    var onMove:(Int,Int)->Unit={_,_->}
    var onConfirm:()->Unit={}
    var onCancel:()->Unit={}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge=context.dp(10).toFloat()
    init {isFocusable=true;isFocusableInTouchMode=true;isClickable=true;minimumHeight=context.dp(48);contentDescription="Posizione del video";tag="vod_timeline"
        if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val y=height/2f;val left=edge;val right=(width-edge).coerceAtLeast(left)
        val thick=context.dp(if(isFocused)6 else 4).toFloat()
        fun x(time:Long)=left+(right-left)*(time.toDouble()/duration.coerceAtLeast(1)).toFloat().coerceIn(0f,1f)
        fun line(end:Float,color:Int){paint.color=color;paint.strokeWidth=thick;paint.strokeCap=Paint.Cap.ROUND;canvas.drawLine(left,y,end,y,paint)}
        line(right,0x66FFFFFF)
        if(duration>0){line(x(buffered),0xBBFFFFFF.toInt());line(x(position),TvStyle.accent)}
        val cursor=x(preview ?: position)
        if(isEnabled){paint.color=TvStyle.white;canvas.drawCircle(cursor,y,context.dp(if(isFocused || preview!=null)7 else 4).toFloat(),paint)}
        if(isFocused){paint.color=TvStyle.white;paint.style=Paint.Style.STROKE;paint.strokeWidth=context.dp(2).toFloat();canvas.drawCircle(cursor,y,context.dp(11).toFloat(),paint);paint.style=Paint.Style.FILL}
    }
    override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:android.graphics.Rect?){super.onFocusChanged(gainFocus,direction,previouslyFocusedRect);invalidate()}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(!isEnabled)return super.onKeyDown(keyCode,event)
        return when(keyCode){
            KeyEvent.KEYCODE_DPAD_LEFT->{onMove(-1,event.repeatCount);true}
            KeyEvent.KEYCODE_DPAD_RIGHT->{onMove(1,event.repeatCount);true}
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->{if(event.repeatCount==0)performClick();true}
            else->super.onKeyDown(keyCode,event)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled || duration<=0)return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE->{parent.requestDisallowInterceptTouchEvent(true);onPreview((((event.x-edge)/(width-2*edge).coerceAtLeast(1f)).coerceIn(0f,1f)*duration).toLong());return true}
            MotionEvent.ACTION_UP->{parent.requestDisallowInterceptTouchEvent(false);performClick();return true}
            MotionEvent.ACTION_CANCEL->{parent.requestDisallowInterceptTouchEvent(false);onCancel();return true}
        }
        return super.onTouchEvent(event)
    }
    override fun performClick():Boolean{if(!isEnabled || duration<=0)return false;super.performClick();onConfirm();return true}
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
        super.onInitializeAccessibilityNodeInfo(info);info.className=SeekBar::class.java.name
        if(duration>0)info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0f,duration.toFloat(),(preview ?: position).coerceIn(0,duration).toFloat())
        if(isEnabled){if(android.os.Build.VERSION.SDK_INT>=24)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)}
    }
    override fun performAccessibilityAction(action:Int,arguments:Bundle?):Boolean {
        if(action==AccessibilityNodeInfo.ACTION_CLICK)return performClick()
        if(isEnabled && android.os.Build.VERSION.SDK_INT>=24 && action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id){
            onPreview(arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.toLong() ?: position);onConfirm();return true
        }
        if(isEnabled)when(action){
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->{onMove(1,0);onConfirm();return true}
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->{onMove(-1,0);onConfirm();return true}
        }
        return super.performAccessibilityAction(action,arguments)
    }
}
