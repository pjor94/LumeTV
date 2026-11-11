package it.lumetv.app.ui.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import it.lumetv.app.R
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp

enum class MobileOption(val label: String) {
    INTERFACE("Interfaccia"),
    ZAPPING("Cambio canale"),
    QUALITY("Qualità e consumo dati"),
    UPDATES("Aggiornamenti"),
    AURA("Collegamento con AURA"),
    ABOUT("Informazioni"),
    LICENSES("Licenze e avvisi"),
}

/** A settings destination in the same activity, keeping the current catalog and player alive. */
@SuppressLint("ViewConstructor")
class MobileOptionsView(context: Context, back: () -> Unit, choose: (MobileOption) -> Unit) : LinearLayout(context) {
    private val rows = linkedMapOf<MobileOption, Pair<View, TextView>>()
    private val column = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(context.dp(20), context.dp(8), context.dp(20), context.dp(24))
    }
    private val scroll = ScrollView(context).apply {
        isSaveEnabled = false
        clipToPadding = false
        addView(column, LayoutParams(-1, -2))
    }
    private val miniHolder = FrameLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(TvStyle.background)
        ViewCompat.setAccessibilityPaneTitle(this, "Opzioni")
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(64)
            setPadding(context.dp(8), context.dp(4), context.dp(20), context.dp(4))
        }
        header.addView(MobileStyle.icon(context, R.drawable.ic_mobile_back, "Chiudi opzioni", back),
            LayoutParams(context.dp(48), context.dp(48)))
        header.addView(TvStyle.text(context, "Opzioni", 28f, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, LayoutParams(0, -2, 1f).apply { leftMargin = context.dp(12) })
        addView(header, LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(miniHolder, LayoutParams(-1, -2).apply {
            leftMargin = context.dp(12); rightMargin = context.dp(12); bottomMargin = context.dp(8)
        })

        fun heading(label: String, first: Boolean = false) {
            column.addView(TvStyle.text(context, label, 14f, TvStyle.accent, bold = true).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            }, LayoutParams(-1, -2).apply { topMargin = context.dp(if (first) 8 else 24); bottomMargin = context.dp(8) })
        }
        fun row(option: MobileOption, divider: Boolean = true) {
            val summary = TvStyle.text(context, "", 14f, TvStyle.muted).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            val copy = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TvStyle.text(context, option.label, 16f, bold = true).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(-1, -2))
                addView(summary, LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
            }
            val item = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = context.dp(72)
                setPadding(context.dp(8), context.dp(14), context.dp(8), context.dp(14))
                isClickable = true; isFocusable = true
                descendantFocusability = FOCUS_BLOCK_DESCENDANTS
                background = MobileStyle.ripple(context)
                TvStyle.buttonSemantics(this)
                addView(copy, LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_mobile_chevron)
                    imageTintList = ColorStateList.valueOf(TvStyle.muted)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(context.dp(24), context.dp(24)).apply { leftMargin = context.dp(16) })
                setOnClickListener { choose(option) }
            }
            rows[option] = item to summary
            column.addView(item, LayoutParams(-1, -2))
            if (divider) column.addView(View(context).apply { setBackgroundColor(TvStyle.surface) },
                LayoutParams(-1, context.dp(1)).apply { leftMargin = context.dp(8); rightMargin = context.dp(8) })
        }
        heading("Preferenze", first = true)
        row(MobileOption.INTERFACE)
        row(MobileOption.ZAPPING)
        row(MobileOption.QUALITY, divider = false)
        heading("App e collegamenti")
        row(MobileOption.UPDATES)
        row(MobileOption.AURA)
        row(MobileOption.ABOUT)
        row(MobileOption.LICENSES, divider = false)
    }

    fun bind(interfaceLabel: String, favoritesOnly: Boolean, dataSaver: Boolean, auraAllowed: Boolean,
             version: String, availableVersion: String?) {
        val values = mapOf(
            MobileOption.INTERFACE to interfaceLabel,
            MobileOption.ZAPPING to if (favoritesOnly) "Solo preferiti" else "Tutte le emittenti",
            MobileOption.QUALITY to if (dataSaver) "Risparmio dati" else "Automatica",
            MobileOption.UPDATES to (availableVersion?.let { "Lume TV $it è disponibile" } ?: "Versione installata: $version"),
            MobileOption.AURA to if (auraAllowed) "Attivo" else "Non attivo",
            MobileOption.ABOUT to "Lume TV $version",
            MobileOption.LICENSES to "Crediti, sorgenti e licenze del software",
        )
        rows.forEach { (option, row) ->
            row.second.text = values.getValue(option)
            row.first.contentDescription = "${option.label}: ${values.getValue(option)}"
        }
    }

    fun setMiniPlayer(view: View) {
        if (view.parent === miniHolder) return
        (view.parent as? ViewGroup)?.removeView(view)
        miniHolder.removeAllViews()
        miniHolder.addView(view, FrameLayout.LayoutParams(-1, -2))
    }

    fun saveState(out: Bundle) { out.putInt("mobile_options_scroll", scroll.scrollY) }
    fun restoreState(state: Bundle?) {
        state?.let { saved -> scroll.doOnLayout { scroll.scrollTo(0, saved.getInt("mobile_options_scroll")) } }
    }
    fun resetScroll() { scroll.scrollTo(0, 0) }
}
