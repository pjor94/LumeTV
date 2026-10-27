package it.lumetv.app.ui

import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.util.Locale

/** Compact rows for browsing over the video; focus never starts playback. */
class LiveChannelAdapter(
    private val logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    private val choose: (Channel) -> Unit,
) : RecyclerView.Adapter<LiveChannelAdapter.Holder>() {
    var items = emptyList<Channel>()
        private set
    var programmes: Map<String, List<Programme>> = emptyMap()
        private set
    var currentId: String? = null
        private set
    private var favoriteIds = emptySet<String>()

    init { setHasStableIds(true) }

    class Holder(
        val row: LinearLayout,
        val number: TextView,
        val logo: ChannelLogoView,
        val name: TextView,
        val badge: TextView,
        val programme: TextView,
        val progress: ProgressBar,
    ) : RecyclerView.ViewHolder(row)

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id.take(15).toLong(16)

    fun submit(newItems: List<Channel>, newProgrammes: Map<String, List<Programme>>, newCurrentId: String?) {
        val oldItems = items
        val oldCurrentId = currentId
        val oldFavorites = favoriteIds
        val newFavorites = newItems.filter(favorite).map { it.id }.toSet()
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = oldItems[oldPosition].id == newItems[newPosition].id
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                val old = oldItems[oldPosition]
                val new = newItems[newPosition]
                return old == new && (old.id == oldCurrentId) == (new.id == newCurrentId) &&
                    (old.id in oldFavorites) == (new.id in newFavorites)
            }
        })
        items = newItems
        programmes = newProgrammes
        currentId = newCurrentId
        favoriteIds = newFavorites
        changes.dispatchUpdatesTo(this)
        refreshProgrammes()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
            layoutParams = RecyclerView.LayoutParams(-1, context.dp(78)).apply {
                bottomMargin = context.dp(6)
            }
        }
        val number = TvStyle.text(context, "", 12f, TvStyle.muted).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            maxLines = 1
        }
        row.addView(number, LinearLayout.LayoutParams(context.dp(28), -2).apply { rightMargin = context.dp(8) })
        val logo = ChannelLogoView(context, logos)
        row.addView(logo, LinearLayout.LayoutParams(context.dp(48), context.dp(42)).apply { rightMargin = context.dp(12) })
        val description = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val nameLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val name = TvStyle.text(context, "", 17f, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val badge = TvStyle.text(context, "", 10f, TvStyle.accent).apply {
            setPadding(context.dp(7), 0, 0, 0)
        }
        nameLine.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        nameLine.addView(badge)
        description.addView(nameLine)
        val programme = TvStyle.text(context, "", 12f, TvStyle.muted).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        description.addView(programme, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(TvStyle.accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x24FFFFFF)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        description.addView(progress, LinearLayout.LayoutParams(-1, context.dp(2)).apply { topMargin = context.dp(7) })
        row.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
        val holder = Holder(row, number, logo, name, badge, programme, progress)
        row.setOnFocusChangeListener { view, focused ->
            updateColors(holder)
            TvStyle.focusMotion(view, focused, 1.015f)
        }
        row.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) choose(items[position])
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        val context = holder.row.context
        val current = channel.id == currentId
        holder.number.text = channel.number.takeIf { it != 9999 }?.let { String.format(Locale.ITALY, "%d", it) }.orEmpty()
        holder.logo.bind(channel)
        holder.name.text = channel.name
        holder.badge.text = when {
            current -> "●"
            favorite(channel) -> "★"
            else -> ""
        }
        holder.row.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), TvStyle.rounded(TvStyle.white, context.dp(16).toFloat()))
            addState(intArrayOf(android.R.attr.state_pressed), TvStyle.rounded(TvStyle.raised, context.dp(16).toFloat()))
            addState(intArrayOf(), TvStyle.rounded(if (current) 0x2699CAFF else 0x08FFFFFF,
                context.dp(16).toFloat(), if (current) 0x6699CAFF else 0x10FFFFFF, context.dp(1)))
        }
        bindProgramme(holder, channel)
        updateColors(holder)
    }

    private fun bindProgramme(holder: Holder, channel: Channel) {
        val window = ProgrammeWindow.at(programmes[channel.epgId].orEmpty(), System.currentTimeMillis())
        holder.programme.text = window.current?.title ?: "Programma non disponibile"
        holder.progress.progress = window.progress
        // Reserve the same space when the guide is missing, so every logo stays aligned.
        holder.progress.visibility = if (window.current != null) View.VISIBLE else View.INVISIBLE
        holder.row.contentDescription = listOfNotNull(
            channel.number.takeIf { it != 9999 }?.toString(), channel.name, window.current?.title,
            "In visione".takeIf { channel.id == currentId }, "Preferito".takeIf { favorite(channel) },
        ).joinToString(", ")
    }

    private fun updateColors(holder: Holder) {
        val focused = holder.row.hasFocus()
        holder.name.setTextColor(if (focused) TvStyle.background else TvStyle.white)
        holder.number.setTextColor(if (focused) 0xFF505967.toInt() else TvStyle.muted)
        holder.programme.setTextColor(if (focused) 0xFF505967.toInt() else TvStyle.muted)
        holder.badge.setTextColor(if (focused) 0xFF235B92.toInt() else TvStyle.accent)
        holder.progress.progressTintList = ColorStateList.valueOf(if (focused) 0xFF356DA5.toInt() else TvStyle.accent)
        holder.progress.progressBackgroundTintList = ColorStateList.valueOf(if (focused) 0x20235B92 else 0x24FFFFFF)
    }

    fun refreshProgrammes() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, "programme")
    }

    fun refreshLogos() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, "logo")
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) return super.onBindViewHolder(holder, position, payloads)
        if (payloads.any { it == "logo" }) holder.logo.bind(items[position])
        if (payloads.any { it == "programme" }) bindProgramme(holder, items[position])
    }

    override fun onViewRecycled(holder: Holder) {
        holder.logo.unbind()
        holder.row.animate().cancel()
        holder.row.scaleX = 1f; holder.row.scaleY = 1f
        super.onViewRecycled(holder)
    }
}
