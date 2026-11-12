package it.lumetv.app.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.media3.ui.PlayerView
import it.lumetv.app.data.HttpClient
import it.lumetv.core.Channel

/** A screen attaches to the service player; it never owns its lifetime. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvPlayer(context: Context, @Suppress("UNUSED_PARAMETER") http: HttpClient) {
    private val app = context.applicationContext
    private var service: PlaybackService? = null
    private var bound = false
    private var view: PlayerView? = null
    private var pending: (() -> Unit)? = null
    var onConnected: () -> Unit = {}
    var onChanged: (PlaybackSnapshot) -> Unit = {}
    var snapshot = PlaybackSnapshot()
        private set
    val isPlaying get() = snapshot.playing
    val isConnected get() = service != null
    private val observer: (PlaybackSnapshot) -> Unit = { state ->
        snapshot = state
        view?.keepScreenOn = state.channel != null && !state.audioOnly && state.wantsToPlay
        onChanged(state)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlaybackService.LocalBinder).service
            view?.player = service!!.player
            service!!.observe(observer)
            val queued = pending; pending = null
            queued?.invoke()
            onConnected()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            view?.player = null
            snapshot = PlaybackSnapshot(error = "Riproduzione interrotta. Riapri l’emittente.")
            onChanged(snapshot)
        }
    }

    fun attach(view: PlayerView) {
        this.view?.takeIf { it !== view }?.player = null
        this.view = view
        view.useController = false
        view.setKeepContentOnPlayerReset(false)
        service?.let { view.player = it.player; observer(it.snapshot); onConnected(); return }
        if (!bound) bound = app.bindService(Intent(app, PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND), connection, Context.BIND_AUTO_CREATE)
    }

    fun select(channel: Channel, mobile: Boolean, audioOnly: Boolean = channel.isRadio, play: Boolean = true) {
        val action = { service?.select(channel, mobile, audioOnly, play); Unit }
        if (service == null) pending = action else action()
    }

    fun togglePause(): Boolean = if (snapshot.wantsToPlay) { service?.pausePlayback(); false }
        else { service?.resume(); true }
    fun pauseForCatalog() { service?.pausePlayback() }
    fun resumeToLive(): Boolean {
        if (snapshot.channel == null) return false
        service?.resume()
        return true
    }
    fun returnToLive(){service?.returnToLive()}
    fun resumePlayback(){service?.resume()}
    fun selectArchive(channel:Channel,eventId:String,mobile:Boolean) {
        val action={service?.selectArchive(channel,eventId,mobile);Unit}
        if(service==null)pending=action else action()
    }
    fun seek(position:Long){service?.seek(position)}
    fun seekBy(delta:Long){service?.seekBy(delta)}
    fun restart(){service?.restart()}
    fun setAudioOnly(value: Boolean) { service?.setAudioOnly(value) }
    fun refreshPreferences() { service?.refreshPreferences() }
    fun stop() { pending = null; service?.stopPlayback() }
    fun release(stop: Boolean = true) {
        pending = null
        service?.removeObserver(observer)
        view?.player = null; view?.keepScreenOn = false; view = null
        if (stop) service?.stopPlayback()
        service = null
        if (bound) { app.unbindService(connection); bound = false }
        if (stop) snapshot = PlaybackSnapshot()
    }
}
