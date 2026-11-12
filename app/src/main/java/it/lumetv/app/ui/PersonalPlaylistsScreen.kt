package it.lumetv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PersonalPlaylistSource
import it.lumetv.app.data.PlaylistDraft
import it.lumetv.app.data.PlaylistLocation
import it.lumetv.app.data.readPlaylistBytes
import it.lumetv.app.ui.TvStyle.dp
import it.lumetv.app.ui.mobile.MobileStyle
import it.lumetv.core.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** An in-window settings page: it never creates or takes ownership of a player. */
class PersonalPlaylistsScreen(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val repository: PersonalPlaylistRepository,
    private val mobile: Boolean,
    private val trackDialog: (AlertDialog) -> Unit,
    private val closed: () -> Unit,
    private val openPlayback: () -> Unit,
    private val stopPlayback: () -> Unit,
) : FrameLayout(activity) {
    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val dialogSecondary = androidx.core.graphics.ColorUtils.blendARGB(TvStyle.muted, TvStyle.white, .4f)
    private val playbackControls = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val addButton = button("Aggiungi URL") { edit("") }
    private var replacingFile: String? = null
    private var selectedId: String? = null
    private var filePickerPackage: String? = null
    var isShowing = false
        private set
    private val picker = activity.registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>) = super.createIntent(context, input).apply {
            filePickerPackage?.let(::setPackage)
        }
    }) { uri ->
        if (uri != null) scope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null
                    } ?: "La mia lista"
                }
                val bytes = withContext(Dispatchers.IO) { activity.contentResolver.openInputStream(uri)?.use { it.readPlaylistBytes() }
                    ?: throw IOException("Il file non è leggibile. Selezionalo nuovamente.") }
                val previous = repository.state.value.firstOrNull { it.id == replacingFile }
                edit(previous?.name ?: name, replacing = replacingFile, bytes = bytes, kind = previous?.defaultKind ?: MediaKind.TV)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error) }
            finally { replacingFile = null }
        }
    }

    init {
        visibility = GONE; setBackgroundColor(TvStyle.background)
        isClickable = true; isFocusable = true
        ViewCompat.setAccessibilityPaneTitle(this, "Le mie liste")
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(if (mobile) 20 else 46), activity.dp(if (mobile) 12 else 26), activity.dp(if (mobile) 20 else 46), activity.dp(20))
        }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("Indietro") { hide() }, LinearLayout.LayoutParams(-2, -2))
        header.addView(TvStyle.text(activity, "Le mie liste", if (mobile) 26f else 32f, bold = true).apply {
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = activity.dp(20) })
        page.addView(header, LinearLayout.LayoutParams(-1, -2))
        page.addView(TvStyle.text(activity, "Aggiungi i tuoi canali TV e radio. Le liste restano su questo dispositivo.", 15f, TvStyle.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12); bottomMargin = activity.dp(16) })
        val actions = LinearLayout(activity)
        actions.addView(addButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = activity.dp(8) })
        actions.addView(button("Importa file") { chooseFile(null) }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(actions, LinearLayout.LayoutParams(-1, -2))
        page.addView(ScrollView(activity).apply {
            isSaveEnabled = false
            addView(column, LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = activity.dp(16) })
        playbackControls.addView(button("Apri la diretta") { hide(); openPlayback() }, LinearLayout.LayoutParams(0, -2, 1f))
        playbackControls.addView(button("Interrompi") { stopPlayback(); bindPlayback(false) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = activity.dp(8) })
        playbackControls.visibility = GONE
        page.addView(playbackControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
        addView(page, LayoutParams(-1, -1))
        scope.launch { repository.state.collect { if (isShowing) render() } }
    }

    fun show() {
        isShowing = true; visibility = VISIBLE; bringToFront()
        scope.launch { repository.local(); render(); focusSelection() }
    }
    fun focusSelection() { post {
        if (isShowing) findViewWithTag<View>(selectedId)?.requestFocusFromTouch() ?: addButton.requestFocusFromTouch()
    } }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && isShowing) focusSelection()
    }
    fun hide() { isShowing = false; visibility = GONE; closed() }
    fun handleBack(): Boolean = if (isShowing) { hide(); true } else false
    fun bindPlayback(active: Boolean) { playbackControls.visibility = if (active) VISIBLE else GONE }
    fun saveState(out: Bundle) {
        out.putBoolean("personal_playlists_open", isShowing); out.putString("personal_playlists_selection", selectedId)
        out.putString("personal_playlists_file_replacing", replacingFile)
    }
    fun restoreState(saved: Bundle?) {
        selectedId = saved?.getString("personal_playlists_selection")
        replacingFile = saved?.getString("personal_playlists_file_replacing")
        if (saved?.getBoolean("personal_playlists_open") == true) show()
    }

    private fun button(label: String, action: () -> Unit): android.widget.TextView =
        (if (mobile) MobileStyle.button(activity, label, action = action) else TvStyle.button(activity, label, action = action)).apply {
            minimumHeight = activity.dp(48)
            if (!mobile) setOnFocusChangeListener { view, focused ->
                setTextColor(if (focused) TvStyle.background else TvStyle.muted)
                TvStyle.focusMotion(view, focused, 1f)
            }
        }

    private fun render() {
        val focus = findFocus()?.tag as? String ?: selectedId
        column.removeAllViews()
        repository.storageError?.let { column.addView(TvStyle.text(activity, it, 15f, TvStyle.muted)) }
        if (repository.state.value.isEmpty()) {
            column.addView(TvStyle.text(activity, "Nessuna lista personale", 20f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(20) })
            column.addView(TvStyle.text(activity, "Puoi usare un indirizzo HTTPS oppure importare un file M3U/M3U8 in UTF-8. I canali del catalogo Lume restano disponibili.", 15f, TvStyle.muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(10) })
        }
        repository.state.value.forEach { source ->
            val state = when { source.refreshing -> "Aggiornamento in corso"; !source.enabled -> "Disattivata"; source.error.isNotBlank() -> "Copia salvata · Aggiornamento non riuscito"; else -> "Attiva" }
            val subtitle = "${source.count} canali · $state · ${if (source.location == PlaylistLocation.URL) "URL" else "File"}"
            val row = button(source.name + "\n" + subtitle) { selectedId = source.id; actions(source) }.apply {
                tag = source.id; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
                contentDescription = "${source.name}, $subtitle. Gestisci lista"
            }
            column.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(8) })
        }
        column.addView(TvStyle.text(activity, "Fino a 10 liste · 5.000 voci per lista · Stream HTTPS\nGuida XMLTV personale, catch-up e direttive DRM aggiuntive non sono inclusi.", 13f, TvStyle.muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(20); bottomMargin = activity.dp(12) })
        post { if (isShowing) (focus?.let { findViewWithTag<View>(it) } ?: addButton).requestFocusFromTouch() }
    }

    private fun actions(source: PersonalPlaylistSource) {
        val choices = mutableListOf<Pair<String, () -> Unit>>()
        choices += "Rinomina" to { rename(source) }
        if (source.location == PlaylistLocation.URL) {
            choices += "Aggiorna ora" to { operation { repository.refresh(source.id) } }
            choices += "Modifica URL" to { scope.launch { edit(source.name, repository.address(source.id), source.id, kind = source.defaultKind) } }
        } else choices += "Sostituisci file" to { chooseFile(source.id) }
        choices += (if (source.enabled) "Disattiva" else "Attiva") to { operation { repository.setEnabled(source.id, !source.enabled) } }
        choices += "Elimina lista" to {
            trackDialog(AlertDialog.Builder(activity).setTitle("Eliminare ${source.name}?")
                .setMessage("Verranno rimossi i canali della lista, i suoi preferiti e i recenti. L’eventuale riproduzione di questa lista verrà interrotta.")
                .setPositiveButton("Elimina") { _, _ -> operation {
                    repository.delete(source.id)
                    if (selectedId == source.id) selectedId = null
                    focusSelection()
                } }.setNegativeButton("Annulla", null).show())
        }
        val updated = if (source.checked > 0) "Ultimo controllo: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(source.checked))}" else "Lista non ancora aggiornata"
        val retry = if (source.nextRetry > System.currentTimeMillis()) "\nProssimo tentativo: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(source.nextRetry))}" else ""
        if (source.revision == 0L) choices.removeAll { it.first != "Elimina lista" }
        trackDialog(AlertDialog.Builder(activity).setTitle(source.name).setItems(choices.map { it.first }.toTypedArray()) { _, index -> choices[index].second() }
            .setNeutralButton("Stato") { _, _ ->
                trackDialog(AlertDialog.Builder(activity).setTitle(source.name).setMessage("${source.count} canali\n$updated$retry" +
                    if (source.error.isNotBlank()) "\n\n${source.error}" else "")
                    .setPositiveButton("Chiudi", null).show())
            }
            .setNegativeButton("Chiudi", null).show())
    }

    private fun rename(source: PersonalPlaylistSource) {
        val input = input("Nome della lista", source.name, 100)
        val dialog = AlertDialog.Builder(activity).setTitle("Rinomina lista").setView(input)
            .setPositiveButton("Salva", null).setNegativeButton("Annulla", null).show()
        trackDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            scope.launch { try { repository.rename(source.id, input.text.toString()); dialog.dismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { input.error = safeMessage(error) } }
        }
    }

    private fun edit(name: String, url: String = "", replacing: String? = null, bytes: ByteArray? = null, kind: MediaKind = MediaKind.TV) {
        val fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(activity.dp(20), activity.dp(8), activity.dp(20), 0) }
        val title = input("Nome della lista", name, 100)
        fields.addView(title, LinearLayout.LayoutParams(-1, -2))
        val address = input("Indirizzo HTTPS della lista", url, 8192).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        if (bytes == null) fields.addView(address, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        fields.addView(TvStyle.text(activity, "Tipo dei canali senza indicazione nella lista", 14f, dialogSecondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(16) })
        val type = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, arrayOf("TV", "Radio"))
            minimumHeight = activity.dp(48); setSelection(if (kind == MediaKind.RADIO) 1 else 0)
        }
        fields.addView(type, LinearLayout.LayoutParams(-1, -2))
        val status = TvStyle.text(activity, "La verifica legge la lista; i singoli canali verranno aperti quando li scegli.", 14f, dialogSecondary)
        fields.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12); bottomMargin = activity.dp(12) })
        val dialog = AlertDialog.Builder(activity).setTitle(if (replacing == null) "Aggiungi lista" else "Sostituisci lista")
            .setView(ScrollView(activity).apply { addView(fields) }).setPositiveButton("Verifica lista", null).setNegativeButton("Annulla", null).show()
        trackDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (title.text.isNullOrBlank()) { title.error = "Assegna un nome alla lista"; return@setOnClickListener }
            val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            action.isEnabled = false; status.text = "Verifico la lista…"
            scope.launch {
                try {
                    val selectedKind = if (type.selectedItemPosition == 1) MediaKind.RADIO else MediaKind.TV
                    val draft = if (bytes == null) repository.prepareUrl(address.text.toString(), selectedKind) else repository.prepareFile(bytes, selectedKind)
                    if (dialog.isShowing) { dialog.dismiss(); preview(title.text.toString(), draft, replacing) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { status.text = safeMessage(error) }
                finally { action.isEnabled = true }
            }
        }
    }

    private fun preview(name: String, draft: PlaylistDraft, replacing: String?) {
        val report = draft.report
        val sample = report.entries.take(5).joinToString("\n") { it.name }
        val imported = if (report.entries.size == 1) "1 voce importabile" else "${report.entries.size} voci importabili"
        val excluded = if (report.excluded == 1) "1 esclusa" else "${report.excluded} escluse"
        val duplicates = if (report.duplicates == 1) "1 duplicata" else "${report.duplicates} duplicate"
        val summary = "$imported · $excluded · $duplicates\n\n$sample" +
            (if (report.entries.size > 5) "\n…" else "") + (if (report.warnings.isNotEmpty()) "\n\n" + report.warnings.take(5).joinToString("\n") else "")
        val dialog = AlertDialog.Builder(activity).setTitle(name).setMessage(summary).setPositiveButton(if (replacing == null) "Importa" else "Sostituisci", null)
            .setNegativeButton("Annulla", null).show()
        trackDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE); button.isEnabled = false
            scope.launch { try { selectedId = repository.save(name, draft, replacing); dialog.dismiss() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure(error) }
                finally { button.isEnabled = true } }
        }
    }

    private fun chooseFile(replacing: String?) {
        replacingFile = replacing
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION")
        val handlers = activity.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        filePickerPackage = handlers.firstOrNull { !it.activityInfo.packageName.contains("frameworkpackagestubs") }?.activityInfo?.packageName
        if (filePickerPackage == null) { unavailablePicker(); return }
        try { picker.launch(arrayOf("*/*")) }
        catch (_: android.content.ActivityNotFoundException) { unavailablePicker() }
    }

    private fun unavailablePicker() {
        replacingFile = null
        trackDialog(AlertDialog.Builder(activity).setTitle("Selettore file non disponibile")
            .setMessage("Questo dispositivo non offre un selettore di documenti. Puoi aggiungere la lista tramite URL HTTPS.")
            .setPositiveButton("Chiudi", null).show())
    }

    fun chooseSource(current: String, includeAll: Boolean, kind: MediaKind?, choose: (String) -> Unit) {
        val choices = mutableListOf("builtin" to "Catalogo Lume")
        if (includeAll) choices.add(0, "all" to "Tutte le liste")
        repository.state.value.filter { it.enabled && (kind == null || repository.cached().any { c -> c.sourceId == it.id && c.kind == kind }) }
            .forEach { choices += it.id to (it.name + if (it.name.equals("Catalogo Lume", true)) " (personale)" else "") }
        trackDialog(AlertDialog.Builder(activity).setTitle("Scegli la lista")
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), choices.indexOfFirst { it.first == current }) { dialog, index ->
                choose(choices[index].first); dialog.dismiss()
            }.setNegativeButton("Annulla", null).show())
    }

    private fun input(hintText: String, value: String, limit: Int) = EditText(activity).apply {
        hint = hintText; contentDescription = hintText; setText(value); setTextColor(TvStyle.white); setHintTextColor(dialogSecondary)
        textSize = 16f; minimumHeight = activity.dp(48); maxLines = 3; filters = arrayOf(InputFilter.LengthFilter(limit))
        isSaveEnabled = false
    }
    private fun operation(action: suspend () -> Unit) { scope.launch { try { action() }
        catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure(error) } } }
    private fun safeMessage(error: Exception) = when (error) {
        is it.lumetv.core.PlaylistFormatException, is it.lumetv.app.data.PlaylistFetchException -> error.message.orEmpty()
        is IOException -> if (error.javaClass == IOException::class.java) error.message ?: "Operazione non riuscita. Riprova." else "Connessione non riuscita. Controlla l’indirizzo e riprova."
        else -> "Operazione non riuscita. La lista precedente è stata conservata."
    }
    private fun failure(error: Exception) { trackDialog(AlertDialog.Builder(activity).setTitle("Lista personale").setMessage(safeMessage(error)).setPositiveButton("Chiudi", null).show()) }
}
