package it.lumetv.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import it.lumetv.app.data.PersonalPlaylistNetwork
import it.lumetv.app.data.StreamResolver
import it.lumetv.core.Channel
import it.lumetv.core.DrmScheme
import it.lumetv.core.ResolvedStream
import it.lumetv.core.PlaybackMode
import it.lumetv.core.PlaybackTarget
import it.lumetv.app.data.PersonalPlaybackResolver
import it.lumetv.app.data.PersonalPlaybackStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    private var refreshPersonalBeforeRetry = false
    private var personalRefreshAttempted = false
    private var personalHttpConsent = false
    private var personalPolicyToken = ""
    private var seekingAllowed = true
    private lateinit var personalResolver:PersonalPlaybackResolver
    private lateinit var history:PersonalPlaybackStore
    private var target=PlaybackTarget("")
    private var contentTitle=""
    private var contentDuration=0L
    private var baseOffset=0L
    private var resumePosition:Long?=null
    private var regenerate=false
    private var loaded=false
    private var ended=false
    private var historySession:it.lumetv.app.data.WatchSession?=null
    private var historyPrepared=false
    private var historyActive=false
    private var episodeJob:Job?=null
    private var episodeGeneration=0L
    private var episodeSeries=""
    private var following:PlaybackEpisode?=null
    private var followingLoading=false
    private var followingError=false
    private lateinit var trackOptions: PlaybackTrackController
    private lateinit var sleepTimer: SleepTimer
    private lateinit var loadControl:PlaybackLoadControl
    private var vodTransfer:VodPlaybackSession?=null
    private var vodPreviews:VodPreviewProvider?=null
    private var vodCache:VodMediaCache.Session?=null
    private var vodCacheTarget:Pair<String,PlaybackTarget>?=null
    private val constrainedPreloadSources=mutableSetOf<String>()
    private var rebufferCount=0
    private var rebufferElapsed=0L
    private var rebufferStarted=0L
    private var playedContent=false
    private var seekingContent=false
    private var previewActive=false
    private var lastBufferProgress=0L
    private var chapters:List<VodChapter> = emptyList()
    @Volatile var snapshot = PlaybackSnapshot()
        private set

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO){runCatching {VodMediaCache.get(this@PlaybackService).startupCleanup.join()}}
        repository = ChannelRepository(this, http)
        personalResolver=PersonalPlaybackResolver(this,repository.personal,http.client)
        history=PersonalPlaybackStore.get(this)
        resolver = StreamResolver(http) { repository.personal.resolve(it, http.client) }
        scope.launch {
            repository.personal.version.collect {
                selected?.takeIf { it.isPersonal }?.let { channel ->
                    if (!repository.personal.available(channel.sourceId) || personalHttpConsent && !repository.personal.allowsHttp(channel.sourceId) ||
                        personalPolicyToken.isNotEmpty() && repository.personal.requestContext(channel.sourceId)?.token!=personalPolicyToken) stopPlayback()
                    else repository.personal.channel(channel.id,channel.sourceId)?.takeIf { it != channel }?.let {
                        selected = it; updateMetadata(); publish()
                    }
                    if(target.mode==PlaybackMode.VOD && selected!=null && episodeSeries.isNotBlank())loadFollowingEpisode()
                }
            }
        }
        network = NetworkMonitor(this) { recovery.onNetworkChanged(it) }
        recovery = PlaybackRecovery(scope, { network.isOnline }, { open() },
            onWaiting = { waiting("Connessione assente · Riprendo quando torna la rete…") },
            onScheduled = { attempt, maximum -> waiting("Riconnetto… Tentativo $attempt di $maximum") },
            onExhausted = { fail(it) })
        loadControl=PlaybackLoadControl(this)
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { publish() }
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { publish() }
                    override fun onRenderedFirstFrame() = debug("first_frame")
                    override fun onPlaybackStateChanged(state: Int) {
                        debug("state=$state")
                        if(state==Player.STATE_BUFFERING && playedContent && !seekingContent && desiredPlaying && rebufferStarted==0L) {
                            rebufferCount++;rebufferStarted=SystemClock.elapsedRealtime()
                            protectPlaybackTransfers()
                        }
                        if(state==Player.STATE_READY){finishRebuffer();seekingContent=false}
                        if (!switching) {
                            if (state == Player.STATE_BUFFERING && desiredPlaying) recovery.onBuffering(target.mode!=PlaybackMode.LIVE)
                            if (state == Player.STATE_READY) {
                                message = ""; error = null
                                if (player.currentMediaItem?.mediaId == selected?.id) changingChannel = false
                            }
                            if (state == Player.STATE_ENDED) {
                                if(target.mode==PlaybackMode.LIVE && desiredPlaying)
                                    recovery.onFailure(PlaybackFailure("La diretta si è interrotta. Riprova o scegli un’altra emittente.", true))
                                else if(target.mode!=PlaybackMode.LIVE) {
                                    ended=true;capturePosition(true);desiredPlaying=false;recovery.stop();message="Riproduzione terminata"
                                    discardVodCache()
                                }
                            }
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
                            playedContent=true
                            if(!historyActive && loaded && target.mode!=PlaybackMode.LIVE) {
                                historyActive=true
                                val channel=selected;val watch=historySession
                                if(channel!=null && watch!=null)repository.personal.requestContext(channel.sourceId)?.let {history.enqueueStart(it,watch,position(),duration())}
                            }
                            recovery.onPlaying()
                            selected?.takeIf { target.mode==PlaybackMode.LIVE && it.id != recordedId }?.let {
                                recordedId = it.id; repository.recordPlayback(it)
                            }
                        } else recovery.onNotPlaying()
                        publish()
                    }
                    override fun onPlayerError(exception: PlaybackException) {
                        protectPlaybackTransfers()
                        debug("error=${exception.errorCodeName}")
                        val status = generateSequence<Throwable>(exception) { it.cause }
                            .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                        requestPersonalRefresh(status)
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
        trackOptions = PlaybackTrackController(this, player)
        sleepTimer = SleepTimer(scope, SystemClock::elapsedRealtime, ::publish, ::stopPlayback)
        val controls = object : ForwardingPlayer(player) {
            override fun getCurrentPosition():Long=if(target.mode==PlaybackMode.ARCHIVE)position() else super.getCurrentPosition()
            override fun getContentPosition():Long=if(target.mode==PlaybackMode.ARCHIVE)position() else super.getContentPosition()
            override fun getDuration():Long=if(target.mode==PlaybackMode.ARCHIVE && contentDuration>0)duration() else super.getDuration()
            override fun getBufferedPosition():Long=if(target.mode==PlaybackMode.ARCHIVE)(baseOffset+super.getBufferedPosition()).coerceAtMost(duration()) else super.getBufferedPosition()
            override fun play() = resume()
            override fun pause() = pausePlayback()
            override fun stop() = stopPlayback()
            override fun seekToNext() = zap(1)
            override fun seekToPrevious() = zap(-1)
            override fun seekToNextMediaItem() = zap(1)
            override fun seekToPreviousMediaItem() = zap(-1)
            override fun seekTo(positionMs:Long) {if(target.mode!=PlaybackMode.LIVE)seek(positionMs) else if(seekingAllowed)super.seekTo(positionMs)}
            override fun seekTo(mediaItemIndex:Int,positionMs:Long) {if(target.mode!=PlaybackMode.LIVE)seek(positionMs) else if(seekingAllowed)super.seekTo(mediaItemIndex,positionMs)}
            override fun seekBack(){if(target.mode!=PlaybackMode.LIVE)seekBy(-10_000) else if(seekingAllowed)super.seekBack()}
            override fun seekForward(){if(target.mode!=PlaybackMode.LIVE)seekBy(30_000) else if(seekingAllowed)super.seekForward()}
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
                .apply {if(!seekingAllowed)removeAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD,Player.COMMAND_SEEK_TO_DEFAULT_POSITION)}
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
        scope.launch {
            var ticks=0
            while(true){delay(1000);if(target.mode!=PlaybackMode.LIVE && selected!=null){
                if(++ticks%5==0)capturePosition()
                val buffered=player.contentBufferedPosition
                if(buffered>lastBufferProgress && player.playbackState==Player.STATE_BUFFERING)recovery.onBufferProgress(target.mode!=PlaybackMode.LIVE)
                lastBufferProgress=buffered
                val videoRate=player.videoFormat?.bitrate ?: -1
                val bandwidth=androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this@PlaybackService).bitrateEstimate
                vodTransfer?.update(player.isPlaying && !previewActive && selected?.sourceId !in constrainedPreloadSources && player.totalBufferedDuration>=loadControl.preloadReserveMs(videoRate) &&
                    (videoRate<=0 || bandwidth>videoRate*1.5),
                    player.currentManifest,player.currentPosition,player.videoFormat,player.audioFormat,player.currentPeriodIndex)
                publish()
            }}
        }
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
        selectTarget(channel,mobile,audio,play,PlaybackTarget(channel.id,if(channel.isVod)PlaybackMode.VOD else PlaybackMode.LIVE))
    }
    fun selectArchive(channel:Channel,eventId:String,mobile:Boolean) {
        if(channel.isPersonal && eventId.isNotEmpty())selectTarget(channel,mobile,channel.isRadio,true,PlaybackTarget(channel.id,PlaybackMode.ARCHIVE,eventId))
    }
    fun selectAt(channel:Channel,mobile:Boolean,position:Long) {
        require(position>=0 && (channel.isVod || position==0L))
        selectTarget(channel,mobile,channel.isRadio,true,PlaybackTarget(channel.id,if(channel.isVod)PlaybackMode.VOD else PlaybackMode.LIVE),initialPosition=position)
    }
    private fun selectTarget(channel:Channel,mobile:Boolean,audio:Boolean,play:Boolean,nextTarget:PlaybackTarget,useSaved:Boolean=false,initialPosition:Long?=null) {
        if (channel.isPersonal && !repository.personal.available(channel.sourceId)) return
        val samePosition=position().takeIf {loaded && !ended && selected?.sourceId==channel.sourceId && nextTarget==target &&
            (!useSaved || historySession?.let(history::isCurrent)==true)}
        capturePosition()
        history.release(historySession)
        historySession=if(channel.isPersonal && nextTarget.mode!=PlaybackMode.LIVE)history.session(channel.sourceId,nextTarget) else null
        historyPrepared=false;historyActive=false;clearFollowingEpisode()
        target=nextTarget;contentTitle=channel.name;contentDuration=0;baseOffset=0;resumePosition=initialPosition ?: samePosition;regenerate=false;loaded=false;ended=false
        finishRebuffer();rebufferCount=0;rebufferElapsed=0;playedContent=false;seekingContent=false;previewActive=false;lastBufferProgress=0;chapters=emptyList()
        changingChannel = selected != null && selected?.id != channel.id
        this.mobile = mobile
        audioOnly = channel.isRadio || audio
        selected = channel
        personalHttpConsent = channel.isPersonal && repository.personal.allowsHttp(channel.sourceId)
        personalPolicyToken = if(channel.isPersonal)repository.personal.requestContext(channel.sourceId)?.token.orEmpty() else ""
        seekingAllowed = true
        refreshPersonalBeforeRetry = false; personalRefreshAttempted = false
        desiredPlaying = play
        error = null; nowPlaying = ""; recordedId = null
        if (play) recovery.start() else recovery.stop()
        open()
    }

    private fun open() {
        if (sleepTimer.isExpired) { stopPlayback(); return }
        val channel = selected ?: return
        capturePosition()
        loaded=false
        val request = ++generation
        resolveJob?.cancel()
        switching = true
        trackOptions.reset()
        player.stop(); player.clearMediaItems()
        vodTransfer?.close();vodTransfer=null;vodPreviews?.close();vodPreviews=null
        if(vodCacheTarget!=(channel.sourceId to target))discardVodCache()
        switching = false
        error = null
        message = "Apro ${channel.name}…"
        publish()
        if (desiredPlaying) recovery.onBuffering(target.mode!=PlaybackMode.LIVE)
        resolveJob = scope.launch {
            try {
                if (refreshPersonalBeforeRetry && channel.isPersonal) {
                    refreshPersonalBeforeRetry = false
                    try { repository.personal.refresh(channel.sourceId, true) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A source error does not discard the cached channel. */ }
                }
                val watch=historySession
                if(watch!=null && !historyPrepared) {
                    val initial=resumePosition
                    val point=initial ?: withContext(Dispatchers.IO){history.read(watch.source,watch.target).resume}
                    if(request!=generation || selected?.id!=channel.id)return@launch
                    resumePosition=point;historyPrepared=true
                }
                val result=if(channel.isPersonal)personalResolver.resolve(channel,target,resumePosition) else null
                val stream = result?.stream ?: resolver.resolve(channel)
                if (request != generation || selected?.id != channel.id) return@launch
                if(result!=null){target=result.target;contentTitle=result.title;contentDuration=result.duration;baseOffset=result.baseOffset;regenerate=result.regenerate
                    episodeSeries=result.seriesKey
                    if(target.mode==PlaybackMode.VOD && episodeSeries.isNotBlank())loadFollowingEpisode() else clearFollowingEpisode()
                }
                if (stream.personalAllowHttp && !repository.personal.allowsHttp(channel.sourceId))
                    throw it.lumetv.app.data.SourceException("Il consenso HTTP di questa lista è stato disattivato.")
                if(stream.personal && repository.personal.requestContext(channel.sourceId)?.token!=stream.personalNetworkToken)
                    throw it.lumetv.app.data.SourceException("La configurazione della lista è cambiata. Riapri il canale.")
                personalPolicyToken=stream.personalNetworkToken;seekingAllowed=stream.seekable!=false
                loadControl.mode=target.mode
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(if (channel.isRadio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                applyTrackPreferences()
                player.setMediaSource(source(stream, channel,result?.metadata),if(result?.target?.mode==PlaybackMode.LIVE)C.TIME_UNSET else result?.startPosition ?: C.TIME_UNSET)
                loaded=true;ended=false
                player.prepare()
                player.playWhenReady = desiredPlaying
                publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) {
                if (request == generation) {
                    debug("source_error type=${exception.javaClass.simpleName} at=${exception.stackTrace.firstOrNull {it.className.startsWith("it.lumetv.")}?.let {it.fileName+":"+it.lineNumber}}")
                    requestPersonalRefresh((exception as? it.lumetv.app.data.PlaylistFetchException)?.status)
                    val failure = PlaybackFailure.fromSource(exception)
                    if (desiredPlaying) recovery.onFailure(failure) else fail(failure.message)
                }
            }
        }
    }

    private fun requestPersonalRefresh(status: Int?) {
        if (selected?.isPersonal == true && !personalRefreshAttempted && status in setOf(401, 403, 404, 410)) {
            personalRefreshAttempted = true; refreshPersonalBeforeRetry = true
        }
    }

    private fun source(stream: ResolvedStream, channel: Channel,details:it.lumetv.core.VodMetadata?=null): androidx.media3.exoplayer.source.MediaSource {
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this,
            PersonalMediaSources.http(streamHttp, stream.headers)))
        val drm = DefaultDrmSessionManagerProvider().apply {
            setDrmHttpDataSourceFactory(OkHttpDataSource.Factory(http.client).setUserAgent(HttpClient.USER_AGENT))
        }
        factory.setDrmSessionManagerProvider(drm)
        val item = MediaItem.Builder().setMediaId(channel.id).setUri(stream.url).setMediaMetadata(metadata(channel)).apply {
            stream.mimeType?.let { setMimeType(it) }
            stream.drm?.let {
                setDrmConfiguration(MediaItem.DrmConfiguration.Builder(if(it.scheme==DrmScheme.CLEARKEY) C.CLEARKEY_UUID else C.WIDEVINE_UUID)
                    .apply { if(it.licenseUrl.isNotBlank())setLicenseUri(it.licenseUrl) }
                    .setLicenseRequestHeaders(it.headers).setMultiSession(it.multiSession)
                    .setForceDefaultLicenseUri(stream.personal && it.licenseUrl.isNotBlank()).build())
            }
        }.build()
        val context=if(stream.personal)repository.personal.requestContext(channel.sourceId) else null
        fun create(mediaItem:MediaItem,wrap:(androidx.media3.datasource.DataSource.Factory)->androidx.media3.datasource.DataSource.Factory):androidx.media3.exoplayer.source.MediaSource =
            if(stream.personal)PersonalMediaSources.create(this,stream,mediaItem,streamHttp,http.client,context,wrap){repository.personal.allowsHttp(channel.sourceId)}
            else DefaultMediaSourceFactory(wrap(DefaultDataSource.Factory(this,PersonalMediaSources.http(streamHttp,stream.headers))))
                .setDrmSessionManagerProvider(drm).createMediaSource(mediaItem)
        val result=create(item){upstream->
            if(target.mode!=PlaybackMode.VOD)upstream else {
                val cache=runCatching {VodMediaCache.get(this)}.getOrNull()
                if(cache==null)upstream else {
                    val ownership=vodCache ?: cache.session().also {vodCache=it;vodCacheTarget=channel.sourceId to target}
                    val namespace=ownership.namespace(VodMediaCache.digest(channel.sourceId+"|"+context?.token.orEmpty()+"|"+stream.url+"|"+stream.headers.toSortedMap().toString()))
                    VodPlaybackSession(scope,cache,namespace,upstream,android.net.Uri.parse(stream.url),ownership){context?.valid()!=false}
                        .also {vodTransfer=it}.playbackFactory
                }
            }
        }
        if(target.mode==PlaybackMode.VOD) {
            val transfer=vodTransfer
            val previewFactory=object:androidx.media3.exoplayer.source.MediaSource.Factory by factory {
                override fun createMediaSource(mediaItem:MediaItem)=create(mediaItem){transfer?.previewFactory ?: it}
            }
            val headers=it.lumetv.core.PlaylistHeaders.merge(stream.headers.filterKeys {it.equals("User-Agent",true)},context?.settings?.rule(it.lumetv.app.data.PlaylistResource.LOGO)?.headers.orEmpty())
            vodPreviews=VodPreviewProvider(this,scope,http,context,headers,details,item,previewFactory,
                stream.drm==null && seekingAllowed,{
                    selected?.id==channel.id && context?.valid()!=false && (player.playbackState==Player.STATE_ENDED || player.playbackState==Player.STATE_READY && player.totalBufferedDuration>=10_000)
                },{values->chapters=values;publish()})
        }
        return result
    }

    private fun metadata(channel: Channel): MediaMetadata = MediaMetadata.Builder().setTitle(contentTitle.ifBlank {channel.name})
        .setArtist(if(target.mode==PlaybackMode.ARCHIVE)"${channel.name} · Archivio" else if(target.mode==PlaybackMode.VOD)"Video" else nowPlaying.ifBlank { if (channel.isRadio) channel.group.ifBlank { "Radio in diretta" } else "TV in diretta" })
        .setMediaType(if(target.mode!=PlaybackMode.LIVE)MediaMetadata.MEDIA_TYPE_MOVIE else if (channel.isRadio) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
        .apply { if (!channel.isPersonal && channel.logo.startsWith("https://")) setArtworkUri(android.net.Uri.parse(channel.logo)) }.build()

    private fun updateMetadata() {
        val channel = selected ?: return
        val item = player.currentMediaItem ?: return
        player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(metadata(channel)).build())
    }

    fun pausePlayback() {
        capturePosition()
        changingChannel = false
        desiredPlaying = false
        recovery.stop(); resolveJob?.cancel(); generation++
        vodTransfer?.suspendPreload();finishRebuffer()
        message = ""
        player.pause()
        publish()
    }

    fun resume() {
        if (sleepTimer.isExpired) { stopPlayback(); return }
        if (selected == null) return
        if(target.mode==PlaybackMode.VOD && historySession?.let(history::isCurrent)==false){reopenVideo();return}
        if(ended && target.mode!=PlaybackMode.LIVE){restart();return}
        desiredPlaying = true
        error = null
        recovery.start()
        if (player.mediaItemCount == 0 || player.playerError != null || player.playbackState in setOf(Player.STATE_IDLE, Player.STATE_ENDED)) open()
        else {
            if (target.mode==PlaybackMode.LIVE && seekingAllowed && player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable) player.seekToDefaultPosition()
            player.play()
            if (player.playbackState == Player.STATE_BUFFERING) recovery.onBuffering(target.mode!=PlaybackMode.LIVE)
        }
        publish()
    }

    fun stopPlayback() {
        sleepTimer.cancel()
        capturePosition();loaded=false
        history.release(historySession);historySession=null;historyPrepared=false;historyActive=false;clearFollowingEpisode()
        changingChannel = false
        generation++; resolveJob?.cancel(); recovery.stop()
        selected = null; desiredPlaying = false; message = ""; error = null; nowPlaying = ""
        player.stop(); player.clearMediaItems()
        discardVodCache();chapters=emptyList();finishRebuffer()
        publish()
        stopSelf()
    }

    fun zap(direction: Int) {
        if(target.mode!=PlaybackMode.LIVE){seekBy(if(direction>0)30_000 else -10_000);return}
        val channel = selected ?: return
        val request = generation
        scope.launch {
            val catalog = repository.local()
            if (request != generation || selected?.id != channel.id) return@launch
            val next=repository.nextChannel(catalog,channel.id,direction,channel)
            if(request==generation && selected?.id==channel.id)next?.let {select(it,mobile)}
        }
    }

    private fun clearFollowingEpisode() {
        episodeGeneration++;episodeJob?.cancel();episodeJob=null;episodeSeries=""
        following=null;followingLoading=false;followingError=false
    }
    fun reloadFollowingEpisode(){if(target.mode==PlaybackMode.VOD && episodeSeries.isNotBlank())loadFollowingEpisode()}
    private fun loadFollowingEpisode() {
        val channel=selected?.takeIf {target.mode==PlaybackMode.VOD && episodeSeries.isNotBlank()} ?: return
        val request=++episodeGeneration;episodeJob?.cancel();following=null;followingLoading=true;followingError=false;publish()
        episodeJob=scope.launch {
            try {
                val next=repository.personal.nextEpisode(channel.sourceId,channel.id)
                if(request!=episodeGeneration || selected?.id!=channel.id || selected?.sourceId!=channel.sourceId || target.mode!=PlaybackMode.VOD)return@launch
                following=next?.let {PlaybackEpisode(it.source,it.id,it.title,it.season,it.episode)};followingLoading=false;publish()
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(request==episodeGeneration){followingLoading=false;followingError=true;publish()}}
        }
    }
    fun playFollowingEpisode(currentId:String,nextId:String) {
        val current=selected?.takeIf {it.id==currentId && target.mode==PlaybackMode.VOD} ?: return
        val next=following?.takeIf {it.id==nextId && it.source==current.sourceId} ?: return
        val request=generation
        scope.launch {
            try {
                val actual=repository.personal.nextEpisode(current.sourceId,current.id)
                if(request!=generation || selected?.id!=currentId || following!=next || target.mode!=PlaybackMode.VOD)return@launch
                if(actual?.id!=next.id || actual.source!=next.source){loadFollowingEpisode();return@launch}
                val channel=repository.personal.channel(next.id,next.source)?.takeIf {it.isVod}
                if(request!=generation || selected?.id!=currentId || following!=next || target.mode!=PlaybackMode.VOD)return@launch
                if(channel!=null)select(channel,mobile,audioOnly,true) else loadFollowingEpisode()
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(request==generation && selected?.id==currentId){following=null;followingError=true;publish()}}
        }
    }
    fun reopenVideo(){selected?.takeIf {target.mode==PlaybackMode.VOD}?.let {selectTarget(it,mobile,audioOnly,true,target,useSaved=true)}}

    fun setAudioOnly(enabled: Boolean) {
        audioOnly = selected?.isRadio == true || enabled
        applyTrackPreferences()
        publish()
    }

    fun refreshPreferences() { trackOptions.refresh(); publish() }

    fun setPlaybackPreferences(value: PlaybackPreferences) { trackOptions.setPreferences(value); publish() }
    fun chooseTrack(revision: Long, type: Int, key: String): Boolean = trackOptions.choose(revision, type, key).also { publish() }
    fun automaticTrack(type: Int) { trackOptions.automatic(type); publish() }
    fun setSleepTimer(minutes: Int) {
        if (minutes == 0) sleepTimer.cancel()
        else if (selected != null) sleepTimer.start(minutes)
        applyTrackPreferences()
        publish()
    }

    private fun applyTrackPreferences() {
        trackOptions.apply(audioOnly, sleepTimer.remaining > 0)
    }

    private fun waiting(text: String) {
        capturePosition();loaded=false
        switching = true; player.stop(); switching = false
        message = text
        publish()
    }

    private fun fail(text: String) {
        capturePosition();loaded=false
        finishRebuffer();vodTransfer?.suspendPreload();vodPreviews?.cancel()
        changingChannel = false
        recovery.stop(); resolveJob?.cancel()
        error = text; message = ""
        player.stop()
        discardVodCache()
        publish()
    }

    private fun publish() {
        if (!::trackOptions.isInitialized || !::sleepTimer.isInitialized) return
        val next = PlaybackSnapshot(selected, player.isPlaying, desiredPlaying,
            selected != null && error == null && desiredPlaying && (message.isNotEmpty() || player.playbackState == Player.STATE_BUFFERING),
            player.playbackState == Player.STATE_READY, audioOnly,
            PlaybackPolicy.canContinueInBackground(mobile, selected, audioOnly), message, error, nowPlaying,
            target.mode,contentTitle,position(),duration(),target.mode!=PlaybackMode.LIVE && seekingAllowed && (regenerate || player.isCurrentMediaItemSeekable),ended,
            trackOptions.snapshot(sleepTimer.remaining),following,followingLoading,followingError,generation.toLong(),
            bufferedPosition=if(loaded && target.mode!=PlaybackMode.LIVE)(if(vodTransfer?.fullyCached()==true)duration() else player.contentBufferedPosition+baseOffset).coerceIn(0,duration().coerceAtLeast(0)) else 0,
            bufferedDuration=if(loaded)player.totalBufferedDuration.coerceAtLeast(0) else 0,
            rebufferCount=rebufferCount,rebufferDuration=rebufferElapsed+if(rebufferStarted>0)SystemClock.elapsedRealtime()-rebufferStarted else 0,
            downloadBitrate=androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(this).bitrateEstimate,
            chapters=chapters,preloadedBytes=vodTransfer?.preloadedBytes ?: 0)
        if (next != snapshot) {
            snapshot = next
            observers.toList().forEach { it(next) }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (target.mode==PlaybackMode.VOD || !snapshot.backgroundAllowed || !desiredPlaying) stopPlayback()
    }

    override fun onDestroy() {
        capturePosition()
        history.release(historySession)
        sleepTimer.cancel()
        discardVodCache()
        recovery.stop(); network.stop(); scope.cancel(); observers.clear()
        session.release(); player.release()
        super.onDestroy()
    }

    private fun position():Long=if(loaded && player.mediaItemCount>0)(baseOffset+player.currentPosition).coerceAtLeast(0) else resumePosition ?: 0L
    private fun duration():Long=if(target.mode==PlaybackMode.ARCHIVE && contentDuration>0)contentDuration else if(player.duration>0)player.duration+baseOffset else contentDuration
    private fun capturePosition(completed:Boolean=ended) {
        val channel=selected ?: return
        if(target.mode==PlaybackMode.LIVE || !channel.isPersonal || !loaded || !historyActive)return
        val point=position();resumePosition=point
        val watch=historySession ?: return
        repository.personal.requestContext(channel.sourceId)?.let {history.enqueue(it,watch,point,duration(),completed)}
    }
    fun seek(position:Long) {
        if(target.mode==PlaybackMode.LIVE || !seekingAllowed || selected==null)return
        val wanted=position.coerceIn(0,(duration()-1).coerceAtLeast(0))
        val finished=ended
        ended=false;message="";seekingContent=true;previewActive=false;vodTransfer?.suspendPreload();finishRebuffer()
        if(finished && target.mode==PlaybackMode.VOD){resumePosition=wanted;loaded=false;open()}
        else if(loaded && player.isCurrentMediaItemSeekable && wanted>=baseOffset){player.seekTo(wanted-baseOffset);resumePosition=wanted;capturePosition();publish()}
        else if(regenerate){capturePosition();resumePosition=wanted;loaded=false;open()}
    }
    fun seekBy(delta:Long)=seek(position()+delta)
    private fun discardVodCache(){
        vodTransfer?.close();vodTransfer=null;vodPreviews?.close();vodPreviews=null
        vodCache?.close();vodCache=null;vodCacheTarget=null
    }
    private fun finishRebuffer(){if(rebufferStarted>0){rebufferElapsed+=SystemClock.elapsedRealtime()-rebufferStarted;rebufferStarted=0}}
    private fun protectPlaybackTransfers(){
        val transfer=vodTransfer ?: return
        if(transfer.preloadedBytes>0)selected?.sourceId?.let(constrainedPreloadSources::add)
        transfer.protectPlayback();vodPreviews?.cancel()
    }
    fun setPreviewActive(value:Boolean){previewActive=value;if(value)vodTransfer?.suspendPreload() else vodPreviews?.cancel()}
    fun requestPreview(position:Long,answer:(android.graphics.Bitmap?)->Unit){vodPreviews?.request(position,answer) ?: answer(null)}
    fun restart(){if(target.mode!=PlaybackMode.LIVE){
        history.release(historySession);historySession=selected?.takeIf {it.isPersonal}?.let {history.session(it.sourceId,target)};historyPrepared=false;historyActive=false
        resumePosition=0;loaded=false;ended=false;desiredPlaying=true;recovery.start();open()
    }}
    fun returnToLive() {val channel=selected ?: return;if(target.mode==PlaybackMode.ARCHIVE)select(channel,mobile,audioOnly,true) else resume()}

    private fun debug(value: String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.i("LumePlayer", value)
    }
}
