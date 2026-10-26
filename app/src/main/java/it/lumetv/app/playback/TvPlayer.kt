package it.lumetv.app.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import it.lumetv.app.data.HttpClient
import it.lumetv.core.ResolvedStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvPlayer(private val context: Context, private val http: HttpClient) {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var view: PlayerView? = null
    var onBuffering: () -> Unit = {}
    var onPlayingChanged: (Boolean) -> Unit = {}
    var onError: (PlaybackFailure) -> Unit = {}
    val isPlaying get() = player?.isPlaying == true
    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            debug("first_frame")
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            debug("state=$playbackState")
            if (playbackState == Player.STATE_BUFFERING) onBuffering()
            if (playbackState == Player.STATE_ENDED) onError(PlaybackFailure("La diretta si è interrotta. Riprova o scegli un altro canale.", true))
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) = onPlayingChanged(isPlaying)
        override fun onPlayerError(error: PlaybackException) {
            debug("error=${error.errorCodeName}")
            onError(PlaybackFailure.fromPlayer(error))
        }
    }

    private fun debug(message: String) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.i("LumePlayer", message)
    }

    fun attach(view: PlayerView) {
        this.view = view
        view.useController = false
        view.keepScreenOn = true
        view.setKeepContentOnPlayerReset(false)
        if (player == null) {
            player = ExoPlayer.Builder(context)
                .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8_000, 25_000, 1_000, 2_000)
                    .setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
                .build().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    setHandleAudioBecomingNoisy(true)
                    addListener(listener)
                }
            session = MediaSession.Builder(context, player!!).build()
        }
        view.player = player
    }

    fun play(stream: ResolvedStream) {
        val player = player ?: return
        val httpFactory = OkHttpDataSource.Factory(http.client)
            .setUserAgent(HttpClient.USER_AGENT).setDefaultRequestProperties(stream.headers)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
        // A separate HTTP factory prevents stream headers from leaking into license requests.
        val drmProvider = DefaultDrmSessionManagerProvider()
        drmProvider.setDrmHttpDataSourceFactory(OkHttpDataSource.Factory(http.client).setUserAgent(HttpClient.USER_AGENT))
        factory.setDrmSessionManagerProvider(drmProvider)
        val item = MediaItem.Builder().setUri(stream.url).apply {
            stream.mimeType?.let { setMimeType(it) }
            stream.drm?.let {
                setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(it.licenseUrl).setLicenseRequestHeaders(it.headers).build())
            }
        }.build()
        player.stop()
        player.clearMediaItems()
        player.setMediaSource(factory.createMediaSource(item))
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePause(): Boolean {
        val active = player ?: return false
        active.playWhenReady = !active.playWhenReady
        return active.playWhenReady
    }
    fun pauseForCatalog() { player?.pause() }
    fun resumeToLive(): Boolean {
        val active = player?.takeIf { it.mediaItemCount > 0 && it.playerError == null &&
            it.playbackState != Player.STATE_IDLE && it.playbackState != Player.STATE_ENDED } ?: return false
        active.seekToDefaultPosition(); active.play()
        return true
    }
    fun stop() { player?.stop(); player?.clearMediaItems() }
    fun release() {
        session?.release(); session = null
        view?.player = null; view = null
        player?.removeListener(listener)
        player?.release(); player = null
    }
}
