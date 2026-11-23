package it.lumetv.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import it.lumetv.app.R
import it.lumetv.app.data.*
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.playback.TrailerMediaSource
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaylistContentKind
import kotlinx.coroutines.*

/** An isolated native player: no playlist identity, playback history, cache or external intents. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TrailerPlayerDialog(
    private val activity: ComponentActivity,
    private val title: String,
    private val trailers: List<TmdbTrailer>,
    private val mobile: Boolean,
    private val resolver: TrailerResolver = resolverForTests ?: YoutubeTrailerResolver
) : ComponentDialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen), DefaultLifecycleObserver {
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK); tag="video_trailer_player" }
    private val video = PlayerView(activity).apply { useController=false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) }
    private val controls = OnDemandControls(activity, !mobile).apply { showOptions=false }
    private val status = TvStyle.text(activity, "", 16f, TvStyle.white).apply {
        tag="video_trailer_status"; gravity=Gravity.CENTER; maxLines=4
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val loading = ProgressBar(activity)
    private val retry = TvVideoStyle.action(activity, "Riprova", R.drawable.ic_mobile_refresh) { restart() }.apply {
        visibility=View.GONE;tag="video_trailer_retry";if(mobile)minHeight=activity.dp(48)
    }
    private val close = TvVideoStyle.action(activity, "Indietro", R.drawable.ic_mobile_back) { dismiss() }.apply {
        tag="video_trailer_close";if(mobile)minHeight=activity.dp(48)
    }
    private val message = LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
        setPadding(activity.dp(24),activity.dp(16),activity.dp(24),activity.dp(16))
    }
    private var portraitLayout:Boolean?=null
    private var queue = TrailerPlaybackQueue(trailers)
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var job: Job? = null
    private var current: TmdbTrailer? = null
    private var closed = false
    private var loadingBudget = 60_000L
    private var bufferingSince = 0L
    private val tick = object : Runnable {
        override fun run() { if (!closed) { render(); handler.postDelayed(this, 500) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super<ComponentDialog>.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        video.setOnClickListener { controls.showControls(!mobile) }
        message.addView(loading,LinearLayout.LayoutParams(activity.dp(40),activity.dp(40)))
        message.addView(status,LinearLayout.LayoutParams(-1,-2).apply {topMargin=activity.dp(16)})
        message.addView(retry,LinearLayout.LayoutParams(-2,-2).apply {topMargin=activity.dp(20)})
        arrange(mobile && activity.resources.configuration.screenHeightDp > activity.resources.configuration.screenWidthDp)
        root.addOnLayoutChangeListener {_,left,top,right,bottom,_,_,_,_->
            val portrait=mobile && bottom-top>right-left
            if(portrait!=portraitLayout)root.post {if(!closed)arrange(portrait)}
        }
        setContentView(root)
        window?.setLayout(-1,-1)
        window?.let { window ->
            WindowCompat.getInsetsController(window,root).apply {
                systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        controls.onToggle={player?.let {if(it.playWhenReady)it.pause() else it.play()};render()}
        controls.onSeek={point->player?.seekTo(point)}
        controls.onSeekBy={delta->player?.let {it.seekTo((it.currentPosition+delta).coerceIn(0,it.duration.coerceAtLeast(0)))}}
        controls.onRestart={player?.seekTo(0);player?.play()}
        controls.canAutoHide={player?.playbackState==Player.STATE_READY}
        onBackPressedDispatcher.addCallback(this) {
            if (controls.isScrubbing) controls.handleBack() else dismiss()
        }
        activity.lifecycle.addObserver(this)
        close.requestFocus()
        handler.post(tick)
        restart()
    }

    private fun arrange(portrait:Boolean) {
        if(portraitLayout==portrait)return
        portraitLayout=portrait
        val focused=root.findFocus()
        for(view in listOf(video,controls,close,message))(view.parent as? android.view.ViewGroup)?.removeView(view)
        root.removeAllViews()
        if(portrait){
            controls.background=null
            val column=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
            column.addView(close,LinearLayout.LayoutParams(-2,-2).apply {setMargins(activity.dp(16),activity.dp(8),activity.dp(16),0)})
            column.addView(video,LinearLayout.LayoutParams(-1,0,1f))
            controls.setPadding(activity.dp(20),activity.dp(12),activity.dp(20),activity.dp(24))
            column.addView(controls,LinearLayout.LayoutParams(-1,-2))
            root.addView(column,FrameLayout.LayoutParams(-1,-1))
        }else{
            if(mobile)controls.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT,0xC0000000.toInt(),0xF2000000.toInt()))
            controls.setPadding(activity.dp(if(mobile)20 else 40),activity.dp(if(mobile)8 else 40),
                activity.dp(if(mobile)20 else 40),activity.dp(if(mobile)12 else 24))
            root.addView(video,FrameLayout.LayoutParams(-1,-1))
            root.addView(controls,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
            root.addView(close,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply {
                setMargins(activity.dp(20),activity.dp(16),activity.dp(20),0)
            })
        }
        root.addView(message,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))
        root.post {focused?.takeIf {it.isShown}?.requestFocus()}
    }

    private fun restart() {
        job?.cancel();releasePlayer()
        queue=TrailerPlaybackQueue(trailers)
        loadingBudget=60_000
        resolveNext()
    }

    private fun resolveNext() {
        if (closed) return
        releasePlayer()
        controls.setPlayerVisible(false)
        retry.visibility=View.GONE;loading.visibility=View.VISIBLE;status.visibility=View.VISIBLE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        job=work.launch {
            try {
                val started=SystemClock.elapsedRealtime()
                val stream=withTimeoutOrNull(loadingBudget.coerceAtLeast(1)) { queue.next(resolver) { candidate ->
                    current=candidate
                    status.text=if(candidate.language=="it")"Carico il trailer in italiano…" else "Carico il trailer in inglese…"
                } }
                loadingBudget=(loadingBudget-(SystemClock.elapsedRealtime()-started)).coerceAtLeast(0)
                if(stream==null)unavailable() else play(stream)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { unavailable() }
        }
    }

    private fun play(stream: TrailerStream) {
        if(closed)return
        current=stream.trailer
        val media=TrailerMediaSource.create(stream)
        val native=ExoPlayer.Builder(activity).build()
        player=native;video.player=native
        mediaSession=MediaSession.Builder(activity,native).setId("lume-trailer").build()
        native.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
        native.setHandleAudioBecomingNoisy(true)
        native.addListener(object:Player.Listener {
            override fun onPlaybackStateChanged(state:Int) {
                if(player!==native || closed)return
                if(state==Player.STATE_ENDED){dismiss();return}
                if(state==Player.STATE_READY){loading.visibility=View.GONE;status.visibility=View.GONE}
                else if(state==Player.STATE_BUFFERING)loading.visibility=View.VISIBLE
                render()
            }
            override fun onPlayerError(error:PlaybackException) {
                if(player===native && !closed)resolveNext()
            }
        })
        native.setMediaSource(media);native.prepare();native.playWhenReady=true
        controls.setPlayerVisible(true)
        render();controls.showControls(!mobile)
    }

    private fun render() {
        val native=player ?: return
        val trailer=current ?: return
        if(native.playWhenReady && native.playbackState==Player.STATE_BUFFERING){
            val now=SystemClock.elapsedRealtime()
            if(bufferingSince==0L)bufferingSince=now
            else if(now-bufferingSince>=20_000){resolveNext();return}
        }else bufferingSince=0
        val identity=Channel("trailer:${trailer.key}",title,0,"","Trailer","","","",trailer.key,
            sourceId="trailer",contentKind=PlaylistContentKind.VOD)
        controls.render(PlaybackSnapshot(channel=identity,playing=native.isPlaying,wantsToPlay=native.playWhenReady,
            buffering=native.playbackState==Player.STATE_BUFFERING,ready=native.playbackState==Player.STATE_READY,
            mode=PlaybackMode.VOD,title="${trailer.label} · $title",position=native.currentPosition.coerceAtLeast(0),
            duration=native.duration.coerceAtLeast(0),seekable=native.isCurrentMediaItemSeekable,
            bufferedPosition=native.bufferedPosition.coerceAtLeast(0)))
    }

    private fun unavailable() {
        loading.visibility=View.GONE
        status.text="Trailer non disponibile. Riprova più tardi."
        status.visibility=View.VISIBLE;retry.visibility=View.VISIBLE
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        retry.requestFocus()
    }

    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE)){
            if(event.action==KeyEvent.ACTION_DOWN)when(event.keyCode){
                KeyEvent.KEYCODE_MEDIA_PLAY->player?.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE->player?.pause()
                else->controls.onToggle()
            }
            return true
        }
        if(!mobile && player!=null && !controls.controlsVisible && event.keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)){
            if(event.action==KeyEvent.ACTION_DOWN)controls.showControls(true)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop(owner:LifecycleOwner) { dismiss() }
    override fun onDestroy(owner:LifecycleOwner) { dismiss() }
    private fun releasePlayer() {
        bufferingSince=0
        mediaSession?.release();mediaSession=null
        val old=player;player=null;video.player=null;old?.release()
    }
    override fun dismiss() {
        if(!closed){
            closed=true;job?.cancel();work.cancel();handler.removeCallbacksAndMessages(null)
            activity.lifecycle.removeObserver(this)
            controls.setPlayerVisible(false);releasePlayer()
        }
        super.dismiss()
    }

    companion object {
        internal var resolverForTests:TrailerResolver?=null
            set(value){check(it.lumetv.app.BuildConfig.DEBUG);field=value}
    }
}
