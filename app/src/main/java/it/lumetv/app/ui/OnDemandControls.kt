package it.lumetv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.annotation.DrawableRes
import it.lumetv.app.R
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.PlaybackMode
import java.util.Locale

/** Presentation and pending seeks are independent from the player's progress notifications. */
class OnDemandControls(context:Context,private val television:Boolean=true):LinearLayout(context) {
    var onPlaybackOptions:()->Unit={}
    var onSeek:(Long)->Unit={}
    var onSeekBy:(Long)->Unit={}
    var onRestart:()->Unit={}
    var onReturnLive:()->Unit={}
    var onToggle:()->Unit={}
    var onNextEpisode:(String,String)->Unit={_,_->}
    var onRetryNextEpisode:()->Unit={}
    var onAudioMode:(()->Unit)?=null
    var onPreviewRequest:(Long,(Bitmap?)->Unit)->Unit={_,answer->answer(null)}
    var onScrubbingChanged:(Boolean)->Unit={}
    var onInteraction:()->Unit={}
    var onVisibilityChanged:(Boolean)->Unit={}
    var canAutoHide:()->Boolean={true}
    var showOptions=true
        set(value){field=value;options.visibility=if(value)VISIBLE else GONE}
    private var state=PlaybackSnapshot()
    private var playerVisible=true
    private var identity=""
    private var lastInteraction=0L
    private var menuOpen=false
    private var externalBlocked=false
    private var previewRevision=0
    private val seekState=VodSeekState()
    val isScrubbing get()=seekState.pending!=null
    val holdingControls get()=isScrubbing || menuOpen
    val controlsVisible get()=visibility==VISIBLE && playerVisible
    internal val subtitleBoundary:View get()=if(previewBox.visibility==VISIBLE)previewBox else label
    private val label=TvStyle.text(context,"",if(television)24f else 20f,TvStyle.white,true).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
    private val position=TvStyle.text(context,"",if(television)16f else 14f,TvStyle.white).apply {fontFeatureSettings="tnum"}
    private val remaining=TvStyle.text(context,"",if(television)16f else 14f,TvStyle.white).apply {fontFeatureSettings="tnum";gravity=Gravity.END}
    val timeline=VodTimeline(context)
    private val hint=TvStyle.text(context,"",if(television)15f else 12f,TvStyle.white).apply {maxLines=2}
    private val previewImage=ImageView(context).apply {tag="vod_scene_preview";scaleType=ImageView.ScaleType.FIT_CENTER;visibility=GONE;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val previewTime=TvStyle.text(context,"",if(television)22f else 18f,TvStyle.white,true).apply {gravity=Gravity.CENTER;fontFeatureSettings="tnum"}
    private val previewBox=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.CENTER;visibility=GONE}
    private fun action(title:String,@DrawableRes icon:Int,primary:Boolean=false,click:()->Unit)=ImageButton(context).apply {
        val size=if(primary)if(television)60 else 52 else if(television)56 else 48
        minimumWidth=context.dp(size);minimumHeight=context.dp(size)
        val padding=context.dp((size-if(television)28 else 24)/2)
        setPadding(padding,padding,padding,padding);scaleType=ImageView.ScaleType.FIT_CENTER
        setImageResource(icon);describe(this,title)
        imageTintList=ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf()),
            intArrayOf(TvStyle.subtle,TvStyle.background,if(primary)TvStyle.background else TvStyle.white,if(primary)TvStyle.background else TvStyle.white))
        background=TvStyle.focusBackground(context,primary=primary)
        isFocusable=true;isClickable=true
        if(Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        setOnClickListener {interact();click()}
        setOnFocusChangeListener {view,focused->TvStyle.focusMotion(view,focused,1.06f);updateTimes()}
    }
    private fun describe(view:View,title:String){view.contentDescription=title;if(Build.VERSION.SDK_INT>=26)view.tooltipText=title}
    private val play=action("Pausa",R.drawable.ic_mobile_pause,true){cancelSeek();onToggle()}
    private val back=action("Indietro di 10 secondi",R.drawable.ic_vod_rewind_10){cancelSeek();onSeekBy(-10_000)}
    private val forward=action("Avanti di 30 secondi",R.drawable.ic_vod_forward_30){cancelSeek();onSeekBy(30_000)}
    private val live=action("Torna alla diretta",R.drawable.ic_vod_live){cancelSeek();onReturnLive()}
    private val options=action("Audio e opzioni",R.drawable.ic_remote_settings){cancelSeek();onPlaybackOptions()}
    private val chapterButton=action("Capitoli",R.drawable.ic_vod_chapters){showChapters()}
    private val next=action("Episodio successivo",R.drawable.ic_vod_next){
        cancelSeek();state.nextEpisode?.let {onNextEpisode(state.channel!!.id,it.id)} ?: onRetryNextEpisode()
    }
    private val more=action("Altro",R.drawable.ic_mobile_more){showMore()}
    private val nextInfo=TvStyle.text(context,"",if(television)16f else 14f,TvStyle.white).apply {
        maxLines=2;visibility=GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val hideControls=Runnable {
        if(canHide() && SystemClock.uptimeMillis()-lastInteraction>=5_000)hideControls()
    }
    private var previewTask:Runnable?=null
    init {
        orientation=VERTICAL;visibility=GONE;tag="vod_controls"
        setPadding(context.dp(if(television)40 else 0),context.dp(if(television)40 else 8),context.dp(if(television)40 else 0),context.dp(if(television)24 else 0))
        if(television)background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xC0000000.toInt(),0xF2000000.toInt()))
        previewBox.addView(previewImage,LayoutParams(context.dp(if(television)288 else 160),context.dp(if(television)162 else 90)))
        previewBox.addView(previewTime,LayoutParams(-1,-2).apply {topMargin=context.dp(6)})
        addView(previewBox,LayoutParams(-1,-2).apply {bottomMargin=context.dp(14)})
        addView(label,LayoutParams(-1,-2).apply {bottomMargin=context.dp(4)})
        val times=LinearLayout(context)
        times.addView(position,LayoutParams(0,-2,1f));times.addView(remaining,LayoutParams(0,-2,1f))
        addView(times,LayoutParams(-1,-2))
        addView(timeline,LayoutParams(-1,context.dp(48)))
        addView(hint,LayoutParams(-1,-2).apply {bottomMargin=context.dp(8)})
        val actions=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        listOf(live,play,back,forward,chapterButton,next,options,more).forEach {actions.addView(it,LayoutParams(it.minimumWidth,it.minimumHeight).apply {rightMargin=context.dp(8)})}
        addView(HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false;addView(actions)},LayoutParams(-1,-2))
        addView(nextInfo,LayoutParams(-1,-2).apply {topMargin=context.dp(8)})
        timeline.onPreview=::previewAt
        timeline.onMove={direction,repeats->if(timeline.isEnabled){beginPreview();previewAt(seekState.move(state.position,state.duration,direction,repeats))}}
        timeline.onConfirm={if(isScrubbing)commitSeek() else {interact();onToggle()}}
        timeline.onCancel=::cancelSeek
        viewTreeObserver.addOnGlobalFocusChangeListener {_,focused->if(focused!=null && contains(focused))interact()}
    }
    private fun contains(view:View):Boolean {var v:android.view.ViewParent?=view.parent;while(v!=null){if(v===this)return true;v=v.parent};return view===this}
    fun setPlayerVisible(value:Boolean) {
        if(playerVisible==value)return
        playerVisible=value
        if(!value){cancelSeek();hideControls()}else if(state.channel!=null && state.mode!=PlaybackMode.LIVE)showControls()
    }
    fun showControls(focus:Boolean=false) {
        if(!playerVisible || state.channel==null || state.mode==PlaybackMode.LIVE)return
        if(visibility!=VISIBLE){visibility=VISIBLE;onVisibilityChanged(true)}
        interact();if(focus)play.requestFocus()
    }
    private fun hideControls(){
        removeCallbacks(hideControls)
        if(visibility!=GONE){visibility=GONE;onVisibilityChanged(false)}
    }
    fun handleBack():Boolean {
        if(isScrubbing){cancelSeek();return true}
        if(television && controlsVisible){hideControls();return true}
        return false
    }
    fun movePreview(direction:Int,repeats:Int=0){if(state.seekable && state.duration>0){showControls();timeline.requestFocus();timeline.onMove(direction,repeats)}}
    fun render(value:PlaybackSnapshot) {
        val old=state;state=value
        val active=value.channel!=null && value.mode!=PlaybackMode.LIVE
        if(!active){identity="";cancelSeek();hideControls();return}
        val key=value.channel!!.sourceId+":"+value.channel.id+":"+value.playbackRevision
        val changed=key!=identity
        if(changed){identity=key;cancelSeek()}
        label.text=value.title
        timeline.duration=value.duration;timeline.position=value.position;timeline.buffered=value.bufferedPosition
        timeline.isEnabled=value.seekable && value.duration>0
        back.isEnabled=value.seekable;forward.isEnabled=value.seekable
        describe(play,if(value.ended)"Rivedi" else if(value.wantsToPlay)"Pausa" else "Riprendi")
        play.setImageResource(if(value.ended)R.drawable.ic_mobile_refresh else if(value.wantsToPlay)R.drawable.ic_mobile_pause else R.drawable.ic_mobile_play)
        live.visibility=if(value.mode==PlaybackMode.ARCHIVE)VISIBLE else GONE
        chapterButton.visibility=if(television && value.chapters.isNotEmpty())VISIBLE else GONE
        val episode=value.nextEpisode
        next.visibility=if(value.mode==PlaybackMode.VOD && (episode!=null || value.nextEpisodeError))VISIBLE else GONE
        val nextLabel=if(value.nextEpisodeError)"Riprova episodio successivo" else if(value.ended)"Guarda il prossimo episodio" else "Episodio successivo"
        describe(next,episode?.let {nextLabel+": "+it.title} ?: nextLabel)
        next.setImageResource(if(value.nextEpisodeError)R.drawable.ic_mobile_refresh else R.drawable.ic_vod_next)
        val nextText=if(!value.ended)"" else when {
            episode!=null->"Prossimo · "+episode.title
            value.nextEpisodeLoading->"Carico l’episodio successivo…"
            value.nextEpisodeError->"Impossibile trovare l’episodio successivo. Riprova."
            else->""
        }
        if(nextInfo.text.toString()!=nextText)nextInfo.text=nextText
        nextInfo.visibility=if(nextText.isBlank())GONE else VISIBLE
        updateTimes()
        val blocked=!canAutoHide();val released=externalBlocked && !blocked;externalBlocked=blocked
        if(playerVisible && (changed || old.wantsToPlay!=value.wantsToPlay || old.ended!=value.ended || old.error!=value.error))showControls()
        else if(released)interact()
        else scheduleHide()
    }
    private fun interact(){lastInteraction=SystemClock.uptimeMillis();onInteraction();scheduleHide()}
    private fun canHide():Boolean=television && controlsVisible && state.wantsToPlay && !state.ended && state.error==null && !isScrubbing && !menuOpen && canAutoHide() &&
        !(context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled && !hasReadingFocus(this)
    private fun hasReadingFocus(view:View):Boolean=view.isAccessibilityFocused || view is android.view.ViewGroup && (0 until view.childCount).any {hasReadingFocus(view.getChildAt(it))}
    private fun scheduleHide(){removeCallbacks(hideControls);if(canHide())postDelayed(hideControls,(5_000-(SystemClock.uptimeMillis()-lastInteraction)).coerceAtLeast(0))}
    private fun previewAt(point:Long){
        if(!state.seekable || state.duration<=0)return
        beginPreview()
        val position=seekState.preview(point,state.duration)
        timeline.preview=position;previewBox.visibility=VISIBLE;previewTime.text=clock(position)
        interact();updateTimes()
        previewTask?.let(::removeCallbacks)
        val request=++previewRevision
        previewTask=Runnable {onPreviewRequest(position){bitmap->post {
            if(request==previewRevision && isScrubbing && controlsVisible){previewImage.setImageBitmap(bitmap);previewImage.visibility=if(bitmap==null)GONE else VISIBLE}
        }}}.also {postDelayed(it,200)}
    }
    private fun beginPreview(){if(!isScrubbing){onScrubbingChanged(true);previewImage.setImageDrawable(null);previewImage.visibility=GONE}}
    private fun commitSeek(){val point=seekState.confirm() ?: return;clearPreview();onScrubbingChanged(false);interact();onSeek(point)}
    fun cancelSeek(){val pending=isScrubbing;seekState.cancel();clearPreview();if(pending)onScrubbingChanged(false);updateTimes();scheduleHide()}
    private fun clearPreview(){previewRevision++;previewTask?.let(::removeCallbacks);previewTask=null;timeline.preview=null;previewBox.visibility=GONE;previewImage.setImageDrawable(null)}
    private fun updateTimes(){
        val point=seekState.pending ?: state.position
        label.visibility=if(!television && isScrubbing)GONE else VISIBLE
        hint.visibility=if(!television && isScrubbing)GONE else VISIBLE
        position.text=clock(point)
        remaining.text=if(state.duration>0)"−"+clock((state.duration-point).coerceAtLeast(0)) else "Durata non disponibile"
        hint.text=when {isScrubbing->if(television)"OK conferma · Indietro annulla" else "Rilascia per riprodurre da qui"
            television && findFocus() is ImageButton->findFocus().contentDescription
            !state.seekable || state.duration<=0->"Avanzamento non disponibile per questo video"
            television->"Seleziona la barra per cercare una scena"
            else->"Trascina la barra per cercare una scena"}
    }
    private fun showMore(){
        menuOpen=true;removeCallbacks(hideControls)
        PopupMenu(context,more).apply {
            menu.add("Dall’inizio");if(!television && state.chapters.isNotEmpty())menu.add("Capitoli");if(onAudioMode!=null)menu.add("Solo audio")
            setOnMenuItemClickListener {cancelSeek();when(it.title){"Solo audio"->onAudioMode?.invoke();"Capitoli"->post {showChapters()};else->onRestart()};true}
            setOnDismissListener {menuOpen=false;interact()};show()
        }
    }
    private fun showChapters(){
        val values=state.chapters;if(values.isEmpty())return
        cancelSeek();menuOpen=true;removeCallbacks(hideControls)
        AlertDialog.Builder(context).setTitle("Capitoli").setItems(values.map {clock(it.position)+"  "+it.title}.toTypedArray()){_,index->onSeek(values[index].position)}
            .setOnDismissListener {menuOpen=false;interact()}.show()
    }
    fun focusNextEpisode():Boolean {showControls();return next.isShown && next.requestFocus()}
    override fun onDetachedFromWindow(){cancelSeek();removeCallbacks(hideControls);super.onDetachedFromWindow()}
    private fun clock(value:Long):String {val s=value.coerceAtLeast(0)/1000;return if(s>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60) else String.format(Locale.ROOT,"%d:%02d",s/60,s%60)}
}
