package it.lumetv.app.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import it.lumetv.app.R
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.*

internal class VideoMetadataPanel(private val activity:ComponentActivity,private val repository:PersonalPlaylistRepository,
    private val scope:CoroutineScope,private val mobile:Boolean) {
    fun show(creditsOnly:Boolean=false) {
        val host=CatalogPanelHost.get(activity)
        val job=SupervisorJob(scope.coroutineContext[Job]);val work=CoroutineScope(scope.coroutineContext+job)
        val panel=CatalogPanelStyle.column(activity,mobile,if(creditsOnly)"TMDB · Crediti" else "Informazioni sui video"){host.back()}
        val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
        panel.addView(ScrollView(activity).apply {isFillViewport=true;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        fun copy(text:String)=TvStyle.text(activity,text,15f,TvStyle.muted).also {
            body.addView(it,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=activity.dp(20)})
        }
        if(!creditsOnly) {
            val online=repository.videoMetadata
            copy("Recupera trama, cast e immagini dei film e delle serie da TMDB. La ricerca invia al servizio il titolo e, quando disponibile, l’anno. Le schede scaricate restano disponibili offline.")
            val status=copy("")
            lateinit var toggle:android.widget.TextView
            fun bind(){
                status.text=if(!online.configured)"Le informazioni online non sono disponibili in questa versione dell’app."
                    else if(online.enabled)"Informazioni online attive" else "Informazioni online disattivate"
                toggle.text=if(online.enabled)"Disattiva informazioni online" else "Attiva informazioni online"
                toggle.isEnabled=online.configured
            }
            toggle=CatalogPanelStyle.button(activity,mobile,""){online.setEnabled(!online.enabled);bind()}.apply {
                tag="video_metadata_toggle";maxLines=3;ellipsize=null
            }
            body.addView(toggle,LinearLayout.LayoutParams(if(mobile)-1 else -2,-2).apply {bottomMargin=activity.dp(12)})
            val clear=CatalogPanelStyle.button(activity,mobile,"Svuota schede scaricate") {}
                .apply {tag="video_metadata_clear";maxLines=3;ellipsize=null}
            clear.setOnClickListener {
                clear.isEnabled=false
                work.launch {try {online.clearCache();status.text="Schede scaricate eliminate. Le associazioni scelte da te sono conservate."}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:Exception){status.text="Impossibile eliminare le schede. Riprova."}
                    finally {clear.isEnabled=true}}
            }
            body.addView(clear,LinearLayout.LayoutParams(if(mobile)-1 else -2,-2).apply {bottomMargin=activity.dp(28)})
            bind()
        }
        body.addView(ImageView(activity).apply {
            setImageResource(R.drawable.tmdb_logo);scaleType=ImageView.ScaleType.FIT_START;contentDescription="The Movie Database"
        },LinearLayout.LayoutParams(activity.dp(92),activity.dp(67)).apply {bottomMargin=activity.dp(16)})
        copy("Dati e immagini cinematografiche forniti da TMDB.\n\nThis product uses the TMDB API but is not endorsed or certified by TMDB.")
        body.addView(CatalogPanelStyle.button(activity,mobile,"Visita TMDB") {
            runCatching {activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.themoviedb.org")))}
                .onFailure {copy("Nessun browser disponibile per aprire TMDB.")}
        },LinearLayout.LayoutParams(if(mobile)-1 else -2,-2))
        host.push(panel){job.cancel()}
    }
}
