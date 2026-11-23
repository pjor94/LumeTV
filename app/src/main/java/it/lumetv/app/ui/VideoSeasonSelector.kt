package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.text.TextUtils
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import it.lumetv.app.data.VideoItem
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle

/** Keep season choices visible without creating a view for every season in a large provider list. */
@SuppressLint("ViewConstructor") // Created with callbacks in code, never inflated from XML.
internal class VideoSeasonSelector(context:Context,private val mobile:Boolean,
    private val choose:(Int?)->Unit,private val more:()->Unit):HorizontalScrollView(context) {
    private val row=LinearLayout(context)
    init {
        tag="video_seasons";isHorizontalScrollBarEnabled=false;clipToPadding=false
        if(!mobile){isFillViewport=true;row.gravity=android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL}
        addView(row)
        row.addView(TvStyle.text(context,"Carico le stagioni…",14f,TvStyle.muted).apply {minimumHeight=context.dp(48)})
    }
    private fun button(label:String,selected:Boolean=false,action:()->Unit):TextView =
        (if(mobile)MobileStyle.button(context,label,selected,action) else TvStyle.button(context,label,selected,action)).apply {
            minimumHeight=context.dp(if(mobile)48 else 40);maxLines=1;ellipsize=TextUtils.TruncateAt.END
        }
    private fun add(view:View) {
        row.addView(view,LinearLayout.LayoutParams(-2,-2).apply {if(row.childCount>0)marginStart=context.dp(8)})
    }
    fun submit(page:Pair<List<Int>,Int>,selected:Int?) {
        row.removeAllViews()
        val seasons=page.first.filter {it>0}.take(8).toMutableList()
        if(selected!=null && selected>0 && selected !in seasons && (selected in page.first || page.second>page.first.size))seasons+=selected
        seasons.sort()
        if(0 in page.first)seasons+=0
        var active:View?=null
        for(season in seasons)add(button(label(season),season==selected){choose(season)}.apply {
            tag="video_season_$season";if(isSelected)active=this
        })
        if(page.second>seasons.size)add(button("Altre stagioni",action=more).apply {tag="video_seasons_more"})
        add(button(label(null),selected==null){choose(null)}.apply {tag="video_season_all";if(isSelected)active=this})
        active?.let {view->view.post {if(view.isShown)view.requestRectangleOnScreen(Rect(0,0,view.width,view.height),true)}}
    }
    fun retry(action:()->Unit) {
        row.removeAllViews();add(button("Riprova stagioni",action=action))
    }
    companion object {
        fun label(season:Int?)=when(season){null->"Tutte le stagioni";0->"Altri episodi";else->"Stagione $season"}
        fun initialSeason(seasons:List<Int>,target:VideoItem):Int? =
            if(target.resume>0)target.season ?: 0 else seasons.firstOrNull {it>0} ?: seasons.firstOrNull()
    }
}
