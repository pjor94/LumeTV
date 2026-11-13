package it.lumetv.app.ui

import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PersonalCatalogQuery
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import kotlinx.coroutines.CoroutineScope

/** Search and continuous scrolling share a bounded cache on TV and touch. */
internal class PersonalCatalogPanels(private val activity:ComponentActivity,private val scope:CoroutineScope,private val hostOverride:CatalogPanelHost?=null) {
    private val mobile=activity is it.lumetv.app.MobileActivity
    private val host get()=hostOverride ?: CatalogPanelHost.get(activity)
    private var embedded:CatalogPanelHost?=null
    private var visibility:(Boolean)->Unit={}
    var restartCurrent:(Channel)->Unit={}
    fun attachVideo(container:android.widget.FrameLayout,onVisibility:(Boolean)->Unit) {
        visibility=onVisibility
        embedded=CatalogPanelHost(activity,false).also {it.tag="lume_video_page_host";container.addView(it,android.widget.FrameLayout.LayoutParams(-1,-1))}
        activity.lifecycle.addObserver(object:androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner:androidx.lifecycle.LifecycleOwner){embedded?.close()}
        })
    }
    private var video:VideoCatalogPanel?=null
    private var pendingVideo:android.os.Bundle?=null
    private var lastVideo:android.os.Bundle?=null
    fun library(repository:PersonalPlaylistRepository,logos:it.lumetv.app.data.ChannelLogoRepository,
        favorite:(Channel)->Boolean,toggleFavorite:(Channel)->Unit,choose:(Channel)->Unit) {
        if(repository.videoSources.value.isEmpty())return
        val target=embedded ?: host
        if(video!=null && target.parked){target.resume();video?.refresh();return}
        if(video!=null && target.isOpen)return
        val restored=pendingVideo ?: lastVideo;pendingVideo=null
        val artwork=it.lumetv.app.data.VideoArtworkRepository(activity,repository,it.lumetv.app.data.HttpClient(),scope)
        val panel=VideoCatalogPanel(activity,scope,repository,artwork,toggleFavorite,choose,restartCurrent,mobile,target,restored)
        if(repository.videoSources.value.isEmpty()){panel.close();return}
        video=panel
        target.push(panel.view){lastVideo=android.os.Bundle().also(panel::save);panel.close();if(video===panel)video=null;visibility(false)}
        visibility(true)
        if(restored?.getBoolean("video_parked")==true)target.park()
    }
    fun handleBack():Boolean {
        if(host.isOpen){if(embedded==null && host.currentView===video?.view && video?.back()==true)return true;return host.back()}
        if(embedded?.isOpen==true){if(embedded?.currentView===video?.view && video?.back()==true)return true;return embedded?.back()==true}
        return false
    }
    fun resume():Boolean {val resumed=embedded?.resume()==true || host.resume();if(resumed)video?.refresh();return resumed}
    fun dismiss(){host.close();embedded?.close()}
    val isShowing get()=host.isOpen || embedded?.isOpen==true
    fun saveState(out:android.os.Bundle) {
        if(video!=null)video?.save(out) else lastVideo?.let {out.putAll(it)}
        out.putBoolean("video_open",video!=null && (embedded ?: host).isOpen)
        out.putBoolean("video_parked",video!=null && (embedded ?: host).parked)
    }
    fun restoreState(saved:android.os.Bundle?) {
        if(saved==null)return
        val state=android.os.Bundle(saved).apply {keySet().toList().filterNot {it.startsWith("video_")}.forEach(::remove)}
        if(saved.containsKey("video_query"))lastVideo=state
        if(saved.getBoolean("video_open") || saved.getBoolean("video_parked"))pendingVideo=android.os.Bundle(state).apply {putBoolean("video_parked",saved.getBoolean("video_parked"))}
    }
    fun restorePending(repository:PersonalPlaylistRepository,logos:it.lumetv.app.data.ChannelLogoRepository,
        favorite:(Channel)->Boolean,toggleFavorite:(Channel)->Unit,choose:(Channel)->Unit) {
        if(pendingVideo!=null && repository.videoSources.value.isNotEmpty())library(repository,logos,favorite,toggleFavorite,choose)
    }
    fun group(repository:PersonalPlaylistRepository,query:it.lumetv.app.data.PersonalCatalogQuery,choose:(String)->Unit) {
        groupPicker(repository,query,"Scegli il gruppo","Tutti i gruppi",choose)
    }
    fun category(repository:PersonalPlaylistRepository,query:it.lumetv.app.data.PersonalCatalogQuery,choose:(String)->Unit) {
        groupPicker(repository,query,"Categorie","Tutte le categorie",choose)
    }
    private fun groupPicker(repository:PersonalPlaylistRepository,query:PersonalCatalogQuery,title:String,allLabel:String,choose:(String)->Unit) {
        paged(title,load={text,offset->
            val all=text.isBlank()
            val (rows,total)=repository.groups(query.copy(text=text,offset=(offset-if(all)1 else 0).coerceAtLeast(0),limit=if(all && offset==0)49 else 50))
            ((if(all && offset==0)listOf(allLabel) else emptyList())+rows) to total+if(all)1 else 0
        },label={it},choose=choose)
    }
    fun <T> paged(title:String,filters:((LinearLayout,()->Unit)->Unit)?=null,
        load:suspend (String,Int)->Pair<List<T>,Int>,label:(T)->String,choose:(T)->Unit) {
        val panel=CatalogPanelStyle.column(activity,mobile,title){host.back()}
        lateinit var search:EditText
        lateinit var picker:ContinuousPicker<T>
        var revision=0
        fun reload(){val query=search.text.toString().trim();picker.bind(query to ++revision){offset->load(query,offset)}}
        val controls=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        filters?.invoke(controls){reload()}
        panel.addView(controls,LinearLayout.LayoutParams(-1,-2))
        search=CatalogPanelStyle.search(activity,"Cerca in $title"){reload()}
        panel.addView(search,LinearLayout.LayoutParams(-1,activity.dp(48)))
        picker=ContinuousPicker(activity,scope,mobile,label){item->host.back();choose(item)}
        panel.addView(picker.view,LinearLayout.LayoutParams(-1,0,1f))
        host.push(panel,picker::close)
        reload()
    }
}
