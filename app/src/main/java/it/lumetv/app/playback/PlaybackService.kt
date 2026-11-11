package it.lumetv.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import it.lumetv.app.MainActivity
import it.lumetv.app.data.ChannelRepository
import it.lumetv.app.data.HttpClient
import it.lumetv.app.data.StreamResolver
import it.lumetv.core.Channel
import it.lumetv.core.ResolvedStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** One playback owner for TV, touch UI, lock-screen controls and Bluetooth. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object {
        const val LOCAL_BIND = "it.lumetv.app.playback.BIND"
        const val TOGGLE = "it.lumetv.app.playback.TOGGLE"
        const val NEXT = "it.lumetv.app.playback.NEXT"
        const val PREVIOUS = "it.lumetv.app.playback.PREVIOUS"
    }
    inner class LocalBinder : Binder() { val service: PlaybackService get() = this@PlaybackService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val http = HttpClient()
    // An MP3/AAC response can last for the whole listening session, unlike an API call.
    private val streamHttp = http.client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    private lateinit var repository: ChannelRepository
    private lateinit var resolver: StreamResolver
    private lateinit var recovery: PlaybackRecovery
    private lateinit var network: NetworkMonitor
    lateinit var player: ExoPlayer
        private set
    private lateinit var session: MediaSession
    private val stopCommand = SessionCommand("it.lumetv.app.playback.STOP", Bundle.EMPTY)
    private val observers = linkedSetOf<(PlaybackSnapshot) -> Unit>()
    private var selected: Channel? = null
    private var resolveJob: Job? = null
    private var generation = 0L
    private var mobile = false
    private var audioOnly = false
    private var desiredPlaying = false
    private var switching = false
    private var changingChannel = false
    private var message = ""
    private var error: String? = null
    private var nowPlaying = ""
    private var recordedId: String? = null
    var snapshot = PlaybackSnapshot()
        private set

    override fun onCreate() {
        super.onCreate()
        repository = ChannelRepository(this, http)
        resolver = StreamResolver(http)
        network = NetworkMonitor(this) { recovery.onNetworkChanged(it) }
        recovery = PlaybackRecovery(scope, { network.isOnline }, { open() },
            onWaiting = { waiting("Connessione assente · Riprendo quando torna la rete…") },
            onScheduled = { attempt, maximum -> waiting("Riconnetto… Tentativo $attempt di $maximum") },
            onExhausted = { fail(it) })
        player = ExoPlayer.Builder(this)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(12_000, 40_000, 1_000, 2_000)
                .setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build())
            .build().apply {
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = debug("first_frame")
                    override fun onPlaybackStateChanged(state: Int) {
                        debug("state=$state")
                        if (!switching) {
                            if (state == Player.STATE_BUFFERING && desiredPlaying) recovery.onBuffering()
                            if (state == Player.STATE_READY) {
                                message = ""; error = null
                                if (player.currentMediaItem?.mediaId == selected?.id) changingChannel = false
                            }
                            if (state == Player.STATE_ENDED && desiredPlaying)
                                recovery.onFailure(PlaybackFailure("La diretta si è interrotta. Riprova o scegli un’altra emittente.", true))
                        }
                        publish()
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (!switching && !playWhenReady && selected != null) {
                            desiredPlaying = false
                            recovery.stop()
                        }
                        publish()
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            recovery.onPlaying()
                            selected?.takeIf { it.id != recordedId }?.let {
                                recordedId = it.id; repository.recordPlayback(it)
                            }
                        } else recovery.onNotPlaying()
                        publish()
                    }
                    override fun onPlayerError(exception: PlaybackException) {
                        debug("error=${exception.errorCodeName}")
                        val failure = PlaybackFailure.fromPlayer(exception)
                        if (desiredPlaying) recovery.onFailure(failure) else fail(failure.message)
                    }
                    override fun onMetadata(metadata: Metadata) {
                        if (selected?.isRadio != true) return
                        for (index in 0 until metadata.length()) {
                            val entry = metadata[index]
                            val title = when {
                                entry is IcyInfo -> entry.title
                                entry is TextInformationFrame && entry.id == "TIT2" -> entry.values.firstOrNull()
                                else -> null
                            }?.trim()?.take(300)
                            if (!title.isNullOrBlank() && title != nowPlaying) {
                                nowPlaying = title
                                updateMetadata()
                                publish()
                            }
                        }
                    }
                })
            }
        debug("player_created")
        val controls = object : ForwardingPlayer(player) {
            override fun play() = resume()
            override fun pause() = pausePlayback()
            override fun stop() = stopPlayback()
            override fun seekToNext() = zap(1)
            override fun seekToPrevious() = zap(-1)
            override fun seekToNextMediaItem() = zap(1)
            override fun seekToPreviousMediaItem() = zap(-1)
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM).build()
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        }
        val activity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).putExtra("open_player", true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, controls).setSessionActivity(activity)
            .setMediaButtonPreferences(listOf(CommandButton.Builder(CommandButton.ICON_STOP)
                .setDisplayName("Interrompi").setSessionCommand(stopCommand).build()))
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val key = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    // Some TVs synthesize an untimed STOP when the old media item is cleared.
                    // It must not cancel the replacement that is still opening.
                    if (key != null && PlaybackPolicy.ignoreSyntheticStop(mobile, changingChannel,
                            key.keyCode, key.eventTime, key.downTime, key.source)) return true
                    return super.onMediaButtonEvent(session, controllerInfo, mediaButtonIntent)
                }
                override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> =
                    Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(stopCommand).build()).build())
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand != stopCommand) return super.onCustomCommand(session, controller, customCommand, args)
                    stopPlayback()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build()
        addSession(session)
        network.start()
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == LOCAL_BIND) binder else super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> if (snapshot.wantsToPlay) pausePlayback() else resume()
            NEXT -> zap(1)
            PREVIOUS -> zap(-1)
            else -> return super.onStartCommand(intent, flags, startId)
        }
        return START_NOT_STICKY
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (controllerInfo.isTrusted || controllerInfo.uid == applicationInfo.uid) session else null

    fun observe(observer: (PlaybackSnapshot) -> Unit) { observers += observer; observer(snapshot) }
    fun removeObserver(observer: (PlaybackSnapshot) -> Unit) { observers -= observer }

    fun select(channel: Channel, mobile: Boolean, audio: Boolean = channel.isRadio, play: Boolean = true) {
        changingChannel = selected != null && selected?.id != channel.id
        this.mobile = mobile
        audioOnly = channel.isRadio || audio
        selected = channel
        desiredPlaying = play
        error = null; nowPlaying = ""; recordedId = null
        if (play) recovery.start() else recovery.stop()
        open()
    }

    private fun open() {
        val channel = selected ?: return
        val request = ++generation
        resolveJob?.cancel()
        switching = true
        player.stop(); player.clearMediaItems()
        switching = false
        error = null
        message = "Apro ${channel.name}…"
        publish()
        if (desiredPlaying) recovery.onBuffering()
        resolveJob = scope.launch {
            try {
                val stream = resolver.resolve(channel)
                if (request != generation || selected?.id != channel.id) return@launch
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(if (channel.isRadio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                applyTrackPreferences()
                player.setMediaSource(source(stream, channel))
                player.prepare()
                player.playWhenReady = desiredPlaying
                publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                if (request == generation) {
                    val failure = PlaybackFailure.fromSource(exception)
                    if (desiredPlaying) recovery.onFailure(failure) else fail(failure.message)
                }
            }
        }
    }

    private fun source(stream: ResolvedStream, channel: Channel): androidx.media3.exoplayer.source.MediaSource {
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this,
            OkHttpDataSource.Factory(streamHttp).setUserAgent(HttpClient.USER_AGENT).setDefaultRequestProperties(stream.headers)))
        val drm = DefaultDrmSessionManagerProvider().apply {
            setDrmHttpDataSourceFactory(OkHttpDataSource.Factory(http.client).setUserAgent(HttpClient.USER_AGENT))
        }
        factory.setDrmSessionManagerProvider(drm)
        val item = MediaItem.Builder().setMediaId(channel.id).setUri(stream.url).setMediaMetadata(metadata(channel)).apply {
            stream.mimeType?.let { setMimeType(it) }
            stream.drm?.let {
                setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(it.licenseUrl).setLicenseRequestHeaders(it.headers).build())
            }
        }.build()
        return factory.createMediaSource(item)
    }

    private fun metadata(channel: Channel): MediaMetadata = MediaMetadata.Builder().setTitle(channel.name)
        .setArtist(nowPlaying.ifBlank { if (channel.isRadio) channel.group.ifBlank { "Radio in diretta" } else "TV in diretta" })
        .setMediaType(if (channel.isRadio) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
        .apply { if (channel.logo.startsWith("https://")) setArtworkUri(android.net.Uri.parse(channel.logo)) }.build()

    private fun updateMetadata() {
        val channel = selected ?: return
        val item = player.currentMediaItem ?: return
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(metadata(channel)).build())
    }

    fun pausePlayback() {
        changingChannel = false
        desiredPlaying = false
        recovery.stop(); resolveJob?.cancel(); generation++
        message = ""
        player.pause()
        publish()
    }

    fun resume() {
        if (selected == null) return
        desiredPlaying = true
        error = null
        recovery.start()
        if (player.mediaItemCount == 0 || player.playerError != null || player.playbackState in setOf(Player.STATE_IDLE, Player.STATE_ENDED)) open()
        else {
            if (player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable) player.seekToDefaultPosition()
            player.play()
            if (player.playbackState == Player.STATE_BUFFERING) recovery.onBuffering()
        }
        publish()
    }

    fun stopPlayback() {
        changingChannel = false
        generation++; resolveJob?.cancel(); recovery.stop()
        selected = null; desiredPlaying = false; message = ""; error = null; nowPlaying = ""
        player.stop(); player.clearMediaItems()
        publish()
        stopSelf()
    }

    fun zap(direction: Int) {
        val channel = selected ?: return
        val request = generation
        scope.launch {
            val catalog = repository.local()
            if (request != generation || selected?.id != channel.id) return@launch
            repository.nextChannel(catalog, channel.id, direction)?.let { select(it, mobile) }
        }
    }

    fun setAudioOnly(enabled: Boolean) {
        audioOnly = selected?.isRadio == true || enabled
        applyTrackPreferences()
        publish()
    }

    fun refreshPreferences() { applyTrackPreferences() }

    private fun applyTrackPreferences() {
        val saver = getSharedPreferences("lume", MODE_PRIVATE).getBoolean("data_saver", false)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .setMaxVideoBitrate(if (saver) 800_000 else Int.MAX_VALUE)
            .setMaxAudioBitrate(if (saver) 96_000 else Int.MAX_VALUE)
            .setForceLowestBitrate(saver)
            .setAudioOffloadPreferences(AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(if (audioOnly) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build())
            .build()
    }

    private fun waiting(text: String) {
        switching = true; player.stop(); switching = false
        message = text
        publish()
    }

    private fun fail(text: String) {
        changingChannel = false
        recovery.stop(); resolveJob?.cancel()
        error = text; message = ""
        player.stop()
        publish()
    }

    private fun publish() {
        val next = PlaybackSnapshot(selected, player.isPlaying, desiredPlaying,
            selected != null && error == null && desiredPlaying && (message.isNotEmpty() || player.playbackState == Player.STATE_BUFFERING),
            player.playbackState == Player.STATE_READY, audioOnly,
            PlaybackPolicy.canContinueInBackground(mobile, selected, audioOnly), message, error, nowPlaying)
        if (next != snapshot) {
            snapshot = next
            observers.toList().forEach { it(next) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!snapshot.backgroundAllowed || !desiredPlaying) stopPlayback()
    }

    override fun onDestroy() {
        recovery.stop(); network.stop(); scope.cancel(); observers.clear()
        session.release(); player.release()
        super.onDestroy()
    }

    private fun debug(value: String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.i("LumePlayer", value)
    }
}
