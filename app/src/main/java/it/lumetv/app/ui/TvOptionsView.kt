package it.lumetv.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import it.lumetv.app.R
import it.lumetv.app.ui.TvStyle.dp

enum class TvOption(val label: String, val description: String) {
    PLAYLISTS("Le mie liste", "Aggiungi e gestisci liste personali M3U per TV e radio, tramite indirizzo HTTPS o file."),
    INTERFACE("Interfaccia", "Scegli la disposizione TV o smartphone, oppure lascia il riconoscimento automatico."),
    ZAPPING("CH+/CH−", "Scegli se i tasti di cambio canale scorrono tutte le emittenti o soltanto i preferiti."),
    AURA("Collegamento con AURA", "Gestisci la condivisione di canali, preferiti e guida con AURA su questa TV."),
    HOME("Canali nella Home", "Scegli fino a 24 canali del catalogo integrato da mostrare nella Home dei launcher compatibili. Il consenso è separato da AURA."),
    UPDATES("Aggiornamenti", "Controlla le nuove versioni di Lume TV e gestisci gli aggiornamenti dell’app."),
    ABOUT("Informazioni", "Consulta la versione di Lume TV, lo stato del catalogo e della guida e i riferimenti del progetto."),
    LICENSES("Licenze e avvisi", "Leggi i crediti, le licenze del software e i riferimenti ai sorgenti."),
}

/** A remote-controlled destination with one focus column and the existing playback preview. */
@SuppressLint("ViewConstructor")
class TvOptionsView(context: Context, choose: (TvOption) -> Unit) : FrameLayout(context) {
    private data class Row(val view: View, val title: TextView, val value: TextView)
    private val rows = linkedMapOf<TvOption, Row>()
    private var selected = TvOption.INTERFACE
    private var navigation = emptyList<View>()
    private var optionsTab: View? = null
    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
    }
    private val listScroll = ScrollView(context).apply {
        isSaveEnabled = false; clipChildren = true; clipToPadding = true
        addView(list, LayoutParams(-1, -2))
    }
    private val detailTitle = TvStyle.text(context, "", 25f, bold = true)
    private val detailText = TvStyle.text(context, "", 16f, TvStyle.muted)
    private val previewSlot = FrameLayout(context)
    private var previewOpen: View? = null
    private var previewStop: View? = null

    init {
        id = R.id.tv_options
        ViewCompat.setAccessibilityPaneTitle(this, "Opzioni")
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(TvStyle.text(context, "Opzioni", 35f, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(-1, -2))
        val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(listScroll, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = context.dp(32) })
        val detail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(12), context.dp(4), context.dp(8))
            addView(detailTitle, LinearLayout.LayoutParams(-1, -2))
            addView(detailText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
            addView(previewSlot, LinearLayout.LayoutParams(context.dp(248), -2).apply { topMargin = context.dp(20) })
        }
        body.addView(ScrollView(context).apply {
            isSaveEnabled = false; clipChildren = true; clipToPadding = true
            addView(detail, LayoutParams(-1, -2))
        },
            LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = context.dp(20) })
        content.addView(TvStyle.text(context, "↑ ↓  Scegli     OK  Apri     ↑ dalla prima voce  Menu     Indietro  Torna", 12f, TvStyle.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12) })
        addView(content, LayoutParams(-1, -1))

        TvOption.entries.forEach { option ->
            val title = TvStyle.text(context, option.label, 18f, bold = true)
            val value = TvStyle.text(context, "", 14f, TvStyle.muted)
            val row = LinearLayout(context).apply {
                id = generateViewId(); orientation = LinearLayout.VERTICAL
                minimumHeight = context.dp(64)
                setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
                isFocusable = true; isClickable = true
                background = TvStyle.focusBackground(context)
                TvStyle.buttonSemantics(this)
                addView(title, LinearLayout.LayoutParams(-1, -2))
                addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
                title.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                value.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { choose(option) }
                setOnFocusChangeListener { view, focused ->
                    title.setTextColor(if (focused) TvStyle.background else TvStyle.white)
                    value.setTextColor(if (focused) TvStyle.raised else TvStyle.muted)
                    TvStyle.focusMotion(view, focused, 1.015f)
                    if (focused) { selected = option; showDescription(); linkFocus() }
                }
            }
            rows[option] = Row(row, title, value)
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(2) })
        }
        showDescription(); linkFocus()
    }

    fun bind(interfaceLabel: String, favoritesOnly: Boolean, auraAllowed: Boolean, version: String, availableVersion: String?, homeSummary: String) {
        val values = mapOf(TvOption.PLAYLISTS to "Liste M3U sul dispositivo", TvOption.INTERFACE to interfaceLabel,
            TvOption.ZAPPING to if (favoritesOnly) "Solo preferiti" else "Tutti i canali",
            TvOption.AURA to if (auraAllowed) "Attivo" else "Non attivo",
            TvOption.HOME to homeSummary,
            TvOption.UPDATES to (availableVersion?.let { "Lume TV $it è disponibile" } ?: "Versione installata: $version"),
            TvOption.ABOUT to "Lume TV $version", TvOption.LICENSES to "Crediti e sorgenti")
        rows.forEach { (option, row) ->
            row.value.text = values.getValue(option)
            row.view.contentDescription = "${option.label}: ${values.getValue(option)}"
        }
    }

    private fun showDescription() { detailTitle.text = selected.label; detailText.text = selected.description }
    fun select(option: TvOption) { selected = option; showDescription(); linkFocus() }

    fun setPreview(view: View) {
        if (view.parent === previewSlot) return
        (view.parent as? ViewGroup)?.removeView(view)
        previewSlot.removeAllViews(); previewSlot.addView(view, LayoutParams(-1, -2))
    }

    fun setPreviewControls(open: View?, stop: View?) { previewOpen = open; previewStop = stop; linkFocus() }

    fun setNavigation(controls: List<View>, activeTab: View?) {
        navigation = controls; optionsTab = activeTab; linkFocus()
    }

    private fun linkFocus() {
        val items = rows.values.toList()
        items.forEachIndexed { index, row ->
            row.view.nextFocusUpId = if (index == 0) optionsTab?.id ?: row.view.id else items[index - 1].view.id
            row.view.nextFocusDownId = items.getOrElse(index + 1) { row }.view.id
            row.view.nextFocusLeftId = row.view.id
            row.view.nextFocusRightId = previewOpen?.id ?: row.view.id
        }
        navigation.forEach { it.nextFocusDownId = rows.getValue(selected).view.id }
        previewOpen?.apply {
            nextFocusLeftId = rows.getValue(selected).view.id
            nextFocusUpId = optionsTab?.id ?: id; nextFocusDownId = previewStop?.id ?: id
        }
        previewStop?.apply { nextFocusLeftId = rows.getValue(selected).view.id; nextFocusUpId = previewOpen?.id ?: id; nextFocusDownId = id }
    }

    fun focusSelection() { rows.getValue(selected).view.requestFocusFromTouch() }
    fun saveState(out: Bundle) { out.putString("tv_options_selection", selected.name); out.putInt("tv_options_scroll", listScroll.scrollY) }
    fun restoreState(state: Bundle?) {
        if (state == null) return
        selected = TvOption.entries.firstOrNull { it.name == state.getString("tv_options_selection") } ?: TvOption.INTERFACE
        showDescription(); linkFocus()
        listScroll.doOnLayout { listScroll.scrollTo(0, state.getInt("tv_options_scroll")) }
    }
}
