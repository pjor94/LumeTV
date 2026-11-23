package it.lumetv.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.VideoArtworkRepository
import it.lumetv.app.data.VideoItem
import it.lumetv.app.data.VideoQuery
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Season and episode navigation inside the Android TV series page. */
internal class TvVideoEpisodes(
    activity: ComponentActivity,
    private val repository: PersonalPlaylistRepository,
    artwork: VideoArtworkRepository,
    private val scope: CoroutineScope,
    saved: Bundle?,
    openEpisode: (VideoItem) -> Unit,
    openSeasons: (VideoItem, (Int?) -> Unit) -> Unit,
) {
    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val manager = LinearLayoutManager(activity)
    private val list = RecyclerView(activity).apply {
        tag = "video_grid_results"
        layoutManager = manager
        itemAnimator = null
        clipChildren = false
        clipToPadding = false
        setPadding(0, activity.dp(2), 0, activity.dp(7))
    }
    private val status = TvStyle.text(activity, "Carico gli episodi…", 14f, TvStyle.muted)
    private val selector = VideoSeasonSelector(activity, false, ::selectSeason) {
        series?.let { openSeasons(it, ::selectSeason) }
    }
    private var series: VideoItem? = null
    private var seasonPage: Pair<List<Int>, Int>? = null
    private var choice: Int? = saved?.getInt("season")?.takeIf { saved.containsKey("season") }
    private var initialized = saved != null
    private var focusedKey = saved?.getString("focus_key")
    private var startPosition = saved?.getInt("position", 0) ?: 0
    private var startTop = saved?.getInt("top", 0) ?: 0
    private var resetAnchor = true
    private var focusFirst = false
    private var restoreFocused = saved?.getBoolean("list_focus") == true
    private var closed = false
    private var catalogKey: Pair<VideoQuery, Any>? = null
    private var decoration: Pair<Long, Long>? = null
    private var watchJob: Job? = null
    private val catalog = ContinuousCatalog<VideoItem>(scope, blockSize = 50, initialDelay = 0, changed = ::onChange)
    private val posters = VideoPosterAdapter(activity, false, artwork, 102, fixedWidth = false, wide = true,
        choose = { focusedKey = it.key; openEpisode(it) },
        focus = { focusedKey = it.key; focusFirst = false; restoreFocused = false },
        episodeRows = true).apply { catalog = this@TvVideoEpisodes.catalog }

    init {
        val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val heading = TvVideoStyle.title(activity, 22f, 1).apply { text = "Stagioni ed episodi" }
        row.addView(heading, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = activity.dp(14) })
        row.addView(selector, LinearLayout.LayoutParams(0, activity.dp(46), 1f))
        view.addView(row, LinearLayout.LayoutParams(-1, -2))
        view.addView(status, LinearLayout.LayoutParams(-1, -2))
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.adapter = posters
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recycler: RecyclerView, dx: Int, dy: Int) {
                catalog.viewport(manager.findFirstVisibleItemPosition(), manager.findLastVisibleItemPosition())
            }
        })
    }

    fun bind(item: VideoItem, target: VideoItem, seasons: Pair<List<Int>, Int>?, historyVersion: Long) {
        series = item
        if (seasons == null) return
        val previous = choice
        if (!initialized || choice != null && seasons.second <= seasons.first.size && choice !in seasons.first) {
            choice = VideoSeasonSelector.initialSeason(seasons.first, target)
            initialized = true
        }
        if (seasons != seasonPage || previous != choice) {
            seasonPage = seasons
            selector.submit(seasons, choice)
        }
        bindCatalog(historyVersion)
    }

    private fun selectSeason(season: Int?) {
        if (choice == season || closed) return
        choice = season
        startPosition = 0
        startTop = 0
        focusedKey = null
        focusFirst = true
        restoreFocused = false
        posters.restoreFocusKey = null
        catalogKey = null
        // The list receives focus after the first page has attached.
        seasonPage?.let { selector.submit(it, choice) }
        bindCatalog(decoration?.second ?: 0)
    }

    private fun bindCatalog(historyVersion: Long) {
        val item = series ?: return
        val query = VideoQuery(source = item.source, series = item.series, season = choice, collapseSeries = false)
        val key = query to repository.videoCacheKey(query)
        val nextDecoration = repository.videoFavoritesVersion.value to historyVersion
        if (catalogKey != null && catalogKey != key) {
            val first = manager.findFirstVisibleItemPosition()
            if (first >= 0) {
                startPosition = first
                startTop = manager.findViewByPosition(first)?.let { manager.getDecoratedTop(it) - list.paddingTop } ?: 0
            }
            restoreFocused = list.hasFocus()
        }
        val offset = startPosition / catalog.blockSize * catalog.blockSize
        val seed = repository.cachedVideos(query.copy(offset = offset, limit = catalog.blockSize))?.let { it.rows to it.total }
        val changed = catalog.bind(key, startPosition, seed) { start ->
            repository.videos(query.copy(offset = start, limit = catalog.blockSize)).let { it.rows to it.total }
        }
        if (changed) {
            resetAnchor = true
            if (seed == null) showStatus("Carico gli episodi…")
            posters.notifyDataSetChanged()
        } else if (decoration != nextDecoration) {
            watchJob?.cancel()
            watchJob = scope.launch { catalog.refreshLoaded() }
        }
        catalogKey = key
        decoration = nextDecoration
    }

    private fun onChange(change: ContinuousCatalog.Change) {
        list.post {
            if (closed) return@post
            val count = catalog.size
            if (count == 0) {
                val failed = catalog.hasError
                showStatus(if (failed) "Impossibile caricare gli episodi. Riprova" else "Nessun episodio in questa stagione.", failed)
            } else {
                showStatus("")
                if (focusFirst || restoreFocused) {
                    posters.restoreFocusKey = focusedKey ?: catalog.peek(0)?.key
                }
            }
            if (change.sizeChanged) posters.notifyDataSetChanged()
            else if (change.start < count)
                posters.notifyItemRangeChanged(change.start, change.count.coerceAtMost(count - change.start))
            if (resetAnchor && count > 0) {
                val position = catalog.indexOf { it.key == focusedKey } ?: startPosition.coerceIn(0, count - 1)
                manager.scrollToPositionWithOffset(position, if (position == startPosition) startTop else 0)
                resetAnchor = false
            }
        }
    }

    private fun showStatus(text: String, retry: Boolean = false) {
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        status.setOnClickListener(if (retry) View.OnClickListener { showStatus("Carico gli episodi…"); catalog.retry() } else null)
        status.isClickable = retry
        status.isFocusable = retry
        status.minimumHeight = if (retry) status.context.dp(42) else 0
    }

    fun restoreFocus() {
        if (focusedKey != null) {
            restoreFocused = true
            posters.restoreFocusKey = focusedKey
        }
    }

    fun hide() {
        catalog.pause()
        posters.pause(list)
        watchJob?.cancel()
    }

    fun show() {
        catalog.resume()
        posters.resume(list)
    }

    fun save(): Bundle? = if (!initialized) null else Bundle().apply {
        choice?.let { putInt("season", it) }
        putString("focus_key", focusedKey)
        putBoolean("list_focus", list.hasFocus() || focusedKey != null && !view.isShown)
        val position = manager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: startPosition
        putInt("position", position)
        putInt("top", manager.findViewByPosition(position)?.let { manager.getDecoratedTop(it) - list.paddingTop } ?: startTop)
    }

    fun close() {
        closed = true
        watchJob?.cancel()
        catalog.close()
        list.adapter = null
    }
}
