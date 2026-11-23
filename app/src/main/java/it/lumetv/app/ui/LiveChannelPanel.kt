package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.Programme
import it.lumetv.app.R
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PersonalCatalogQuery

/** A focus-contained channel browser above the existing, uninterrupted player. */
@SuppressLint("ViewConstructor")
class LiveChannelPanel(
    context: Context,
    logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    choose: (Channel) -> Unit,
    private val close: () -> Unit,
    private val scope: CoroutineScope,
    readProgrammes: suspend (List<Channel>, Long) -> Map<String, Programme>,
) : FrameLayout(context) {
    private val guide = LiveProgrammeLoader(scope, readProgrammes, ::updateGuideRows)
    private val channels = LiveChannelAdapter(logos, favorite, choose, guide::result)
    private val list = RecyclerView(context)
    private val count = TvStyle.text(context, "", 12f, LiveOverlayStyle.secondary).apply { fontFeatureSettings = "tnum" }
    private val empty = TvStyle.text(context, "", 15f, LiveOverlayStyle.secondary)
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(13), context.dp(15), context.dp(13), context.dp(12))
        background = LiveOverlayStyle.material(context)
        visibility = GONE
        setOnClickListener { }
        isFocusable = false
        ViewCompat.setAccessibilityPaneTitle(this, "Canali in diretta")
    }
    private var allChannels = emptyList<Channel>()
    private var programmes = emptyMap<String, List<Programme>>()
    private var currentId: String? = null
    private var favoritesOnly = false
    private var focusGeneration = 0
    private var pendingFocusId: String? = null
    private var personal:PersonalPlaylistRepository?=null
    private var playing:Channel?=null
    private var personalFavorites:(()->Set<String>)?=null
    private var personalCatalog:ContinuousCatalog<Channel>?=null
    private var positionJob:Job?=null
    private var pendingCatalogFocus:Int?=null
    private var scrollDirection = 1
    private val allTab = filter("Tutti", false)
    private val favoritesTab = filter("Preferiti", true)
    private val layout get() = list.layoutManager as LinearLayoutManager
    var isOpen = false
        private set

    init {
        visibility = GONE
        isFocusable = false
        // Outside the floating surface, the picture retains its original brightness.
        setOnClickListener { close() }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(TvStyle.text(context, "Canali", 25f, bold = true).apply {
            letterSpacing = -0.025f
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(count)
        panel.addView(heading, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = context.dp(5); marginEnd = context.dp(5)
        })
        val filters = LinearLayout(context).apply {
            clipChildren = false; clipToPadding = false
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            background = TvStyle.rounded(0x60091221, context.dp(20).toFloat())
        }
        filters.addView(allTab, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(3) })
        filters.addView(favoritesTab, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(filters, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        val body = FrameLayout(context)
        list.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = channels
            itemAnimator = null
            isFocusable = false
            clipToPadding = false
            setPadding(context.dp(4), context.dp(3), context.dp(4), context.dp(3))
            isVerticalScrollBarEnabled = false
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0) scrollDirection = if (dy > 0) 1 else -1
                    updatePersonalViewport()
                }
            })
        }
        body.addView(list, LayoutParams(-1, -1))
        empty.apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
        }
        body.addView(empty, LayoutParams(-1, -1))
        panel.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(8) })
        panel.addView(TvStyle.text(context, "←  Filtri     ·     OK  Guarda", 12f, LiveOverlayStyle.secondary).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(7); marginStart = context.dp(5) })
        addView(panel, LayoutParams(-2, -1, Gravity.START))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
        val inset = context.dp(if (MeasureSpec.getSize(heightMeasureSpec) < context.dp(460)) 18 else 24)
        val available = (containerWidth - inset * 2).coerceAtLeast(0)
        (panel.layoutParams as LayoutParams).apply {
            width = minOf(available, context.dp(410), maxOf(context.dp(280), (containerWidth * .35).toInt()))
            setMargins(inset, inset, inset, inset)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updatePersonalViewport() {
        val model = personalCatalog ?: return
        val first = layout.findFirstVisibleItemPosition()
        val last = layout.findLastVisibleItemPosition()
        if (first >= 0 && last >= first) model.viewport(first, last)
        requestGuide()
    }

    private fun requestGuide(target: Int? = pendingCatalogFocus) {
        val model = personalCatalog?.takeIf { it.size > 0 } ?: return
        val firstVisible = layout.findFirstVisibleItemPosition()
        val lastVisible = layout.findLastVisibleItemPosition()
        val focused = list.findFocus()?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition }
            ?.takeIf { it != RecyclerView.NO_POSITION }
        val position = (target ?: focused ?: firstVisible.takeIf { it >= 0 } ?: model.anchor).coerceIn(0, model.size - 1)
        val hasViewport = firstVisible >= 0 && position in firstVisible..lastVisible
        val first = if (hasViewport) firstVisible else position
        val last = minOf(if (hasViewport) lastVisible else position + 5, model.size - 1)
        val lookBehind = if (scrollDirection < 0) 12 else 5
        val lookAhead = if (scrollDirection > 0) 12 else 5
        val before = first - 1 downTo maxOf(0, first - lookBehind)
        val after = last + 1..minOf(model.size - 1, last + lookAhead)
        val positions = if (scrollDirection > 0) listOf(first..last, after, before) else listOf(first..last, before, after)
        guide.request(buildList {
            model.peek(position)?.let(::add)
            positions.forEach { range -> range.forEach { model.peek(it)?.let(::add) } }
        })
    }

    private fun updateGuideRows(ids: Set<String>) {
        list.post {
            val model = personalCatalog?.takeIf { isOpen } ?: return@post
            ids.forEach { epgId -> model.indexOf { it.epgId == epgId }?.let(channels::refreshProgramme) }
        }
    }

    private fun open() {
        isOpen = true
        isClickable = true
        visibility = VISIBLE
        panel.descendantFocusability = FOCUS_AFTER_DESCENDANTS
        LiveOverlayStyle.reveal(panel, fromX = -context.dp(22))
    }

    private fun filter(label: String, favorites: Boolean) = LiveOverlayStyle.button(context, label) {
        if (favoritesOnly != favorites) {
            favoritesOnly = favorites
            updateFilters()
            showChannels(focusRows = true)
        } else focusChannel(currentId)
    }.apply {
        id = View.generateViewId()
        minHeight = context.dp(33)
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
    }

    private fun updateFilters() {
        allTab.isSelected = !favoritesOnly
        favoritesTab.isSelected = favoritesOnly
    }

    fun show(items: List<Channel>, programmes: Map<String, List<Programme>>, currentId: String?) {
        clearPersonal()
        allChannels = items
        this.programmes = programmes
        this.currentId = currentId
        favoritesOnly = false
        open()
        updateFilters()
        showChannels(focusRows = true)
    }

    fun dismiss(animated: Boolean) {
        focusGeneration++
        pendingFocusId = null
        list.stopScroll()
        isOpen = false
        isClickable = false
        panel.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        clearFocus()
        clearPersonal()
        LiveOverlayStyle.conceal(panel, toX = -context.dp(22), animated = animated) {
            visibility = GONE
        }
    }

    internal fun showPersonal(repository: PersonalPlaylistRepository, current: Channel, favorites: () -> Set<String>) {
        clearPersonal();personal=repository;playing=current;currentId=current.id
        personalFavorites=favorites
        scrollDirection = 1
        favoritesOnly=false;open();updateFilters()
        lateinit var model: ContinuousCatalog<Channel>
        model = ContinuousCatalog(scope, 50, initialDelay = 0) { change -> list.post {
            if (isOpen && personalCatalog === model) {
                requestGuide()
                val scroll=layout.onSaveInstanceState()
                if(change.sizeChanged)channels.notifyDataSetChanged() else if(change.start<model.size)channels.notifyItemRangeChanged(change.start,change.count.coerceAtMost(model.size-change.start))
                if(!change.sizeChanged)layout.onRestoreInstanceState(scroll)
                count.text=resources.getQuantityString(R.plurals.live_channel_count, model.size, model.size)
                empty.isVisible = model.size == 0
                list.isVisible = model.size != 0
                empty.text=when {model.loading->"Carico i canali…";model.hasError->"Impossibile caricare. Premi OK per riprovare.";favoritesOnly->"Nessun preferito in questa lista.";else->"Nessun canale disponibile."}
                empty.setOnClickListener(if (model.hasError) View.OnClickListener { model.retry() } else null)
                empty.isFocusable = model.hasError
                empty.isClickable = model.hasError
                if(change.sizeChanged)pendingCatalogFocus?.let {focusPosition(it)}
                updatePersonalViewport()
            }
        }}
        personalCatalog = model
        channels.catalog = model
        showChannels(focusRows = true)
    }
    private fun clearPersonal() {
        guide.stop()
        positionJob?.cancel();positionJob=null;personalCatalog?.close();personalCatalog=null;channels.catalog=null
        personal=null;personalFavorites=null;playing=null;pendingCatalogFocus=null
    }

    fun update(items: List<Channel>, programmes: Map<String, List<Programme>>, currentId: String?) {
        if (!isOpen) return
        allChannels = items
        this.programmes = programmes
        this.currentId = currentId
        showChannels()
    }

    fun invalidatePersonalGuide() = guide.invalidate()

    private fun showChannels(focusRows: Boolean = false) {
        val repository=personal;val model=personalCatalog;val current=playing
        if(repository!=null && model!=null && current!=null) {
            channels.submit(emptyList(), emptyMap(), currentId)
            val selected=if(favoritesOnly)personalFavorites?.invoke().orEmpty() else null
            val query=PersonalCatalogQuery(source=current.sourceId,kind=current.kind,ids=selected,limit=50,favoriteOrder=favoritesOnly)
            positionJob?.cancel()
            positionJob=scope.launch {
                val position=if(focusRows)repository.catalogPosition(current,selected) else model.anchor
                pendingCatalogFocus=position.takeIf {focusRows}
                if (model.bind(Triple(query, repository.version.value, it.lumetv.app.data.ChannelPersonalization.get(context).revision.value), position) { offset ->
                    repository.catalog(query.copy(offset = offset)).let { it.rows to it.total }
                }) {
                    guide.stop()
                    count.text = ""
                    empty.text = "Carico i canali…"
                    empty.isVisible = true
                    empty.isFocusable = false
                    empty.isClickable = false
                    list.isVisible = false
                    channels.notifyDataSetChanged()
                }
                if(model.size>0 && focusRows)focusPosition(position)
            }
            return
        }
        val focusedId = list.findFocus()?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition }
            ?.takeIf { it != RecyclerView.NO_POSITION }?.let { channels.items.getOrNull(it)?.id }
        val personalization=it.lumetv.app.data.ChannelPersonalization.get(context)
        val visible=personalization.visible(allChannels)
        channels.submit(if (favoritesOnly) personalization.ordered(visible.filter(favorite)) else visible, programmes, currentId)
        count.text = resources.getQuantityString(R.plurals.live_channel_count, channels.itemCount, channels.itemCount)
        empty.isVisible = channels.items.isEmpty()
        list.isVisible = channels.items.isNotEmpty()
        empty.text = if (favoritesOnly) "Nessun preferito in questa lista." else "Nessun canale disponibile."
        empty.setOnClickListener(null)
        empty.isFocusable = false
        empty.isClickable = false
        if (focusRows) focusChannel(currentId)
        else if (focusedId != null && channels.items.none { it.id == focusedId }) focusChannel(currentId)
    }

    private fun focusChannel(id: String?, anchor: Boolean = true) {
        personalCatalog?.let {model->focusPosition(model.indexOf {it.id==id} ?: pendingCatalogFocus ?: model.anchor,anchor);return}
        val generation = ++focusGeneration
        pendingFocusId = null
        if (channels.items.isEmpty()) {
            (if (favoritesOnly) favoritesTab else allTab).requestFocus()
            return
        }
        val position = channels.items.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val holder = list.findViewHolderForAdapterPosition(position)
        // Reopening or leaving the filters often targets a row that is already laid out.
        // Focus it immediately, so the next remote event can keep navigating that row.
        if (holder?.itemId == channels.getItemId(position) && holder.itemView.isShown &&
            holder.itemView.top >= list.paddingTop && holder.itemView.bottom <= list.height - list.paddingBottom) {
            holder.itemView.requestFocus()
            return
        }
        pendingFocusId = channels.items[position].id
        // Layout the anchor before requesting focus; off-screen/current rows may not exist yet.
        list.doOnNextLayout {
            if (isOpen && generation == focusGeneration) {
                pendingFocusId = null
                list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
        if (anchor) layout.scrollToPositionWithOffset(position, context.dp(4))
        else layout.scrollToPosition(position)
    }

    private fun focusPosition(value:Int,anchor:Boolean=true) {
        if(channels.itemCount==0){(if(favoritesOnly)favoritesTab else allTab).requestFocus();return}
        val position=value.coerceIn(0,channels.itemCount-1)
        personalCatalog?.ensure(position);pendingCatalogFocus=position
        requestGuide(position)
        val generation=++focusGeneration
        list.findViewHolderForAdapterPosition(position)?.takeIf {it.itemView.isShown && it.itemView.top>=list.paddingTop && it.itemView.bottom<=list.height-list.paddingBottom}?.let {
            pendingCatalogFocus=null;it.itemView.requestFocus();return
        }
        list.doOnNextLayout {if(isOpen && generation==focusGeneration) {
            list.findViewHolderForAdapterPosition(position)?.itemView?.let {pendingCatalogFocus=null;it.requestFocus()}
        }}
        if(anchor)layout.scrollToPositionWithOffset(position,context.dp(4)) else layout.scrollToPosition(position)
    }

    fun refreshProgrammes() {
        if (!isOpen) return
        if (personalCatalog != null) requestGuide() else channels.refreshProgrammes()
    }

    fun refreshLogos() { if (isOpen) channels.refreshLogos() }

    fun handleDirection(keyCode: Int): Boolean {
        val pendingPosition = pendingCatalogFocus ?: pendingFocusId?.let { id -> channels.items.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
        val rowPosition = pendingPosition ?: list.findFocus()?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition }
            ?.takeIf { it != RecyclerView.NO_POSITION }
        // Navigate by stable channel positions, including when a fast repeat reaches a row
        // RecyclerView has not created yet. Native focus search can stall at viewport edges.
        if (rowPosition != null && channels.itemCount > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && rowPosition == 0) {
                focusGeneration++; pendingFocusId = null;pendingCatalogFocus=null
                (if (favoritesOnly) favoritesTab else allTab).requestFocus()
            } else {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                if (personalCatalog != null) {
                    scrollDirection = direction
                    focusPosition((rowPosition + direction).coerceIn(0, channels.itemCount - 1), false)
                } else focusChannel(channels.items[(rowPosition + direction).coerceIn(0, channels.items.lastIndex)].id, anchor = false)
            }
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                focusGeneration++; pendingFocusId = null;pendingCatalogFocus=null
                if (favoritesTab.hasFocus()) allTab.requestFocus()
                else if (!allTab.hasFocus()) (if (favoritesOnly) favoritesTab else allTab).requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                focusGeneration++; pendingFocusId = null
                if (pendingPosition == null && allTab.hasFocus()) favoritesTab.requestFocus() else close()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (allTab.hasFocus() || favoritesTab.hasFocus()) { focusChannel(currentId); true } else false
            }
            else -> false
        }
    }

    override fun focusSearch(focused: View, direction: Int): View? {
        val candidate = super.focusSearch(focused, direction) ?: return focused
        var ancestor: android.view.ViewParent? = candidate.parent
        while (ancestor != null) {
            if (ancestor === this) return candidate
            ancestor = ancestor.parent
        }
        return focused
    }
}
