package it.lumetv.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.core.Channel
import it.lumetv.app.ui.TvStyle.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChannelAdapter(
    private val logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    private val choose: (Channel) -> Unit,
    private val toggleFavorite: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {
    init { setHasStableIds(true) }
    var items = emptyList<Channel>()
    var catalog:ContinuousCatalog<Channel>?=null
    fun channelAt(position:Int):Channel?=if(position<items.size)items.getOrNull(position) else catalog?.peek(position-items.size)
    fun visibleRows(first:Int,last:Int)=(first..last.coerceAtMost(itemCount-1)).mapNotNull(::channelAt)
    fun positionOf(id:String?)=items.indexOfFirst {it.id==id}.takeIf {it>=0} ?: catalog?.indexOf {it.id==id}?.plus(items.size)
    var sourceNames = emptyMap<String, String>()
    var programmes: Map<String, List<Programme>> = emptyMap()
    var guideMode = false
    var channelCards = false
    var selectedChannelId: String? = null
    var onChannelFocus: (Channel, Int) -> Unit = { _, _ -> }
    var currentId: String? = null
    var guidePlaceholder = "Guida assente per questo canale · OK per la diretta"

    class Holder(val card: LinearLayout, val number: TextView, val badge: TextView, val name: TextView,
                 val show: TextView, val logo: ChannelLogoView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        if (viewType == LIVE_CARD) return createLiveCard(parent)
        val context = parent.context
        val card = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = if (viewType == 1) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true; isLongClickable = true
            TvStyle.buttonSemantics(this)
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(context.dp(18), context.dp(13), context.dp(18), context.dp(13))
            layoutParams = RecyclerView.LayoutParams(-1, context.dp(if (viewType == 1) 108 else 140)).apply {
                setMargins(context.dp(7), context.dp(7), context.dp(7), context.dp(7))
            }
        }
        val logo = ChannelLogoView(context, logos)
        val content = if (viewType == 1) LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            card.addView(logo, LinearLayout.LayoutParams(context.dp(76), context.dp(60)).apply { rightMargin = context.dp(20) })
            card.addView(this, LinearLayout.LayoutParams(0, -1, 1f))
        } else card
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val number = TvStyle.text(context, "", 12f, TvStyle.muted).apply {
            fontFeatureSettings = "tnum"; letterSpacing = 0.06f
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val badge = TvStyle.text(context, "", 10f, TvStyle.accent, true)
        top.addView(number, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(badge)
        content.addView(top)
        if (viewType == 0) {
            val brand = FrameLayout(context)
            brand.addView(logo, FrameLayout.LayoutParams(context.dp(76), context.dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
            content.addView(brand, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val name = TvStyle.text(context, "", if (viewType == 1) 23f else 21f, bold = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(if (viewType == 1) 6 else 0) })
        val show = TvStyle.text(context, "", 12f, TvStyle.muted).apply {
            maxLines = if (viewType == 1) 2 else 1; ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(show, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        card.setOnFocusChangeListener { view, focused ->
            number.setTextColor(if (focused) TvStyle.white else TvStyle.muted)
            show.setTextColor(if (focused) TvStyle.white else TvStyle.muted)
            view.elevation = if (focused) context.dp(8).toFloat() else 0f
            TvStyle.focusMotion(view, focused, if (viewType == 1) 1.012f else 1.04f)
        }
        return Holder(card, number, badge, name, show, logo)
    }

    private fun createLiveCard(parent: ViewGroup): Holder {
        val context = parent.context
        val config = context.resources.configuration
        val compact = config.screenWidthDp < 740
        val cardHeight = when {
            config.fontScale > 1.15f -> 53
            compact -> 48
            else -> 50
        }
        val card = LinearLayout(context).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isLongClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            TvStyle.buttonSemantics(this)
            val inset = context.dp(if (compact) 6 else 9)
            setPadding(inset, 0, inset, 0)
            layoutParams = RecyclerView.LayoutParams(-1, context.dp(cardHeight)).apply {
                setMargins(context.dp(4), context.dp(3), context.dp(4), context.dp(3))
            }
            background = StateListDrawable().apply {
                val radius = context.dp(10).toFloat()
                addState(intArrayOf(android.R.attr.state_pressed),
                    TvStyle.rounded(0xFF344862.toInt(), radius, TvStyle.accent, context.dp(1)))
                addState(intArrayOf(android.R.attr.state_focused),
                    TvStyle.rounded(0xFF22364B.toInt(), radius, TvStyle.accent, context.dp(2)))
                addState(intArrayOf(android.R.attr.state_selected),
                    TvStyle.rounded(0xFF1D2B3C.toInt(), radius, 0x806CAADC.toInt(), context.dp(1)))
                addState(intArrayOf(), TvStyle.rounded(0xFF1A202B.toInt(), radius, 0x22C9DCF5, context.dp(1)))
            }
        }
        val logo = ChannelLogoView(context, logos, compact = true, fallbackIcon = true)
        val tileSize = context.dp(if (compact) 26 else 33)
        card.addView(logo, LinearLayout.LayoutParams(tileSize, tileSize).apply {
            marginEnd = context.dp(if (compact) 6 else 8)
        })
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val name = TvStyle.text(context, "", if (compact) 12f else 14f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val number = TvStyle.text(context, "", if (compact) 9f else 10f, TvStyle.muted).apply {
            maxLines = 1
            fontFeatureSettings = "tnum"
            maxWidth = context.dp(if (compact) 23 else 35)
            ellipsize = TextUtils.TruncateAt.END
        }
        val badge = TvStyle.text(context, "", 10f, TvStyle.accent, bold = true)
        copy.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            addView(badge)
            addView(number, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(4) })
        }, LinearLayout.LayoutParams(-1, -2))
        val show = TvStyle.text(context, "", if (compact) 11f else 12f, TvStyle.muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        copy.addView(show, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(2) })
        card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val holder = Holder(card, number, badge, name, show, logo)
        card.setOnFocusChangeListener { view, focused ->
            show.setTextColor(if (focused) TvStyle.white else TvStyle.muted)
            TvStyle.focusMotion(view, focused, 1.015f)
            if (focused) notifyChannelFocus(holder)
        }
        return holder
    }

    private fun notifyChannelFocus(holder: Holder) {
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) channelAt(position)?.let { onChannelFocus(it, position) }
    }

    override fun getItemViewType(position: Int) = when {
        guideMode -> 1
        channelCards -> LIVE_CARD
        else -> 0
    }
    override fun getItemCount() = items.size+(catalog?.size ?: 0)
    override fun getItemId(position: Int) = if(position<items.size)items[position].id.take(15).toLong(16) else Long.MIN_VALUE+position
    override fun onBindViewHolder(holder: Holder, position: Int) {
        catalog?.ensure(position-items.size)
        val channel = channelAt(position)
        val liveCard = holder.itemViewType == LIVE_CARD
        if(channel==null) {
            val failed=catalog?.failedAt(position-items.size)==true
            holder.logo.unbind();holder.logo.visibility=View.INVISIBLE
            holder.number.text="";holder.badge.text="";holder.show.text=""
            holder.name.text=if(failed)"Riprova a caricare" else "Caricamento…"
            holder.card.isSelected = false
            if (!liveCard) {
                holder.name.textSize = 21f
                holder.card.background = TvStyle.focusBackground(holder.card.context)
            }
            holder.card.contentDescription="${holder.name.text}, canale ${position+1}"
            holder.card.setOnClickListener {if(failed)holder.bindingAdapterPosition.takeIf {it>=items.size}?.let {catalog?.retry(it-items.size)}}
            holder.card.isClickable=failed
            holder.card.setOnLongClickListener(null)
            return
        }
        holder.logo.visibility=View.VISIBLE
        holder.card.isClickable=true
        val context = holder.card.context
        holder.number.text = if (liveCard) channel.displayNumber?.let { "%02d".format(Locale.ITALY, it) }.orEmpty() else {
            val sourceLabel = (if (channel.isPersonal) sourceNames[channel.sourceId] ?: "Lista personale" else channel.family).uppercase(Locale.ITALY)
            if (channel.displayNumber == null) sourceLabel else "%02d   /   %s".format(channel.number, sourceLabel)
        }
        if (liveCard) {
            val config = holder.card.resources.configuration
            val showNumber = holder.number.text.isNotEmpty() && !(config.screenWidthDp < 740 && config.fontScale > 1.15f)
            holder.number.visibility = if (showNumber) View.VISIBLE else View.GONE
        }
        holder.badge.text = when {
            liveCard && channel.id == currentId -> "●"
            favorite(channel) -> "★"
            channel.id == currentId -> "●"
            else -> ""
        }
        holder.name.text = channel.name
        val secondaryColor = if (holder.card.hasFocus()) TvStyle.white else TvStyle.muted
        holder.number.setTextColor(if (liveCard && channel.id == currentId) TvStyle.accent else secondaryColor)
        holder.show.setTextColor(secondaryColor)
        if (!liveCard) {
            holder.name.textSize = when { guideMode -> 23f; channel.name.length > 18 -> 17f; channel.name.length > 12 -> 19f; else -> 21f }
        }
        holder.logo.bind(channel)
        bindProgramme(holder, channel)
        if (!liveCard) {
            val tint = tint(channel)
            fun panel(focused: Boolean, pressed: Boolean = false) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(if (pressed) TvStyle.raised else if (focused) brighten(tint) else tint,
                    if (pressed || focused) TvStyle.raised else TvStyle.surface)).apply {
                cornerRadius = context.dp(18).toFloat()
                setStroke(context.dp(1), if (pressed) TvStyle.accent else if (focused) TvStyle.white else 0x12FFFFFF)
            }
            holder.card.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), panel(false, pressed = true))
                addState(intArrayOf(android.R.attr.state_focused), panel(true))
                addState(intArrayOf(), panel(false))
            }
        }
        holder.card.setOnClickListener { choose(channel) }
        holder.card.setOnLongClickListener { toggleFavorite(channel); true }
        if (liveCard && holder.card.hasFocus()) onChannelFocus(channel, position)
        holder.card.isSelected = liveCard && channel.id == selectedChannelId
    }

    override fun onViewRecycled(holder: Holder) {
        holder.logo.unbind()
        holder.card.animate().cancel()
        holder.card.scaleX = 1f; holder.card.scaleY = 1f; holder.card.elevation = 0f
        super.onViewRecycled(holder)
    }

    fun refreshLogos() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, "logo")
    }

    fun refreshProgrammes() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, "programme")
    }

    private fun bindProgramme(holder: Holder, channel: Channel) {
        val now = System.currentTimeMillis()
        val window = ProgrammeWindow.at(programmes[channel.epgId].orEmpty(), now)
        val current = window.current
        val next = window.next
        val label = if (guideMode) {
            listOfNotNull(current?.let { "ORA  ${it.title}" }, next?.let { "${time(it.start)}  ${it.title}" })
                .joinToString("\n").ifBlank { guidePlaceholder }
        } else current?.title ?: when {
            holder.itemViewType == LIVE_CARD -> "Guida non disponibile"
            channel.isVod -> "Video"
            channel.isRadio -> channel.group.ifBlank { "Ascolta la radio" }
            else -> "In diretta"
        }
        if (holder.show.text.toString() != label) holder.show.text = label
        holder.card.contentDescription = listOfNotNull(
            channel.displayNumber?.toString(), channel.name, holder.show.text.toString(),
            "canale corrente".takeIf { channel.id == currentId }, "preferito".takeIf { favorite(channel) },
        ).joinToString(", ")
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) super.onBindViewHolder(holder, position, payloads)
        else {
            channelAt(position)?.let {channel->
                if ("logo" in payloads) holder.logo.bind(channel)
                if ("programme" in payloads) bindProgramme(holder, channel)
            }
        }
    }

    private fun tint(channel: Channel): Int = when {
        channel.name == "Rai 1" -> 0xFF203953.toInt()
        channel.name == "Rai 2" -> 0xFF49272F.toInt()
        channel.name == "Rai 3" -> 0xFF203D35.toInt()
        channel.family == "Rai" -> 0xFF263043.toInt()
        channel.family == "Mediaset" -> 0xFF35303D.toInt()
        channel.name.startsWith("La7") -> 0xFF403720.toInt()
        else -> 0xFF26323C.toInt()
    }
    private fun brighten(color: Int) = Color.rgb((Color.red(color) * 1.35).toInt(), (Color.green(color) * 1.35).toInt(), (Color.blue(color) * 1.35).toInt())
    private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(value))

    private companion object { const val LIVE_CARD = 2 }
}
