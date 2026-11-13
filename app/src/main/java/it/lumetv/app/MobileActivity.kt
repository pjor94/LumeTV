package it.lumetv.app

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.PendingIntent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.*
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.app.integration.LaunchQueue
import it.lumetv.app.integration.LaunchRequest
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.playback.PlaybackService
import it.lumetv.app.ui.mobile.MobileMiniPlayer
import it.lumetv.app.ui.mobile.MobileOption
import it.lumetv.app.ui.mobile.MobileOptionsView
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileBrowseItem
import it.lumetv.app.ui.mobile.MobileBrowseView
import it.lumetv.app.ui.mobile.MobileChannelAdapter
import it.lumetv.app.ui.mobile.MobilePlaybackView
import it.lumetv.app.ui.mobile.MobileSection
import it.lumetv.app.ui.mobile.MobileStyle
import it.lumetv.app.update.UpdateController
import it.lumetv.core.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Touch UI. Repositories, source resolution, DRM, recovery and storage are shared with TV. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MobileActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val http = HttpClient()
    private lateinit var repository: ChannelRepository
    private lateinit var guide: GuideRepository
    private lateinit var logos: ChannelLogoRepository
    private lateinit var player: TvPlayer
    private lateinit var reminders: ProgrammeReminders
    private lateinit var updates: UpdateController
    private lateinit var root: FrameLayout
    private lateinit var browse: MobileBrowseView
    private lateinit var catalogPanels:it.lumetv.app.ui.PersonalCatalogPanels
    private lateinit var miniPlayer: MobileMiniPlayer
    private lateinit var options: MobileOptionsView
    private lateinit var playlists: it.lumetv.app.ui.PersonalPlaylistsScreen
    private lateinit var homeSettings: it.lumetv.app.home.HomeChannelSettings
    private lateinit var playback: MobilePlaybackView
    private var channels = emptyList<Channel>()
    private var launchGeneration=0
    private var programmes = emptyMap<String, List<Programme>>()
    private var current: Channel? = null
    private var showingCatalog = true
    private var showingOptions = false
    private var userPaused = false
    private var failed = false
    private var catalogLoaded = false
    private var playerReady = false
    private var activeSessionSeen = false
    private var explicitRequestSeen = false
    private var catalogJob: Job? = null
    private var guideJob: Job? = null
    private var personalGuideJob:Job?=null
    private var guideLoading = false
    private val launchQueue = LaunchQueue()
    private var programmeDialog: Dialog? = null
    private var channelsDialog: Dialog? = null
    private var refreshPicker: (() -> Unit)? = null
    private var reminderDialog: AlertDialog? = null
    private val catalogDialogs = mutableSetOf<AlertDialog>()

    private val tick = object : Runnable {
        override fun run() {
            if (showingCatalog) browse.tick() else updateLiveInfo()
            refreshPicker?.invoke()
            checkReminders()
            updates.maybePrompt()
            handler.postDelayed(this, 30_000)
        }
    }
    private val syncTick = object : Runnable {
        override fun run() {
            if (catalogLoaded) { refreshCatalog(); refreshGuide() }
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        repository = ChannelRepository(this, http)
        reminders = ProgrammeReminders(this)
        logos = ChannelLogoRepository(this, http, lifecycleScope)
        guide = GuideRepository(this, http, logos::updateSources)
        player = TvPlayer(this, http)
        showingCatalog = savedInstanceState?.getBoolean("mobile_catalog", true) ?: true
        showingOptions = savedInstanceState?.getBoolean("mobile_options", false) ?: false
        if (showingOptions) showingCatalog = true
        userPaused = savedInstanceState?.getBoolean("mobile_paused", false) ?: false
        if (!showingCatalog) requestedOrientation = savedInstanceState?.getInt("mobile_orientation", ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
            ?: ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleMediaKey(event) || super.dispatchKeyEvent(event)
        }.apply { setBackgroundColor(TvStyle.background) }
        browse = MobileBrowseView(this, logos, repository::isFavorite, ::chooseItem, ::toggleFavorite,
            { refreshCatalog(true); refreshGuide(true) }, ::showOptions, { updates.show() })
        browse.restoreState(savedInstanceState)
        playback = MobilePlaybackView(this, logos, { showCatalog() }, ::togglePause, { zap(-1) }, { zap(1) },
            ::showChannels, ::toggleFullscreen, { current?.let(::toggleFavorite) }, ::showCurrentProgramme,
            {if(player.snapshot.channel!=null)player.resumePlayback() else current?.let { selectChannel(it) } }, ::toggleAudioOnly, ::enterPip, ::stopPlayback).apply { visibility = View.GONE }
        miniPlayer = MobileMiniPlayer(this, ::openPlayback, ::togglePause, ::stopPlayback)
        browse.setMiniPlayer(miniPlayer)
        options = MobileOptionsView(this, ::closeOptions, ::chooseOption).apply {
            visibility = if (showingOptions) View.VISIBLE else View.GONE
        }
        options.restoreState(savedInstanceState)
        playback.pipButton.visibility = if (supportsPip()) View.VISIBLE else View.GONE
        root.addView(browse, FrameLayout.LayoutParams(-1, -1))
        root.addView(playback, FrameLayout.LayoutParams(-1, -1))
        root.addView(options, FrameLayout.LayoutParams(-1, -1))
        playlists = it.lumetv.app.ui.PersonalPlaylistsScreen(this, lifecycleScope, repository.personal, true,
            {}, ::openPlayback, ::stopPlayback)
        root.addView(playlists, FrameLayout.LayoutParams(-1, -1))
        playlists.restoreState(savedInstanceState)
        homeSettings = it.lumetv.app.home.HomeChannelSettings(this, lifecycleScope, ::trackDialog,
            { showingOptions && !playlists.isShowing }, ::renderOptions, ::message)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                it.lumetv.app.home.HomeChannels.revisions.collect { if (showingOptions) renderOptions() }
            }
        }
        browse.sourceChooser = { selected, kind, choose -> playlists.chooseSource(selected, false, kind, choose) }
        browse.trackModal = ::trackDialog
        browse.favoriteIds=repository::favorites
        browse.catalogPager=it.lumetv.app.ui.PersonalCatalog(lifecycleScope,repository.personal,browse::catalogChanged)
        browse.catalogPager?.restore(savedInstanceState?.getInt("personal_page_offset") ?: 0)
        browse.choosePersonalGroup={source,kind,choose->it.lumetv.app.ui.PersonalCatalogPanels(this,lifecycleScope)
            .group(repository.personal,it.lumetv.app.data.PersonalCatalogQuery(source=source,kind=kind),choose)}
        browse.choosePersonalChannel={source,choose->it.lumetv.app.ui.PersonalCatalogPanels(this,lifecycleScope)
            .paged("Scegli un canale",load={q,o->repository.personal.catalog(it.lumetv.app.data.PersonalCatalogQuery(source=source,kind=it.lumetv.core.MediaKind.TV,text=q,offset=o,limit=50)).let {it.rows to it.total}},label={it.name},choose=choose)}
        browse.openVideoLibrary=::openVideoLibrary
        browse.onGuideWindow=::loadPersonalGuideWindow
        browse.onProgrammeSearch={channel->it.lumetv.app.ui.PersonalCatalogPanels(this,lifecycleScope).paged("Cerca nella guida di ${channel.name}",
            load={q,o->guide.personal.search(channel,q,o)},label={it.title+" · "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(it.start))}) {showProgramme(channel,it)}}
        lifecycleScope.launch {guide.personal.state.collect {if(catalogLoaded){updateGuideStatus();browse.reloadGuideWindow()}}}
        playback.onDemand.apply {onSeek=player::seek;onSeekBy=player::seekBy;onRestart=player::restart;onToggle={player.togglePause()};onReturnLive=player::returnToLive}
        lifecycleScope.launch {
            repository.personal.version.collect {
                if (catalogLoaded) {
                    channels = channels.filterNot { it.isPersonal } + repository.personal.cached()
                    renderData(); refreshPicker?.invoke()
                }
            }
        }
        if (showingOptions) { browse.visibility = View.GONE; options.setMiniPlayer(miniPlayer) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) view.setPadding(0, 0, 0, 0)
            else view.setPadding(safe.left, safe.top, safe.right, max(safe.bottom, if (showingCatalog) keyboard.bottom else 0))
            insets
        }
        setContentView(root)
        catalogPanels=it.lumetv.app.ui.PersonalCatalogPanels(this,lifecycleScope)
        catalogPanels.attachVideo(browse.videoContainer,browse::showVideo)
        catalogPanels.restartCurrent={channel->if(player.snapshot.channel?.id==channel.id)player.restart()}
        browse.onSectionSelected=catalogPanels::dismiss
        catalogPanels.restoreState(savedInstanceState)
        lifecycleScope.launch {repository.personal.classification.collect {state->browse.setCatalogLoadingMessage(state?.let {"Classifico ${it.name}… ${it.processed} / ${it.total} voci"})}}
        lifecycleScope.launch {repository.personal.videoSources.collect {sources->
            browse.setVideoAvailable(sources.isNotEmpty())
            if(catalogLoaded)catalogPanels.restorePending(repository.personal,logos,repository::isFavorite,::toggleFavorite,::selectChannel)
        }}
        updates = UpdateController(this, savedInstanceState, {
            catalogLoaded && showingCatalog && !showingOptions && programmeDialog?.isShowing != true &&
                channelsDialog?.isShowing != true && reminderDialog?.isShowing != true &&
                catalogDialogs.none { it.isShowing } && !browse.isSearching && !catalogPanels.isShowing
        }, notice = { version -> browse.showUpdate(version); if (showingOptions) renderOptions() })
        renderOptions()
        applySystemBars()
        player.onChanged = ::renderPlayback
        player.onConnected = {
            playerReady = true
            if (catalogLoaded && !consumeLaunchRequest()) {
                if (player.snapshot.channel != null) {
                    if (intent.getBooleanExtra("open_player", false)) { intent.removeExtra("open_player"); openPlayback() }
                    else renderPlayback(player.snapshot)
                } else if (!showingCatalog && current != null) selectChannel(current!!, restorePause = true)
            }
        }
        // Keep the consumed marker across repeated recreation, so an old catalog link
        // cannot replace the restored options page or another navigation destination.
        explicitRequestSeen = savedInstanceState?.getBoolean("mobile_explicit_consumed") == true
        if (!explicitRequestSeen && intent.action == Intent.ACTION_VIEW) {
            explicitRequestSeen = true
            launchQueue.replace(LaunchRequest.parse(intent.dataString))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    catalogPanels.handleBack() -> Unit
                    playlists.handleBack() -> Unit
                    showingOptions -> closeOptions()
                    !showingCatalog -> {showCatalog();catalogPanels.resume()}
                    browse.closeSearch() -> Unit
                    browse.section != MobileSection.CHANNELS -> browse.navigate(MobileSection.CHANNELS)
                    else -> { launchQueue.consume(true); finish() }
                }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                logos.revisions.collect { browse.refreshLogos(); refreshPicker?.invoke() }
            }
        }
        lifecycleScope.launch {
            logos.local()
            channels = repository.local()
            programmes = guide.local()
            if (isFinishing || isDestroyed) return@launch
            catalogLoaded = true
            catalogPanels.restorePending(repository.personal,logos,repository::isFavorite,::toggleFavorite,::selectChannel)
            val previous = savedInstanceState?.getString("mobile_channel") ?: repository.lastChannelId
            current = player.snapshot.channel ?: repository.find(previous)
            renderData()
            if (!consumeLaunchRequest()) {
                if (player.snapshot.channel != null) {
                    if (intent.getBooleanExtra("open_player", false)) { intent.removeExtra("open_player"); openPlayback() }
                    else if (!showingCatalog) openPlayback() else renderPlayback(player.snapshot)
                } else if (!showingCatalog && current != null && playerReady && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                    selectChannel(current!!, restorePause = true)
                else if (current == null && !showingOptions) showCatalog()
            }
            refreshCatalog(); refreshGuide()
            root.post { updates.maybePrompt() }
        }
    }

    override fun onStart() {
        super.onStart()
        updates.onStart()
        player.attach(playback.video)
        handler.post(tick); handler.post(syncTick)
    }

    override fun onStop() {
        updates.onStop()
        programmeDialog?.dismiss(); programmeDialog = null
        channelsDialog?.dismiss(); channelsDialog = null; refreshPicker = null
        reminderDialog?.dismiss(); reminderDialog = null
        catalogJob?.cancel(); guideJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        releasePlayback(stop = !player.snapshot.backgroundAllowed)
        playback.stopTimers()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (showingOptions) renderOptions()
        root.post { updates.maybePrompt() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::updates.isInitialized) root.post { updates.maybePrompt() }
    }

    override fun onDestroy() {
        catalogDialogs.toList().forEach { it.dismiss() }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        playback.updateConfiguration()
        applySystemBars()
        root.post {
            channelsDialog?.window?.setLayout(root.width, (root.height * .78f).toInt())
            programmeDialog?.window?.setLayout(root.width, -2)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        catalogPanels.saveState(outState)
        outState.putInt("personal_page_offset",browse.catalogPager?.offset ?: 0)
        playlists.saveState(outState)
        outState.putString("mobile_channel", current?.id)
        outState.putBoolean("mobile_catalog", showingCatalog)
        outState.putBoolean("mobile_options", showingOptions)
        outState.putBoolean("mobile_paused", userPaused)
        outState.putInt("mobile_orientation", requestedOrientation)
        outState.putBoolean("mobile_explicit_consumed", explicitRequestSeen && launchQueue.pending == null)
        updates.saveState(outState)
        browse.saveState(outState)
        options.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            explicitRequestSeen = true
            // A channel link replaces playback directly, preserving fullscreen orientation.
            programmeDialog?.dismiss(); channelsDialog?.dismiss()
            activeSessionSeen = false
            player.stop()
            launchQueue.replace(LaunchRequest.parse(intent.dataString))
            consumeLaunchRequest()
        }
        if (intent.getBooleanExtra("open_player", false) && player.isConnected) {
            intent.removeExtra("open_player"); openPlayback()
        }
        updates.onNewIntent(intent)
    }

    private fun consumeLaunchRequest(): Boolean {
        val request = launchQueue.consume(catalogLoaded && playerReady) ?: return false
        val generation=++launchGeneration
        catalogPanels.dismiss()
        catalogDialogs.toList().forEach { it.dismiss() }
        if (playlists.isShowing) playlists.hide()
        when (request) {
            is LaunchRequest.ChannelId -> lifecycleScope.launch {val found=repository.find(request.id)
                if(generation!=launchGeneration)return@launch
                found?.let {selectChannel(it)}
                ?: run {showCatalog(MobileSection.CHANNELS);message("Canale non più disponibile")}}
            LaunchRequest.Last -> channels.firstOrNull { !it.isPersonal && it.id == repository.lastIntegratedChannelId }?.let { selectChannel(it) }
                ?: run { showCatalog(MobileSection.CHANNELS); message("Nessun ultimo canale disponibile") }
            LaunchRequest.Catalog -> showCatalog(MobileSection.CHANNELS)
            LaunchRequest.Favorites -> showCatalog(MobileSection.FAVORITES)
            LaunchRequest.Guide -> showCatalog(MobileSection.GUIDE)
            LaunchRequest.Discover -> showCatalog(MobileSection.DISCOVER)
            LaunchRequest.Integration -> { showCatalog(MobileSection.CHANNELS); showOptions(); showIntegrationOptions() }
            LaunchRequest.Home -> { showCatalog(MobileSection.CHANNELS); showOptions(); homeSettings.show() }
            LaunchRequest.Invalid -> { showCatalog(MobileSection.CHANNELS); message("Collegamento non valido") }
        }
        return true
    }

    private fun chooseItem(item: MobileBrowseItem) {
        if (item.channelRow || item.programme == null) selectChannel(item.channel)
        else showProgramme(item.channel, item.programme)
    }

    private fun selectChannel(channel: Channel, restorePause: Boolean = false,archiveEvent:String?=null) {
        launchGeneration++
        dismissOptions()
        programmeDialog?.dismiss(); channelsDialog?.dismiss()
        browse.hideKeyboard()
        val same = archiveEvent==null && player.snapshot.mode!=it.lumetv.core.PlaybackMode.ARCHIVE && player.snapshot.channel?.id == channel.id && player.snapshot.error == null
        current = channel; showingCatalog = false; failed = false
        playback.restoreVideo()
        browse.visibility = View.GONE; playback.visibility = View.VISIBLE
        if (!same) {
            playback.showLoading("Apro ${channel.name}…")
            if(archiveEvent!=null)player.selectArchive(channel,archiveEvent,true)
            else player.select(channel, mobile = true, play = !restorePause || !userPaused)
        } else {
            if (!restorePause && !player.snapshot.wantsToPlay) player.resumeToLive()
            renderPlayback(player.snapshot)
        }
        updateLiveInfo(); applySystemBars(); updatePip()
        if(channel.isPersonal)loadPersonalGuideWindow(listOf(channel),System.currentTimeMillis(),System.currentTimeMillis()+GuideSchedule.WINDOW)
    }

    private fun openPlayback() {
        dismissOptions()
        val state = player.snapshot
        if (state.channel == null) { current?.let { selectChannel(it) }; return }
        current = state.channel
        showingCatalog = false
        playback.restoreVideo()
        browse.visibility = View.GONE; playback.visibility = View.VISIBLE
        updateLiveInfo(); renderPlayback(state); applySystemBars(); updatePip()
    }

    private fun renderPlayback(state: PlaybackSnapshot) {
        val changedChannel=current?.id!=state.channel?.id
        if (::playlists.isInitialized) playlists.bindPlayback(state.channel != null)
        if (!::miniPlayer.isInitialized) return
        if (state.channel == null) {
            miniPlayer.visibility = View.GONE
            if (activeSessionSeen) { activeSessionSeen = false; if (!showingOptions) showCatalog() }
            if (state.error != null && !showingCatalog) playback.showError(state.error)
            applySystemBars()
            updatePip()
            return
        }
        activeSessionSeen = true
        current = state.channel
        userPaused = !state.wantsToPlay
        failed = state.error != null
        updateLiveInfo()
        if (state.error != null) playback.showError(state.error)
        else if (state.buffering) playback.showLoading(state.message.ifBlank { "Carico la diretta…" })
        else playback.ready(state.playing)
        playback.renderState(state, repository.isFavorite(state.channel))
        if (showingCatalog) miniPlayer.bind(state, playback.video)
        else miniPlayer.visibility = View.GONE
        if(changedChannel)renderData()
        applySystemBars(); updatePip()
    }

    private fun togglePause() {
        if (player.snapshot.channel == null) return
        if (player.snapshot.error != null) player.resumePlayback()
        else player.togglePause()
    }

    private fun showCatalog(section: MobileSection? = null) {
        dismissOptions()
        programmeDialog?.dismiss(); channelsDialog?.dismiss()
        showingCatalog = true
        playback.stopTimers(); playback.visibility = View.GONE; browse.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        if (section != null) browse.navigate(section)
        miniPlayer.bind(player.snapshot, playback.video)
        renderData(); applySystemBars(); updatePip()
        root.post { updates.maybePrompt() }
    }

    private fun stopPlayback() {
        val keepOptions = showingOptions
        player.stop()
        if (!keepOptions) showCatalog()
    }

    private fun releasePlayback(stop: Boolean = true) {
        playerReady = false
        activeSessionSeen = false
        player.release(stop)
    }

    private fun toggleAudioOnly() {
        if (current?.isRadio == true) return
        player.setAudioOnly(!player.snapshot.audioOnly)
        updatePip()
    }

    private fun supportsPip() = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipEligible(): Boolean = supportsPip() && !showingCatalog && player.snapshot.channel != null &&
        !player.snapshot.audioOnly && !failed && player.snapshot.wantsToPlay

    private fun updatePip() {
        if (Build.VERSION.SDK_INT < 26 || !supportsPip()) return
        val eligible = pipEligible()
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        val rect = Rect()
        if (playback.video.getGlobalVisibleRect(rect) && !rect.isEmpty) params.setSourceRectHint(rect)
        fun action(drawable: Int, label: String, command: String, request: Int): RemoteAction = RemoteAction(
            Icon.createWithResource(this, drawable), label, label,
            PendingIntent.getService(this, request, Intent(this, PlaybackService::class.java).setAction(command),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        params.setActions(listOf(
            action(R.drawable.ic_mobile_previous, "Precedente", PlaybackService.PREVIOUS, 1),
            action(if (player.snapshot.wantsToPlay) R.drawable.ic_mobile_pause else R.drawable.ic_mobile_play,
                if (player.snapshot.wantsToPlay) "Pausa" else "Riprendi", PlaybackService.TOGGLE, 2),
            action(R.drawable.ic_mobile_next, "Successivo", PlaybackService.NEXT, 3)))
        if (Build.VERSION.SDK_INT >= 31) params.setAutoEnterEnabled(eligible).setSeamlessResizeEnabled(true)
        setPictureInPictureParams(params.build())
    }

    private fun enterPip() {
        if (!pipEligible() || Build.VERSION.SDK_INT < 26) return
        updatePip()
        try {
            if (!enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()))
                message("Picture in Picture non disponibile. Controlla le impostazioni dell’app.")
        } catch (_: IllegalStateException) { message("Picture in Picture non disponibile su questo dispositivo.") }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pipEligible()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playback.setInPip(isInPictureInPictureMode)
        applySystemBars()
        if (!isInPictureInPictureMode) renderPlayback(player.snapshot)
    }

    private fun zap(direction: Int) {
        if(player.snapshot.mode!=it.lumetv.core.PlaybackMode.LIVE){player.seekBy(if(direction>0)30_000 else -10_000);return}
        val before=current
        lifecycleScope.launch {val next=repository.nextChannel(channels,before?.id,direction,before)
            if(current?.id==before?.id)next?.let {selectChannel(it)} ?: message("Nessun preferito. Aggiungi un canale ai preferiti o cambia la modalità nelle Opzioni.")}
    }

    private fun updateLiveInfo() {
        current?.let { playback.bind(it, programmes[it.epgId].orEmpty(), repository.isFavorite(it)) }
        player.snapshot.channel?.let {playback.renderState(player.snapshot,repository.isFavorite(it))}
    }

    private fun toggleFavorite(channel: Channel) {
        repository.toggleFavorite(channel)
        renderData(); updateLiveInfo(); refreshPicker?.invoke()
        val state = player.snapshot
        state.channel?.let { playback.renderState(state, repository.isFavorite(it)) }
    }

    private fun renderData() {
        browse.setSourceNames(repository.personal.state.value.filter { it.enabled }.associate { it.id to it.name })
        browse.submit(channels, programmes, current?.id, catalogLoaded, repository.recentIds)
        updateGuideStatus()
    }

    private fun refreshCatalog(force: Boolean = false) {
        if (!catalogLoaded || catalogJob?.isActive == true) return
        catalogJob = lifecycleScope.launch {
            try {
                repository.refresh(force)?.let { updated ->
                    channels = updated
                    current = updated.firstOrNull { it.id == current?.id } ?: current
                    renderData(); refreshPicker?.invoke()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (force) message("Catalogo salvato disponibile. Aggiornamento non riuscito.") }
        }
    }

    private fun refreshGuide(force: Boolean = false) {
        if (!catalogLoaded || guideJob?.isActive == true) return
        guideJob = lifecycleScope.launch {
            guideLoading = true; updateGuideStatus()
            try { guide.refresh(channels, force)?.let { programmes = it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the cached EPG and the shared retry policy. */ }
            finally { guideLoading = false; renderData(); updateLiveInfo(); refreshPicker?.invoke();browse.reloadGuideWindow() }
        }
    }
    private fun loadPersonalGuideWindow(rows:List<Channel>,from:Long,until:Long) {
        if(!catalogLoaded)return
        personalGuideJob?.cancel()
        personalGuideJob=lifecycleScope.launch {
            val now=System.currentTimeMillis()
            programmes=mergeProgrammeWindows(guide.window(rows,from,until),guide.personal.window(listOfNotNull(current),now,now+GuideSchedule.WINDOW))
            renderData();updateLiveInfo()
        }
    }

    private fun updateGuideStatus() {
        browse.setPersonalGuideMessages(repository.personal.state.value.associate {source->source.id to it.lumetv.app.ui.PersonalGuideFeedback.message(
            guide.personal.state.value.firstOrNull {it.sourceId==source.id},repository.personal.requestContext(source.id)?.settings?.guide?.enabled!=false)})
        val now = System.currentTimeMillis()
        val available = programmes.values.any { shows -> shows.any { it.end > now } }
        browse.setGuideStatus(when {
            guideLoading -> "Aggiorno la guida…"
            available -> ""
            guide.failure == "rate_limit" && guide.nextRetry > now -> "Il servizio richiede una pausa. Riprova dopo le ${time(guide.nextRetry)}."
            guide.failure.isNotEmpty() -> "Guida temporaneamente non disponibile. Tocca Aggiorna per riprovare."
            else -> "La guida viene aggiornata automaticamente."
        })
    }

    private fun toggleFullscreen() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, root)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (!showingCatalog && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        if ((showingCatalog && player.snapshot.channel == null) || player.snapshot.audioOnly || !player.snapshot.wantsToPlay)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ViewCompat.requestApplyInsets(root)
    }

    private fun showChannels() {
        if (showingCatalog || channels.isEmpty()) return
        channelsDialog?.dismiss()
        val playing=current?.takeIf {it.isPersonal}
        var initialPosition=0
        var ready=playing==null
        var closed=false
        var positionJob:Job?=null
        var favoritesOnly = false
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(16), dp(12), dp(12)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TvStyle.text(this, "Canali", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        top.addView(MobileStyle.icon(this, R.drawable.ic_mobile_close, "Chiudi elenco canali") { channelsDialog?.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(top)
        val filters = LinearLayout(this)
        panel.addView(filters)
        val rows = MobileChannelAdapter(logos, repository::isFavorite, { item ->
            channelsDialog?.dismiss()
            if (item.channel.id == current?.id && !failed) playback.setControlsVisible(true) else selectChannel(item.channel)
        }, ::toggleFavorite)
        val list = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MobileActivity); adapter = rows; itemAnimator = null }
        val empty = TvStyle.text(this, "Nessun preferito. Tocca il cuore accanto a un canale per aggiungerlo.", 16f, TvStyle.muted)
        lateinit var model:it.lumetv.app.ui.ContinuousCatalog<Channel>
        model=it.lumetv.app.ui.ContinuousCatalog(lifecycleScope,50){change->list.post {
            if(!closed && playing!=null) {
                val scroll=list.layoutManager?.onSaveInstanceState()
                if(change.sizeChanged)rows.notifyDataSetChanged() else rows.notifyItemRangeChanged(change.start,change.count.coerceAtMost((rows.itemCount-change.start).coerceAtLeast(0)))
                if(!change.sizeChanged)list.layoutManager?.onRestoreInstanceState(scroll)
                else if(rows.itemCount>0){list.scrollToPosition(initialPosition.coerceAtMost(rows.itemCount-1));initialPosition=0}
                empty.visibility=if(rows.itemCount==0)View.VISIBLE else View.GONE
                empty.text=when {model.loading->"Carico i canali…";model.hasError->"Impossibile caricare. Tocca qui per riprovare.";favoritesOnly->"Nessun preferito in questa lista.";else->"Nessun canale disponibile."}
                empty.isClickable=model.hasError;empty.setOnClickListener {model.retry()}
            }
        }}
        if(playing!=null)rows.catalog=model
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
            if(playing!=null){val manager=list.layoutManager as LinearLayoutManager;model.viewport(manager.findFirstVisibleItemPosition().coerceAtLeast(0),manager.findLastVisibleItemPosition().coerceAtLeast(0))}
        }})
        panel.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        fun render() {
            if(!ready)return
            if(playing!=null) {
                val query=it.lumetv.app.data.PersonalCatalogQuery(source=playing.sourceId,kind=playing.kind,ids=if(favoritesOnly)repository.favorites() else null,limit=50)
                model.bind(query to repository.personal.version.value,initialPosition){offset->repository.personal.catalog(query.copy(offset=offset)).let {it.rows to it.total}}
                rows.items=emptyList()
            } else rows.items = channels.filter { !it.isVod && it.isRadio == (current?.isRadio == true) && (current == null || it.sourceId == current?.sourceId) && (!favoritesOnly || repository.isFavorite(it)) }.map {
                MobileBrowseItem(it, ProgrammeWindow.at(programmes[it.epgId].orEmpty(), System.currentTimeMillis()).current, channelRow = true)
            }
            rows.notifyDataSetChanged()
            empty.visibility = if (rows.itemCount==0 && playing==null) View.VISIBLE else View.GONE
            filters.removeAllViews()
            listOf("Tutti", "Preferiti").forEachIndexed { index, title ->
                filters.addView(MobileStyle.button(this, title, favoritesOnly == (index == 1)) { favoritesOnly = index == 1;initialPosition=0;render() },
                    LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        }
        render()
        if(playing!=null)positionJob=lifecycleScope.launch {initialPosition=repository.personal.catalogPosition(playing);ready=true;if(!closed)render()}
        refreshPicker = ::render
        channelsDialog = Dialog(this).apply {
            setContentView(panel)
            setOnDismissListener {closed=true;positionJob?.cancel();model.close();channelsDialog = null; refreshPicker = null; playback.setControlsVisible(true) }
            show()
            window?.setBackgroundDrawable(TvStyle.rounded(TvStyle.surface, dp(20).toFloat()))
            window?.setGravity(Gravity.BOTTOM)
            window?.setLayout(root.width, (root.height * .78f).toInt())
        }
        if(playing==null)list.scrollToPosition(rows.items.indexOfFirst { it.channel.id == current?.id }.coerceAtLeast(0))
    }

    private fun openVideoLibrary(){launchGeneration++;launchQueue.consume(true);catalogPanels.library(repository.personal,logos,repository::isFavorite,::toggleFavorite,::selectChannel)}

    private fun showCurrentProgramme() {
        current?.let { channel ->
            val programme = ProgrammeWindow.at(programmes[channel.epgId].orEmpty(), System.currentTimeMillis()).current
            if (programme != null) showProgramme(channel, programme) else message("Palinsesto non disponibile per ${channel.name}")
        }
    }

    private fun showProgramme(channel: Channel, programme: Programme) {
        programmeDialog?.dismiss()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(24)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TvStyle.text(this, channel.name, 14f, TvStyle.accent), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(MobileStyle.icon(this, R.drawable.ic_mobile_close, "Chiudi dettagli") { programmeDialog?.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(top)
        panel.addView(TvStyle.text(this, programme.title, 24f, bold = true))
        panel.addView(TvStyle.text(this, "${time(programme.start)} – ${time(programme.end)}", 14f, TvStyle.accent), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        panel.addView(TvStyle.text(this, programme.description.ifBlank { "Descrizione non disponibile." }, 16f, TvStyle.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(20) })
        panel.addView(MobileStyle.button(this, "Guarda il canale ora", true) { programmeDialog?.dismiss(); selectChannel(channel) }, LinearLayout.LayoutParams(-1, -2))
        if(programme.replayAvailable)panel.addView(MobileStyle.button(this,if(programme.end>System.currentTimeMillis())"Guarda dall’inizio" else "Rivedi dall’archivio",true) {
            programmeDialog?.dismiss();selectChannel(channel,archiveEvent=programme.eventId)
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8)})
        val item = DiscoveryProgramme(channel, programme)
        if (programme.start > System.currentTimeMillis()) {
            val remind = MobileStyle.button(this, "") {}
            fun updateReminderLabel() { remind.text = if (reminders.contains(item)) "Rimuovi avviso" else "Avvisami prima dell’inizio" }
            updateReminderLabel()
            remind.setOnClickListener { reminders.toggle(item, System.currentTimeMillis()); updateReminderLabel() }
            panel.addView(remind, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            panel.addView(TvStyle.text(this, "Gli avvisi vengono mostrati mentre Lume TV è aperta.", 13f, TvStyle.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((root.height * .85f).toInt(), MeasureSpec.AT_MOST))
            }
        }.apply { addView(panel) }
        programmeDialog = Dialog(this).apply {
            setContentView(scroll)
            setOnDismissListener { programmeDialog = null; if (!showingCatalog) playback.setControlsVisible(true) }
            show()
            window?.setBackgroundDrawable(TvStyle.rounded(TvStyle.surface, dp(20).toFloat()))
            window?.setGravity(Gravity.BOTTOM)
            window?.setLayout(root.width, -2)
        }
    }

    private var reminderQuery:Job?=null
    private fun checkReminders() {
        if(reminderQuery?.isActive==true || reminders.channelIds.isEmpty())return
        if (playlists.isShowing || catalogPanels.isShowing || catalogDialogs.any { it.isShowing }) return
        if (!catalogLoaded || !hasWindowFocus() || reminderDialog?.isShowing == true || programmeDialog?.isShowing == true ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        reminderQuery=lifecycleScope.launch {
        val available=reminders.channelIds.mapNotNull {repository.find(it)}.associateBy {it.id}
        val due = reminders.takeDue(available.keys, System.currentTimeMillis()) ?: return@launch
        val channel = available[due.channelId] ?: return@launch
        reminderDialog = AlertDialog.Builder(this@MobileActivity).setTitle(R.string.discovery_reminder_title)
            .setMessage(getString(R.string.discovery_reminder_message, due.title, channel.name, time(due.start)))
            .setPositiveButton(R.string.discovery_watch) { _, _ -> selectChannel(channel) }
            .setNegativeButton(R.string.discovery_later, null).create().also { dialog ->
                dialog.setOnDismissListener { reminderDialog = null }
                dialog.show()
            }
        }
    }

    private fun showOptions() {
        if(::catalogPanels.isInitialized)catalogPanels.dismiss()
        browse.hideKeyboard()
        showingOptions = true
        options.resetScroll()
        renderOptions()
        options.setMiniPlayer(miniPlayer)
        miniPlayer.bind(player.snapshot, playback.video)
        browse.visibility = View.GONE
        options.visibility = View.VISIBLE
        applySystemBars(); updatePip()
    }

    private fun closeOptions() {
        dismissOptions()
        browse.visibility = View.VISIBLE
        miniPlayer.bind(player.snapshot, playback.video)
        applySystemBars(); updatePip()
        root.post { updates.maybePrompt() }
    }

    private fun dismissOptions() {
        if (!showingOptions) return
        showingOptions = false
        options.visibility = View.GONE
        browse.setMiniPlayer(miniPlayer)
    }

    private fun renderOptions() {
        options.bind(InterfacePreferences.selected(this).label, repository.zapFavoritesOnly,
            getSharedPreferences("lume", MODE_PRIVATE).getBoolean("data_saver", false),
            IntegrationStore.allowed(this), BuildConfig.VERSION_NAME, updates.available?.versionName,
            it.lumetv.app.home.HomeChannels.summary(this))
    }

    private fun chooseOption(option: MobileOption) {
        when (option) {
            MobileOption.PLAYLISTS -> { playlists.bindPlayback(player.snapshot.channel != null); playlists.show() }
            MobileOption.INTERFACE -> trackDialog(InterfacePreferences.choose(this, ::changeInterface))
            MobileOption.ZAPPING -> trackDialog(AlertDialog.Builder(this).setTitle("Cambio canale")
                .setSingleChoiceItems(arrayOf("Tutte le emittenti", "Solo preferiti"), if (repository.zapFavoritesOnly) 1 else 0) { dialog, choice ->
                    repository.zapFavoritesOnly = choice == 1; dialog.dismiss()
                }.setNegativeButton("Annulla", null).show())
            MobileOption.QUALITY -> {
                val prefs = getSharedPreferences("lume", MODE_PRIVATE)
                trackDialog(AlertDialog.Builder(this).setTitle("Qualità e consumo dati")
                    .setSingleChoiceItems(arrayOf("Automatica", "Risparmio dati"), if (prefs.getBoolean("data_saver", false)) 1 else 0) { dialog, choice ->
                        prefs.edit().putBoolean("data_saver", choice == 1).apply()
                        player.refreshPreferences(); dialog.dismiss()
                    }.setNegativeButton("Chiudi", null).show())
            }
            MobileOption.UPDATES -> updates.show()
            MobileOption.AURA -> showIntegrationOptions()
            MobileOption.HOME -> homeSettings.show()
            MobileOption.ABOUT -> trackDialog(AlertDialog.Builder(this).setTitle("Lume TV · ${BuildConfig.VERSION_NAME}")
                .setMessage("La TV, fatta semplice.\n\nTV, radio, guida e preferiti sul tuo dispositivo.\n\nIntegrazioni adattate da WorldLiveTV e WLTV Group. Software libero, GPL-3.0-or-later.\n\nSorgenti: github.com/pjor94/LumeTV.")
                .setPositiveButton("Chiudi", null).setNeutralButton("Licenze") { _, _ -> showLicenses() }.show())
            MobileOption.LICENSES -> showLicenses()
        }
    }

    private fun trackDialog(dialog: AlertDialog) {
        catalogDialogs += dialog
        dialog.setOnDismissListener {
            catalogDialogs -= dialog
            if (playlists.isShowing) root.post { if (catalogDialogs.none { it.isShowing }) playlists.focusSelection() }
            if (showingOptions) renderOptions()
            root.post { updates.maybePrompt() }
        }
    }

    private fun changeInterface() {
        val destination = if (!showingCatalog && current != null) "channel/${current!!.id}" else when (browse.section) {
            MobileSection.CHANNELS, MobileSection.RADIO -> "catalog"; MobileSection.FAVORITES -> "favorites"
            MobileSection.GUIDE -> "guide"; MobileSection.DISCOVER -> "discover"
        }
        releasePlayback()
        startActivity(updates.carrySession(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://$destination"))))
        finish()
    }

    private fun showIntegrationOptions() {
        val enabled = IntegrationStore.allowed(this)
        trackDialog(AlertDialog.Builder(this).setTitle("Collegamento con AURA")
            .setMessage("Condividi con AURA i canali, i preferiti, la guida e l’ultimo canale guardato su questo dispositivo. Puoi revocare il collegamento dalle Opzioni.")
            .setPositiveButton(if (enabled) "Revoca collegamento" else "Consenti collegamento") { _, _ ->
                IntegrationStore.setAllowed(this, !enabled)
                message(if (enabled) "Collegamento con AURA revocato" else "Collegamento con AURA attivo")
            }.setNegativeButton("Chiudi", null).show())
    }

    private fun showLicenses() {
        val documents = linkedMapOf("Crediti e sorgenti" to "THIRD_PARTY_NOTICES.md", "Lume TV · GNU GPL v3" to "LICENSE.txt",
            "Apache License 2.0" to "licenses/Apache-2.0.txt", "R8 · BSD" to "licenses/BSD-3-Clause-R8.txt",
            "Desugar JDK · GPL v2 e Classpath Exception" to "licenses/GPL-2.0-with-classpath-exception.txt",
            "Mozilla Public License 2.0" to "licenses/MPL-2.0.txt", "XZ · Pubblico dominio" to "licenses/XZ-PUBLIC-DOMAIN.txt",
            "Avvisi delle dipendenze" to "DEPENDENCY_NOTICES.txt", "OpenJDK · Informazioni aggiuntive" to "licenses/DESUGAR-ADDITIONAL_LICENSE_INFO.txt",
            "OpenJDK · Assembly Exception" to "licenses/OPENJDK-ASSEMBLY_EXCEPTION.txt")
        trackDialog(AlertDialog.Builder(this).setTitle("Licenze e avvisi").setItems(documents.keys.toTypedArray()) { _, index ->
            val text = TvStyle.text(this, assets.open(documents.values.elementAt(index)).bufferedReader().use { it.readText() }, 14f)
                .apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
            trackDialog(AlertDialog.Builder(this).setTitle(documents.keys.elementAt(index)).setView(ScrollView(this).apply { addView(text) })
                .setPositiveButton("Chiudi", null).show())
        }.setNegativeButton("Chiudi", null).show())
    }

    private fun handleMediaKey(event: KeyEvent): Boolean {
        if (player.snapshot.channel != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.resumeToLive(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pauseForCatalog(); return true }
                KeyEvent.KEYCODE_MEDIA_STOP -> { stopPlayback(); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
            }
        }
        return false
    }

    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(value))
}
