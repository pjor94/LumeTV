package it.lumetv.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.PlaybackMode
import java.util.Locale

/** The same transport works with touch, D-pad and accessibility actions. */
class OnDemandControls(context:Context):LinearLayout(context) {
    var onPlaybackOptions: () -> Unit = {}
    var showOptions: Boolean = true
        set(value) { field = value; options.visibility = if (value) View.VISIBLE else View.GONE }
    var onSeek:(Long)->Unit={}
    var onSeekBy:(Long)->Unit={}
    var onRestart:()->Unit={}
    var onReturnLive:()->Unit={}
    var onToggle:()->Unit={}
    var onNextEpisode:(String,String)->Unit={_,_->}
    var onRetryNextEpisode:()->Unit={}
    private val label=TvStyle.text(context,"",14f,TvStyle.white).apply {maxLines=2}
    private val position=TvStyle.text(context,"",14f,TvStyle.white).apply {fontFeatureSettings="tnum"}
    private var duration=0L
    private var dragging=false
    private var promoteNext=false
    private val seek=SeekBar(context).apply {max=10_000;minimumHeight=context.dp(48);progressTintList=ColorStateList.valueOf(TvStyle.accent);thumbTintList=ColorStateList.valueOf(TvStyle.accent);contentDescription="Posizione del video"}
    private val play=TvStyle.button(context,"Pausa"){onToggle()}
    private val back=TvStyle.button(context,"−10 s"){onSeekBy(-10_000)}
    private val forward=TvStyle.button(context,"+30 s"){onSeekBy(30_000)}
    private val restart=TvStyle.button(context,"Dall’inizio"){onRestart()}
    private val live=TvStyle.button(context,"Torna alla diretta"){onReturnLive()}
    private val options=TvStyle.button(context,"Opzioni"){onPlaybackOptions()}
    private val next=TvStyle.button(context,"Episodio successivo"){}
    private val nextInfo=TvStyle.text(context,"",14f,TvStyle.muted).apply {
        maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;visibility=GONE
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    init {
        orientation=VERTICAL;setPadding(context.dp(12),context.dp(8),context.dp(12),context.dp(8));setBackgroundColor(0xE610151E.toInt());visibility=GONE
        addView(label,LayoutParams(-1,-2));addView(position,LayoutParams(-1,-2));addView(seek,LayoutParams(-1,context.dp(48)))
        val actions=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        listOf(live,play,next,back,forward,restart,options).forEach {it.minimumHeight=context.dp(48);actions.addView(it,LayoutParams(-2,-2).apply {rightMargin=context.dp(8)})}
        next.setOnFocusChangeListener {view,focused->
            next.setTextColor(if(focused)TvStyle.background else if(promoteNext)TvStyle.white else TvStyle.muted)
            TvStyle.focusMotion(view,focused,1.035f)
        }
        addView(HorizontalScrollView(context).apply {isHorizontalScrollBarEnabled=false;addView(actions)},LayoutParams(-1,-2))
        addView(nextInfo,LayoutParams(-1,-2).apply {topMargin=context.dp(4)})
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar:SeekBar){dragging=true}
            override fun onStopTrackingTouch(seekBar:SeekBar){dragging=false;onSeek(duration*seekBar.progress/10_000)}
            override fun onProgressChanged(seekBar:SeekBar,value:Int,fromUser:Boolean){if(fromUser && !dragging)onSeek(duration*value/10_000)}
        })
    }
    fun render(state:PlaybackSnapshot) {
        visibility=if(state.channel!=null && state.mode!=PlaybackMode.LIVE)View.VISIBLE else View.GONE
        if(visibility!=VISIBLE)return
        duration=state.duration
        label.text=(if(state.mode==PlaybackMode.ARCHIVE)"Archivio · " else "Video · ")+state.title
        position.text=clock(state.position)+" / "+if(duration>0)clock(duration) else "Durata non disponibile"
        if(!dragging)seek.progress=if(duration>0)(state.position*10_000/duration).toInt().coerceIn(0,10_000) else 0
        seek.isEnabled=state.seekable && duration>0
        back.isEnabled=state.seekable;forward.isEnabled=state.seekable
        restart.isEnabled=state.ready || state.ended || state.error!=null
        live.visibility=if(state.mode==PlaybackMode.ARCHIVE)VISIBLE else GONE
        play.text=if(state.ended)"Rivedi" else if(state.wantsToPlay)"Pausa" else "Riprendi"
        options.text=if(state.options.sleepRemaining>0)"Timer · "+it.lumetv.app.playback.sleepTimerLabel(state.options.sleepRemaining).substringBefore(" rimanenti") else "Opzioni"
        val episode=state.nextEpisode
        if(promoteNext!=state.ended){promoteNext=state.ended;next.background=TvStyle.focusBackground(context,promoteNext)}
        next.setTextColor(if(next.hasFocus())TvStyle.background else if(promoteNext)TvStyle.white else TvStyle.muted)
        next.visibility=if(state.mode==PlaybackMode.VOD && (episode!=null || state.nextEpisodeError))VISIBLE else GONE
        next.text=if(state.nextEpisodeError)"Riprova episodio successivo" else if(state.ended)"Guarda il prossimo episodio" else "Episodio successivo"
        next.contentDescription=if(episode!=null)"${next.text}: ${episode.title}" else next.text
        next.setOnClickListener {if(episode!=null)onNextEpisode(state.channel!!.id,episode.id) else onRetryNextEpisode()}
        val nextText=if(!state.ended)"" else when {
            episode!=null->"Prossimo · ${episode.title}"
            state.nextEpisodeLoading->"Carico l’episodio successivo…"
            state.nextEpisodeError->"Impossibile trovare l’episodio successivo. Riprova."
            else->""
        }
        if(nextInfo.text.toString()!=nextText)nextInfo.text=nextText
        nextInfo.maxLines=if(resources.configuration.screenHeightDp<480)1 else 2
        nextInfo.visibility=if(nextText.isBlank())GONE else VISIBLE
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val available=MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight-context.dp(8)
        if(available>0 && next.maxWidth!=available)next.maxWidth=available
        val lines=if(resources.configuration.screenHeightDp<480)1 else 2
        if(nextInfo.maxLines!=lines)nextInfo.maxLines=lines
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    fun focusNextEpisode():Boolean=next.isShown && next.requestFocus()
    private fun clock(value:Long):String {val s=value.coerceAtLeast(0)/1000;return if(s>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60) else String.format(Locale.ROOT,"%d:%02d",s/60,s%60)}
}
