package it.lumetv.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import it.lumetv.app.R
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle
import org.json.JSONObject
import java.util.Locale

/** Presentation only: the phone screen keeps connection, authorization and command ownership. */
@android.annotation.SuppressLint("ViewConstructor")
internal class CompanionRemoteView(
    context:Context,
    private val command:(String,JSONObject)->Unit,
    private val tracks:(String,Int,String)->Unit,
    private val sleep:()->Unit,
):LinearLayout(context) {
    private var state:JSONObject?=null
    private var available=false
    private var navigationAvailable=false
    private var dragging=false
    private var dragSession:String?=null
    private var requestedPosition:Pair<String,Long>?=null
    private var expanded=false
    private var wide:Boolean?=null
    private val connection=TvStyle.text(context,"Collegamento alla TV…",13f,TvStyle.muted).apply {
        tag="companion_connection";accessibilityLiveRegion=ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val title=TvStyle.text(context,"Attendo lo stato della TV…",18f,bold=true).apply {
        tag="companion_title";maxLines=3;ellipsize=TextUtils.TruncateAt.END
        ViewCompat.setAccessibilityHeading(this,true)
    }
    private val detail=TvStyle.text(context,"",14f,TvStyle.muted)
    private val elapsed=TvStyle.text(context,"",13f,TvStyle.muted).apply {fontFeatureSettings="tnum"}
    private val duration=TvStyle.text(context,"",13f,TvStyle.muted).apply {fontFeatureSettings="tnum";gravity=Gravity.END}
    private val seek=SeekBar(context).apply {
        tag="companion_seek";max=1000;minimumHeight=context.dp(48)
        progressTintList=ColorStateList.valueOf(TvStyle.accent)
        thumbTintList=ColorStateList.valueOf(TvStyle.accent)
        progressBackgroundTintList=ColorStateList.valueOf(TvStyle.raised)
        contentDescription="Posizione del video sulla TV"
    }
    private val timeline=LinearLayout(context).apply {orientation=VERTICAL}
    private val metadata=LinearLayout(context).apply {orientation=VERTICAL}
    private val content=LinearLayout(context).apply {orientation=VERTICAL}
    private val navigation=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL}
    private val directionPad=FrameLayout(context).apply {
        tag="companion_direction_pad";background=TvStyle.rounded(TvStyle.surface,context.dp(108).toFloat())
    }
    private val directionTargets=linkedMapOf<String,View>()
    private val shortcuts=LinearLayout(context).apply {gravity=Gravity.TOP}
    private val playback=LinearLayout(context).apply {orientation=VERTICAL}
    private val navigationButtons=mutableListOf<View>()
    private val breathingRoom=View(context).apply {importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;minimumHeight=context.dp(24)}
    private val transport=LinearLayout(context).apply {orientation=VERTICAL;gravity=Gravity.CENTER}
    private val buttons=LinearLayout(context).apply {
        gravity=Gravity.TOP
        setPadding(context.dp(8),context.dp(12),context.dp(8),context.dp(12))
        background=TvStyle.rounded(TvStyle.surface,context.dp(16).toFloat())
    }
    private val previous=TransportButton(false)
    private val toggle=TransportButton(true)
    private val next=TransportButton(false)
    private val timer=TvStyle.text(context,"",13f,TvStyle.accent).apply {gravity=Gravity.CENTER;fontFeatureSettings="tnum";visibility=GONE}
    private val following=MobileStyle.button(context,"Prossimo episodio") {send("nextEpisode")}.apply {tag="companion_control_nextEpisode";visibility=GONE}
    private val live=MobileStyle.button(context,"Torna alla diretta") {send("live")}.apply {tag="companion_control_live";visibility=GONE}
    private val options=LinearLayout(context).apply {orientation=VERTICAL;visibility=GONE;tag="companion_options"}
    private val optionButtons=mutableListOf<Pair<View,(JSONObject)->Boolean>>()
    private val disclosure=OptionsRowView(context,true,"Opzioni riproduzione") {expand(!expanded)}.apply {
        tag="companion_options_toggle";minimumHeight=context.dp(56)
        setPadding(context.dp(12),context.dp(12),context.dp(12),context.dp(12))
        titleView.textSize=14f
    }

    init {
        orientation=VERTICAL
        addView(connection,LayoutParams(-1,-2).apply {bottomMargin=context.dp(12)})
        metadata.addView(title,LayoutParams(-1,-2))
        metadata.addView(detail,LayoutParams(-1,-2).apply {topMargin=context.dp(6)})
        val times=LinearLayout(context).apply {
            addView(elapsed,LayoutParams(0,-2,1f));addView(duration,LayoutParams(0,-2,1f))
        }
        timeline.addView(seek,LayoutParams(-1,context.dp(48)))
        timeline.addView(times,LayoutParams(-1,-2))
        val pad=directionPad
        fun direction(key:String,label:String,rotation:Float,x:Int,y:Int) {
            val button=MobileStyle.icon(context,R.drawable.ic_mobile_chevron,label) {navigate(key)}.apply {
                tag="companion_nav_$key";this.rotation=rotation
                setPadding(context.dp(20),context.dp(20),context.dp(20),context.dp(20))
                background=MobileStyle.ripple(context,radius=32)
            }
            pad.addView(button,FrameLayout.LayoutParams(context.dp(64),context.dp(64)).apply {leftMargin=context.dp(x);topMargin=context.dp(y)})
            directionTargets[key]=button
            navigationButtons+=button
        }
        direction("up","Su sulla TV",-90f,76,0)
        direction("left","Sinistra sulla TV",180f,0,76)
        val ok=TvStyle.text(context,"OK",18f,TvStyle.background,true).apply {
            tag="companion_nav_ok";gravity=Gravity.CENTER;contentDescription="OK sulla TV"
            isClickable=true;isFocusable=true;TvStyle.buttonSemantics(this)
            background=RippleDrawable(ColorStateList.valueOf(0x33717886),
                TvStyle.rounded(TvStyle.white,context.dp(36).toFloat()),TvStyle.rounded(Color.WHITE,context.dp(36).toFloat()))
            setOnClickListener {navigate("ok")}
        }
        pad.addView(ok,FrameLayout.LayoutParams(context.dp(72),context.dp(72)).apply {leftMargin=context.dp(72);topMargin=context.dp(72)})
        directionTargets["ok"]=ok
        navigationButtons+=ok
        direction("right","Destra sulla TV",0f,152,76)
        direction("down","Giù sulla TV",90f,76,152)
        navigation.addView(pad,LayoutParams(context.dp(216),context.dp(216)))
        for((key,caption,drawable) in listOf(Triple("back","Indietro",R.drawable.ic_mobile_back),
            Triple("home","Home",R.drawable.ic_remote_home),Triple("options","Opzioni",R.drawable.ic_remote_settings))) {
            val button=LinearLayout(context).apply {
                orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;isClickable=true;isFocusable=true
                tag="companion_nav_$key";contentDescription=if(key=="home")"Home di Lume sulla TV" else "$caption sulla TV"
                TvStyle.buttonSemantics(this);background=MobileStyle.ripple(context)
                addView(MobileStyle.icon(context,drawable,"",{}).apply {
                    isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
                },LayoutParams(context.dp(48),context.dp(48)))
                addView(TvStyle.text(context,caption,12f,TvStyle.muted).apply {
                    gravity=Gravity.CENTER;maxLines=2;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
                },LayoutParams(-1,-2))
                setOnClickListener {navigate(key)}
            }
            shortcuts.addView(button,LayoutParams(0,-2,1f).apply {if(shortcuts.childCount>0)leftMargin=context.dp(8)})
            navigationButtons+=button
        }
        navigation.addView(shortcuts,LayoutParams(-1,-2).apply {topMargin=context.dp(8)})
        buttons.addView(previous,LayoutParams(0,-2,1f))
        buttons.addView(toggle,LayoutParams(context.dp(104),-2).apply {leftMargin=context.dp(8);rightMargin=context.dp(8)})
        buttons.addView(next,LayoutParams(0,-2,1f))
        transport.addView(buttons,LayoutParams(-1,-2))
        transport.addView(timeline,LayoutParams(-1,-2).apply {topMargin=context.dp(8)})
        transport.addView(timer,LayoutParams(-1,-2).apply {topMargin=context.dp(8)})
        transport.addView(following,LayoutParams(-1,-2).apply {topMargin=context.dp(8)})
        transport.addView(live,LayoutParams(-1,-2).apply {topMargin=context.dp(8)})
        playback.addView(metadata,LayoutParams(-1,-2).apply {bottomMargin=context.dp(12)})
        playback.addView(transport,LayoutParams(-1,-2))
        content.addView(navigation,LayoutParams(-1,-2))
        content.addView(breathingRoom,LayoutParams(-1,context.dp(24)))
        content.addView(playback,LayoutParams(-1,-2))
        addView(content,LayoutParams(-1,0,1f))
        addView(disclosure,LayoutParams(-1,-2).apply {topMargin=context.dp(16)})
        addView(options,LayoutParams(-1,-2))

        previous.setOnClickListener {if(state?.optString("mode")=="LIVE")send("channelPrevious") else send("seekBy",JSONObject().put("delta",-10_000))}
        next.setOnClickListener {if(state?.optString("mode")=="LIVE")send("channelNext") else send("seekBy",JSONObject().put("delta",30_000))}
        toggle.setOnClickListener {send(if(state?.optBoolean("wantsToPlay")==true)"pause" else "play")}
        toggle.tag="companion_control_toggle"
        fun option(label:String,tag:String,enabled:(JSONObject)->Boolean={it.optBoolean("active")},action:()->Unit) {
            val row=OptionsRowView(context,true,label,action=action).apply {this.tag="companion_control_$tag"}
            options.addView(row,LayoutParams(-1,-2));options.addView(OptionsRowView.divider(context),LayoutParams(-1,context.dp(1)))
            optionButtons+=row to enabled
        }
        fun hasTracks(name:String):(JSONObject)->Boolean={it.optJSONObject("options")?.optJSONArray(name)?.length()?.let {n->n>0}==true}
        option("Audio","audio",hasTracks("audio")) {tracks("audio",1,"Audio sulla TV")}
        option("Sottotitoli","subtitles",hasTracks("subtitles")) {tracks("subtitles",3,"Sottotitoli sulla TV")}
        option("Qualità","quality",hasTracks("video")) {tracks("video",2,"Qualità sulla TV")}
        option("Timer di arresto","sleep") {sleep()}
        option("Interrompi sulla TV","stop") {send("stop")}

        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean) {
                if(!user)return
                if(dragging && dragSession!=state?.optString("session"))return
                val total=state?.optLong("duration") ?: return
                elapsed.text=time((total.toDouble()*value/1000).toLong())
                if(!dragging && available)seekTo(value)
            }
            override fun onStartTrackingTouch(bar:SeekBar){dragging=true;dragSession=state?.optString("session")}
            override fun onStopTrackingTouch(bar:SeekBar){
                val same=dragging && dragSession==state?.optString("session")
                dragging=false;dragSession=null
                if(same && available)seekTo(bar.progress)
                else showPosition()
            }
        })
        render(null,false,false)
    }

    fun setConnectionMessage(value:String) {if(connection.text.toString()!=value)connection.text=value}

    private fun expand(value:Boolean) {
        // Keep the transport in place while the options are appended below it.
        content.minimumHeight=if(value)content.height else 0
        expanded=value;options.visibility=if(value)VISIBLE else GONE
        ViewCompat.setStateDescription(disclosure,if(value)"Espanso" else "Compresso")
        disclosure.getChildAt(1).rotation=if(value)90f else 0f
        if(value)options.post {
            if(expanded)options.getChildAt(0)?.let {it.requestRectangleOnScreen(Rect(0,0,it.width,it.height),true)}
        }
    }

    fun render(value:JSONObject?,connected:Boolean,commanding:Boolean) {
        if(dragSession!=null && dragSession!=value?.optString("session"))dragSession=null
        state=value
        if(!commanding || requestedPosition?.first!=value?.optString("session"))requestedPosition=null
        val active=value?.optBoolean("active")==true
        available=connected && !commanding && value?.optBoolean("transitioning")!=true
        navigationAvailable=connected && value?.optBoolean("navigationSupported")==true
        navigationButtons.forEach {it.isEnabled=navigationAvailable;it.alpha=if(navigationAvailable)1f else .4f}
        connection.setTextColor(if(connected)TvStyle.accent else TvStyle.muted)
        title.text=when {value==null->"Attendo lo stato della TV…";active->value.optString("title");else->"Nessuna riproduzione"}
        detail.text=when {
            value!=null && !value.optBoolean("navigationSupported")->"Aggiorna Lume sulla TV per usare frecce e OK."
            value?.optBoolean("failed")==true->"La TV segnala un problema di riproduzione."
            value?.optBoolean("transitioning")==true->"Cambio contenuto sulla TV…"
            value?.optBoolean("buffering")==true->"Caricamento sulla TV…"
            active && value?.optBoolean("wantsToPlay")!=true->"In pausa sulla TV"
            active->value?.optString("programme").orEmpty().ifBlank {if(value?.optString("mode")=="LIVE")"In diretta" else if(value?.optString("mode")=="ARCHIVE")"Archivio" else "Video"}
            else->"Usa frecce e OK per scegliere sulla TV."
        }
        val seekable=value?.optBoolean("seekable")==true
        val total=value?.optLong("duration") ?: 0
        timeline.visibility=if(seekable && total>0)VISIBLE else GONE
        seek.isEnabled=available && seekable
        if(!dragging || dragSession!=value?.optString("session"))showPosition()
        duration.text=time(total)
        val liveMode=value?.optString("mode")=="LIVE"
        previous.bind(if(liveMode)R.drawable.ic_remote_minus else R.drawable.ic_remote_rewind,
            if(liveMode)"CH −" else "−10 s",if(liveMode)"Canale precedente sulla TV" else "Indietro di 10 secondi sulla TV",available && active && (liveMode || seekable))
        next.bind(if(liveMode)R.drawable.ic_remote_plus else R.drawable.ic_remote_forward,
            if(liveMode)"CH +" else "+30 s",if(liveMode)"Canale successivo sulla TV" else "Avanti di 30 secondi sulla TV",available && active && (liveMode || seekable))
        previous.tag=if(liveMode)"companion_control_previous" else "companion_control_back"
        next.tag=if(liveMode)"companion_control_next" else "companion_control_forward"
        val playing=value?.optBoolean("wantsToPlay")==true
        toggle.bind(if(playing)R.drawable.ic_mobile_pause else R.drawable.ic_mobile_play,
            if(playing)"Pausa" else "Riprendi",if(playing)"Pausa sulla TV" else "Riprendi sulla TV",available && active)
        ViewCompat.setStateDescription(toggle,if(playing)"In riproduzione" else "In pausa")
        val remaining=value?.optJSONObject("options")?.optLong("sleepRemaining") ?: 0
        timer.visibility=if(remaining>0)VISIBLE else GONE
        if(remaining>0)timer.text="Timer: ${time(remaining)}"
        following.visibility=if(value?.optString("nextEpisode").isNullOrEmpty())GONE else VISIBLE
        following.contentDescription="Prossimo episodio: "+value?.optString("nextEpisode").orEmpty()
        live.visibility=if(active && value?.optString("mode")=="ARCHIVE")VISIBLE else GONE
        listOf(following,live).forEach {it.isEnabled=available && active;it.alpha=if(it.isEnabled)1f else .4f}
        optionButtons.forEach {(view,enabled)->view.isEnabled=available && value!=null && enabled(value);view.alpha=if(view.isEnabled)1f else .45f}
    }

    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val nextWide=MeasureSpec.getSize(widthMeasureSpec)>=context.dp(600)
        if(nextWide!=wide) {
            content.minimumHeight=0
            wide=nextWide;content.orientation=if(nextWide)HORIZONTAL else VERTICAL;content.gravity=Gravity.TOP
            navigation.layoutParams=if(nextWide)LayoutParams(0,-2,1f) else LayoutParams(-1,-2)
            navigation.orientation=if(nextWide)HORIZONTAL else VERTICAL
            navigation.gravity=if(nextWide)Gravity.CENTER else Gravity.CENTER_HORIZONTAL
            shortcuts.orientation=if(nextWide)VERTICAL else HORIZONTAL
            shortcuts.layoutParams=if(nextWide)LayoutParams(context.dp(48),-2).apply {leftMargin=context.dp(16)}
                else LayoutParams(-1,-2).apply {topMargin=context.dp(8)}
            for(index in 0 until shortcuts.childCount) {
                val button=shortcuts.getChildAt(index) as LinearLayout
                button.getChildAt(1).visibility=if(nextWide)GONE else VISIBLE
                button.layoutParams=if(nextWide)LayoutParams(-1,context.dp(48)).apply {if(index>0)topMargin=context.dp(8)}
                    else LayoutParams(0,-2,1f).apply {if(index>0)leftMargin=context.dp(8)}
            }
            breathingRoom.layoutParams=if(nextWide)LayoutParams(context.dp(24),1) else LayoutParams(-1,context.dp(24))
            playback.layoutParams=if(nextWide)LayoutParams(0,-2,1f) else LayoutParams(-1,-2)
            val edge=if(nextWide)48 else 64;val center=if(nextWide)56 else 72
            val size=edge*2+center+16;val middle=(size-edge)/2;val far=size-edge
            directionPad.layoutParams=LayoutParams(context.dp(size),context.dp(size))
            directionPad.background=TvStyle.rounded(TvStyle.surface,context.dp(size/2).toFloat())
            for((key,target) in directionTargets) {
                val extent=if(key=="ok")center else edge
                val (x,y)=when(key) {
                    "up"->middle to 0;"down"->middle to far;"left"->0 to middle;"right"->far to middle
                    else->(edge+8) to (edge+8)
                }
                target.layoutParams=FrameLayout.LayoutParams(context.dp(extent),context.dp(extent)).apply {leftMargin=context.dp(x);topMargin=context.dp(y)}
                if(key!="ok"){val inset=context.dp((edge-24)/2);target.setPadding(inset,inset,inset,inset)}
            }
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }

    private fun seekTo(progress:Int) {
        val total=state?.optLong("duration") ?: return
        if(total>0 && available) {
            val wanted=(total.toDouble()*progress/1000).toLong()
            requestedPosition=state!!.getString("session") to wanted
            send("seek",JSONObject().put("position",wanted))
        }
    }
    private fun showPosition() {
        val position=requestedPosition?.second ?: state?.optLong("position") ?: 0
        val total=state?.optLong("duration") ?: 0
        seek.progress=if(total>0)(position.toDouble()*1000/total).toInt().coerceIn(0,1000) else 0
        elapsed.text=time(position)
    }
    private fun send(action:String,args:JSONObject=JSONObject()){if(available)command(action,args)}
    private fun navigate(key:String){if(navigationAvailable)command("navigate",JSONObject().put("key",key))}
    private fun time(value:Long):String {
        val seconds=value.coerceAtLeast(0)/1000
        return if(seconds>=3600)String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
            else String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60)
    }

    private inner class TransportButton(primary:Boolean):LinearLayout(context) {
        val icon=MobileStyle.icon(context,R.drawable.ic_mobile_play,"",{}).apply {
            val size=if(primary)56 else 48
            setPadding(context.dp(16),context.dp(16),context.dp(16),context.dp(16))
            background=RippleDrawable(ColorStateList.valueOf(0x3399CAFF),
                TvStyle.rounded(TvStyle.raised,context.dp(size/2).toFloat()),
                TvStyle.rounded(Color.WHITE,context.dp(size/2).toFloat()))
            imageTintList=ColorStateList.valueOf(TvStyle.white)
        }
        private val label=TvStyle.text(context,"",13f,TvStyle.muted).apply {
            gravity=Gravity.CENTER;maxLines=2;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        init {
            orientation=VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;isClickable=true;isFocusable=true
            TvStyle.buttonSemantics(this)
            icon.isClickable=false;icon.isFocusable=false;icon.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            icon.isDuplicateParentStateEnabled=true
            val size=context.dp(if(primary)56 else 48)
            addView(FrameLayout(context).apply {addView(icon,FrameLayout.LayoutParams(size,size,Gravity.CENTER))},LayoutParams(-1,context.dp(56)))
            addView(label,LayoutParams(-1,-2).apply {topMargin=context.dp(8)})
        }
        fun bind(drawable:Int,caption:String,description:String,enabled:Boolean) {
            icon.setImageResource(drawable);contentDescription=description;isEnabled=enabled;icon.isEnabled=enabled
            label.text=caption;alpha=if(enabled)1f else .4f
        }
    }
}
