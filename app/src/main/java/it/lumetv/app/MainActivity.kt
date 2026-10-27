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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.*
import it.lumetv.app.playback.TvPlayer
import it.lumetv.app.playback.NetworkMonitor
import it.lumetv.app.playback.PlaybackFailure
import it.lumetv.app.playback.PlaybackRecovery
import it.lumetv.app.ui.ChannelAdapter
import it.lumetv.app.ui.CatalogBackdrop
import it.lumetv.app.ui.LiveBanner
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.update.UpdateController
import it.lumetv.core.Channel
import it.lumetv.core.ChannelNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val http = HttpClient()
    private lateinit var repository: ChannelRepository
    private lateinit var guide: GuideRepository
    private lateinit var resolver: StreamResolver
    private lateinit var tvPlayer: TvPlayer
    private lateinit var recovery: PlaybackRecovery
    private lateinit var network: NetworkMonitor
    private lateinit var video: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var catalog: FrameLayout
    private lateinit var grid: RecyclerView
    private lateinit var adapter: ChannelAdapter
    private lateinit var tabs: LinearLayout
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var heading: TextView
    private lateinit var clock: TextView
    private lateinit var syncStatus: TextView
    private lateinit var updateNotice: TextView
    private lateinit var updates: UpdateController
    private lateinit var banner: LiveBanner
    private var channelBannerVisible = false
    private lateinit var loading: LinearLayout
    private lateinit var loadingTitle: TextView
    private lateinit var errorBox: LinearLayout
    private val launchQueue = LaunchQueue()
    private var playerReady = false
    private var explicitRequestSeen = false
    private var launchMessage: String? = null
    private var playbackGeneration = 0L
    private var activePlaybackChannelId: String? = null
    private var integrationDialog: android.app.AlertDialog? = null
    private var resolveJob: Job? = null
    private var guideJob: Job? = null
    private var catalogJob: Job? = null
    private var catalogLoaded = false
    private var guideLoading = false
    private var channels = emptyList<Channel>()
    private var programmes = emptyMap<String, List<Programme>>()
    private var current: Channel? = null
    private var currentFilter = "Tutti"
    private var showingCatalog = true
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
        val channel = number?.let { ChannelNavigation.byNumber(channels, it) }
        if (channel != null) selectChannel(channel) else showBanner("Canale $number non presente", "Scegli un canale dall’elenco")
    }
    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date())
            handler.postDelayed(this, 30_000)
        }
    }
    private val catalogTick = object : Runnable {
        override fun run() {
            if (catalogLoaded) { refreshCatalog(false); refreshGuide(); updates.checkAutomatically() }
            handler.postDelayed(this, 15 * 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        repository = ChannelRepository(this, http)
        guide = GuideRepository(this, http)
        resolver = StreamResolver(http)
        tvPlayer = TvPlayer(this, http)
        network = NetworkMonitor(this) { online -> recovery.onNetworkChanged(online) }
        recovery = PlaybackRecovery(lifecycleScope, { network.isOnline }, { openCurrentChannel(isRetry = true) },
            onWaiting = { showRecoveryStatus("Connessione assente · Riprendo quando torna la rete…") },
            onScheduled = { attempt, maximum -> showRecoveryStatus("Riconnetto la diretta… Tentativo $attempt di $maximum") },
            onExhausted = ::showError)
        currentFilter = savedInstanceState?.getString("filter") ?: "Tutti"
        buildUi()
        updates = UpdateController(this) { version ->
            updateNotice.contentDescription = "Aggiorna Lume TV alla versione $version"
            updateNotice.visibility = if (version != null) View.VISIBLE else View.GONE
        }
        if (savedInstanceState?.getBoolean("explicit_consumed") != true && intent.action == Intent.ACTION_VIEW) {
            explicitRequestSeen = true
            launchQueue.replace(LaunchRequest.parse(intent.dataString))
        }
        tvPlayer.onBuffering = {
            if (!showingCatalog && !failed) { loading.visibility = View.VISIBLE; recovery.onBuffering() }
        }
        tvPlayer.onPlayingChanged = { playing ->
            if (!showingCatalog && !failed && playing && tvPlayer.isPlaying) {
                loading.visibility = View.GONE
                current?.takeIf { it.id == activePlaybackChannelId }?.let(repository::recordPlayback)
                recovery.onPlaying()
            } else recovery.onNotPlaying()
        }
        tvPlayer.onError = { error -> if (!showingCatalog) recovery.onFailure(error) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!showingCatalog) showCatalog()
                else if (current != null && !failed) {
                    showingCatalog = false; catalog.visibility = View.GONE; video.visibility = View.VISIBLE
                    resumeLive()
                    showChannelBanner()
                } else finish()
            }
        })
        lifecycleScope.launch {
            channels = repository.local()
            catalogLoaded = true
            programmes = guide.local()
            updateCatalog()
            if (launchQueue.pending != null) {
                consumeLaunchRequest()
            } else if (!explicitRequestSeen) {
                val previous = savedInstanceState?.getString("channel") ?: repository.lastChannelId
                current = channels.firstOrNull { it.id == previous }
                showingCatalog = savedInstanceState?.getBoolean("catalog") ?: (current == null)
                if (current != null && !showingCatalog && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) selectChannel(current!!)
                else focusFirstChannel()
            }
            refreshCatalog(false)
            refreshGuide()
        }
    }

    override fun onStart() {
        super.onStart()
        updates.onStart()
        tvPlayer.attach(video)
        playerReady = true
        network.start()
        handler.post(tick)
        handler.post(catalogTick)
        if (!consumeLaunchRequest() && current != null && !showingCatalog) selectChannel(current!!)
    }

    override fun onStop() {
        updates.onStop()
        playerReady = false
        recovery.stop()
        network.stop()
        resolveJob?.cancel(); guideJob?.cancel(); catalogJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        digits = ""
        playbackGeneration++
        activePlaybackChannelId = null
        tvPlayer.release()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("channel", current?.id)
        outState.putString("filter", currentFilter)
        outState.putBoolean("catalog", showingCatalog)
        outState.putBoolean("explicit_consumed", explicitRequestSeen && launchQueue.pending == null)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            explicitRequestSeen = true
            launchQueue.replace(LaunchRequest.parse(intent.dataString))
            integrationDialog?.dismiss()
            recovery.stop()
            resolveJob?.cancel()
            playbackGeneration++
            activePlaybackChannelId = null
            tvPlayer.stop()
            current = null
            showingCatalog = true
            consumeLaunchRequest()
        }
    }

    private fun consumeLaunchRequest(): Boolean {
        val request = launchQueue.consume(catalogLoaded && playerReady) ?: return false
        when (request) {
            is LaunchRequest.ChannelId -> {
                val channel = channels.firstOrNull { it.id == request.id }
                if (channel != null) selectChannel(channel)
                else showRequestedCatalog("Tutti", "Canale non più disponibile · Scegli un canale dal catalogo")
            }
            LaunchRequest.Last -> {
                val channel = channels.firstOrNull { it.id == repository.lastChannelId }
                if (channel != null) selectChannel(channel)
                else showRequestedCatalog("Tutti", "Nessun ultimo canale disponibile · Scegli un canale")
            }
            LaunchRequest.Catalog -> showRequestedCatalog("Tutti")
            LaunchRequest.Favorites -> showRequestedCatalog("Preferiti")
            LaunchRequest.Guide -> showRequestedCatalog("Guida TV")
            LaunchRequest.Integration -> { showRequestedCatalog("Tutti"); showIntegrationOptions() }
            LaunchRequest.Invalid -> showRequestedCatalog("Tutti", "Collegamento non valido · Scegli un canale dal catalogo")
        }
        return true
    }

    private fun showRequestedCatalog(filter: String, message: String? = null) {
        playbackGeneration++
        activePlaybackChannelId = null
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
            }.setNegativeButton("Chiudi", null).show()
    }

    override fun onDestroy() {
        integrationDialog?.dismiss()
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
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val brand = ImageView(this).apply {
            setImageResource(R.drawable.lume_wordmark); scaleType = ImageView.ScaleType.FIT_START
            contentDescription = "Lume TV"
        }
        top.addView(brand, LinearLayout.LayoutParams(dp(112), dp(44)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = TvStyle.rounded(0xB21C2028.toInt(), dp(28).toFloat(), 0x14FFFFFF, dp(1))
        }
        top.addView(tabs, LinearLayout.LayoutParams(-2, dp(50)))
        buildTabs()
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        clock = TvStyle.text(this, "", 16f, TvStyle.muted).apply { fontFeatureSettings = "tnum"; gravity = Gravity.END }
        top.addView(clock, LinearLayout.LayoutParams(dp(62), -2))
        content.addView(top)
        heading = TvStyle.text(this, "La TV, fatta semplice.", 35f, bold = true)
        content.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(23) })
        status = TvStyle.text(this, "Carico i tuoi canali…", 13f, TvStyle.muted)
        content.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7); bottomMargin = dp(16) })
        val gridArea = FrameLayout(this)
        adapter = ChannelAdapter(repository::isFavorite, ::selectChannel) { channel ->
            val added = repository.toggleFavorite(channel)
            updateCatalog()
            status.text = if (added) "${channel.name} aggiunto ai preferiti" else "${channel.name} rimosso dai preferiti"
        }
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = this@MainActivity.adapter
            clipToPadding = false; clipChildren = false
            setPadding(0, dp(5), 0, dp(8))
            itemAnimator = null
        }
        gridArea.addView(grid, FrameLayout.LayoutParams(-1, -1))
        empty = TvStyle.text(this, "Tieni premuto OK su un canale per aggiungerlo ai preferiti.", 18f, TvStyle.muted).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        gridArea.addView(empty, FrameLayout.LayoutParams(-1, -1))
        content.addView(gridArea, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        footer.addView(TvStyle.text(this, "OK  Guarda     /     OK prolungato  Preferiti     /     CH +/−  Cambia", 11f, TvStyle.muted), LinearLayout.LayoutParams(0, -2, 1f))
        updateNotice = TvStyle.button(this, "Aggiorna Lume TV") { updates.show() }.apply { visibility = View.GONE }
        footer.addView(updateNotice, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) })
        syncStatus = TvStyle.text(this, "Catalogo automatico", 10f, TvStyle.muted)
        footer.addView(syncStatus)
        content.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        catalog.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(catalog, FrameLayout.LayoutParams(-1, -1))

        banner = LiveBanner(this).apply { visibility = View.GONE }
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
        setContentView(root)
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        listOf("Tutti", "Rai", "Mediaset", "Preferiti", "Guida TV").forEach { label ->
            val tab = TvStyle.button(this, label, currentFilter == label) {
                launchMessage = null
                currentFilter = label
                buildTabs(); updateCatalog(); focusFirstChannel()
                if (label == "Guida TV") refreshGuide()
            }
            tabs.addView(tab, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(2) })
        }
        val refresh = TvStyle.button(this, "↻", false) { refreshCatalog(true); refreshGuide(true) }.apply { contentDescription = "Aggiorna canali e guida TV" }
        refresh.setPadding(dp(13), dp(8), dp(13), dp(8))
        tabs.addView(refresh, LinearLayout.LayoutParams(dp(42), -1))
        tabs.addView(TvStyle.button(this, "Opzioni") { showOptions() }.apply { setPadding(dp(13), dp(8), dp(13), dp(8)) }, LinearLayout.LayoutParams(-2, -1))
    }

    private fun showOptions() {
        val mode = if (repository.zapFavoritesOnly) "solo preferiti" else "tutti i canali"
        android.app.AlertDialog.Builder(this).setTitle("Opzioni")
            .setItems(arrayOf("CH+/CH−: $mode", "Collegamento con AURA", "Aggiornamenti", "Informazioni", "Licenze e avvisi")) { _, index ->
                when (index) { 0 -> showZappingOptions(); 1 -> showIntegrationOptions(); 2 -> updates.show(); 3 -> showAbout(); 4 -> showLicenses() }
            }.setNegativeButton("Chiudi", null).show()
    }

    private fun showZappingOptions() {
        android.app.AlertDialog.Builder(this).setTitle("Cambio canale con CH+/CH−")
            .setSingleChoiceItems(arrayOf("Tutti i canali", "Solo preferiti"), if (repository.zapFavoritesOnly) 1 else 0) { dialog, selected ->
                repository.zapFavoritesOnly = selected == 1
                status.text = if (selected == 1) "CH+/CH− scorre i preferiti · OK prolungato per aggiungere un canale"
                    else "CH+/CH− scorre tutti i canali"
                dialog.dismiss()
            }.setNegativeButton("Annulla", null).show()
    }

    private fun showAbout() {
        val updated = if (repository.lastUpdated > 0) SimpleDateFormat("d MMM, HH:mm", Locale.ITALY).format(Date(repository.lastUpdated)) else "lista iniziale inclusa"
        val guideUpdated = if (guide.lastUpdated > 0) SimpleDateFormat("d MMM, HH:mm", Locale.ITALY).format(Date(guide.lastUpdated)) else "in attesa del primo aggiornamento"
        android.app.AlertDialog.Builder(this).setTitle("Lume TV · ${BuildConfig.VERSION_NAME}")
            .setMessage("La TV, fatta semplice.\n\nCatalogo automatico ogni 24 ore: $updated.\nGuida automatica ogni 6 ore: $guideUpdated.\n↻ aggiorna canali e guida.\n\nIntegrazioni adattate da WorldLiveTV, WLTV Group; modifiche Lume TV del 25 ottobre 2026. Software libero, GPL-3.0-or-later: puoi ridistribuirlo e modificarlo secondo la licenza. Nessuna garanzia, nei limiti di legge.\n\nLe trasmissioni sono fornite dalle rispettive emittenti. Sorgenti e segnalazioni: github.com/pjor94/LumeTV.")
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Licenze e avvisi") { _, _ -> showLicenses() }.show()
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
        android.app.AlertDialog.Builder(this).setTitle("Licenze e avvisi")
            .setItems(titles) { _, index ->
                val text = TvStyle.text(this, assets.open(documents.getValue(titles[index])).bufferedReader().use { it.readText() }, 13f).apply {
                    setPadding(dp(24), dp(16), dp(24), dp(16))
                }
                val scroll = android.widget.ScrollView(this).apply { addView(text); isFocusable = true }
                android.app.AlertDialog.Builder(this).setTitle(titles[index]).setView(scroll)
                    .setPositiveButton("Chiudi", null).show()
            }.setPositiveButton("Chiudi", null).show()
    }

    private fun updateCatalog() {
        val visible = when (currentFilter) {
            "Rai", "Mediaset" -> channels.filter { it.family == currentFilter }
            "Preferiti" -> channels.filter(repository::isFavorite)
            else -> channels
        }
        val guideMode = currentFilter == "Guida TV"
        val modeChanged = adapter.guideMode != guideMode
        adapter.guideMode = guideMode
        adapter.items = visible
        adapter.programmes = programmes
        adapter.currentId = current?.id
        if (modeChanged) grid.recycledViewPool.clear()
        (grid.layoutManager as GridLayoutManager).spanCount = if (guideMode) 1 else 4
        adapter.notifyDataSetChanged()
        heading.text = when (currentFilter) { "Guida TV" -> "Ora. E tra poco."; "Preferiti" -> "I tuoi preferiti."; "Rai" -> "Il mondo Rai."; "Mediaset" -> "Il mondo Mediaset."; else -> "La TV, fatta semplice." }
        status.text = "${visible.size} canali  ·  ${if (currentFilter in setOf("Rai", "Mediaset")) "Tutti i canali $currentFilter della lista WLTV" else "Scegli un canale. Mettiti comodo."}"
        updateGuideStatus()
        launchMessage?.let { status.text = it }
        syncStatus.text = if (repository.lastUpdated > 0) "●  Catalogo aggiornato" else "Catalogo automatico"
        empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        if (showingCatalog && (currentFocus == null || currentFocus === root)) focusFirstChannel()
    }

    private fun focusFirstChannel() {
        if (adapter.items.isEmpty()) {
            val index = listOf("Tutti", "Rai", "Mediaset", "Preferiti", "Guida TV").indexOf(currentFilter).coerceAtLeast(0)
            tabs.getChildAt(index)?.requestFocus()
            return
        }
        if (adapter.items.isNotEmpty()) grid.post {
            val position = adapter.items.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
            grid.scrollToPosition(position)
            grid.post { grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: grid.requestFocus() }
        }
    }

    private fun selectChannel(channel: Channel) {
        launchMessage = null
        recovery.start()
        resolveJob?.cancel()
        handler.removeCallbacks(numberAction); digits = ""
        current = channel; showingCatalog = false; failed = false
        catalog.visibility = View.GONE; video.visibility = View.VISIBLE; errorBox.visibility = View.GONE
        root.requestFocus()
        showChannelBanner()
        openCurrentChannel()
    }

    private fun openCurrentChannel(isRetry: Boolean = false) {
        val channel = current ?: return
        val generation = ++playbackGeneration
        resolveJob?.cancel()
        activePlaybackChannelId = null
        tvPlayer.stop()
        if (!isRetry) loadingTitle.text = channel.name
        loading.visibility = View.VISIBLE
        recovery.onBuffering()
        resolveJob = lifecycleScope.launch {
            try {
                val stream = resolver.resolve(channel)
                ensureActive()
                if (generation != playbackGeneration || current?.id != channel.id || showingCatalog ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                activePlaybackChannelId = channel.id
                tvPlayer.play(stream)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { if (generation == playbackGeneration) recovery.onFailure(PlaybackFailure.fromSource(error)) }
        }
    }

    private fun showRecoveryStatus(message: String) {
        resolveJob?.cancel()
        tvPlayer.stop()
        loadingTitle.text = message
        loading.visibility = View.VISIBLE
    }

    private fun showCatalog() {
        recovery.stop()
        resolveJob?.cancel()
        handler.removeCallbacks(hideBanner); handler.removeCallbacks(bannerTick); handler.removeCallbacks(numberAction); digits = ""
        tvPlayer.pauseForCatalog()
        showingCatalog = true
        banner.visibility = View.GONE; loading.visibility = View.GONE; errorBox.visibility = View.GONE
        video.visibility = View.INVISIBLE; catalog.visibility = View.VISIBLE
        updateCatalog(); focusFirstChannel()
    }

    private fun showError(message: String) {
        recovery.stop()
        resolveJob?.cancel()
        tvPlayer.stop()
        failed = true; loading.visibility = View.GONE; banner.visibility = View.GONE
        errorBox.removeAllViews()
        errorBox.addView(TvStyle.text(this, current?.name.orEmpty(), 26f, bold = true))
        errorBox.addView(TvStyle.text(this, message, 17f, TvStyle.muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(22) })
        val buttons = LinearLayout(this)
        val retry = TvStyle.button(this, "Riprova") { current?.let(::selectChannel) }
        buttons.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        buttons.addView(TvStyle.button(this, "Scegli un canale") { showCatalog() })
        errorBox.addView(buttons)
        errorBox.visibility = View.VISIBLE
        retry.requestFocus()
    }

    private fun resumeLive() {
        recovery.start()
        if (!tvPlayer.resumeToLive()) current?.let(::selectChannel)
        else if (tvPlayer.isPlaying) recovery.onPlaying()
        else { loading.visibility = View.VISIBLE; recovery.onBuffering() }
    }

    private fun showChannelBanner() {
        if (current == null) return
        channelBannerVisible = true
        updateChannelBanner()
        displayBanner()
        handler.removeCallbacks(bannerTick); handler.post(bannerTick)
    }

    private fun updateChannelBanner() {
        current?.let { banner.bind(it, programmes[it.epgId].orEmpty(), repository.zapFavoritesOnly) }
    }

    private fun showBanner(title: String, subtitle: String) {
        channelBannerVisible = false
        handler.removeCallbacks(bannerTick)
        banner.message(title, subtitle)
        displayBanner()
    }

    private fun displayBanner() {
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner); handler.postDelayed(hideBanner, 6_000)
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
        if (currentFilter != "Guida TV") return
        status.text = when {
            guideLoading -> "Aggiorno la guida TV…"
            covered > 0 -> "$covered di ${channels.size} canali con guida  ·  Ora e prossimo programma"
            guide.failure == "rate_limit" && guide.nextRetry > now -> "Il servizio richiede una pausa  ·  Nuovo tentativo alle ${SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(guide.nextRetry))}"
            guide.failure.isNotEmpty() -> "Guida temporaneamente non disponibile  ·  Premi ↻ per riprovare"
            else -> "La guida verrà aggiornata automaticamente  ·  ↻ Aggiorna ora"
        }
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
                if (showingCatalog) updateCatalog()
                else if (channelBannerVisible && banner.visibility == View.VISIBLE) updateChannelBanner()
            }
        }
    }

    private fun zap(direction: Int) {
        val next = repository.nextChannel(channels, current?.id, direction)
        if (next != null) {
            if (next.id != current?.id || showingCatalog || failed) selectChannel(next)
            else showChannelBanner()
        } else {
            val message = "Nessun preferito · Tieni premuto OK su un canale per aggiungerlo"
            if (showingCatalog) status.text = message
            else showBanner("Nessun canale preferito", "Aggiungi un preferito dal catalogo o cambia la modalità in Opzioni")
        }
    }

    private fun handleRemoteKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && event.repeatCount == 0) {
            digits = (digits + (event.keyCode - KeyEvent.KEYCODE_0)).takeLast(4)
            handler.removeCallbacks(numberAction); handler.postDelayed(numberAction, 1_000)
            if (!showingCatalog) showBanner(digits, "Selezione canale…")
            else status.text = "Canale $digits…"
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
            KeyEvent.KEYCODE_GUIDE -> { currentFilter = "Guida TV"; buildTabs(); showCatalog(); refreshGuide(); return true }
            KeyEvent.KEYCODE_MENU -> { showCatalog(); return true }
            KeyEvent.KEYCODE_INFO -> { if (!showingCatalog && !failed) showChannelBanner(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (!showingCatalog && !failed && !recovery.isRecovering) {
                    if (tvPlayer.togglePause()) resumeLive()
                    else { recovery.stop(); resolveJob?.cancel(); loading.visibility = View.GONE }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!showingCatalog && !failed) {
                if (!tvPlayer.isPlaying && !recovery.isRecovering) {
                    resumeLive()
                }
                showChannelBanner(); return true
            }
        }
        return false
    }
}
