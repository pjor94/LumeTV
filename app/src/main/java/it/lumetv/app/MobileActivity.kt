package it.lumetv.app

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
import it.lumetv.app.playback.NetworkMonitor
import it.lumetv.app.playback.PlaybackFailure
import it.lumetv.app.playback.PlaybackRecovery
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
import kotlinx.coroutines.ensureActive
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
    private lateinit var resolver: StreamResolver
    private lateinit var player: TvPlayer
    private lateinit var recovery: PlaybackRecovery
    private lateinit var network: NetworkMonitor
    private lateinit var reminders: ProgrammeReminders
    private lateinit var updates: UpdateController
    private lateinit var root: FrameLayout
    private lateinit var browse: MobileBrowseView
    private lateinit var playback: MobilePlaybackView
    private var channels = emptyList<Channel>()
    private var programmes = emptyMap<String, List<Programme>>()
    private var current: Channel? = null
    private var showingCatalog = true
    private var userPaused = false
    private var failed = false
    private var catalogLoaded = false
    private var playerReady = false
    private var explicitRequestSeen = false
    private var playbackGeneration = 0L
    private var activePlaybackChannelId: String? = null
    private var resolveJob: Job? = null
    private var catalogJob: Job? = null
    private var guideJob: Job? = null
    private var guideLoading = false
    private val launchQueue = LaunchQueue()
    private var programmeDialog: Dialog? = null
    private var channelsDialog: Dialog? = null
    private var refreshPicker: (() -> Unit)? = null
    private var reminderDialog: AlertDialog? = null

    private val tick = object : Runnable {
        override fun run() {
            if (showingCatalog) browse.tick() else updateLiveInfo()
            refreshPicker?.invoke()
            checkReminders()
            handler.postDelayed(this, 30_000)
        }
    }
    private val syncTick = object : Runnable {
        override fun run() {
            if (catalogLoaded) { refreshCatalog(); refreshGuide(); updates.checkAutomatically() }
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
        resolver = StreamResolver(http)
        player = TvPlayer(this, http)
        network = NetworkMonitor(this) { online -> recovery.onNetworkChanged(online) }
        recovery = PlaybackRecovery(lifecycleScope, { network.isOnline }, { openCurrentChannel() },
            onWaiting = { showRecovery("Connessione assente · Riprendo quando torna la rete…") },
            onScheduled = { attempt, maximum -> showRecovery("Riconnetto la diretta… Tentativo $attempt di $maximum") },
            onExhausted = ::showError)
        showingCatalog = savedInstanceState?.getBoolean("mobile_catalog", true) ?: true
        userPaused = savedInstanceState?.getBoolean("mobile_paused", false) ?: false
        if (!showingCatalog) requestedOrientation = savedInstanceState?.getInt("mobile_orientation", ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
            ?: ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleMediaKey(event) || super.dispatchKeyEvent(event)
        }.apply { setBackgroundColor(TvStyle.background) }
        browse = MobileBrowseView(this, logos, repository::isFavorite, ::chooseItem, ::toggleFavorite,
            { refreshCatalog(true); refreshGuide(true) }, ::showOptions, { updates.show() })
        browse.restoreState(savedInstanceState)
        playback = MobilePlaybackView(this, { showCatalog() }, ::togglePause, { zap(-1) }, { zap(1) },
            ::showChannels, ::toggleFullscreen, { current?.let(::toggleFavorite) }, ::showCurrentProgramme,
            { current?.let { selectChannel(it) } }).apply { visibility = View.GONE }
        root.addView(browse, FrameLayout.LayoutParams(-1, -1))
        root.addView(playback, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, max(safe.bottom, if (showingCatalog) keyboard.bottom else 0))
            insets
        }
        setContentView(root)
        updates = UpdateController(this, browse::showUpdate)
        applySystemBars()
        player.onBuffering = {
            if (!showingCatalog && !failed && !userPaused) {
                playback.showLoading("Carico la diretta…"); recovery.onBuffering()
            }
        }
        player.onReady = { if (!showingCatalog && !failed && userPaused) playback.ready(false) }
        player.onPlayingChanged = { playing ->
            if (!showingCatalog && !failed && playing) {
                playback.ready(true)
                current?.takeIf { it.id == activePlaybackChannelId }?.let(repository::recordPlayback)
                recovery.onPlaying()
            } else {
                recovery.onNotPlaying()
                if (!showingCatalog) playback.setPlaying(false)
            }
        }
        player.onError = { error ->
            if (!showingCatalog) {
                if (userPaused) showError(error.message) else recovery.onFailure(error)
            }
        }
        if (savedInstanceState?.getBoolean("mobile_explicit_consumed") != true && intent.action == Intent.ACTION_VIEW) {
            explicitRequestSeen = true
            launchQueue.replace(LaunchRequest.parse(intent.dataString))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    !showingCatalog -> showCatalog()
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
            val previous = savedInstanceState?.getString("mobile_channel") ?: repository.lastChannelId
            current = channels.firstOrNull { it.id == previous }
            renderData()
            if (!consumeLaunchRequest()) {
                if (!showingCatalog && current != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                    selectChannel(current!!, restorePause = true)
                else if (current == null) showCatalog()
            }
            refreshCatalog(); refreshGuide()
        }
    }

    override fun onStart() {
        super.onStart()
        updates.onStart()
        player.attach(playback.video)
        playerReady = true
        network.start()
        handler.post(tick); handler.post(syncTick)
        if (!consumeLaunchRequest() && catalogLoaded && !showingCatalog && current != null)
            selectChannel(current!!, restorePause = true)
    }

    override fun onStop() {
        updates.onStop()
        programmeDialog?.dismiss(); programmeDialog = null
        channelsDialog?.dismiss(); channelsDialog = null; refreshPicker = null
        reminderDialog?.dismiss(); reminderDialog = null
        network.stop()
        catalogJob?.cancel(); guideJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        releasePlayback()
        playback.stopTimers()
        super.onStop()
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
        outState.putString("mobile_channel", current?.id)
        outState.putBoolean("mobile_catalog", showingCatalog)
        outState.putBoolean("mobile_paused", userPaused)
        outState.putInt("mobile_orientation", requestedOrientation)
        outState.putBoolean("mobile_explicit_consumed", explicitRequestSeen && launchQueue.pending == null)
        browse.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            explicitRequestSeen = true
            showCatalog()
            player.stop()
            launchQueue.replace(LaunchRequest.parse(intent.dataString))
            consumeLaunchRequest()
        }
    }

    private fun consumeLaunchRequest(): Boolean {
        val request = launchQueue.consume(catalogLoaded && playerReady) ?: return false
        when (request) {
            is LaunchRequest.ChannelId -> channels.firstOrNull { it.id == request.id }?.let { selectChannel(it) }
                ?: run { showCatalog(MobileSection.CHANNELS); message("Canale non più disponibile") }
            LaunchRequest.Last -> channels.firstOrNull { it.id == repository.lastChannelId }?.let { selectChannel(it) }
                ?: run { showCatalog(MobileSection.CHANNELS); message("Nessun ultimo canale disponibile") }
            LaunchRequest.Catalog -> showCatalog(MobileSection.CHANNELS)
            LaunchRequest.Favorites -> showCatalog(MobileSection.FAVORITES)
            LaunchRequest.Guide -> showCatalog(MobileSection.GUIDE)
            LaunchRequest.Discover -> showCatalog(MobileSection.DISCOVER)
            LaunchRequest.Integration -> { showCatalog(MobileSection.CHANNELS); showIntegrationOptions() }
            LaunchRequest.Invalid -> { showCatalog(MobileSection.CHANNELS); message("Collegamento non valido") }
        }
        return true
    }

    private fun chooseItem(item: MobileBrowseItem) {
        if (item.channelRow || item.programme == null) selectChannel(item.channel)
        else showProgramme(item.channel, item.programme)
    }

    private fun selectChannel(channel: Channel, restorePause: Boolean = false) {
        programmeDialog?.dismiss(); channelsDialog?.dismiss()
        browse.hideKeyboard()
        recovery.start()
        current = channel; showingCatalog = false; failed = false
        if (!restorePause) userPaused = false
        browse.visibility = View.GONE; playback.visibility = View.VISIBLE
        updateLiveInfo()
        applySystemBars()
        openCurrentChannel()
    }

    private fun openCurrentChannel() {
        val channel = current ?: return
        resolveJob?.cancel()
        val generation = ++playbackGeneration
        activePlaybackChannelId = null
        player.stop()
        playback.showLoading("Apro ${channel.name}…")
        if (!userPaused) recovery.onBuffering()
        resolveJob = lifecycleScope.launch {
            try {
                val stream = resolver.resolve(channel)
                ensureActive()
                if (generation != playbackGeneration || current?.id != channel.id || showingCatalog ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                activePlaybackChannelId = channel.id
                player.play(stream, playWhenReady = !userPaused)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == playbackGeneration) {
                    if (userPaused) showError(PlaybackFailure.fromSource(error).message)
                    else recovery.onFailure(PlaybackFailure.fromSource(error))
                }
            }
        }
    }

    private fun togglePause() {
        if (showingCatalog || current == null || failed) return
        if (player.isPlaying) {
            userPaused = true
            recovery.stop()
            player.pauseForCatalog()
            playback.ready(false)
        } else {
            userPaused = false
            recovery.start()
            if (!player.resumeToLive()) openCurrentChannel()
            else if (player.isPlaying) { playback.ready(true); recovery.onPlaying() }
            else { playback.showLoading("Riprendo la diretta…"); recovery.onBuffering() }
        }
    }

    private fun showCatalog(section: MobileSection? = null) {
        programmeDialog?.dismiss(); channelsDialog?.dismiss()
        recovery.stop(); resolveJob?.cancel()
        playbackGeneration++; activePlaybackChannelId = null
        player.pauseForCatalog()
        showingCatalog = true
        playback.stopTimers(); playback.visibility = View.GONE; browse.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        if (section != null) browse.navigate(section)
        renderData(); applySystemBars()
    }

    private fun showRecovery(text: String) {
        resolveJob?.cancel(); player.stop(); playback.showLoading(text)
    }

    private fun showError(text: String) {
        recovery.stop(); resolveJob?.cancel(); player.stop()
        failed = true
        playback.showError(text)
    }

    private fun releasePlayback() {
        playerReady = false
        recovery.stop(); resolveJob?.cancel()
        playbackGeneration++; activePlaybackChannelId = null
        player.release()
    }

    private fun zap(direction: Int) {
        repository.nextChannel(channels, current?.id, direction)?.let { selectChannel(it) }
            ?: message("Nessun preferito. Aggiungi un canale ai preferiti o cambia la modalità nelle Opzioni.")
    }

    private fun updateLiveInfo() {
        current?.let { playback.bind(it, programmes[it.epgId].orEmpty(), repository.isFavorite(it)) }
    }

    private fun toggleFavorite(channel: Channel) {
        repository.toggleFavorite(channel)
        renderData(); updateLiveInfo(); refreshPicker?.invoke()
    }

    private fun renderData() {
        browse.submit(channels, programmes, current?.id, catalogLoaded)
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
            finally { guideLoading = false; renderData(); updateLiveInfo(); refreshPicker?.invoke() }
        }
    }

    private fun updateGuideStatus() {
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
        if (showingCatalog) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ViewCompat.requestApplyInsets(root)
    }

    private fun showChannels() {
        if (showingCatalog || channels.isEmpty()) return
        channelsDialog?.dismiss()
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
        panel.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        fun render() {
            rows.items = channels.filter { !favoritesOnly || repository.isFavorite(it) }.map {
                MobileBrowseItem(it, ProgrammeWindow.at(programmes[it.epgId].orEmpty(), System.currentTimeMillis()).current, channelRow = true)
            }
            rows.notifyDataSetChanged()
            empty.visibility = if (rows.items.isEmpty()) View.VISIBLE else View.GONE
            filters.removeAllViews()
            listOf("Tutti", "Preferiti").forEachIndexed { index, title ->
                filters.addView(MobileStyle.button(this, title, favoritesOnly == (index == 1)) { favoritesOnly = index == 1; render() },
                    LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            }
        }
        render()
        refreshPicker = ::render
        channelsDialog = Dialog(this).apply {
            setContentView(panel)
            setOnDismissListener { channelsDialog = null; refreshPicker = null; playback.setControlsVisible(true) }
            show()
            window?.setBackgroundDrawable(TvStyle.rounded(TvStyle.surface, dp(20).toFloat()))
            window?.setGravity(Gravity.BOTTOM)
            window?.setLayout(root.width, (root.height * .78f).toInt())
        }
        list.scrollToPosition(rows.items.indexOfFirst { it.channel.id == current?.id }.coerceAtLeast(0))
    }

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

    private fun checkReminders() {
        if (!catalogLoaded || reminderDialog?.isShowing == true || programmeDialog?.isShowing == true ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val due = reminders.takeDue(channels.map { it.id }.toSet(), System.currentTimeMillis()) ?: return
        val channel = channels.firstOrNull { it.id == due.channelId } ?: return
        reminderDialog = AlertDialog.Builder(this).setTitle(R.string.discovery_reminder_title)
            .setMessage(getString(R.string.discovery_reminder_message, due.title, channel.name, time(due.start)))
            .setPositiveButton(R.string.discovery_watch) { _, _ -> selectChannel(channel) }
            .setNegativeButton(R.string.discovery_later, null).create().also { dialog ->
                dialog.setOnDismissListener { reminderDialog = null }
                dialog.show()
            }
    }

    private fun showOptions() {
        val zap = if (repository.zapFavoritesOnly) "solo preferiti" else "tutti i canali"
        AlertDialog.Builder(this).setTitle("Opzioni")
            .setItems(arrayOf("Interfaccia: ${InterfacePreferences.selected(this).label}", "Cambio canale: $zap", "Aggiornamenti", "Collegamento con AURA", "Informazioni", "Licenze e avvisi")) { _, index ->
                when (index) {
                    0 -> InterfacePreferences.choose(this, ::changeInterface)
                    1 -> AlertDialog.Builder(this).setTitle("Cambio canale")
                        .setSingleChoiceItems(arrayOf("Tutti i canali", "Solo preferiti"), if (repository.zapFavoritesOnly) 1 else 0) { dialog, choice ->
                            repository.zapFavoritesOnly = choice == 1; dialog.dismiss()
                        }.setNegativeButton("Annulla", null).show()
                    2 -> updates.show()
                    3 -> showIntegrationOptions()
                    4 -> AlertDialog.Builder(this).setTitle("Lume TV · ${BuildConfig.VERSION_NAME}")
                        .setMessage("La TV, fatta semplice.\n\nCanali, guida e preferiti sul tuo dispositivo.\n\nIntegrazioni adattate da WorldLiveTV e WLTV Group. Software libero, GPL-3.0-or-later. Nessuna garanzia, nei limiti di legge.\n\nSorgenti e segnalazioni: github.com/pjor94/LumeTV.")
                        .setPositiveButton("Chiudi", null).setNeutralButton("Licenze") { _, _ -> showLicenses() }.show()
                    5 -> showLicenses()
                }
            }.setNegativeButton("Chiudi", null).show()
    }

    private fun changeInterface() {
        val destination = if (!showingCatalog && current != null) "channel/${current!!.id}" else when (browse.section) {
            MobileSection.CHANNELS -> "catalog"; MobileSection.FAVORITES -> "favorites"
            MobileSection.GUIDE -> "guide"; MobileSection.DISCOVER -> "discover"
        }
        releasePlayback()
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://$destination")))
        finish()
    }

    private fun showIntegrationOptions() {
        val enabled = IntegrationStore.allowed(this)
        AlertDialog.Builder(this).setTitle("Collegamento con AURA")
            .setMessage("Condividi con AURA i canali, i preferiti, la guida e l’ultimo canale guardato su questo dispositivo. Puoi revocare il collegamento dalle Opzioni.")
            .setPositiveButton(if (enabled) "Revoca collegamento" else "Consenti collegamento") { _, _ ->
                IntegrationStore.setAllowed(this, !enabled)
                message(if (enabled) "Collegamento con AURA revocato" else "Collegamento con AURA attivo")
            }.setNegativeButton("Chiudi", null).show()
    }

    private fun showLicenses() {
        val documents = linkedMapOf("Crediti e sorgenti" to "THIRD_PARTY_NOTICES.md", "Lume TV · GNU GPL v3" to "LICENSE.txt",
            "Apache License 2.0" to "licenses/Apache-2.0.txt", "R8 · BSD" to "licenses/BSD-3-Clause-R8.txt",
            "Desugar JDK · GPL v2 e Classpath Exception" to "licenses/GPL-2.0-with-classpath-exception.txt",
            "Mozilla Public License 2.0" to "licenses/MPL-2.0.txt", "XZ · Pubblico dominio" to "licenses/XZ-PUBLIC-DOMAIN.txt",
            "Avvisi delle dipendenze" to "DEPENDENCY_NOTICES.txt", "OpenJDK · Informazioni aggiuntive" to "licenses/DESUGAR-ADDITIONAL_LICENSE_INFO.txt",
            "OpenJDK · Assembly Exception" to "licenses/OPENJDK-ASSEMBLY_EXCEPTION.txt")
        AlertDialog.Builder(this).setTitle("Licenze e avvisi").setItems(documents.keys.toTypedArray()) { _, index ->
            val text = TvStyle.text(this, assets.open(documents.values.elementAt(index)).bufferedReader().use { it.readText() }, 14f)
                .apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
            AlertDialog.Builder(this).setTitle(documents.keys.elementAt(index)).setView(ScrollView(this).apply { addView(text) })
                .setPositiveButton("Chiudi", null).show()
        }.setNegativeButton("Chiudi", null).show()
    }

    private fun handleMediaKey(event: KeyEvent): Boolean {
        if (!showingCatalog && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
            }
        }
        return false
    }

    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(value))
}
