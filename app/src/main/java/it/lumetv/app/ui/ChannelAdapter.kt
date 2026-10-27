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
    var programmes: Map<String, List<Programme>> = emptyMap()
    var guideMode = false
    var currentId: String? = null
    var guidePlaceholder = "Guida assente per questo canale · OK per la diretta"

    class Holder(val card: LinearLayout, val number: TextView, val badge: TextView, val name: TextView,
                 val show: TextView, val logo: ChannelLogoView) : RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val card = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = if (viewType == 1) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true; isLongClickable = true
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
        val number = TvStyle.text(context, "", 11f, TvStyle.muted).apply {
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
        val show = TvStyle.text(context, "", 11f, TvStyle.muted).apply {
            maxLines = if (viewType == 1) 2 else 1; ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(show, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        card.setOnFocusChangeListener { view, focused ->
            view.elevation = if (focused) context.dp(8).toFloat() else 0f
            TvStyle.focusMotion(view, focused, if (viewType == 1) 1.012f else 1.04f)
        }
        return Holder(card, number, badge, name, show, logo)
    }

    override fun getItemViewType(position: Int) = if (guideMode) 1 else 0
    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].id.take(15).toLong(16)
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        val context = holder.card.context
        holder.number.text = if (channel.number == 9999) channel.family.uppercase(Locale.ITALY) else "%02d   /   %s".format(channel.number, channel.family.uppercase(Locale.ITALY))
        holder.badge.text = when {
            favorite(channel) -> "★"
            channel.id == currentId -> "●"
            else -> ""
        }
        holder.name.text = channel.name
        holder.name.textSize = when { guideMode -> 23f; channel.name.length > 18 -> 17f; channel.name.length > 12 -> 19f; else -> 21f }
        holder.logo.bind(channel)
        val now = System.currentTimeMillis()
        val shows = programmes[channel.epgId].orEmpty().filter { it.end > now }
        val current = shows.firstOrNull { it.start <= now }
        val next = shows.firstOrNull { it.start > now }
        holder.show.text = if (guideMode) {
            listOfNotNull(current?.let { "ORA  ${it.title}" }, next?.let { "${time(it.start)}  ${it.title}" })
                .joinToString("\n").ifBlank { guidePlaceholder }
        } else current?.title ?: "In diretta"
        val tint = tint(channel)
        fun panel(focused: Boolean) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(if (focused) brighten(tint) else tint, if (focused) TvStyle.raised else TvStyle.surface)).apply {
            cornerRadius = context.dp(18).toFloat()
            setStroke(context.dp(1), if (focused) TvStyle.white else 0x12FFFFFF)
        }
        holder.card.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), panel(true))
            addState(intArrayOf(), panel(false))
        }
        holder.card.contentDescription = "${channel.number}, ${channel.name}, ${holder.show.text}" + if (channel.id == currentId) ", canale corrente" else ""
        holder.card.setOnClickListener { choose(channel) }
        holder.card.setOnLongClickListener { toggleFavorite(channel); true }
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

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == "logo" }) holder.logo.bind(items[position])
        else super.onBindViewHolder(holder, position, payloads)
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
}
