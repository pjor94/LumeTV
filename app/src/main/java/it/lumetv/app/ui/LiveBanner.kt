package it.lumetv.app.ui

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.Gravity
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.core.view.isVisible
import it.lumetv.app.R
import it.lumetv.app.data.Programme
import it.lumetv.app.data.ProgrammeWindow
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.core.Channel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("ViewConstructor") // Programmatic UI with an activity-scoped image repository.
class LiveBanner(context: Context, logos: ChannelLogoRepository) : LinearLayout(context) {
    private val logo = ChannelLogoView(context, logos)
    private val title = TvStyle.text(context, "", 27f, bold = true)
    private val show = TvStyle.text(context, "", 17f)
    private val timing = TvStyle.text(context, "", 13f, TvStyle.muted).apply { fontFeatureSettings = "tnum" }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(TvStyle.accent)
        progressBackgroundTintList = ColorStateList.valueOf(TvStyle.raised)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val next = TvStyle.text(context, "", 14f, TvStyle.muted)
    private val synopsis = TvStyle.text(context, "", 15f, 0xFFD2D7E0.toInt()).apply {
        setLineSpacing(context.dp(3).toFloat(), 1f)
    }
    private val synopsisScroll: ScrollView = object : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = synopsis.lineHeight * 3
            val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit
                else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            synopsisHint.visibility = if (hasDescription && canScrollSynopsis()) VISIBLE else GONE
        }
    }.apply {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        overScrollMode = OVER_SCROLL_NEVER
        addView(synopsis)
    }
    private val synopsisHint = TvStyle.text(context, context.getString(R.string.live_description_scroll), 12f, TvStyle.accent)
    private var synopsisProgramme: Pair<String, Long?>? = null
    private val hint = TvStyle.text(context, "", 12f, TvStyle.muted)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALY)

    init {
        orientation = VERTICAL
        setPadding(context.dp(28), context.dp(20), context.dp(28), context.dp(20))
        background = TvStyle.rounded(0xE61A1D24.toInt(), context.dp(24).toFloat(), 0x24FFFFFF, context.dp(1))
        title.maxLines = 1; title.ellipsize = TextUtils.TruncateAt.END
        show.maxLines = 2; show.ellipsize = TextUtils.TruncateAt.END
        next.maxLines = 1; next.ellipsize = TextUtils.TruncateAt.END
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(logo, LayoutParams(context.dp(76), context.dp(60)).apply { rightMargin = context.dp(20) })
        val information = LinearLayout(context).apply { orientation = VERTICAL }
        information.addView(title)
        information.addView(show, LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        header.addView(information, LayoutParams(0, -2, 1f))
        addView(header)
        addView(timing, LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
        addView(progress, LayoutParams(-1, context.dp(4)).apply { topMargin = context.dp(9) })
        addView(synopsisScroll, LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        addView(synopsisHint, LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        addView(next, LayoutParams(-1, -2).apply { topMargin = context.dp(9) })
        addView(hint, LayoutParams(-1, -2).apply { topMargin = context.dp(14) })
    }

    val hasDescription: Boolean get() = synopsisScroll.isVisible

    fun resetDescriptionScroll() = synopsisScroll.scrollTo(0, 0)

    fun scrollDescription(direction: Int): Boolean {
        if (!hasDescription || !canScrollSynopsis()) return false
        // Immediate, bounded steps preserve the player's focus and support held remote keys.
        synopsisScroll.scrollBy(0, direction * synopsis.lineHeight * 2)
        return true
    }

    private fun canScrollSynopsis() = synopsisScroll.canScrollVertically(-1) || synopsisScroll.canScrollVertically(1)

    fun bind(channel: Channel, programmes: List<Programme>, favoritesOnly: Boolean, now: Long = System.currentTimeMillis()) {
        hint.visibility = VISIBLE
        logo.visibility = VISIBLE
        logo.bind(channel)
        val window = ProgrammeWindow.at(programmes, now)
        val description = window.current?.description.orEmpty().trim()
        val programme = channel.id to window.current?.start
        if (synopsisProgramme != programme || synopsis.text.toString() != description) {
            synopsisProgramme = programme
            synopsis.text = description
            resetDescriptionScroll()
        }
        synopsisScroll.visibility = if (description.isBlank()) GONE else VISIBLE
        if (!hasDescription) synopsisHint.visibility = GONE
        title.text = "${channel.number.takeIf { it != 9999 }?.let { "$it  ·  " }.orEmpty()}${channel.name}"
        show.text = window.current?.title ?: "Diretta · Guida non disponibile per il programma attuale"
        timing.visibility = if (window.current == null) GONE else VISIBLE
        progress.visibility = timing.visibility
        window.current?.let {
            timing.text = "${time(it.start)}–${time(it.end)}  ·  ${window.remainingMinutes} min rimanenti"
            progress.progress = window.progress
        }
        next.text = window.next?.let { "A seguire  ·  ${time(it.start)}  ${it.title}" }.orEmpty()
        next.visibility = if (window.next == null) GONE else VISIBLE
        hint.text = "←  Canali     ·     OK  Info / Riprendi     ·     Indietro  Catalogo     ·     CH +/−  ${if (favoritesOnly) "Preferiti" else "Cambia"}"
    }

    fun message(title: String, subtitle: String) {
        logo.unbind(); logo.visibility = GONE
        this.title.text = title
        show.text = subtitle
        timing.visibility = GONE; progress.visibility = GONE; next.visibility = GONE; hint.text = ""
        synopsisScroll.visibility = GONE; synopsisHint.visibility = GONE
        hint.visibility = GONE
    }

    private fun time(value: Long) = timeFormat.format(Date(value))
}
