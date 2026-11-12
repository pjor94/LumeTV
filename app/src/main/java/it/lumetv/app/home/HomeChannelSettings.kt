package it.lumetv.app.home

import android.app.AlertDialog
import android.content.Intent
import android.media.tv.TvContract
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.app.integration.SharedCatalog
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A narrow extension of the existing native options dialogs, shared by both interfaces on a TV. */
class HomeChannelSettings(
    private val activity: ComponentActivity, private val scope: CoroutineScope,
    private val track: (AlertDialog) -> Unit, private val visible: () -> Boolean,
    private val changed: () -> Unit, private val message: (String) -> Unit,
) {
    private var working = false
    private val browse = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            withContext(Dispatchers.IO) {
                HomeChannels.publish(activity.applicationContext)?.let { HomeChannels.scheduleBoundary(activity.applicationContext, it.nextUpdate) }
            }
            changed()
        }
    }

    fun show() {
        if (working) { message("Aggiornamento della Home in corso…"); return }
        if (!HomeChannels.supported(activity)) {
            track(AlertDialog.Builder(activity).setTitle("Canali nella Home")
                .setMessage("La pubblicazione dei canali richiede un dispositivo Android TV con Android 8 o successivo e un launcher compatibile. " +
                    "Puoi continuare a usare Lume TV e il collegamento con AURA.")
                .setPositiveButton("Chiudi", null).show())
            return
        }
        scope.launch {
            val content = withContext(Dispatchers.IO) { IntegrationStore.content(activity.applicationContext) }
            if (visible() && !activity.isFinishing && !activity.isDestroyed) showActions(content)
        }
    }

    private fun showActions(content: SharedCatalog) {
        val config = HomeChannels.configuration(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(4), activity.dp(24), activity.dp(8))
        }
        val detail = HomeChannels.summary(activity) + "\n\n" +
            "Mostra fino a 24 canali nella Home dei launcher compatibili. Ogni tessera apre la diretta in Lume TV.\n\n" +
            "Condividi nomi, immagini e programma in onda con la Home. Liste personali e radio sono escluse. " +
            "Il consenso per AURA resta separato."
        body.addView(TvStyle.text(activity, detail, 16f, TvStyle.white), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = activity.dp(16)
        })
        val dialog = AlertDialog.Builder(activity).setTitle("Canali nella Home")
            .setView(ScrollView(activity).apply { addView(body) }).setNegativeButton("Chiudi", null).create()
        fun action(label: String, callback: () -> Unit) {
            body.addView(TvStyle.button(activity, label) { dialog.dismiss(); callback() }.apply {
                minimumHeight = activity.dp(48); textSize = 16f
                setTextColor(TvStyle.white)
                setOnFocusChangeListener { _, focused -> setTextColor(if (focused) TvStyle.background else TvStyle.white) }
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(8) })
        }
        action(if (config.enabled) "Canali da mostrare" else "Attiva e scegli canali") { chooseMode(content) }
        if (config.enabled) {
            action("Mostra nella Home") {
                update(config.selection, config.manualIds, requestRow = true, askBrowsable = true)
            }
            if (config.suppressedIds.isNotEmpty()) action("Ripristina tessere rimosse") {
                update(config.selection, config.manualIds, restore = true)
            }
            action("Disattiva e rimuovi") { disable() }
        } else if (HomeChannels.summary(activity).startsWith("Rimozione")) action("Completa rimozione") { disable() }
        dialog.show(); track(dialog)
        body.post { body.getChildAt(1)?.requestFocusFromTouch() }
    }

    private fun chooseMode(content: SharedCatalog) {
        val config = HomeChannels.configuration(activity)
        var mode = config.selection
        val count = content.channels.count { !it.radio && it.favorite }
        val dialog = AlertDialog.Builder(activity).setTitle("Canali da mostrare")
            .setSingleChoiceItems(arrayOf("I miei preferiti ($count, fino a 24)", "Selezione manuale"), mode.ordinal) { _, index ->
                mode = HomeSelection.entries[index]
            }.setPositiveButton("Continua") { _, _ ->
                if (mode == HomeSelection.MANUAL) chooseChannels(content)
                else update(mode, config.manualIds, askBrowsable = !config.enabled)
            }.setNegativeButton("Annulla", null).show()
        track(dialog)
    }

    private fun chooseChannels(content: SharedCatalog) {
        val config = HomeChannels.configuration(activity)
        val channels = content.channels.filterNot { it.radio }
            .sortedWith(compareBy<it.lumetv.app.integration.SharedChannel> { it.number ?: Int.MAX_VALUE }.thenBy { it.name })
        if (channels.isEmpty()) { message("Il catalogo integrato non contiene canali disponibili"); return }
        val selected = channels.filter { it.id in config.manualIds }.map { it.id }.toMutableSet()
        val checks = BooleanArray(channels.size) { channels[it].id in selected }
        val dialog = AlertDialog.Builder(activity).setTitle("Scegli canali · ${selected.size}/24")
            .setMultiChoiceItems(channels.map { "${it.number?.let { number -> "$number · " }.orEmpty()}${it.name}" }.toTypedArray(), checks) { owner, index, checked ->
                val window = owner as AlertDialog
                val id = channels[index].id
                if (checked && selected.size >= HomePublicationPlanner.LIMIT) {
                    checks[index] = false
                    window.listView.setItemChecked(index, false)
                    window.listView.announceForAccessibility("Puoi scegliere fino a 24 canali")
                } else if (checked) selected += id else selected -= id
                window.setTitle("Scegli canali · ${selected.size}/24")
                window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = selected.isNotEmpty()
            }.setPositiveButton("Pubblica") { _, _ -> update(HomeSelection.MANUAL, selected, reselect = true, askBrowsable = !config.enabled) }
            .setNeutralButton("Deseleziona tutti", null).setNegativeButton("Annulla", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = selected.isNotEmpty()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            selected.clear(); checks.fill(false)
            channels.indices.forEach { dialog.listView.setItemChecked(it, false) }
            dialog.setTitle("Scegli canali · 0/24"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        }
        track(dialog)
    }

    private fun update(mode: HomeSelection, ids: Set<String>, restore: Boolean = false, requestRow: Boolean = false,
                       reselect: Boolean = false, askBrowsable: Boolean = false) {
        publish({ HomeChannels.configure(activity, true, mode, ids, restore, requestRow, reselect) }, askBrowsable)
    }

    private fun disable() = publish({ HomeChannels.configure(activity, false) }, false)

    private fun publish(configure: () -> Unit, askBrowsable: Boolean) {
        if (working) return
        working = true; message("Aggiorno i canali nella Home…")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    configure()
                    HomeChannels.publish(activity.applicationContext).also {
                        it?.let { publication -> HomeChannels.scheduleBoundary(activity.applicationContext, publication.nextUpdate) }
                    }
                }
                changed(); message(HomeChannels.summary(activity))
                if (askBrowsable && result?.rowId != null && result.ids.isNotEmpty() && result.status != HomeStatus.ERROR && visible())
                    requestBrowsable(result.rowId)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                message("Non riesco a salvare la scelta. Riapri Canali nella Home e riprova.")
            } finally { working = false }
        }
    }

    private fun requestBrowsable(id: Long) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            browse.launch(Intent(TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContract.EXTRA_CHANNEL_ID, id))
        } catch (_: RuntimeException) {
            track(AlertDialog.Builder(activity).setTitle("Canali pronti")
                .setMessage("Apri le impostazioni del tuo launcher e abilita la riga Lume TV, se disponibile. " +
                    "La visualizzazione e l’ordine delle righe dipendono dal launcher.")
                .setPositiveButton("Chiudi", null).show())
        }
    }
}
