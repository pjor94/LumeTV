package it.lumetv.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.R
import it.lumetv.app.data.VideoArtworkRepository
import it.lumetv.app.data.VideoItem
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.Job

/** Poster cards have no channel numbers, live-guide labels or inline channel controls. */
internal class VideoPosterAdapter(private val context:Context,private val mobile:Boolean,
    private val artwork:VideoArtworkRepository,var width:Int,private val fixedWidth:Boolean=true,
    var wide:Boolean=false,private val mayRestore:(String)->Boolean={true},private val choose:(VideoItem)->Unit,
    private val focus:(VideoItem)->Unit={},private val episodeRows:Boolean=false) : RecyclerView.Adapter<VideoPosterAdapter.Holder>() {
    var items:List<VideoItem> = emptyList()
    var loadingCount=0
    var catalog:ContinuousCatalog<VideoItem>?=null
    var restoreFocusKey:String?=null
    private fun item(position:Int)=catalog?.peek(position) ?: items.getOrNull(position)
    init {setHasStableIds(true)}
    inner class Holder(val card:LinearLayout,val image:ImageView,val placeholder:LinearLayout,val title:TextView,
        val detail:TextView,val progress:ProgressBar,val frame:FrameLayout,val fallbackTitle:TextView?,
        val playIcon:ImageView?,val episodeState:TextView?):RecyclerView.ViewHolder(card) {
        var request:Job?=null;var key="";var imageBinding="";var boundItem:VideoItem?=null
        fun release(){
            request?.cancel();request=null;key="";imageBinding="";boundItem=null;image.setImageDrawable(null)
            val focusTarget=if(!mobile && !episodeRows)frame else card
            focusTarget.animate().cancel();focusTarget.scaleX=1f;focusTarget.scaleY=1f
        }
    }
    override fun getItemCount()=catalog?.size ?: if(loadingCount>0)loadingCount else items.size
    override fun getItemId(position:Int):Long {
        val key=item(position)?.key ?: return Long.MIN_VALUE+position
        var hash=-3750763034362895579L;key.forEach {hash=(hash xor it.code.toLong())*1099511628211L};return hash
    }
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        val card=LinearLayout(context).apply {
            orientation=if(episodeRows)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if(episodeRows)gravity=Gravity.CENTER_VERTICAL
            isFocusable=true;isClickable=true
            descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS;TvStyle.buttonSemantics(this)
            if(!mobile){clipChildren=false;clipToPadding=false}
            setPadding(context.dp(if(episodeRows)5 else 3),context.dp(if(mobile)3 else 4),context.dp(if(episodeRows)13 else 3),context.dp(if(episodeRows)4 else 8))
            background=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),TvStyle.rounded(TvStyle.raised,context.dp(12).toFloat()))
                if(mobile || episodeRows)addState(intArrayOf(android.R.attr.state_focused),
                    TvStyle.rounded(TvStyle.surface,context.dp(14).toFloat(),TvStyle.white,context.dp(2)))
                addState(intArrayOf(),TvStyle.rounded(if(episodeRows)0xFF151B24.toInt() else android.graphics.Color.TRANSPARENT,context.dp(12).toFloat()))
            }
            layoutParams=RecyclerView.LayoutParams(if(fixedWidth)context.dp(width) else -1,-2).apply {setMargins(context.dp(4),context.dp(4),context.dp(4),context.dp(4))}
        }
        val frame=FrameLayout(context).apply {
            val radius=context.dp(10).toFloat()
            background=TvStyle.rounded(TvStyle.surface,radius);clipToOutline=true
            if(!mobile && !episodeRows){
                isDuplicateParentStateEnabled=true
                foreground=StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused),TvStyle.rounded(android.graphics.Color.TRANSPARENT,radius,TvStyle.white,context.dp(2)))
                }
            }
        }
        val fallbackTitle=if(mobile)null else TvStyle.text(context,"",if(episodeRows)11f else 16f,bold=true).apply {
            gravity=Gravity.CENTER;maxLines=3;ellipsize=TextUtils.TruncateAt.END
        }
        val fallback=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(context.dp(10),context.dp(12),context.dp(10),context.dp(12))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if(fallbackTitle!=null)addView(fallbackTitle,LinearLayout.LayoutParams(-1,-2))
            else addView(ImageView(context).apply {setImageResource(R.drawable.ic_mobile_play);imageTintList=ColorStateList.valueOf(TvStyle.muted)},LinearLayout.LayoutParams(context.dp(28),context.dp(28)))
        }
        frame.addView(fallback,FrameLayout.LayoutParams(-1,-1))
        val image=ImageView(context).apply {scaleType=ImageView.ScaleType.FIT_CENTER;visibility=View.INVISIBLE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        frame.addView(image,FrameLayout.LayoutParams(-1,-1))
        val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=1000;progressTintList=ColorStateList.valueOf(TvStyle.accent);progressBackgroundTintList=ColorStateList.valueOf(TvStyle.raised);visibility=View.GONE
        }
        if(!episodeRows)frame.addView(progress,FrameLayout.LayoutParams(-1,context.dp(if(mobile)3 else 4),Gravity.BOTTOM))
        val playIcon=if(!mobile && !episodeRows)ImageView(context).apply {
            setImageResource(R.drawable.ic_tv_video_play)
            setColorFilter(TvStyle.background)
            setPadding(context.dp(6),context.dp(6),context.dp(6),context.dp(6))
            background=TvStyle.rounded(TvStyle.white,context.dp(20).toFloat())
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility=View.GONE
            frame.addView(this,FrameLayout.LayoutParams(context.dp(29),context.dp(29),Gravity.BOTTOM or Gravity.START).apply {
                marginStart=context.dp(9);bottomMargin=context.dp(10)
            })
        } else null
        val height=if(wide)width*9/16 else width*3/2
        card.addView(frame,LinearLayout.LayoutParams(if(episodeRows)context.dp(width) else -1,context.dp(height)))
        val title=TvStyle.text(context,"",if(mobile)14f else 16f,bold=true).apply {maxLines=2;minLines=2;ellipsize=TextUtils.TruncateAt.END}
        val detail=TvStyle.text(context,"",12f,TvStyle.muted).apply {maxLines=1;ellipsize=TextUtils.TruncateAt.END}
        title.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;detail.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        androidx.core.view.ViewCompat.setScreenReaderFocusable(card,true)
        val episodeState=if(episodeRows)TvStyle.text(context,"",13f,TvStyle.muted).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            maxLines=1
        } else null
        if(episodeState!=null){
            val copy=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                addView(title)
                addView(detail,LinearLayout.LayoutParams(-1,-2).apply {topMargin=context.dp(4)})
            }
            card.addView(copy,LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=context.dp(13);marginEnd=context.dp(12)})
            val trailing=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.END
                addView(episodeState,LinearLayout.LayoutParams(-2,-2))
                addView(progress,LinearLayout.LayoutParams(context.dp(88),context.dp(4)).apply {topMargin=context.dp(4)})
            }
            card.addView(trailing,LinearLayout.LayoutParams(-2,-2))
        }else{
            card.addView(title,LinearLayout.LayoutParams(-1,-2).apply {topMargin=context.dp(if(mobile)8 else 6);leftMargin=context.dp(4);rightMargin=context.dp(4)})
            card.addView(detail,LinearLayout.LayoutParams(-1,-2).apply {topMargin=context.dp(3);leftMargin=context.dp(4);rightMargin=context.dp(4)})
        }
        return Holder(card,image,fallback,title,detail,progress,frame,fallbackTitle,playIcon,episodeState)
    }
    override fun onBindViewHolder(holder:Holder,position:Int) {
        val value=item(position);catalog?.ensure(position)
        val target=context.dp(if(wide)width else width*3/2)
        val binding=value?.let {artwork.bindingKey(it,wide,target)}.orEmpty()
        val reload=value==null || holder.imageBinding!=binding
        if(reload)holder.release()
        val height=context.dp(if(wide)width*9/16 else width*3/2);if(holder.frame.layoutParams.height!=height)holder.frame.layoutParams.height=height
        if(fixedWidth && holder.card.layoutParams.width!=context.dp(width))holder.card.layoutParams.width=context.dp(width)
        if(reload){holder.placeholder.visibility=View.VISIBLE;holder.image.visibility=View.INVISIBLE}
        if(value==null) {
            val failed=catalog?.failedAt(position)==true
            holder.title.text=if(failed)"Riprova a caricare" else "Caricamento…";holder.detail.text="";holder.progress.visibility=View.GONE
            holder.fallbackTitle?.text="";holder.episodeState?.text="";holder.playIcon?.visibility=View.GONE
            holder.card.contentDescription=holder.title.text
            holder.card.setOnClickListener {catalog?.retry(holder.bindingAdapterPosition)}
            holder.card.isClickable=failed;holder.card.onFocusChangeListener=null
            return
        }
        if(holder.boundItem!=value) {
            holder.key=value.key
            holder.title.text=if(episodeRows && value.episode!=null)"${value.episode}. ${value.displayTitle}" else value.displayTitle
            holder.title.minLines=if(wide)1 else 2
            holder.fallbackTitle?.text=value.displayTitle
            val duration=value.watchedDuration.takeIf {it>0} ?: value.duration
            val episode=if(value.season!=null && value.episode!=null)"S${value.season} E${value.episode}" else ""
            val remaining=videoRemaining(value.resume,duration)
            holder.detail.text=when {
                episodeRows->value.description.ifBlank {episode}
                value.isSeries->"${value.episodes} ${if(value.episodes==1)"episodio" else "episodi"}"
                value.completed->"Visto"
                value.resume>0 && !mobile && wide->"Riprendi · "+episode.ifBlank {remaining ?: videoTime(value.resume)}
                value.resume>0->"Riprendi · ${videoTime(value.resume)}"
                value.year!=null->value.year.toString()
                episode.isNotBlank()->episode
                else->""
            }
            holder.episodeState?.text=when {
                value.completed->"Visto"
                value.resume>0->remaining ?: "Riprendi da ${videoTime(value.resume)}"
                duration>=60_000->"${duration/60_000} min"
                else->""
            }
            holder.progress.visibility=if(!value.isSeries && (value.position>0 || value.completed) && duration>0)View.VISIBLE else View.GONE
            holder.progress.progress=if(value.completed)1000 else if(duration>0)(value.position.toDouble()/duration*1000).toInt().coerceIn(0,1000) else 0
            holder.card.contentDescription=listOf(value.displayTitle,holder.detail.text.toString(),holder.episodeState?.text?.toString().orEmpty(),"Nella mia lista".takeIf {value.saved}.orEmpty()).filter(String::isNotBlank).joinToString(", ")
            holder.card.isClickable=true;holder.card.setOnClickListener {choose(value)}
            val resumeCard=!episodeRows && wide && value.resume>0
            fun playIcon(focused:Boolean){holder.playIcon?.visibility=if(focused && resumeCard)View.VISIBLE else View.GONE}
            playIcon(holder.card.isFocused)
            holder.card.setOnFocusChangeListener {view,focused->
                TvStyle.focusMotion(if(!mobile && !episodeRows)holder.frame else view,focused,if(episodeRows)1.012f else 1.035f)
                playIcon(focused)
                if(focused){restoreFocusKey=null;focus(value)}
            }
            holder.boundItem=value
        }
        if(!mobile && restoreFocusKey==value.key)holder.card.post {
            if(holder.key==value.key && holder.card.isShown && restoreFocusKey==value.key && mayRestore(value.key)){restoreFocusKey=null;holder.card.requestFocus()}
        }
        fun display(bitmap:android.graphics.Bitmap?){
            if(holder.key==value.key && holder.imageBinding==binding && bitmap!=null){
                holder.image.scaleType=if(!mobile && wide && bitmap.width>=bitmap.height*1.3f)ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                holder.image.setImageBitmap(bitmap);holder.image.visibility=View.VISIBLE;holder.placeholder.visibility=View.GONE
            }
        }
        if(reload || holder.request==null && holder.image.visibility!=View.VISIBLE) {
            holder.imageBinding=binding
            holder.request=artwork.request(value,wide,target){bitmap->
                if(bitmap==null && wide && holder.key==value.key && holder.imageBinding==binding)
                    holder.request=artwork.request(value,targetPx=target,display=::display)
                else display(bitmap)
            }
        }
    }
    fun submit(next:List<VideoItem>) {
        val old=items;items=next;loadingCount=0
        val diff=androidx.recyclerview.widget.DiffUtil.calculateDiff(object:androidx.recyclerview.widget.DiffUtil.Callback(){
            override fun getOldListSize()=old.size
            override fun getNewListSize()=next.size
            override fun areItemsTheSame(a:Int,b:Int)=old[a].key==next[b].key
            override fun areContentsTheSame(a:Int,b:Int)=old[a]==next[b]
            override fun getChangePayload(a:Int,b:Int):Any="metadata"
        });diff.dispatchUpdatesTo(this)
    }
    fun pause(list:RecyclerView){for(i in 0 until list.childCount)(list.getChildViewHolder(list.getChildAt(i)) as? Holder)?.let {it.request?.cancel();it.request=null}}
    fun resume(list:RecyclerView){for(i in 0 until list.childCount)(list.getChildViewHolder(list.getChildAt(i)) as? Holder)?.let {if(it.bindingAdapterPosition>=0)onBindViewHolder(it,it.bindingAdapterPosition)}}
    override fun onViewRecycled(holder:Holder){holder.release();super.onViewRecycled(holder)}
    override fun onViewDetachedFromWindow(holder:Holder){holder.release();super.onViewDetachedFromWindow(holder)}
    override fun onViewAttachedToWindow(holder:Holder){super.onViewAttachedToWindow(holder);if(holder.bindingAdapterPosition!=RecyclerView.NO_POSITION && holder.request==null)onBindViewHolder(holder,holder.bindingAdapterPosition)}
}

internal fun videoTime(position:Long):String {val seconds=(position/1000).coerceAtLeast(0);return if(seconds>=3600)"%d:%02d:%02d".format(java.util.Locale.ROOT,seconds/3600,seconds/60%60,seconds%60) else "%d:%02d".format(java.util.Locale.ROOT,seconds/60,seconds%60)}

internal fun videoRemaining(position:Long,duration:Long):String? {
    if(position<=0 || duration<=position)return null
    val minutes=(1+(duration-position-1)/60_000)
    return if(minutes>=60)"${minutes/60} h ${(minutes%60).toString().padStart(2,'0')} min rimasti"
        else "$minutes min rimasti"
}
