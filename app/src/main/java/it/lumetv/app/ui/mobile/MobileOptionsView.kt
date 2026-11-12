package it.lumetv.app.ui.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import it.lumetv.app.ui.OptionsRowView
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp

enum class MobileOption(val label: String) {
    PLAYLISTS("Le mie liste"),
    INTERFACE("Interfaccia"),
    ZAPPING("Cambio canale"),
    QUALITY("Qualità e consumo dati"),
    UPDATES("Aggiornamenti"),
    AURA("Collegamento con AURA"),
    HOME("Canali nella Home"),
    ABOUT("Informazioni"),
    LICENSES("Licenze e avvisi"),
}

/** A settings destination in the same activity, keeping the current catalog and player alive. */
@SuppressLint("ViewConstructor")
class MobileOptionsView(context: Context, back: () -> Unit, choose: (MobileOption) -> Unit) : LinearLayout(context) {
    private val rows = linkedMapOf<MobileOption, OptionsRowView>()
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
        val header=MobileStyle.pageHeader(context,TvStyle.text(context, "Opzioni", 28f, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        },"Chiudi opzioni",back)
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
            val item=OptionsRowView(context,true,option.label){choose(option)}
            rows[option] = item
            column.addView(item, LayoutParams(-1, -2))
            if (divider) column.addView(OptionsRowView.divider(context),
                LayoutParams(-1, context.dp(1)).apply { leftMargin = context.dp(8); rightMargin = context.dp(8) })
        }
        heading("Preferenze", first = true)
        row(MobileOption.PLAYLISTS)
        row(MobileOption.INTERFACE)
        row(MobileOption.ZAPPING)
        row(MobileOption.QUALITY, divider = false)
        heading("App e collegamenti")
        row(MobileOption.UPDATES)
        row(MobileOption.AURA)
        if (it.lumetv.app.home.HomeChannels.television(context)) row(MobileOption.HOME)
        row(MobileOption.ABOUT)
        row(MobileOption.LICENSES, divider = false)
    }

    fun bind(interfaceLabel: String, favoritesOnly: Boolean, dataSaver: Boolean, auraAllowed: Boolean,
             version: String, availableVersion: String?, homeSummary: String) {
        val values = mapOf(
            MobileOption.PLAYLISTS to "Aggiungi liste M3U da URL HTTP/HTTPS o file",
            MobileOption.INTERFACE to interfaceLabel,
            MobileOption.ZAPPING to if (favoritesOnly) "Solo preferiti" else "Tutte le emittenti",
            MobileOption.QUALITY to if (dataSaver) "Risparmio dati" else "Automatica",
            MobileOption.UPDATES to (availableVersion?.let { "Lume TV $it è disponibile" } ?: "Versione installata: $version"),
            MobileOption.AURA to if (auraAllowed) "Attivo" else "Non attivo",
            MobileOption.HOME to homeSummary,
            MobileOption.ABOUT to "Lume TV $version",
            MobileOption.LICENSES to "Crediti, sorgenti e licenze del software",
        )
        rows.forEach { (option, row) ->
            row.bind(option.label,values.getValue(option))
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
