package it.lumetv.app

import android.content.Intent
import android.graphics.Color
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.app.integration.LaunchRequest
import it.lumetv.app.integration.LaunchQueue
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.*
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.app.ui.AudioPlaybackView
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.ui.ChannelAdapter
import it.lumetv.app.ui.CatalogBackdrop
import it.lumetv.app.ui.LiveBanner
import it.lumetv.app.ui.LiveChannelPanel
import it.lumetv.app.ui.GuideView
import it.lumetv.app.ui.DiscoveryView
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvOption
import it.lumetv.app.ui.TvOptionsView
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.update.UpdateController
import it.lumetv.core.Channel
import it.lumetv.core.ChannelNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val http = HttpClient()
    private lateinit var repository: ChannelRepository
    private lateinit var guide: GuideRepository
    private lateinit var logos: ChannelLogoRepository
    private lateinit var tvPlayer: TvPlayer
    private lateinit var demandControls:it.lumetv.app.ui.OnDemandControls
    private var personalGuideJob:kotlinx.coroutines.Job?=null
    private var personalGuideWindowKey=""
    private lateinit var audioView: AudioPlaybackView
    private lateinit var previewColumn: LinearLayout
    private lateinit var previewHolder: FrameLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewParent: LinearLayout
    private lateinit var previewOpen: TextView
    private lateinit var previewStop: TextView
    private lateinit var options: TvOptionsView
    private lateinit var playlists: it.lumetv.app.ui.PersonalPlaylistsScreen
    private lateinit var catalogPanels:it.lumetv.app.ui.PersonalCatalogPanels
    private lateinit var videoContainer:FrameLayout
    private lateinit var liveCatalogContent:LinearLayout
    private var videoAvailable=false
    private var showingVideo=false
    private lateinit var homeSettings: it.lumetv.app.home.HomeChannelSettings
    private lateinit var sourceRow: LinearLayout
    private lateinit var sourceButton: TextView
    private lateinit var groupButton: TextView
    private var catalogSource = "builtin"
    private var catalogGroup: String? = null
    private lateinit var catalogContent: LinearLayout
    private var radioFilter = "Tutte"
    private var radioQuery = ""
    private lateinit var video: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var catalog: FrameLayout
    private lateinit var grid: RecyclerView
    private lateinit var adapter: ChannelAdapter
    private lateinit var tabs: LinearLayout
    private lateinit var channelFilters: LinearLayout
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var heading: TextView
    private lateinit var clock: TextView
    private lateinit var syncStatus: TextView
    private lateinit var updateNotice: TextView
    private lateinit var updates: UpdateController
    private lateinit var banner: LiveBanner
    private lateinit var liveChannels: LiveChannelPanel
    private lateinit var guideView: GuideView
    private lateinit var discoveryView: DiscoveryView
    private lateinit var reminders: ProgrammeReminders
    private var reminderDialog: android.app.AlertDialog? = null
    private var exitDialog: android.app.AlertDialog? = null
    private lateinit var catalogFooter: LinearLayout
    private var liveChannelsReturnFocus: View? = null
    private var channelBannerVisible = false
    private lateinit var loading: LinearLayout
    private lateinit var loadingTitle: TextView
    private lateinit var errorBox: LinearLayout
    private val launchQueue = LaunchQueue()
    private var playerReady = false
    private var activeSessionSeen = false
    private var explicitRequestSeen = false
    private var initialPlaybackCancelled = false
    private var launchMessage: String? = null
    private var integrationDialog: android.app.AlertDialog? = null
    private val catalogDialogs = mutableSetOf<android.app.AlertDialog>()
    private var guideJob: Job? = null
    private var catalogJob: Job? = null
    private var catalogLoaded = false
    private var guideLoading = false
    private var channels = emptyList<Channel>()
    private var launchGeneration=0
    private lateinit var personalPager:it.lumetv.app.ui.PersonalCatalog
    private var catalogFocusPosition=0
    private var pendingCatalogScroll:android.os.Parcelable?=null
    private var pendingCatalogFocus:Int?=null
    private var personalQuery=""
    private lateinit var personalSearch:android.widget.TextView
    private lateinit var catalogSearchRow:LinearLayout
    private lateinit var catalogSearchField:android.widget.EditText
    private lateinit var resetFilters:TextView
    private lateinit var catalogControls:android.widget.HorizontalScrollView
    private val sectionContexts=linkedMapOf<String,Bundle>()
    private var programmes = emptyMap<String, List<Programme>>()
    private var current: Channel? = null
    private var currentFilter = "Tutti"
    private var lastChannelFilter = "Tutti"
    private val channelSections = listOf("Tutti", "Rai", "Mediaset", "Preferiti", "Recenti")
    private var showingCatalog = true
    private var showingOptions = false
    private var failed = false
    private var digits = ""
    private val hideBanner = Runnable { banner.visibility = View.GONE; handler.removeCallbacks(bannerTick) }
    private val bannerTick = object : Runnable {
        override fun run() {
            if (!showingCatalog && channelBannerVisible && banner.visibility == View.VISIBLE) {
                updateChannelBanner()
                handler.postDelayed(this, 1_000)
            }
        }
    }
    private val numberAction = Runnable {
        val number = digits.toIntOrNull()
        digits = ""
        if(number!=null)lifecycleScope.launch {selectNumber(number)}
    }
    private suspend fun selectNumber(number:Int) {
        val source=if(showingCatalog)catalogSource else current?.sourceId ?: "builtin"
        val candidates=if(source!="builtin" && source.startsWith("user_"))repository.personal.catalog(
            it.lumetv.app.data.PersonalCatalogQuery(source=source,kind=it.lumetv.core.MediaKind.TV,number=number)).rows
            else (if(showingCatalog)channelsForCatalog() else channelsForPlayback()).filter {!it.isRadio && it.displayNumber==number}
        if (candidates.size > 1) {
            trackDialog(android.app.AlertDialog.Builder(this).setTitle("Scegli il canale $number")
                .setItems(candidates.map { candidate ->
                    candidate.name + " · " + (repository.personal.state.value.firstOrNull { it.id == candidate.sourceId }?.name ?: "Catalogo Lume")
                }.toTypedArray()) { _, index ->
                    if (showingCatalog && currentFilter == "Guida TV") guideView.selectChannel(candidates[index].id)
                    else if (showingCatalog && currentFilter == "Cosa guardare") discoveryView.selectChannel(candidates[index].id)
                    else selectChannel(candidates[index])
                }.setNegativeButton("Annulla", null).show())
            return
        }
        val channel = candidates.singleOrNull()
        if (channel != null) {
            if (showingCatalog && currentFilter == "Guida TV") guideView.selectChannel(channel.id)
            else if (showingCatalog && currentFilter == "Cosa guardare") discoveryView.selectChannel(channel.id)
            else selectChannel(channel)
        } else if (showingCatalog && currentFilter == "Guida TV") guideView.showMessage("Canale $number non presente")
        else if (showingCatalog && currentFilter == "Cosa guardare") discoveryView.showMessage("Canale $number non presente")
        else showBanner("Canale $number non presente", "Scegli un canale dall’elenco")
    }
    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date())
            liveChannels.refreshProgrammes()
            if (showingCatalog && currentFilter in channelSections) adapter.refreshProgrammes()
            if (showingCatalog && currentFilter == "Guida TV") { guideView.tick(); updateGuideStatus() }
            if (showingCatalog && currentFilter == "Cosa guardare") { discoveryView.tick(); updateGuideStatus() }
            checkProgrammeReminders()
            updates.maybePrompt()
            handler.postDelayed(this, 30_000)
        }
    }
    private val catalogTick = object : Runnable {
        override fun run() {
            if (catalogLoaded) { refreshCatalog(false); refreshGuide() }
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        repository = ChannelRepository(this, http)
        catalogSource = savedInstanceState?.getString("catalog_source")
            ?: getSharedPreferences("lume", MODE_PRIVATE).getString("catalog_source", "builtin") ?: "builtin"
        catalogGroup = savedInstanceState?.getString("catalog_group")
        personalQuery=savedInstanceState?.getString("personal_search").orEmpty()
        reminders = ProgrammeReminders(this)
        logos = ChannelLogoRepository(this, http, lifecycleScope)
        guide = GuideRepository(this, http, logos::updateSources)
        tvPlayer = TvPlayer(this, http)
        radioFilter = savedInstanceState?.getString("radio_filter") ?: "Tutte"
        radioQuery = savedInstanceState?.getString("radio_query").orEmpty()
        savedInstanceState?.getBundle("tv_sections")?.let {states->states.keySet().forEach {key->states.getBundle(key)?.let {sectionContexts[key]=it}}}
        currentFilter = savedInstanceState?.getString("filter") ?: "Tutti"
        lastChannelFilter = savedInstanceState?.getString("channel_filter")?.takeIf { it in channelSections } ?: "Tutti"
        showingOptions = savedInstanceState?.getBoolean("tv_options", false) ?: false
        buildUi()
        catalogPanels=it.lumetv.app.ui.PersonalCatalogPanels(this,lifecycleScope)
        catalogPanels.attachVideo(videoContainer){active->
            showingVideo=active;liveCatalogContent.visibility=if(active)View.GONE else View.VISIBLE
            updateTabSelection()
        }
        catalogPanels.restartCurrent={channel->if(tvPlayer.snapshot.channel?.id==channel.id)tvPlayer.restart()}
        catalogPanels.restoreState(savedInstanceState)
        lifecycleScope.launch {repository.personal.classification.collect {state->if(!catalogLoaded){
            status.text=state?.let {"Classifico ${it.name}… ${it.processed} / ${it.total} voci"} ?: "Carico i tuoi canali…"
            empty.text=status.text;empty.visibility=if(state!=null)View.VISIBLE else View.GONE
        }}}
        lifecycleScope.launch {repository.personal.videoSources.collect {sources->
            val available=sources.isNotEmpty()
            if(videoAvailable!=available) {
                val returnToChannels=showingVideo && !available
                videoAvailable=available
                if(returnToChannels){catalogPanels.dismiss();rememberCatalogContext();restoreCatalogContext("Canali")}
                buildTabs()
                if(returnToChannels){updateCatalog(restoreFocus=false);tabs.findViewWithTag<View>("Canali")?.requestFocus()}
            }
            if(catalogLoaded)restoreVideoPanel()
        }}
        playlists = it.lumetv.app.ui.PersonalPlaylistsScreen(this, lifecycleScope, repository.personal, false,
            { if (showingOptions) focusOptions() }, ::openPlayback, { tvPlayer.stop() })
        root.addView(playlists, FrameLayout.LayoutParams(-1, -1))
        playlists.restoreState(savedInstanceState)
        homeSettings = it.lumetv.app.home.HomeChannelSettings(this, lifecycleScope, ::trackDialog,
            { showingOptions && !playlists.isShowing }, ::renderOptions, { status.text = it })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                it.lumetv.app.home.HomeChannels.revisions.collect { if (showingOptions) renderOptions() }
            }
        }
        lifecycleScope.launch {
            repository.personal.version.collect {
                if (catalogLoaded) {
                    channels = channels.filterNot { it.isPersonal } + repository.personal.cached()
                    buildTabs(); updateCatalog(restoreFocus = false)
                    liveChannels.update(channelsForPlayback(), programmes, current?.id)
                }
            }
        }
        options.restoreState(savedInstanceState)
        personalQuery=savedInstanceState?.getString("personal_search").orEmpty()
        personalPager.restore(savedInstanceState?.getInt("personal_page_offset") ?: 0)
        catalogFocusPosition=savedInstanceState?.getInt("personal_catalog_focus") ?: 0
        @Suppress("DEPRECATION")
        val savedCatalogScroll:android.os.Parcelable?=savedInstanceState?.getParcelable("personal_catalog_scroll")
        pendingCatalogScroll=savedCatalogScroll
        pendingCatalogFocus=savedCatalogScroll?.let {catalogFocusPosition}
        if (showingOptions) {
            options.visibility = View.VISIBLE; options.setPreview(previewColumn)
            updateCatalog(restoreFocus = false)
        }
        guideView.restoreState(savedInstanceState)
        discoveryView.restoreState(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                logos.revisions.collect {
                    adapter.refreshLogos()
                    liveChannels.refreshLogos()
                    guideView.refreshLogos()
                    discoveryView.refreshLogos()
                    if (channelBannerVisible && banner.visibility == View.VISIBLE) updateChannelBanner()
                }
            }
        }
        updates = UpdateController(this, savedInstanceState, {
            catalogLoaded && showingCatalog && !showingOptions && digits.isEmpty() && reminderDialog?.isShowing != true &&
                exitDialog?.isShowing != true && integrationDialog?.isShowing != true &&
                catalogDialogs.none { it.isShowing } && !catalogPanels.isShowing && !guideView.hasDetails && !discoveryView.hasDetails &&
                !(currentFilter == "Cosa guardare" && discoveryView.isEditing)
        }, { previous ->
            if (previous != null && previous !== root && previous !== grid && previous.isAttachedToWindow && previous.isShown)
                previous.requestFocusFromTouch()
            else if (showingOptions) focusOptions()
            else { root.requestFocusFromTouch(); focusFirstChannel() }
        }) { version ->
            updateNotice.contentDescription = "Aggiorna Lume TV alla versione $version"
            updateNotice.visibility = if (version != null) View.VISIBLE else View.GONE
            if (showingOptions) renderOptions()
        }
        renderOptions()
        explicitRequestSeen = savedInstanceState?.getBoolean("explicit_consumed") == true
        if (!explicitRequestSeen && intent.action == Intent.ACTION_VIEW) {
            explicitRequestSeen = true
            launchQueue.replace(LaunchRequest.parse(intent.dataString))
        }
        tvPlayer.onChanged = ::renderPlayback
        tvPlayer.onConnected = {
            playerReady = true
            if (!consumeLaunchRequest() && catalogLoaded && !showingCatalog) {
                if(tvPlayer.snapshot.channel!=null)openPlayback() else current?.let(::selectChannel)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wasVideo=showingVideo
                if (catalogPanels.handleBack()) {
                    if(wasVideo && !showingVideo)tabs.findViewWithTag<View>("Video")?.requestFocus()
                }
                else if (catalogSearchRow.visibility==View.VISIBLE) closeCatalogSearch()
                else if (playlists.handleBack()) Unit
                else if (showingOptions) closeOptions()
                else if (liveChannels.isOpen) closeLiveChannels()
                else if (showingCatalog && currentFilter == "Cosa guardare" && discoveryView.handleBack()) Unit
                else if (!showingCatalog) {showCatalog();catalogPanels.resume()}
                else if (currentFilter == "Guida TV" || currentFilter == "Cosa guardare") {
                    currentFilter = "Tutti"; buildTabs(); updateCatalog(); focusFirstChannel()
                } else showExitConfirmation()
            }
        })
        lifecycleScope.launch {
            logos.local()
            channels = repository.local()
            programmes = guide.local()
            if (isFinishing || isDestroyed) return@launch
            catalogLoaded = true
            restoreVideoPanel()
            updateCatalog()
            if (launchQueue.pending != null) {
                consumeLaunchRequest()
            } else if (!explicitRequestSeen || savedInstanceState != null) {
                val previous = savedInstanceState?.getString("channel") ?: repository.lastChannelId
                val active=tvPlayer.snapshot.channel
                current = active ?: repository.find(previous)
                showingCatalog = showingOptions || initialPlaybackCancelled || (savedInstanceState?.getBoolean("catalog") ?: (current == null))
                if (current != null && !showingCatalog && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && playerReady) {
                    if(active!=null)openPlayback() else selectChannel(current!!)
                }
                else if (showingOptions) focusOptions() else focusFirstChannel()
            }
            refreshCatalog(false)
            refreshGuide()
            root.post { updates.maybePrompt() }
        }
    }

    override fun onStart() {
        super.onStart()
        updates.onStart()
        tvPlayer.attach(video)
        handler.post(tick)
        handler.post(catalogTick)
        if (playlists.isShowing) playlists.focusSelection()
        else if (showingOptions) focusOptions()
        else if (catalogLoaded && showingCatalog) focusFirstChannel()
    }

    override fun onStop() {
        updates.onStop()
        closeLiveChannels(restoreFocus = false)
        guideView.deactivate()
        discoveryView.deactivate()
        reminderDialog?.dismiss(); reminderDialog = null
        exitDialog?.dismiss(); exitDialog = null
        guideJob?.cancel(); catalogJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        digits = ""
        releasePlayback()
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

    private fun releasePlayback() {
        playerReady = false
        activeSessionSeen = false
        tvPlayer.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        catalogPanels.saveState(outState)
        rememberCatalogContext()
        outState.putBundle("tv_sections",Bundle().apply {sectionContexts.forEach {(key,value)->putBundle(key,value)}})
        outState.putInt("personal_page_offset",personalPager.offset)
        outState.putInt("personal_catalog_focus",grid.findContainingViewHolder(currentFocus ?: grid)?.bindingAdapterPosition?.takeIf {it>=0} ?: catalogFocusPosition)
        outState.putParcelable("personal_catalog_scroll",grid.layoutManager?.onSaveInstanceState())
        outState.putString("personal_search",personalQuery)
        playlists.saveState(outState)
        outState.putString("catalog_source", catalogSource)
        outState.putString("catalog_group", catalogGroup)
        outState.putString("channel", current?.id)
        outState.putString("filter", currentFilter)
        outState.putString("radio_filter", radioFilter)
        outState.putString("radio_query", radioQuery)
        outState.putString("channel_filter", lastChannelFilter)
        outState.putBoolean("catalog", showingCatalog)
        outState.putBoolean("tv_options", showingOptions)
        outState.putBoolean("explicit_consumed", explicitRequestSeen && launchQueue.pending == null)
        updates.saveState(outState)
        guideView.saveState(outState)
        discoveryView.saveState(outState)
        options.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            explicitRequestSeen = true
            launchQueue.replace(LaunchRequest.parse(intent.dataString))
            integrationDialog?.dismiss()
            exitDialog?.dismiss()
            tvPlayer.stop()
            current = null
            showingCatalog = true
            consumeLaunchRequest()
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
            is LaunchRequest.ChannelId -> lifecycleScope.launch {
                val channel = repository.find(request.id)
                if(generation!=launchGeneration)return@launch
                if (channel != null) selectChannel(channel)
                else showRequestedCatalog("Tutti", "Canale non più disponibile · Scegli un canale dal catalogo")
            }
            LaunchRequest.Last -> {
                val channel = channels.firstOrNull { !it.isPersonal && it.id == repository.lastIntegratedChannelId }
                if (channel != null) selectChannel(channel)
                else showRequestedCatalog("Tutti", "Nessun ultimo canale disponibile · Scegli un canale")
            }
            LaunchRequest.Catalog -> showRequestedCatalog("Tutti")
            LaunchRequest.Favorites -> showRequestedCatalog("Preferiti")
            LaunchRequest.Guide -> showRequestedCatalog("Guida TV")
            LaunchRequest.Discover -> showRequestedCatalog("Cosa guardare")
            LaunchRequest.Integration -> { showRequestedCatalog("Tutti"); showOptions(); showIntegrationOptions() }
            LaunchRequest.Home -> { showRequestedCatalog("Tutti"); options.select(TvOption.HOME); showOptions(); homeSettings.show() }
            LaunchRequest.Invalid -> showRequestedCatalog("Tutti", "Collegamento non valido · Scegli un canale dal catalogo")
        }
        return true
    }

    private fun showRequestedCatalog(filter: String, message: String? = null) {
        current = null
        tvPlayer.stop()
        currentFilter = filter
        launchMessage = message
        buildTabs()
        showCatalog()
        if (message != null) status.text = message
    }

    private fun showIntegrationOptions() {
        val enabled = IntegrationStore.allowed(this)
        integrationDialog?.dismiss()
        integrationDialog = android.app.AlertDialog.Builder(this).setTitle("Collegamento con AURA")
            .setMessage("Condividi con AURA i canali, i preferiti, la guida e l’ultimo canale guardato su questa TV. " +
                "I dati sono comuni al dispositivo, non ai profili AURA. Puoi revocare il collegamento in qualsiasi momento da Opzioni.")
            .setPositiveButton(if (enabled) "Revoca collegamento" else "Consenti collegamento") { _, _ ->
                IntegrationStore.setAllowed(this, !enabled)
                status.text = if (enabled) "Collegamento con AURA revocato" else "Collegamento con AURA attivo"
            }.setNegativeButton("Chiudi", null).show().also { dialog ->
                dialog.setOnDismissListener {
                    integrationDialog = null
                    if (showingOptions) { renderOptions(); focusOptions() }
                    root.post { updates.maybePrompt() }
                }
            }
    }

    override fun onDestroy() {
        catalogDialogs.toList().forEach { it.dismiss() }
        integrationDialog?.dismiss()
        reminderDialog?.dismiss()
        exitDialog?.dismiss()
        super.onDestroy()
    }

    private fun buildUi() {
        root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleRemoteKey(event) || super.dispatchKeyEvent(event)
        }.apply {
            setBackgroundColor(TvStyle.background); isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        video = PlayerView(this).apply { useController = false; isFocusable = false; setBackgroundColor(Color.BLACK) }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        catalog = FrameLayout(this)
        catalog.addView(CatalogBackdrop(this), FrameLayout.LayoutParams(-1, -1))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(46), dp(26), dp(46), dp(20))
            clipChildren = false; clipToPadding = false
        }
        catalogContent = content
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val brand = ImageView(this).apply {
            setImageResource(R.drawable.lume_wordmark); scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "Lume TV"
        }
        top.addView(brand, LinearLayout.LayoutParams(dp(112), dp(44)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tabs = LinearLayout(this).apply {
            tag="tv_navigation"
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = TvStyle.rounded(0xB21C2028.toInt(), dp(28).toFloat(), 0x14FFFFFF, dp(1))
        }
        channelFilters = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        top.addView(tabs, LinearLayout.LayoutParams(-2, dp(50)))
        buildTabs()
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        clock = TvStyle.text(this, "", 16f, TvStyle.muted).apply { fontFeatureSettings = "tnum"; gravity = Gravity.END }
        top.addView(clock, LinearLayout.LayoutParams(dp(62), -2))
        content.addView(top)
        val titleRow=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        heading = TvStyle.text(this, "La TV, fatta semplice.", 26f, bold = true)
        titleRow.addView(heading,LinearLayout.LayoutParams(-2,-2))
        status = TvStyle.text(this, "Carico i tuoi canali…", 13f, TvStyle.muted)
        status.maxLines=2;status.ellipsize=android.text.TextUtils.TruncateAt.END;status.gravity=Gravity.END
        titleRow.addView(status,LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=dp(20)})
        content.addView(titleRow,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(8);bottomMargin=dp(6)})
        val controls=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;clipChildren=false}
        controls.addView(channelFilters,LinearLayout.LayoutParams(-2,dp(48)))
        sourceRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sourceButton = TvStyle.button(this, "Lista: Catalogo Lume") { chooseCatalogSource() }.apply { minimumHeight = dp(48) }
        groupButton = TvStyle.button(this, "Tutti i gruppi") { chooseCatalogGroup() }.apply { minimumHeight = dp(48) }
        sourceButton.maxLines = 1; sourceButton.ellipsize = android.text.TextUtils.TruncateAt.END
        groupButton.maxLines = 1; groupButton.ellipsize = android.text.TextUtils.TruncateAt.END
        sourceButton.maxWidth=dp(230);groupButton.maxWidth=dp(190)
        sourceRow.addView(sourceButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        sourceRow.addView(groupButton, LinearLayout.LayoutParams(-2, -2))
        controls.addView(sourceRow,LinearLayout.LayoutParams(-2,dp(48)))
        personalSearch=TvStyle.button(this,"Cerca",action=::openCatalogSearch).apply {maxLines=1;maxWidth=dp(240);ellipsize=android.text.TextUtils.TruncateAt.END;minimumHeight=dp(48)}
        controls.addView(personalSearch,LinearLayout.LayoutParams(-2,dp(48)))
        resetFilters=TvStyle.button(this,"Azzera filtri",action=::clearCatalogFilters).apply {minimumHeight=dp(48)}
        controls.addView(resetFilters,LinearLayout.LayoutParams(-2,dp(48)))
        catalogControls=android.widget.HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false;clipChildren=false;addView(controls)}
        content.addView(catalogControls,LinearLayout.LayoutParams(-1,dp(48)).apply {bottomMargin=dp(6)})
        catalogSearchRow=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE}
        catalogSearchField=it.lumetv.app.ui.CatalogPanelStyle.search(this,"Cerca per nome o numero"){}.apply {
            setOnEditorActionListener {_,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){applyCatalogSearch();true}else false}
        }
        catalogSearchRow.addView(catalogSearchField,LinearLayout.LayoutParams(0,dp(48),1f))
        catalogSearchRow.addView(TvStyle.button(this,"Cerca",action=::applyCatalogSearch),LinearLayout.LayoutParams(-2,dp(48)))
        catalogSearchRow.addView(TvStyle.button(this,"Annulla",action=::closeCatalogSearch),LinearLayout.LayoutParams(-2,dp(48)))
        content.addView(catalogSearchRow,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(6)})
        personalPager=it.lumetv.app.ui.PersonalCatalog(lifecycleScope,repository.personal){change->grid.post {updateCatalog(false,change)}}
        val gridArea = FrameLayout(this)
        adapter = ChannelAdapter(logos, repository::isFavorite, ::selectChannel) { channel ->
            val added = repository.toggleFavorite(channel)
            updateCatalog()
            status.text = if (added) "${channel.name} aggiunto ai preferiti" else "${channel.name} rimosso dai preferiti"
        }
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@TvActivity, 4)
            adapter = this@TvActivity.adapter
            clipToPadding = false; clipChildren = false
            setPadding(0, dp(5), 0, dp(8))
            itemAnimator = null
            addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
                val manager=layoutManager as GridLayoutManager
                if(showingCatalog)catalogFocusPosition=findContainingViewHolder(currentFocus ?: this@apply)?.bindingAdapterPosition?.takeIf {it>=0}
                    ?: manager.findFirstVisibleItemPosition().coerceAtLeast(0)
                this@TvActivity.adapter.catalog?.viewport((manager.findFirstVisibleItemPosition()-this@TvActivity.adapter.items.size).coerceAtLeast(0),
                    (manager.findLastVisibleItemPosition()-this@TvActivity.adapter.items.size).coerceAtLeast(0))
                requestVisibleProgrammes()
            }})
        }
        gridArea.addView(grid, FrameLayout.LayoutParams(-1, -1))
        empty = TvStyle.text(this, "Tieni premuto OK su un canale per aggiungerlo ai preferiti.", 18f, TvStyle.muted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        gridArea.addView(empty, FrameLayout.LayoutParams(-1, -1))
        guideView = GuideView(this, logos, repository::isFavorite, ::selectChannel, { channel ->
            val added = repository.toggleFavorite(channel)
            updateCatalog()
            guideView.showMessage(channel.name + if (added) " aggiunto ai preferiti" else " rimosso dai preferiti")
        }, { tabs.findViewWithTag<View>("Guida TV")?.requestFocus() }).apply { visibility = View.GONE }
        guideView.onReplay={channel,p->selectTarget(channel,p.eventId)}
        guideView.onSearch={channel->if(channel.isPersonal)it.lumetv.app.ui.PersonalCatalogPanels(this,lifecycleScope).paged("Cerca nella guida di ${channel.name}",
            load={q,o->guide.personal.search(channel,q,o)},label={it.title+" · "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(it.start))}) {guideView.showProgramme(channel,it)}}
        guideView.onWindowChanged=::loadPersonalGuideWindow
        lifecycleScope.launch {guide.personal.state.collect {if(catalogLoaded){personalGuideWindowKey="";guideView.reloadWindow();requestVisibleProgrammes()}}}
        gridArea.addView(guideView, FrameLayout.LayoutParams(-1, -1))
        discoveryView = DiscoveryView(this, logos, repository::isFavorite, { channel ->
            repository.toggleFavorite(channel)
            updateCatalog()
        }, reminders, ::selectChannel, {
            currentFilter = lastChannelFilter; buildTabs(); updateCatalog(); focusFirstChannel()
        }, { tabs.findViewWithTag<View>("Cosa guardare")?.requestFocus() }).apply { visibility = View.GONE }
        gridArea.addView(discoveryView, FrameLayout.LayoutParams(-1, -1))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previewParent = body
        body.addView(gridArea, LinearLayout.LayoutParams(0, -1, 1f))
        previewColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = TvStyle.rounded(TvStyle.surface, dp(12).toFloat())
            visibility = View.GONE
        }
        previewHolder = FrameLayout(this)
        previewColumn.addView(previewHolder, LinearLayout.LayoutParams(-1, dp(126)))
        previewTitle = TvStyle.text(this, "", 16f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        previewColumn.addView(previewTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(8) })
        previewOpen = TvStyle.button(this, "Apri la diretta",action=::openPlayback).apply { id = View.generateViewId() }
        previewStop = TvStyle.button(this, "Interrompi") { tvPlayer.stop(); updatePreview() }.apply { id = View.generateViewId() }
        previewColumn.addView(previewOpen, LinearLayout.LayoutParams(-1, -2))
        previewColumn.addView(previewStop, LinearLayout.LayoutParams(-1, -2))
        body.addView(previewColumn, LinearLayout.LayoutParams(dp(248), -2).apply { leftMargin = dp(16); topMargin = dp(6) })
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        catalogFooter = footer
        footer.addView(TvStyle.text(this, "← Filtri     /     OK Guarda     /     OK prolungato Preferiti     /     CH +/− Cambia", 12f, TvStyle.muted), LinearLayout.LayoutParams(0, -2, 1f))
        updateNotice = TvStyle.button(this, "Aggiorna Lume TV") { updates.show() }.apply { visibility = View.GONE }
        footer.addView(updateNotice, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) })
        syncStatus = TvStyle.text(this, "Catalogo automatico", 12f, TvStyle.muted)
        footer.addView(syncStatus)
        content.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        liveCatalogContent=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        while(content.childCount>1){val child=content.getChildAt(1);val params=child.layoutParams;content.removeViewAt(1);liveCatalogContent.addView(child,params)}
        videoContainer=FrameLayout(this).apply {addView(liveCatalogContent,FrameLayout.LayoutParams(-1,-1))}
        content.addView(videoContainer,LinearLayout.LayoutParams(-1,0,1f))
        catalog.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(catalog, FrameLayout.LayoutParams(-1, -1))

        audioView = AudioPlaybackView(this, logos, true, ::showCatalog, { tvPlayer.togglePause() },
            { zap(-1) }, { zap(1) }, {
                current?.let { repository.toggleFavorite(it); audioView.bind(tvPlayer.snapshot, repository.isFavorite(it)); updateCatalog() }
            }, { tvPlayer.stop(); showCatalog() }, { current?.let(::selectChannel) }).apply { visibility = View.GONE }
        root.addView(audioView, FrameLayout.LayoutParams(-1, -1))
        banner = LiveBanner(this, logos).apply { visibility = View.GONE }
        root.addView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(46); rightMargin = dp(46); bottomMargin = dp(28)
        })
        loading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
        loading.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(TvStyle.accent) }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        loadingTitle = TvStyle.text(this, "Apro la diretta…", 18f)
        loading.addView(loadingTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(40), dp(30), dp(40), dp(30))
            background = TvStyle.rounded(TvStyle.surface, dp(20).toFloat())
            visibility = View.GONE
        }
        root.addView(errorBox, FrameLayout.LayoutParams(dp(580), -2, Gravity.CENTER))
        liveChannels = LiveChannelPanel(this, logos, repository::isFavorite, { channel ->
            if (channel.id == current?.id && !failed) {
                closeLiveChannels()
                if (!tvPlayer.isPlaying && !tvPlayer.snapshot.buffering) resumeLive()
                showChannelBanner()
            } else selectChannel(channel)
        }, ::closeLiveChannels)
        root.addView(liveChannels, FrameLayout.LayoutParams(-1, -1))
        demandControls=it.lumetv.app.ui.OnDemandControls(this).apply {
            onSeek=tvPlayer::seek;onSeekBy=tvPlayer::seekBy;onRestart=tvPlayer::restart;onToggle={tvPlayer.togglePause()};onReturnLive=tvPlayer::returnToLive
        }
        root.addView(demandControls,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply {leftMargin=dp(28);rightMargin=dp(28);bottomMargin=dp(18)})
        options = TvOptionsView(this, ::chooseOption).apply { visibility = View.GONE }
        catalogContent.addView(options, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(23) })
        linkOptionsNavigation()
        setContentView(root)
    }

    private fun buildTabs() {
        val focused=tabs.findFocus()?.tag as? String
        tabs.removeAllViews()
        if (currentFilter in channelSections) lastChannelFilter = currentFilter
        val destinations=buildList {add("Canali");if(videoAvailable)add("Video");addAll(listOf("Radio","Cosa guardare","Guida TV"))}
        val tabPadding=dp(if(videoAvailable && resources.configuration.fontScale>1.1f)12 else 18)
        destinations.forEach { label ->
            val selected = tabSelected(label)
            val tab = TvStyle.button(this, label, selected) {
                if(label=="Video"){openVideoLibrary();return@button}
                if(::catalogPanels.isInitialized)catalogPanels.dismiss()
                dismissOptions()
                launchMessage = null
                rememberCatalogContext()
                restoreCatalogContext(label)
                buildTabs(); updateCatalog(); focusFirstChannel()
                if (label == "Guida TV" || label == "Cosa guardare") refreshGuide()
            }.apply { tag = label;setPadding(tabPadding,paddingTop,tabPadding,paddingBottom) }
            tabs.addView(tab, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(2) })
        }
        channelFilters.removeAllViews()
        if (currentFilter == "Radio") {
            listOf("Tutte", "Preferite", "Recenti").forEach { label ->
                channelFilters.addView(TvStyle.button(this, label, radioFilter == label) {
                    radioFilter = label; buildTabs(); updateCatalog(); focusFirstChannel()
                }, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(6) })
            }
        } else channelSections.filter { catalogSource == "builtin" || it !in listOf("Rai", "Mediaset") }.forEach { label ->
            channelFilters.addView(TvStyle.button(this, label, currentFilter == label) {
                launchMessage = null; currentFilter = label
                buildTabs(); updateCatalog(); focusFirstChannel()
            }.apply { textSize = 12f; minHeight = 0; setPadding(dp(16), dp(5), dp(16), dp(5)) },
                LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(6) })
        }
        val refresh = TvStyle.button(this, "↻", false) { refreshCatalog(true); refreshGuide(true) }.apply { contentDescription = "Aggiorna canali e guida TV" }
        refresh.setPadding(dp(13), dp(8), dp(13), dp(8))
        tabs.addView(refresh, LinearLayout.LayoutParams(dp(42), -1))
        tabs.addView(TvStyle.button(this, "Opzioni", showingOptions) { showOptions() }.apply {
            tag = "Opzioni"; setPadding(dp(13), dp(8), dp(13), dp(8))
        }, LinearLayout.LayoutParams(-2, -1))
        tabs.addView(TvStyle.button(this, getString(R.string.exit_app)) { showExitConfirmation() }.apply {
            setPadding(dp(13), dp(8), dp(13), dp(8))
        }, LinearLayout.LayoutParams(-2, -1))
        linkOptionsNavigation()
        focused?.let {tabs.findViewWithTag<View>(it)?.requestFocus()}
    }

    private fun tabSelected(label:String)=!showingOptions && if(label=="Video")showingVideo else !showingVideo &&
        (if(label=="Canali")currentFilter in channelSections else currentFilter==label)

    private fun updateTabSelection() {
        listOf("Canali","Video","Radio","Cosa guardare","Guida TV").forEach {label->
            tabs.findViewWithTag<TextView>(label)?.let {TvStyle.setButtonSelected(it,tabSelected(label))}
        }
    }

    private fun linkOptionsNavigation() {
        val controls = (0 until tabs.childCount).map { tabs.getChildAt(it) }
        controls.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        if (::options.isInitialized) options.setNavigation(if (showingOptions) controls else emptyList(),
            tabs.findViewWithTag<View>("Opzioni").takeIf { showingOptions })
    }

    private fun showExitConfirmation() {
        if (isFinishing || isDestroyed || exitDialog?.isShowing == true) return
        // User navigation takes precedence over playback still queued during startup.
        initialPlaybackCancelled = true
        launchQueue.consume(true)
        // A pending numeric selection must not open a channel beneath the confirmation.
        handler.removeCallbacks(numberAction); digits = ""
        exitDialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.exit_app_title)
            .setMessage(R.string.exit_app_message)
            .setPositiveButton(R.string.exit_app) { _, _ ->
                // A new instance can start before onStop; release the media session first.
                releasePlayback()
                finishAndRemoveTask()
            }
            .setNegativeButton(R.string.exit_app_cancel, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { exitDialog = null }
                dialog.show()
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).requestFocus()
            }
    }

    private fun showOptions() {
        if(::catalogPanels.isInitialized)catalogPanels.dismiss()
        if (showingOptions) { focusOptions(); return }
        initialPlaybackCancelled = true
        launchQueue.consume(true)
        handler.removeCallbacks(numberAction); digits = ""
        guideView.deactivate(); discoveryView.deactivate()
        showingOptions = true
        options.setPreview(previewColumn)
        buildTabs(); updateCatalog(restoreFocus = false)
        renderOptions(); updatePreview()
        options.visibility = View.VISIBLE
        focusOptions()
    }

    private fun closeOptions() {
        dismissOptions()
        catalog.visibility = View.VISIBLE
        updatePreview(); updateCatalog(restoreFocus = false)
        tabs.findViewWithTag<View>("Opzioni")?.requestFocusFromTouch()
        root.post { updates.maybePrompt() }
    }

    private fun dismissOptions() {
        if (!showingOptions) return
        showingOptions = false
        options.visibility = View.GONE
        (previewColumn.parent as? ViewGroup)?.removeView(previewColumn)
        previewParent.addView(previewColumn, LinearLayout.LayoutParams(dp(248), -2).apply { leftMargin = dp(16); topMargin = dp(6) })
        buildTabs()
    }

    private fun renderOptions() {
        options.bind(InterfacePreferences.selected(this).label, repository.zapFavoritesOnly,
            IntegrationStore.allowed(this), BuildConfig.VERSION_NAME, updates.available?.versionName,
            it.lumetv.app.home.HomeChannels.summary(this))
    }

    private fun focusOptions() {
        options.post {
            if (showingOptions && !(::playlists.isInitialized && playlists.isShowing) && catalogDialogs.none { it.isShowing } && integrationDialog?.isShowing != true)
                options.focusSelection()
        }
    }

    private fun chooseOption(option: TvOption) {
        when (option) {
            TvOption.PLAYLISTS -> { playlists.bindPlayback(tvPlayer.snapshot.channel != null); playlists.show() }
            TvOption.INTERFACE -> trackDialog(InterfacePreferences.choose(this) {
                releasePlayback()
                val destination = when (currentFilter) {
                    "Guida TV" -> "guide"; "Cosa guardare" -> "discover"; "Preferiti" -> "favorites"; else -> "catalog"
                }
                startActivity(updates.carrySession(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(android.net.Uri.parse("lumetv://$destination"))))
                finish()
            })
            TvOption.ZAPPING -> showZappingOptions()
            TvOption.AURA -> showIntegrationOptions()
            TvOption.HOME -> homeSettings.show()
            TvOption.UPDATES -> updates.show()
            TvOption.ABOUT -> showAbout()
            TvOption.LICENSES -> showLicenses()
        }
    }

    private fun trackDialog(dialog: android.app.AlertDialog) {
        catalogDialogs += dialog
        dialog.setOnDismissListener {
            catalogDialogs -= dialog
            if (playlists.isShowing) root.post { if (catalogDialogs.none { it.isShowing }) playlists.focusSelection() }
            else if (showingOptions) { renderOptions(); focusOptions() }
            root.post { updates.maybePrompt() }
        }
    }

    private fun showZappingOptions() {
        trackDialog(android.app.AlertDialog.Builder(this).setTitle("Cambio canale con CH+/CH−")
            .setSingleChoiceItems(arrayOf("Tutti i canali", "Solo preferiti"), if (repository.zapFavoritesOnly) 1 else 0) { dialog, selected ->
                repository.zapFavoritesOnly = selected == 1
                status.text = if (selected == 1) "CH+/CH− scorre i preferiti · OK prolungato per aggiungere un canale"
                    else "CH+/CH− scorre tutti i canali"
                dialog.dismiss()
            }.setNegativeButton("Annulla", null).show())
    }

    private fun showAbout() {
        val updated = if (repository.lastUpdated > 0) SimpleDateFormat("d MMM, HH:mm", Locale.ITALY).format(Date(repository.lastUpdated)) else "lista iniziale inclusa"
        val guideUpdated = if (guide.lastUpdated > 0) SimpleDateFormat("d MMM, HH:mm", Locale.ITALY).format(Date(guide.lastUpdated)) else "in attesa del primo aggiornamento"
        trackDialog(android.app.AlertDialog.Builder(this).setTitle("Lume TV · ${BuildConfig.VERSION_NAME}")
            .setMessage("La TV, fatta semplice.\n\nCatalogo automatico ogni 24 ore: $updated.\nGuida automatica ogni 6 ore: $guideUpdated.\n↻ aggiorna canali e guida.\n\nIntegrazioni adattate da WorldLiveTV, WLTV Group; modifiche Lume TV del 27 ottobre 2026. Software libero, GPL-3.0-or-later: puoi ridistribuirlo e modificarlo secondo la licenza. Nessuna garanzia, nei limiti di legge.\n\nLe trasmissioni sono fornite dalle rispettive emittenti. Sorgenti e segnalazioni: github.com/pjor94/LumeTV.")
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Licenze e avvisi") { _, _ -> showLicenses() }.show())
    }

    private fun showLicenses() {
        val documents = linkedMapOf(
            "Crediti, modifiche e sorgenti" to "THIRD_PARTY_NOTICES.md",
            "Lume TV · GNU GPL v3" to "LICENSE.txt",
            "Apache License 2.0" to "licenses/Apache-2.0.txt",
            "R8 · BSD a tre clausole" to "licenses/BSD-3-Clause-R8.txt",
            "Desugar JDK · GPL v2 e Classpath Exception" to "licenses/GPL-2.0-with-classpath-exception.txt",
            "Public Suffix List · Mozilla Public License 2.0" to "licenses/MPL-2.0.txt",
            "XZ for Java · Pubblico dominio" to "licenses/XZ-PUBLIC-DOMAIN.txt",
            "Avvisi originali delle dipendenze" to "DEPENDENCY_NOTICES.txt",
            "OpenJDK · Informazioni aggiuntive" to "licenses/DESUGAR-ADDITIONAL_LICENSE_INFO.txt",
            "OpenJDK · Assembly Exception" to "licenses/OPENJDK-ASSEMBLY_EXCEPTION.txt",
        )
        val titles = documents.keys.toTypedArray()
        trackDialog(android.app.AlertDialog.Builder(this).setTitle("Licenze e avvisi")
            .setItems(titles) { _, index ->
                val text = TvStyle.text(this, assets.open(documents.getValue(titles[index])).bufferedReader().use { it.readText() }, 13f).apply {
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                }
                val scroll = android.widget.ScrollView(this).apply { addView(text); isFocusable = true }
                trackDialog(android.app.AlertDialog.Builder(this).setTitle(titles[index]).setView(scroll)
                    .setPositiveButton("Chiudi", null).show())
            }.setPositiveButton("Chiudi", null).show())
    }

    private fun updateCatalog(restoreFocus: Boolean = true,catalogChange:it.lumetv.app.ui.ContinuousCatalog.Change?=null) {
        if (catalogLoaded && catalogSource != "builtin" && repository.personal.state.value.none { it.id == catalogSource && it.enabled }) {
            catalogSource = "builtin"; catalogGroup = null
            getSharedPreferences("lume", MODE_PRIVATE).edit().putString("catalog_source", catalogSource).apply()
            buildTabs()
        }
        if (catalogSource=="builtin" && catalogGroup != null && it.lumetv.core.CatalogSelection.channels(channels, catalogSource)
                .none { it.isRadio == (currentFilter == "Radio") && catalogGroup in it.groups }) catalogGroup = null
        val favorites=currentFilter=="Preferiti" || currentFilter=="Radio" && radioFilter=="Preferite"
        val paged=catalogSource!="builtin" || favorites
        val restricted=when {favorites->repository.favorites();currentFilter=="Recenti" || currentFilter=="Radio" && radioFilter=="Recenti"->repository.recentIds.toSet();else->null}
        val catalogReset=if(paged)personalPager.bind(it.lumetv.app.data.PersonalCatalogQuery(source=catalogSource.takeUnless {favorites},
            kind=if(currentFilter=="Radio")it.lumetv.core.MediaKind.RADIO else it.lumetv.core.MediaKind.TV,
            content=it.lumetv.core.PlaylistContentKind.LIVE,
            text=if(currentFilter=="Radio")radioQuery else personalQuery,group=catalogGroup.takeUnless {favorites},ids=restricted))
        else {personalPager.hide();false}
        if(showingOptions)personalPager.hide()
        val scoped = if(paged)(if(favorites)channels.filterNot {it.isPersonal} else emptyList())+personalPager.rows else channelsForCatalog()
        val television = scoped.filter {!it.isRadio && !it.isVod && (personalQuery.isBlank() || it.name.contains(personalQuery,true) || it.displayNumber?.toString()==personalQuery)}
        val visible = when (currentFilter) {
            "Radio" -> scoped.filter { it.isRadio && (radioFilter != "Preferite" || repository.isFavorite(it)) &&
                (radioFilter != "Recenti" || it.id in repository.recentIds) &&
                (radioQuery.isBlank() || RadioCatalog.matches(it, radioQuery)) }
                .let { rows -> if (radioFilter == "Recenti") rows.sortedBy { repository.recentIds.indexOf(it.id) } else rows }
            "Rai", "Mediaset" -> television.filter { it.family == currentFilter }
            "Preferiti" -> scoped.filter {!it.isRadio && repository.isFavorite(it)}
            "Recenti" -> repository.recent(television)
            else -> television
        }
        val guideMode = currentFilter == "Guida TV"
        val discoveryMode = currentFilter == "Cosa guardare"
        val programmeMode = guideMode || discoveryMode
        val continuous=paged && !programmeMode && currentFilter!="Recenti" && !(currentFilter=="Radio" && radioFilter=="Recenti")
        adapter.guideMode = false
        adapter.sourceNames = repository.personal.state.value.associate { it.id to it.name }
        adapter.items = if(continuous)visible.filterNot {it.isPersonal} else visible
        adapter.catalog=if(continuous)personalPager.data else null
        adapter.programmes = programmes
        adapter.currentId = current?.id
        (grid.layoutManager as GridLayoutManager).spanCount = if (::previewColumn.isInitialized && previewColumn.visibility == View.VISIBLE) 3 else 4
        if(continuous && catalogChange!=null && !catalogChange.sizeChanged) {
            val scroll=grid.layoutManager?.onSaveInstanceState()
            val start=adapter.items.size+catalogChange.start
            val amount=catalogChange.count.coerceAtMost(adapter.itemCount-start)
            if(amount>0)adapter.notifyItemRangeChanged(start,amount)
            grid.layoutManager?.onRestoreInstanceState(scroll)
        } else adapter.notifyDataSetChanged()
        if(catalogReset){if(pendingCatalogScroll==null)catalogFocusPosition=personalPager.offset;grid.scrollToPosition(personalPager.offset)}
        if(adapter.itemCount>0 && adapter.catalog?.let {it.loading && it.size==0}!=true)pendingCatalogScroll?.let {scroll->
            val focus=pendingCatalogFocus ?: catalogFocusPosition
            pendingCatalogFocus=null
            pendingCatalogScroll=null;grid.layoutManager?.onRestoreInstanceState(scroll)
            grid.post {if(showingCatalog && !showingOptions && !tabs.hasFocus() && adapter.itemCount>0)grid.findViewHolderForAdapterPosition(focus.coerceIn(0,adapter.itemCount-1))?.itemView?.requestFocus()}
        }
        grid.post {requestVisibleProgrammes()}
        grid.visibility = if (programmeMode) View.GONE else View.VISIBLE
        heading.visibility = if (programmeMode || showingOptions) View.GONE else View.VISIBLE
        status.visibility = if (programmeMode || showingOptions) View.GONE else View.VISIBLE
        channelFilters.visibility = if (programmeMode || showingOptions) View.GONE else View.VISIBLE
        catalogControls.visibility=if(showingOptions)View.GONE else View.VISIBLE
        videoContainer.visibility=if(showingOptions)View.GONE else View.VISIBLE
        sourceRow.visibility = if (repository.personal.state.value.isEmpty() || favorites) View.GONE else View.VISIBLE
        sourceButton.text = "Lista: " + (repository.personal.state.value.firstOrNull { it.id == catalogSource }?.name ?: "Catalogo Lume")
        sourceButton.contentDescription=sourceButton.text
        val activeQuery=if(currentFilter=="Radio")radioQuery else personalQuery
        personalSearch.text=if(activeQuery.isBlank())"Cerca" else "Cerca: $activeQuery"
        personalSearch.contentDescription=if(activeQuery.isBlank())"Cerca nella lista" else "Ricerca attiva: $activeQuery"
        personalSearch.visibility=if(!programmeMode)View.VISIBLE else View.GONE
        resetFilters.visibility=if(!programmeMode && (activeQuery.isNotBlank() || catalogGroup!=null || currentFilter in setOf("Rai","Mediaset") || currentFilter=="Radio" && radioFilter!="Tutte"))View.VISIBLE else View.GONE
        groupButton.text = catalogGroup ?: "Tutti i gruppi"
        groupButton.contentDescription="Gruppo: ${groupButton.text}"
        groupButton.visibility = if (catalogSource == "builtin") View.GONE else View.VISIBLE
        catalogFooter.visibility = if (programmeMode || showingOptions) View.GONE else View.VISIBLE
        previewParent.visibility = if (showingOptions) View.GONE else View.VISIBLE
        guideView.visibility = if (guideMode) View.VISIBLE else View.GONE
        if (guideMode) guideView.submit(television, programmes, current?.id)
        else guideView.deactivate()
        discoveryView.visibility = if (discoveryMode) View.VISIBLE else View.GONE
        if (discoveryMode) discoveryView.submit(television, programmes)
        else discoveryView.deactivate()
        heading.text = when (currentFilter) { "Radio" -> "Radio, in diretta."; "Recenti" -> "Le ultime dirette."; "Guida TV" -> "Ora. E tra poco."; "Preferiti" -> "I tuoi preferiti."; "Rai" -> "Il mondo Rai."; "Mediaset" -> "Il mondo Mediaset."; else -> "La TV, fatta semplice." }
        val count=adapter.itemCount
        status.text = if (paged && personalPager.loading && count==0)"Carico i canali…" else listOfNotNull("$count ${if(currentFilter=="Radio")"radio" else "canali"}",
            repository.personal.state.value.firstOrNull {it.id==catalogSource}?.name,catalogGroup,activeQuery.takeIf {it.isNotBlank()}?.let {"Ricerca: $it"}).joinToString(" · ")
        status.contentDescription=status.text
        updateGuideStatus()
        launchMessage?.let { status.text = it }
        syncStatus.text = if (repository.lastUpdated > 0) "●  Catalogo aggiornato" else "Catalogo automatico"
        empty.visibility = if (!programmeMode && count==0) View.VISIBLE else View.GONE
        empty.text = when {
            paged && personalPager.error.isNotBlank()->"Impossibile aprire la lista. Premi OK per riprovare."
            paged && personalPager.loading->"Carico i canali…"
            currentFilter == "Radio" && radioQuery.isNotBlank() -> "Nessuna radio trovata. Prova un altro nome o una città."
            currentFilter == "Recenti" || currentFilter == "Radio" && radioFilter == "Recenti" -> "Le emittenti riprodotte compariranno qui."
            else -> "Tieni premuto OK su un’emittente per aggiungerla ai preferiti."
        }
        empty.isFocusable=paged && personalPager.error.isNotBlank()
        empty.isClickable=empty.isFocusable
        empty.setOnClickListener {personalPager.data.retry()}
        if (restoreFocus && showingCatalog && (currentFocus == null || currentFocus === root)) focusFirstChannel()
    }

    private fun channelsForCatalog(): List<Channel> = if (currentFilter == "Preferiti" || currentFilter == "Radio" && radioFilter == "Preferite") channels
        else it.lumetv.core.CatalogSelection.channels(channels, catalogSource, catalogGroup)

    private fun channelsForPlayback(): List<Channel> = (current?.let { playing -> channels.filter { it.sourceId == playing.sourceId && it.kind == playing.kind } } ?: channels).filterNot {it.isVod}

    private fun contextSection()=if(currentFilter in channelSections)"Canali" else currentFilter
    private fun rememberCatalogContext() {
        if(!::grid.isInitialized)return
        sectionContexts[contextSection()]=Bundle().apply {
            putString("source",catalogSource);putString("group",catalogGroup);putString("filter",currentFilter)
            putString("query",personalQuery);putString("radio_query",radioQuery);putString("radio_filter",radioFilter)
            putParcelable("scroll",grid.layoutManager?.onSaveInstanceState());putInt("focus",catalogFocusPosition)
        }
    }
    private fun restoreCatalogContext(section:String) {
        closeCatalogSearch()
        val state=sectionContexts[section]
        currentFilter=state?.getString("filter") ?: if(section=="Canali")lastChannelFilter else section
        if(state!=null) {
            catalogSource=state.getString("source") ?: "builtin";catalogGroup=state.getString("group")
            personalQuery=state.getString("query").orEmpty();radioQuery=state.getString("radio_query").orEmpty();radioFilter=state.getString("radio_filter") ?: "Tutte"
            @Suppress("DEPRECATION") val scroll:android.os.Parcelable?=state.getParcelable("scroll")
            pendingCatalogScroll=scroll;pendingCatalogFocus=state.getInt("focus");catalogFocusPosition=state.getInt("focus")
        }
    }
    private fun openCatalogSearch() {
        catalogSearchRow.visibility=View.VISIBLE
        catalogSearchField.hint=if(currentFilter=="Radio")"Cerca per nome o città" else "Cerca per nome o numero"
        catalogSearchField.setText(if(currentFilter=="Radio")radioQuery else personalQuery)
        catalogSearchField.setSelection(catalogSearchField.length());catalogSearchField.requestFocus()
    }
    private fun closeCatalogSearch() {
        if(!::catalogSearchRow.isInitialized || catalogSearchRow.visibility!=View.VISIBLE)return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(catalogSearchField.windowToken,0)
        catalogSearchRow.visibility=View.GONE;personalSearch.requestFocus()
    }
    private fun applyCatalogSearch() {
        val query=catalogSearchField.text.toString().trim()
        if(currentFilter=="Radio")radioQuery=query else personalQuery=query
        closeCatalogSearch();updateCatalog(false)
    }
    private fun clearCatalogFilters() {
        personalQuery="";radioQuery="";catalogGroup=null;radioFilter="Tutte"
        if(currentFilter in setOf("Rai","Mediaset"))currentFilter="Tutti"
        personalPager.resetPosition();pendingCatalogScroll=null;pendingCatalogFocus=null;catalogFocusPosition=0
        closeCatalogSearch();buildTabs();updateCatalog(false);personalSearch.requestFocus()
    }

    private fun chooseCatalogSource() {
        playlists.chooseSource(catalogSource, false, if (currentFilter == "Radio") it.lumetv.core.MediaKind.RADIO else it.lumetv.core.MediaKind.TV) { id ->
            handler.removeCallbacks(numberAction); digits = ""
            catalogSource = id; catalogGroup = null
            if (currentFilter == "Rai" || currentFilter == "Mediaset") currentFilter = "Tutti"
            getSharedPreferences("lume", MODE_PRIVATE).edit().putString("catalog_source", id).apply()
            buildTabs(); updateCatalog()
        }
    }

    private fun toggleVideoFavorite(channel:Channel){repository.toggleFavorite(channel);updateCatalog(false)}
    private fun openVideoLibrary(){
        if(repository.personal.videoSources.value.isEmpty())return
        val fromOptions=showingOptions;dismissOptions();launchMessage=null
        if(fromOptions){updateCatalog(restoreFocus=false);tabs.findViewWithTag<View>("Video")?.requestFocus()}
        initialPlaybackCancelled=true;launchGeneration++;launchQueue.consume(true)
        catalogPanels.library(repository.personal,logos,repository::isFavorite,::toggleVideoFavorite,::selectChannel)
    }
    private fun restoreVideoPanel(){catalogPanels.restorePending(repository.personal,logos,repository::isFavorite,::toggleVideoFavorite,::selectChannel)}

    private fun chooseCatalogGroup() {
        if(catalogSource!="builtin") {
            it.lumetv.app.ui.PersonalCatalogPanels(this,lifecycleScope).group(repository.personal,
                it.lumetv.app.data.PersonalCatalogQuery(source=catalogSource,kind=if(currentFilter=="Radio")it.lumetv.core.MediaKind.RADIO else it.lumetv.core.MediaKind.TV)) {
                catalogGroup=it.takeUnless {it=="Tutti i gruppi"};updateCatalog()
            }
            return
        }
        val groups = it.lumetv.core.CatalogSelection.channels(channels, catalogSource).filter { it.isRadio == (currentFilter == "Radio") }.flatMap { it.groups }.distinct().sorted()
        val labels = listOf("Tutti i gruppi") + groups
        trackDialog(android.app.AlertDialog.Builder(this).setTitle("Scegli il gruppo")
            .setSingleChoiceItems(labels.toTypedArray(), catalogGroup?.let { groups.indexOf(it) + 1 } ?: 0) { dialog, index ->
                catalogGroup = if (index == 0) null else groups[index - 1]
                dialog.dismiss(); updateCatalog()
            }.setNegativeButton("Annulla", null).show())
    }

    private fun focusFirstChannel() {
        if (showingOptions) return
        if (currentFilter == "Cosa guardare") {
            discoveryView.post { if (showingCatalog && !showingOptions && currentFilter == "Cosa guardare") discoveryView.focusSelection() }
            return
        }
        if (currentFilter == "Guida TV") {
            guideView.post { if (showingCatalog && !showingOptions && currentFilter == "Guida TV") guideView.focusSelection() }
            return
        }
        if (adapter.itemCount==0) {
            val index = channelSections.indexOf(currentFilter).coerceAtLeast(0)
            channelFilters.getChildAt(index)?.requestFocus()
            return
        }
        if (adapter.itemCount>0) grid.post {
            if (showingOptions) return@post
            val position = adapter.positionOf(current?.id) ?: catalogFocusPosition.coerceIn(0,adapter.itemCount-1)
            grid.scrollToPosition(position)
            grid.post { grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: grid.requestFocus() }
        }
    }

    private fun selectChannel(channel: Channel) {
        grid.findContainingViewHolder(currentFocus ?: grid)?.bindingAdapterPosition?.takeIf {it>=0}?.let {catalogFocusPosition=it}
        launchGeneration++
        selectTarget(channel,null)
    }
    private fun openPlayback(){val selected=tvPlayer.snapshot.channel;if(selected!=null)selectTarget(selected,null,true) else current?.let(::selectChannel)}
    private fun selectTarget(channel:Channel,archiveEvent:String?,preserveCurrent:Boolean=false) {
        dismissOptions()
        guideView.dismissDetails()
        discoveryView.deactivate()
        closeLiveChannels(restoreFocus = false)
        launchMessage = null
        handler.removeCallbacks(numberAction); digits = ""
        val same = preserveCurrent || archiveEvent==null && tvPlayer.snapshot.mode!=it.lumetv.core.PlaybackMode.ARCHIVE && tvPlayer.snapshot.channel?.id == channel.id && tvPlayer.snapshot.error == null
        current = channel; showingCatalog = false; failed = false
        restoreVideo()
        previewColumn.visibility = View.GONE
        catalog.visibility = View.GONE; errorBox.visibility = View.GONE
        video.visibility = if (channel.isRadio) View.GONE else View.VISIBLE
        audioView.visibility = if (channel.isRadio) View.VISIBLE else View.GONE
        root.requestFocus()
        if(archiveEvent!=null)tvPlayer.selectArchive(channel,archiveEvent,false)
        else if (!same) tvPlayer.select(channel, mobile = false)
        else { if (!tvPlayer.snapshot.wantsToPlay) tvPlayer.resumePlayback(); renderPlayback(tvPlayer.snapshot) }
        if (channel.isRadio) audioView.focusPlayback() else if(tvPlayer.snapshot.mode==it.lumetv.core.PlaybackMode.LIVE)showChannelBanner()
        if(channel.isPersonal)loadPersonalGuideWindow(listOf(channel),System.currentTimeMillis(),System.currentTimeMillis()+GuideSchedule.WINDOW)
    }

    private fun renderPlayback(state: PlaybackSnapshot) {
        if(::demandControls.isInitialized){demandControls.render(state);if(showingCatalog)demandControls.visibility=View.GONE}
        if(state.mode!=it.lumetv.core.PlaybackMode.LIVE && ::banner.isInitialized)banner.visibility=View.GONE
        if (::playlists.isInitialized) playlists.bindPlayback(state.channel != null)
        val channel = state.channel
        if (channel == null) {
            if (activeSessionSeen) {
                activeSessionSeen = false
                if (showingOptions) { updatePreview(); focusOptions() } else showCatalog()
            }
            else if (::previewColumn.isInitialized) updatePreview()
            return
        }
        activeSessionSeen = true
        current = channel
        failed = state.error != null
        if (showingCatalog) { updatePreview(); return }
        audioView.visibility = if (channel.isRadio) View.VISIBLE else View.GONE
        video.visibility = if (channel.isRadio) View.GONE else View.VISIBLE
        if (channel.isRadio) {
            audioView.bind(state, repository.isFavorite(channel))
            banner.visibility = View.GONE; loading.visibility = View.GONE; errorBox.visibility = View.GONE
        } else if (state.error != null) showError(state.error)
        else {
            errorBox.visibility = View.GONE
            loading.visibility = if (state.buffering) View.VISIBLE else View.GONE
            loadingTitle.text = state.message.ifBlank { "Carico la diretta…" }
        }
    }

    private fun restoreVideo() {
        if (video.parent !== root) {
            (video.parent as? ViewGroup)?.removeView(video)
            root.addView(video, 0, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun updatePreview() {
        val state = tvPlayer.snapshot
        val visible = showingCatalog && state.channel != null
        previewColumn.visibility = if (visible) View.VISIBLE else View.GONE
        if (showingOptions) options.setPreviewControls(if (visible) previewOpen else null, if (visible) previewStop else null)
        if (!visible) { (grid.layoutManager as GridLayoutManager).spanCount = 4; return }
        previewTitle.text = state.channel.name + when {
            state.error != null -> " · Interrotta"
            !state.wantsToPlay -> " · In pausa"
            state.mode==it.lumetv.core.PlaybackMode.ARCHIVE -> " · Archivio"
            state.mode==it.lumetv.core.PlaybackMode.VOD -> " · Video"
            state.channel.isRadio -> " · In ascolto"
            else -> " · In diretta"
        }
        previewOpen.text=when(state.mode){it.lumetv.core.PlaybackMode.VOD->"Apri il video";it.lumetv.core.PlaybackMode.ARCHIVE->"Apri l’archivio";else->"Apri la diretta"}
        previewHolder.visibility = if (state.channel.isRadio) View.GONE else View.VISIBLE
        if (!state.channel.isRadio && video.parent !== previewHolder) {
            (video.parent as? ViewGroup)?.removeView(video)
            previewHolder.addView(video, FrameLayout.LayoutParams(-1, -1))
            video.visibility = View.VISIBLE
        }
        val spans = if (visible) 3 else 4
        if ((grid.layoutManager as GridLayoutManager).spanCount != spans) (grid.layoutManager as GridLayoutManager).spanCount = spans
    }

    private fun showCatalog() {
        dismissOptions()
        closeLiveChannels(restoreFocus = false)
        handler.removeCallbacks(hideBanner); handler.removeCallbacks(bannerTick); handler.removeCallbacks(numberAction); digits = ""
        showingCatalog = true
        banner.visibility = View.GONE; loading.visibility = View.GONE; errorBox.visibility = View.GONE; audioView.visibility = View.GONE
        catalog.visibility = View.VISIBLE
        updatePreview()
        if (tvPlayer.snapshot.channel == null) video.visibility = View.INVISIBLE
        updateCatalog(); focusFirstChannel()
        root.post { updates.maybePrompt() }
    }

    private fun showError(message: String) {
        failed = true; loading.visibility = View.GONE; banner.visibility = View.GONE
        errorBox.removeAllViews()
        errorBox.addView(TvStyle.text(this, current?.name.orEmpty(), 26f, bold = true))
        errorBox.addView(TvStyle.text(this, message, 17f, TvStyle.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(22) })
        val buttons = LinearLayout(this)
        val retry = TvStyle.button(this, "Riprova") {if(tvPlayer.snapshot.channel!=null)tvPlayer.resumePlayback() else current?.let(::selectChannel)}
        buttons.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        buttons.addView(TvStyle.button(this, "Scegli un canale") { showCatalog() })
        errorBox.addView(buttons)
        errorBox.visibility = View.VISIBLE
        if (!liveChannels.isOpen) retry.requestFocus()
    }

    private fun showLiveChannels() {
        if (showingCatalog || current == null || channels.isEmpty()) return
        handler.removeCallbacks(numberAction); digits = ""
        handler.removeCallbacks(hideBanner); handler.removeCallbacks(bannerTick)
        banner.visibility = View.GONE
        liveChannelsReturnFocus = currentFocus
        val playing=current
        if(playing?.isPersonal==true)liveChannels.showPersonal(repository.personal,lifecycleScope,playing,programmes,repository::favorites)
        else liveChannels.show(channelsForPlayback(), programmes, current?.id)
    }

    private fun closeLiveChannels(restoreFocus: Boolean = true) {
        if (!liveChannels.isOpen) return
        liveChannels.dismiss()
        val previous = liveChannelsReturnFocus
        liveChannelsReturnFocus = null
        if (restoreFocus) {
            if (failed) errorBox.requestFocus()
            else if (previous?.isShown != true || !previous.requestFocus()) root.requestFocus()
        }
    }

    private fun resumeLive() {
        if (!tvPlayer.resumeToLive()) current?.let(::selectChannel)
    }

    private fun showChannelBanner(reading: Boolean = false) {
        if(tvPlayer.snapshot.mode!=it.lumetv.core.PlaybackMode.LIVE){demandControls.render(tvPlayer.snapshot);return}
        if (current == null || current?.isRadio == true) return
        closeLiveChannels()
        channelBannerVisible = true
        updateChannelBanner()
        banner.resetDescriptionScroll()
        displayBanner(reading)
        handler.removeCallbacks(bannerTick); handler.post(bannerTick)
    }

    private fun updateChannelBanner() {
        current?.let { banner.bind(it, programmes[it.epgId].orEmpty(), repository.zapFavoritesOnly) }
    }

    private fun showBanner(title: String, subtitle: String) {
        closeLiveChannels()
        channelBannerVisible = false
        handler.removeCallbacks(bannerTick)
        banner.message(title, subtitle)
        displayBanner()
    }

    private fun displayBanner(reading: Boolean = false) {
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, if (reading && banner.hasDescription) 15_000 else 6_000)
    }

    private var reminderQuery:Job?=null
    private fun checkProgrammeReminders() {
        if(reminderQuery?.isActive==true || reminders.channelIds.isEmpty())return
        if (playlists.isShowing || catalogPanels.isShowing || catalogDialogs.any { it.isShowing }) return
        if (!catalogLoaded || !hasWindowFocus() || reminderDialog?.isShowing == true || exitDialog?.isShowing == true ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        reminderQuery=lifecycleScope.launch {
        val available=reminders.channelIds.mapNotNull {repository.find(it)}.associateBy {it.id}
        val due = reminders.takeDue(available.keys, System.currentTimeMillis()) ?: return@launch
        val channel = available[due.channelId] ?: return@launch
        val start = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(due.start))
        reminderDialog = android.app.AlertDialog.Builder(this@TvActivity)
            .setTitle(R.string.discovery_reminder_title)
            .setMessage(getString(R.string.discovery_reminder_message, due.title, channel.name, start))
            .setPositiveButton(R.string.discovery_watch) { _, _ -> selectChannel(channel) }
            .setNegativeButton(R.string.discovery_later, null).create().also { dialog ->
                dialog.setOnDismissListener { reminderDialog = null }
                dialog.show()
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).requestFocus()
            }
        }
    }

    private fun refreshCatalog(force: Boolean) {
        if (catalogJob?.isActive == true) return
        catalogJob = lifecycleScope.launch {
            if (force) syncStatus.text = "Aggiorno il catalogo…"
            try {
                repository.refresh(force)?.let { updated ->
                    channels = updated
                    current = updated.firstOrNull { it.id == current?.id } ?: current
                    if (showingCatalog) updateCatalog()
                    liveChannels.update(channelsForPlayback(), programmes, current?.id)
                }
                syncStatus.text = if (repository.lastUpdated > 0) "●  Catalogo aggiornato" else "Catalogo automatico"
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { syncStatus.text = "Catalogo salvato · Riproverò più tardi" }
        }
    }

    private fun updateGuideStatus() {
        val now = System.currentTimeMillis()
        val covered = channels.count { channel -> programmes[channel.epgId].orEmpty().any { it.end > now } }
        adapter.guidePlaceholder = if (guideLoading && covered == 0) "Carico la guida…" else "Guida assente per questo canale · OK per la diretta"
        if (currentFilter != "Guida TV" && currentFilter != "Cosa guardare") return
        val message = when {
            catalogSource!="builtin"->if(channels.filter {it.sourceId==catalogSource}.any {programmes[it.epgId].orEmpty().any {p->p.end>now}})"" else
                it.lumetv.app.ui.PersonalGuideFeedback.message(guide.personal.state.value.firstOrNull {it.sourceId==catalogSource},repository.personal.requestContext(catalogSource)?.settings?.guide?.enabled!=false)
            guideLoading -> "Aggiorno la guida TV…"
            covered > 0 -> ""
            guide.failure == "rate_limit" && guide.nextRetry > now -> "Il servizio richiede una pausa  ·  Nuovo tentativo alle ${SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(guide.nextRetry))}"
            guide.failure.isNotEmpty() -> "Guida temporaneamente non disponibile  ·  Premi ↻ per riprovare"
            else -> "La guida verrà aggiornata automaticamente  ·  ↻ Aggiorna ora"
        }
        guideView.setStatus(message)
        discoveryView.setStatus(message)
    }

    private fun refreshGuide(force: Boolean = false) {
        if (guideJob?.isActive == true || !catalogLoaded) return
        guideJob = lifecycleScope.launch {
            guideLoading = true
            updateGuideStatus()
            try { guide.refresh(channels, force)?.let { programmes = it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the previous programmes and let GuideSync schedule a retry. */ }
            finally {
                guideLoading = false
                personalGuideWindowKey=""
                if (showingCatalog) updateCatalog()
                else if (channelBannerVisible && banner.visibility == View.VISIBLE) updateChannelBanner()
                liveChannels.update(channelsForPlayback(), programmes, current?.id)
                guideView.reloadWindow()
            }
        }
    }

    private fun loadPersonalGuideWindow(rows:List<it.lumetv.core.Channel>,from:Long,until:Long) {
        if(!catalogLoaded)return
        val key=rows.joinToString {it.id}+from+":"+until
        if(key==personalGuideWindowKey)return
        personalGuideWindowKey=key
        personalGuideJob?.cancel()
        personalGuideJob=lifecycleScope.launch {
            val now=System.currentTimeMillis()
            val result=mergeProgrammeWindows(guide.window(rows,from,until),guide.personal.window(listOfNotNull(current),now,now+GuideSchedule.WINDOW))
            programmes=result
            if(showingCatalog) {
                if(currentFilter in setOf("Guida TV","Cosa guardare"))updateCatalog()
                else {
                    val scroll=grid.layoutManager?.onSaveInstanceState()
                    adapter.programmes=result;adapter.refreshProgrammes()
                    grid.layoutManager?.onRestoreInstanceState(scroll)
                }
            } else if(channelBannerVisible && tvPlayer.snapshot.mode==it.lumetv.core.PlaybackMode.LIVE)updateChannelBanner()
        }
    }
    private fun requestVisibleProgrammes() {
        if(!catalogLoaded || !showingCatalog || currentFilter in setOf("Guida TV","Cosa guardare") || !::grid.isInitialized)return
        val manager=grid.layoutManager as GridLayoutManager
        val first=manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last=manager.findLastVisibleItemPosition().coerceAtLeast(first+11)
        val rows=adapter.visibleRows(first,last.coerceAtMost(first+79))
        if(rows.none {it.isPersonal})return
        val now=System.currentTimeMillis()/1_800_000*1_800_000
        loadPersonalGuideWindow(rows,now,now+GuideSchedule.WINDOW)
    }

    private fun zap(direction: Int) {
        if(tvPlayer.snapshot.mode!=it.lumetv.core.PlaybackMode.LIVE){tvPlayer.seekBy(if(direction>0)30_000 else -10_000);return}
        lifecycleScope.launch {
        val before=current
        val next = repository.nextChannel(channels, current?.id, direction, current)
        if(current?.id!=before?.id)return@launch
        if (next != null) {
            if (next.id != current?.id || showingCatalog || failed) selectChannel(next)
            else showChannelBanner()
        } else {
            val message = "Nessun preferito · Tieni premuto OK su un canale per aggiungerlo"
            if (showingCatalog) status.text = message
            else showBanner("Nessun canale preferito", "Aggiungi un preferito dal catalogo o cambia la modalità in Opzioni")
        }
        }
    }

    private fun handleRemoteKey(event: KeyEvent): Boolean {
        if(::catalogPanels.isInitialized && catalogPanels.isShowing)return false
        if (::playlists.isInitialized && playlists.isShowing) return false
        if(event.action==KeyEvent.ACTION_DOWN && showingCatalog && !showingOptions && grid.visibility==View.VISIBLE) {
            val position=grid.findContainingViewHolder(currentFocus ?: grid)?.bindingAdapterPosition ?: -1
            if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && position>=0 && position%(grid.layoutManager as GridLayoutManager).spanCount==0) {
                catalogFocusPosition=position
                (if(sourceRow.visibility==View.VISIBLE)sourceButton else channelFilters.getChildAt(0) ?: tabs.getChildAt(0)).requestFocus()
                return true
            }
            if(event.keyCode==KeyEvent.KEYCODE_DPAD_DOWN && position<0 && catalogControls.hasFocus() && adapter.itemCount>0) {
                grid.scrollToPosition(catalogFocusPosition.coerceIn(0,adapter.itemCount-1))
                grid.post {grid.findViewHolderForAdapterPosition(catalogFocusPosition.coerceIn(0,adapter.itemCount-1))?.itemView?.requestFocus()}
                return true
            }
        }
        if (showingOptions) {
            if (event.action != KeyEvent.ACTION_DOWN) return false
            return when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { tvPlayer.togglePause(); true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { tvPlayer.resumeToLive(); true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { tvPlayer.pauseForCatalog(); true }
                KeyEvent.KEYCODE_MEDIA_STOP -> { tvPlayer.stop(); true }
                KeyEvent.KEYCODE_GUIDE -> { currentFilter = "Guida TV"; buildTabs(); showCatalog(); refreshGuide(); true }
                KeyEvent.KEYCODE_MENU -> { closeOptions(); true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_INFO -> true
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
                else -> false
            }
        }
        if (showingCatalog && currentFilter == "Guida TV" && guideView.handleKey(event)) return true
        if (showingCatalog && currentFilter == "Cosa guardare") {
            if (discoveryView.handleKey(event)) return true
            if (discoveryView.isEditing) return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if(!showingCatalog && tvPlayer.snapshot.mode!=it.lumetv.core.PlaybackMode.LIVE) {
            when(event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND->{tvPlayer.seekBy(-10_000);return true}
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD->{tvPlayer.seekBy(30_000);return true}
                KeyEvent.KEYCODE_DPAD_LEFT->if(!demandControls.hasFocus()){tvPlayer.seekBy(-10_000);return true}
                KeyEvent.KEYCODE_DPAD_RIGHT->if(!demandControls.hasFocus()){tvPlayer.seekBy(30_000);return true}
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER->if(!demandControls.hasFocus()){tvPlayer.togglePause();return true}
                KeyEvent.KEYCODE_DPAD_DOWN->if(!demandControls.hasFocus()){demandControls.requestFocus();return true}
            }
        }
        if (!showingCatalog && tvPlayer.snapshot.mode==it.lumetv.core.PlaybackMode.LIVE && current?.isRadio != true && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !liveChannels.isOpen) {
            if (event.repeatCount == 0) showLiveChannels()
            return true
        }
        if (liveChannels.isOpen && liveChannels.handleDirection(event.keyCode)) return true
        if (!showingCatalog && !failed && channelBannerVisible && banner.isVisible &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            banner.scrollDescription(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)) {
            displayBanner(reading = true)
            return true
        }
        if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && event.repeatCount == 0) {
            closeLiveChannels()
            digits = (digits + (event.keyCode - KeyEvent.KEYCODE_0)).take(6)
            handler.removeCallbacks(numberAction); handler.postDelayed(numberAction, 1_000)
            if (!showingCatalog) showBanner(digits, "Selezione canale…")
            else if (currentFilter == "Guida TV") guideView.showMessage("Canale $digits…")
            else if (currentFilter == "Cosa guardare") discoveryView.showMessage("Canale $digits…")
            else status.text = "Canale $digits…"
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (showingCatalog && currentFilter == "Guida TV") guideView.pageChannels(1)
                else if (showingCatalog && currentFilter == "Cosa guardare") discoveryView.page(1) else zap(1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (showingCatalog && currentFilter == "Guida TV") guideView.pageChannels(-1)
                else if (showingCatalog && currentFilter == "Cosa guardare") discoveryView.page(-1) else zap(-1)
                return true
            }
            KeyEvent.KEYCODE_GUIDE -> { currentFilter = "Guida TV"; buildTabs(); showCatalog(); refreshGuide(); return true }
            KeyEvent.KEYCODE_MENU -> { showCatalog(); return true }
            KeyEvent.KEYCODE_INFO -> { if (!showingCatalog && !failed) showChannelBanner(reading = true); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (tvPlayer.snapshot.channel != null) tvPlayer.togglePause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { tvPlayer.resumeToLive(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { tvPlayer.pauseForCatalog(); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { tvPlayer.stop(); showCatalog(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!showingCatalog && current?.isRadio != true && !failed && !liveChannels.isOpen) {
                if (!tvPlayer.isPlaying && !tvPlayer.snapshot.buffering) {
                    resumeLive()
                }
                showChannelBanner(reading = true); return true
            }
        }
        return false
    }
}
