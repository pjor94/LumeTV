package it.lumetv.app.update

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import it.lumetv.app.BuildConfig
import it.lumetv.app.R
import it.lumetv.app.data.SourceException
import it.lumetv.app.ui.TvStyle
import it.lumetv.app.ui.TvStyle.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Foreground checks only. Automatic discovery never opens a dialog over live TV. */
internal class UpdateController(
    private val activity: ComponentActivity,
    savedInstanceState: Bundle?,
    private val canPrompt: () -> Boolean,
    private val restoreFocus: (View?) -> Unit = { previous ->
        if (previous?.isAttachedToWindow == true && previous.isShown) previous.requestFocusFromTouch()
    },
    private val notice: (String?) -> Unit,
) {
    companion object {
        const val LAUNCH_KEY = "update_prompt_launch"
        private const val SESSION_KEY = "update_prompt_versions"
    }
    private val launchId = activity.intent.getStringExtra(LAUNCH_KEY)
        ?: savedInstanceState?.getString(LAUNCH_KEY) ?: UUID.randomUUID().toString()
    private val promptSession = UpdatePromptSession(savedInstanceState?.getString(LAUNCH_KEY) ?: launchId,
        savedInstanceState?.getIntArray(SESSION_KEY) ?: intArrayOf()).apply {
        enter(launchId, activity.intent.getIntArrayExtra(SESSION_KEY) ?: intArrayOf())
    }
    private val prefs = activity.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val store = object : UpdateStore {
        override var lastSuccess: Long
            get() = prefs.getLong("last_success", 0)
            set(value) { prefs.edit { putLong("last_success", value) } }
        override var lastAttempt: Long
            get() = prefs.getLong("last_attempt", 0)
            set(value) { prefs.edit { putLong("last_attempt", value) } }
        override var retryAfter: Long
            get() = prefs.getLong("retry_after", 0)
            set(value) { prefs.edit { putLong("retry_after", value) } }
        override var rateLimitUntil: Long
            get() = prefs.getLong("rate_limit_until", 0)
            set(value) { prefs.edit { putLong("rate_limit_until", value) } }
        override var cached: String?
            get() = prefs.getString("candidate", null)
            set(value) { prefs.edit { putString("candidate", value) } }
    }
    private val network = GithubUpdateClient()
    private val repository = UpdateRepository(network, store, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT)
    private val directory = File(activity.cacheDir, "updates")
    private val apk get() = File(directory, "update.apk")
    private var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit { putBoolean("automatic", value) } }
    private var job: Job? = null
    private var automaticJob: Job? = null
    private var started = false
    private var busy = false
    private var generation = 0
    private var ready: UpdateInfo? = runCatching {
        prefs.getString("prepared", null)?.let { UpdateInfo.parse(it) }?.takeIf { apk.isFile }
    }.getOrNull()
        set(value) { field = value; prefs.edit { putString("prepared", value?.json()) } }
    private var message: String? = null
    private var percent: Int? = null
    private var dialog: AlertDialog? = null
    private var promptDialog: AlertDialog? = null
    private var permissionDialog: AlertDialog? = null
    private var summary: TextView? = null
    private var progress: ProgressBar? = null
    private var autoToggle: CheckBox? = null
    val available get() = repository.available(includePrereleases = false)

    private val permission = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstall()) installReady()
        else { message = "Installazione non autorizzata. Consenti a Lume TV di installare app nelle impostazioni della TV."; show() }
    }
    private val installer = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        message = "Installazione chiusa. Se l’aggiornamento non è stato completato, puoi riprovare."
        show()
    }

    fun onStart() { started = true; render(); checkAutomatically() }
    fun onStop() {
        started = false
        automaticJob?.cancel()
        generation++
        job?.cancel()
        if (busy) message = "Operazione interrotta. Puoi riprovare da questa schermata."
        busy = false; percent = null
        dialog?.dismiss()
        permissionDialog?.dismiss()
        promptDialog?.dismiss()
    }
    fun checkAutomatically() {
        if (!started || !automatic) return
        if (!busy && repository.nextCheckDelay() == 0L) check(false) else scheduleAutomaticCheck()
        maybePrompt()
    }

    private fun scheduleAutomaticCheck() {
        automaticJob?.cancel()
        if (!started || !automatic) return
        automaticJob = activity.lifecycleScope.launch {
            delay(maxOf(1000L, repository.nextCheckDelay(), if (busy) 60_000L else 0L))
            checkAutomatically()
        }
    }

    fun saveState(state: Bundle) {
        state.putString(LAUNCH_KEY, promptSession.launchId)
        state.putIntArray(SESSION_KEY, promptSession.snapshot())
    }
    fun carrySession(intent: Intent): Intent = intent.putExtra(LAUNCH_KEY, promptSession.launchId)
        .putExtra(SESSION_KEY, promptSession.snapshot())
    fun onNewIntent(intent: Intent) {
        promptDialog?.dismiss()
        promptSession.enter(intent.getStringExtra(LAUNCH_KEY) ?: return, intent.getIntArrayExtra(SESSION_KEY) ?: intArrayOf())
        if (dialog?.isShowing == true) available?.let { promptSession.acknowledge(it.versionCode) }
        checkAutomatically()
    }

    fun maybePrompt() {
        if (!started || !automatic || busy || activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !activity.hasWindowFocus() ||
            dialog?.isShowing == true || permissionDialog?.isShowing == true || promptDialog?.isShowing == true || !canPrompt()) return
        val info = available ?: return
        if (!promptSession.shouldOffer(info.versionCode)) return
        val previousFocus = activity.currentFocus
        val window = AlertDialog.Builder(activity).setTitle(R.string.update_available_title)
            .setMessage(activity.getString(R.string.update_available_message, info.versionName, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_open) { _, _ -> show() }
            .setNegativeButton(R.string.update_later, null).create()
        promptDialog = window
        window.setOnDismissListener {
            if (promptDialog === window) promptDialog = null
            activity.window.decorView.post {
                if (started && canPrompt() && dialog?.isShowing != true && permissionDialog?.isShowing != true &&
                    promptDialog?.isShowing != true && !activity.isFinishing && !activity.isDestroyed) restoreFocus(previousFocus)
            }
        }
        window.show()
        promptSession.acknowledge(info.versionCode)
        window.getButton(AlertDialog.BUTTON_NEGATIVE).apply { isFocusableInTouchMode = true; requestFocus() }
    }

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        available?.let { promptSession.acknowledge(it.versionCode) }
        promptDialog?.dismiss()
        dialog?.dismiss()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
        }
        content.addView(TvStyle.text(activity, "Versione installata: ${BuildConfig.VERSION_NAME}", 14f, TvStyle.muted))
        summary = TvStyle.text(activity, "", 16f).apply { setPadding(0, activity.dp(16), 0, activity.dp(12)) }
        content.addView(summary)
        progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addView(progress, LinearLayout.LayoutParams(-1, activity.dp(8)))
        autoToggle = CheckBox(activity).apply {
            setText(R.string.update_auto_check)
            isChecked = automatic; minHeight = activity.dp(48)
            setOnCheckedChangeListener { _, checked ->
                automatic = checked
                if (checked) checkAutomatically() else automaticJob?.cancel()
            }
        }
        content.addView(autoToggle)
        val window = AlertDialog.Builder(activity).setTitle("Aggiornamenti").setView(content)
            .setPositiveButton("Controlla ora", null).setNeutralButton("Controlla ora", null)
            .setNegativeButton("Chiudi") { _, _ -> cancel() }.create()
        dialog = window
        window.setOnCancelListener { cancel() }
        window.setOnDismissListener {
            if (dialog === window) { dialog = null; summary = null; progress = null; autoToggle = null }
        }
        window.show()
        window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            when { ready != null && ready == available && apk.isFile -> installReady(); available != null -> download(); else -> check(true) }
        }
        window.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { check(true) }
        render()
        window.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
    }

    private fun cancel() {
        generation++; job?.cancel()
        if (busy) message = "Operazione annullata."
        busy = false; percent = null; render()
        scheduleAutomaticCheck()
    }

    private fun check(force: Boolean) {
        if (busy) return
        val token = ++generation
        busy = true
        if (force) message = "Controllo le release su GitHub…"
        render()
        job = activity.lifecycleScope.launch {
            try {
                repository.refresh(includePrereleases = false, force = force)
                ensureActive()
                if (token == generation) {
                    if (ready != null && ready != available) { ready = null; apk.delete() }
                    message = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { if (token == generation) message = errorMessage(error) }
            finally { if (token == generation) { busy = false; render(); scheduleAutomaticCheck(); maybePrompt() } }
        }
    }

    private fun download() {
        val info = available ?: return
        val token = ++generation
        busy = true; percent = 0; message = "Scarico Lume TV ${info.versionName}…"; render()
        job = activity.lifecycleScope.launch {
            try {
                val file = network.download(info, directory) { value ->
                    activity.runOnUiThread { if (token == generation) { percent = value; render() } }
                }
                UpdateInstaller.verify(activity, file, info)
                ensureActive()
                ready = info; busy = false; percent = null
                message = "Aggiornamento verificato e pronto da installare."
                requestInstall()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { apk.delete(); ready = null; message = errorMessage(error) }
            finally { if (token == generation) { busy = false; percent = null; render(); scheduleAutomaticCheck() } }
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    private fun requestInstall() {
        if (canInstall()) {
            dialog?.dismiss()
            try { installer.launch(UpdateInstaller.intent(activity, apk)) }
            catch (_: ActivityNotFoundException) { message = "Questa TV non dispone di un programma per installare le APK."; show() }
            catch (_: SecurityException) { message = "L’installazione è bloccata dalle impostazioni della TV."; show() }
        } else {
            permissionDialog = AlertDialog.Builder(activity).setTitle("Consenti l’aggiornamento di Lume TV")
                .setMessage("Nella schermata successiva consenti a Lume TV di installare app, poi torna qui per completare l’aggiornamento.")
                .setPositiveButton("Apri impostazioni") { _, _ ->
                    dialog?.dismiss()
                    try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri())) }
                    catch (_: ActivityNotFoundException) {
                        try { permission.launch(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                        catch (_: ActivityNotFoundException) { message = "Apri le impostazioni della TV e consenti a Lume TV di installare app."; show() }
                        catch (_: SecurityException) { message = "Le impostazioni di installazione sono bloccate su questa TV."; show() }
                    }
                    catch (_: SecurityException) { message = "Le impostazioni di installazione sono bloccate su questa TV."; show() }
                }.setNegativeButton("Più tardi", null).show()
        }
    }

    private fun installReady() {
        val info = ready?.takeIf { it == available } ?: return
        val token = ++generation
        busy = true; message = "Verifico l’aggiornamento…"; render()
        job = activity.lifecycleScope.launch {
            try { UpdateInstaller.verify(activity, apk, info); ensureActive(); busy = false; requestInstall() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { apk.delete(); ready = null; message = errorMessage(error); show() }
            finally { if (token == generation) { busy = false; render(); scheduleAutomaticCheck() } }
        }
    }

    private fun render() {
        val info = available
        if (dialog?.isShowing == true) info?.let { promptSession.acknowledge(it.versionCode) }
        notice(info?.versionName)
        val checked = if (store.lastSuccess > 0) "Ultimo controllo: " + SimpleDateFormat("d MMM, HH:mm", Locale.ITALY).format(Date(store.lastSuccess))
            else "Controllo all’avvio e ogni 30 minuti mentre usi Lume TV."
        summary?.text = message ?: if (info != null) {
            "Disponibile Lume TV ${info.versionName}.\n" +
                "L’aggiornamento conserva preferiti e impostazioni.\n$checked"
        } else if (store.lastSuccess > 0) "Nessun aggiornamento disponibile per questa TV.\n$checked"
        else "Controlla le nuove versioni su GitHub.\n$checked"
        progress?.apply { visibility = if (busy) View.VISIBLE else View.GONE; isIndeterminate = percent == null; progress = percent ?: 0 }
        autoToggle?.isEnabled = !busy
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = when { busy -> if (percent != null) "Download ${percent}%" else "Controllo…"
                info != null && ready == info && apk.isFile -> "Installa aggiornamento"
                info != null -> "Scarica e aggiorna"; else -> "Controlla ora" }
            isEnabled = !busy
        }
        dialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply { visibility = if (info != null) View.VISIBLE else View.GONE; isEnabled = !busy }
        dialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = if (busy) "Annulla" else "Chiudi"
    }

    private fun errorMessage(error: Exception): String = when {
        error is UpdateRateLimitException -> error.message.orEmpty()
        error is UpdateException -> error.message.orEmpty()
        error is SourceException && error.status in setOf(403, 429) -> "GitHub richiede una pausa. Riprova più tardi."
        else -> "Non riesco a verificare o scaricare l’aggiornamento. Controlla la connessione e riprova."
    }
}
