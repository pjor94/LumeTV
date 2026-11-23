package it.lumetv.app.ui

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.R
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.util.Locale

/** Compact rows for browsing over the video; focus never starts playback. */
internal class LiveChannelAdapter(
    private val logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    private val choose: (Channel) -> Unit,
    private val liveProgramme: (String) -> LiveProgrammeLoader.Result?,
) : RecyclerView.Adapter<LiveChannelAdapter.Holder>() {
    var items = emptyList<Channel>()
        private set
    var programmes: Map<String, List<Programme>> = emptyMap()
        private set
    var currentId: String? = null
        private set
    private var favoriteIds = emptySet<String>()
    internal var catalog:ContinuousCatalog<Channel>?=null
    fun channelAt(position:Int)=if(catalog!=null)catalog?.peek(position) else items.getOrNull(position)

    init { setHasStableIds(true) }

    class Holder(
        val row: LinearLayout,
        val number: TextView,
        val logo: ChannelLogoView,
        val name: TextView,
        val badge: TextView,
        val heart: ImageView,
        val programme: TextView,
    ) : RecyclerView.ViewHolder(row)

    override fun getItemCount() = catalog?.size ?: items.size
    override fun getItemId(position: Int) = if(catalog!=null)Long.MIN_VALUE+position else items[position].id.take(15).toLong(16)

    fun submit(newItems: List<Channel>, newProgrammes: Map<String, List<Programme>>, newCurrentId: String?) {
        if(catalog!=null) {
            items=emptyList();programmes=newProgrammes;currentId=newCurrentId;notifyDataSetChanged();return
        }
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
            minimumHeight = context.dp(53)
            setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                bottomMargin = context.dp(5)
            }
        }
        val number = TvStyle.text(context, "", 12f, LiveOverlayStyle.secondary).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(number, LinearLayout.LayoutParams(context.dp(31), -2).apply { marginEnd = context.dp(5) })
        val logo = ChannelLogoView(context, logos, compact = true, fallbackIcon = true)
        row.addView(logo, LinearLayout.LayoutParams(context.dp(37), context.dp(34)).apply { marginEnd = context.dp(10) })
        val description = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val nameLine = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val name = TvStyle.text(context, "", 16f, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val badge = TvStyle.text(context, "In visione", 11f, TvStyle.white, bold = true).apply {
            maxLines = 1
            setPadding(context.dp(7), context.dp(3), context.dp(7), context.dp(3))
            background = TvStyle.rounded(0x526C88A8, context.dp(12).toFloat())
        }
        val heart = ImageView(context).apply {
            setImageResource(R.drawable.ic_mobile_heart)
            imageTintList = ColorStateList.valueOf(LiveOverlayStyle.secondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        nameLine.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        nameLine.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(6) })
        nameLine.addView(heart, LinearLayout.LayoutParams(context.dp(14), context.dp(14)).apply { marginStart = context.dp(6) })
        description.addView(nameLine)
        val programme = TvStyle.text(context, "", 13f, LiveOverlayStyle.secondary).apply {
            setLines(1)
            ellipsize = TextUtils.TruncateAt.END
        }
        description.addView(programme, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(2) })
        row.addView(description, LinearLayout.LayoutParams(0, -2, 1f))
        val holder = Holder(row, number, logo, name, badge, heart, programme)
        row.background = LiveOverlayStyle.focusBackground(context)
        TvStyle.buttonSemantics(row)
        row.setOnFocusChangeListener { view, focused ->
            TvStyle.focusMotion(view, focused, 1.012f)
        }
        row.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) channelAt(position)?.let(choose) ?: run {
                if(catalog?.failedAt(position)==true)catalog?.retry(position)
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        catalog?.ensure(position)
        val channel = channelAt(position)
        if(channel==null) {
            val failed=catalog?.failedAt(position)==true
            holder.logo.unbind();holder.logo.visibility=View.INVISIBLE;holder.number.text=""
            holder.badge.isVisible=false;holder.heart.isVisible=false
            holder.name.text=if(failed)"Riprova a caricare" else "Caricamento…";holder.programme.text=""
            holder.row.isClickable=failed;holder.row.contentDescription="${holder.name.text}, canale ${position+1}"
            return
        }
        holder.row.isClickable=true;holder.logo.visibility=View.VISIBLE
        val current = channel.id == currentId
        holder.number.text = channel.displayNumber?.let { String.format(Locale.ITALY, "%d", it) }.orEmpty()
        holder.logo.bind(channel)
        holder.name.text = channel.name
        holder.badge.isVisible = current
        holder.heart.isVisible = !current && favorite(channel)
        bindProgramme(holder, channel)
    }

    private fun bindProgramme(holder: Holder, channel: Channel) {
        val paged = catalog != null
        val cached = if (paged) liveProgramme(channel.epgId) else null
        val title = if (paged) cached?.programme?.title
            else ProgrammeWindow.at(programmes[channel.epgId].orEmpty(), System.currentTimeMillis()).current?.title
        holder.programme.text = title ?: if (paged && cached == null) "" else "Guida non disponibile"
        holder.row.contentDescription = listOfNotNull(
            channel.displayNumber?.toString(), channel.name, title,
            "In visione".takeIf { channel.id == currentId }, "Preferito".takeIf { favorite(channel) },
        ).joinToString(", ")
    }

    fun refreshProgramme(position: Int) = notifyItemChanged(position, "programme")

    fun refreshProgrammes() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, "programme")
    }

    fun refreshLogos() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, "logo")
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) return super.onBindViewHolder(holder, position, payloads)
        channelAt(position)?.let {channel->
            if (payloads.any { it == "logo" }) holder.logo.bind(channel)
            if (payloads.any { it == "programme" }) bindProgramme(holder, channel)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.logo.unbind()
        holder.row.animate().cancel()
        holder.row.scaleX = 1f; holder.row.scaleY = 1f
        super.onViewRecycled(holder)
    }
}
