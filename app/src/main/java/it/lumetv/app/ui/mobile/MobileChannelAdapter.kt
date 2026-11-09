package it.lumetv.app.ui.mobile

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import it.lumetv.app.R
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.Programme
import it.lumetv.app.ui.ChannelLogoView
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MobileBrowseItem(val channel: Channel, val programme: Programme? = null, val channelRow: Boolean = false)

class MobileChannelAdapter(
    private val logos: ChannelLogoRepository,
    private val favorite: (Channel) -> Boolean,
    private val choose: (MobileBrowseItem) -> Unit,
    private val toggleFavorite: (Channel) -> Unit,
) : RecyclerView.Adapter<MobileChannelAdapter.Holder>() {
    var items = emptyList<MobileBrowseItem>()
    init { setHasStableIds(true) }

    class Holder(val row: LinearLayout, val logo: ChannelLogoView, val title: TextView,
                 val subtitle: TextView, val timing: TextView, val progress: ProgressBar,
                 val favorite: ImageButton) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(100)
            setPadding(context.dp(8), context.dp(14), 0, context.dp(14))
            layoutParams = RecyclerView.LayoutParams(-1, -2)
            isClickable = true; isFocusable = true
            background = MobileStyle.ripple(context)
            TvStyle.buttonSemantics(this)
        }
        val logo = ChannelLogoView(context, logos, compact = true)
        row.addView(logo, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), 0, context.dp(4), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val title = TvStyle.text(context, "", 16f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val subtitle = TvStyle.text(context, "", 13f, TvStyle.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val timing = TvStyle.text(context, "", 12f, TvStyle.accent).apply { fontFeatureSettings = "tnum" }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(TvStyle.accent)
            progressBackgroundTintList = ColorStateList.valueOf(TvStyle.raised)
        }
        copy.addView(title)
        copy.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        copy.addView(timing, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        copy.addView(progress, LinearLayout.LayoutParams(-1, context.dp(2)).apply { topMargin = context.dp(8) })
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val star = MobileStyle.icon(context, R.drawable.ic_mobile_heart, "Aggiungi ai preferiti") {}
        row.addView(star, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        return Holder(row, logo, title, subtitle, timing, progress, star)
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int): Long = items[position].let {
        it.channel.id.take(15).toLong(16) xor if (it.channelRow) 0L else it.programme?.start ?: 0L
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val channel = item.channel
        val programme = item.programme
        val now = System.currentTimeMillis()
        val live = programme != null && programme.start <= now && programme.end > now
        val number = channel.number.takeIf { it != 9999 }?.let { "$it · " }.orEmpty()
        holder.title.text = if (item.channelRow) number + channel.name else programme?.title.orEmpty()
        holder.subtitle.text = if (item.channelRow) programme?.title ?: "Guarda la diretta" else number + channel.name
        holder.timing.text = programme?.let { (if (live) "In onda · " else "") + time(it.start) + " – " + time(it.end) }.orEmpty()
        holder.timing.visibility = if (programme == null) View.GONE else View.VISIBLE
        holder.progress.visibility = if (live) View.VISIBLE else View.GONE
        if (live) holder.progress.progress = ((now - programme.start).toDouble() /
            (programme.end - programme.start).coerceAtLeast(1) * 1000).toInt().coerceIn(0, 1000)
        holder.logo.bind(channel)
        val preferred = favorite(channel)
        holder.favorite.imageTintList = ColorStateList.valueOf(if (preferred) TvStyle.accent else TvStyle.muted)
        holder.favorite.background = MobileStyle.ripple(holder.row.context, preferred)
        holder.favorite.isSelected = preferred
        holder.favorite.contentDescription = (if (preferred) "Rimuovi ${channel.name} dai preferiti" else "Aggiungi ${channel.name} ai preferiti")
        holder.favorite.setOnClickListener { toggleFavorite(channel) }
        holder.row.contentDescription = listOf(holder.title.text, holder.subtitle.text, holder.timing.text,
            if (preferred) "Preferito" else "").filter { it.isNotBlank() }.joinToString(", ")
        holder.row.setOnClickListener { choose(item) }
        holder.row.setOnLongClickListener { toggleFavorite(channel); true }
    }

    override fun onViewRecycled(holder: Holder) { holder.logo.unbind(); super.onViewRecycled(holder) }
    fun refresh() { if (itemCount > 0) notifyItemRangeChanged(0, itemCount) }
    private fun time(value: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(value))
}
