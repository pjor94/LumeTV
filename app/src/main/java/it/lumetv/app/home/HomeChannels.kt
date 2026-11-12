package it.lumetv.app.home

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import it.lumetv.app.integration.IntegrationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object HomeChannels {
    internal val lock = Any()
    const val CHANGE_JOB = 4_801
    const val BOUNDARY_JOB = 4_802
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences("home_channels", Context.MODE_PRIVATE)
    fun television(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    fun supported(context: Context) = Build.VERSION.SDK_INT >= 26 && television(context) &&
        context.packageManager.resolveContentProvider("android.media.tv", 0) != null

    internal fun configuration(context: Context): HomeConfiguration = prefs(context).let {
        HomeConfiguration(it.getBoolean("enabled", false),
            HomeSelection.entries.firstOrNull { mode -> mode.name == it.getString("selection", null) } ?: HomeSelection.FAVORITES,
            it.getStringSet("manual_ids", emptySet()).orEmpty().toSet(), it.getStringSet("suppressed_ids", emptySet()).orEmpty().toSet(),
            it.getStringSet("published_ids", emptySet()).orEmpty().toSet(), it.getLong("row_id", -1).takeIf { id -> id >= 0 },
            it.getBoolean("create_requested", false))
    }

    internal fun artworkNames(context: Context) = prefs(context).getStringSet("artwork_names", emptySet()).orEmpty().toSet()

    internal fun configure(context: Context, enabled: Boolean, selection: HomeSelection = configuration(context).selection,
                           manualIds: Set<String> = configuration(context).manualIds, restoreRemoved: Boolean = false,
                           requestRow: Boolean = false, restoreSelection: Boolean = false) = synchronized(lock) {
        val old = configuration(context)
        val editor = prefs(context).edit().putBoolean("enabled", enabled).putString("selection", selection.name)
            .putStringSet("manual_ids", manualIds.filter { Regex("[a-f0-9]{24}").matches(it) }.take(HomePublicationPlanner.LIMIT).toSet())
        if (enabled && (!old.enabled || restoreRemoved || requestRow)) editor.putBoolean("create_requested", true)
        if (enabled && selection == HomeSelection.MANUAL && restoreSelection) {
            editor.putStringSet("suppressed_ids", old.suppressedIds - manualIds)
                .putStringSet("published_ids", old.publishedIds - manualIds)
        }
        if (restoreRemoved) editor.putStringSet("suppressed_ids", emptySet()).putStringSet("published_ids", emptySet())
        check(editor.commit())
        changes.update { it + 1 }
    }

    fun summary(context: Context): String {
        if (!supported(context)) return "Disponibile sui dispositivi Android TV compatibili"
        if (!configuration(context).enabled) return if (prefs(context).getString("status", null) == HomeStatus.ERROR.name)
            "Rimozione da completare · Riprova" else "Non attivi"
        val count = prefs(context).getInt("count", 0)
        return when (prefs(context).getString("status", null)) {
            HomeStatus.PUBLISHED.name -> if (count == 1) "1 canale pubblicato" else "$count canali pubblicati"
            HomeStatus.HIDDEN.name -> (if (count == 1) "1 canale pronto" else "$count canali pronti") + " · Visibilità gestita dal launcher"
            HomeStatus.REMOVED.name -> "Riga rimossa dalla Home"
            HomeStatus.EMPTY.name -> "Nessun canale da mostrare"
            HomeStatus.ERROR.name -> "Pubblicazione non riuscita · Riprova"
            else -> "Pubblicazione in corso…"
        }
    }

    /** Cheap event hook. Jobs read local caches only and never resolve or fetch streams. */
    fun schedule(context: Context) {
        if (!supported(context)) return
        val config = configuration(context)
        if (!config.enabled && config.rowId == null && prefs(context).getString("status", null) != HomeStatus.ERROR.name) return
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(CHANGE_JOB, ComponentName(context, HomePublicationJob::class.java))
                .setMinimumLatency(1_000).setOverrideDeadline(10_000).setPersisted(true).build()
            check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS)
        }.onFailure { Log.w("LumeHome", "schedule_failed type=${it.javaClass.simpleName}") }
    }

    internal fun publish(context: Context): HomePublication? = synchronized(lock) {
        if (!supported(context)) return@synchronized null
        val now = System.currentTimeMillis()
        val config = configuration(context)
        try {
            val content = IntegrationStore.content(context, now)
            val publication = HomePublicationPlanner.synchronize(config, content, AndroidHomeRowStore(context), now)
            if (publication.rowId != null && prefs(context).getLong("logo_row_id", -1) != publication.rowId)
                HomeArtwork.rowLogo(context, publication.rowId)
            val names = content.channels.filter { it.id in publication.ids }.map(HomeArtwork::key).toSet()
            check(prefs(context).edit().putLong("row_id", publication.rowId ?: -1).putString("status", publication.status.name)
                .putInt("count", publication.ids.size).putStringSet("published_ids", publication.ids)
                .putStringSet("suppressed_ids", publication.suppressed).putStringSet("artwork_names", names)
                .putLong("logo_row_id", publication.rowId ?: -1)
                .putBoolean("create_requested", false).commit())
            HomeArtwork.prune(context, names)
            changes.update { it + 1 }
            publication
        } catch (error: Exception) {
            prefs(context).edit().putString("status", HomeStatus.ERROR.name).commit()
            changes.update { it + 1 }
            Log.w("LumeHome", "publish_failed type=${error.javaClass.simpleName}")
            HomePublication(config.rowId, HomeStatus.ERROR, config.publishedIds, config.suppressedIds, now + 15 * 60_000L)
        }
    }

    internal fun scheduleBoundary(context: Context, nextUpdate: Long) = synchronized(lock) {
        if (!supported(context)) return@synchronized
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val config = configuration(context)
        if ((!config.enabled && prefs(context).getString("status", null) != HomeStatus.ERROR.name) || nextUpdate <= 0) {
            scheduler.cancel(BOUNDARY_JOB); return@synchronized
        }
        val delay = (nextUpdate - System.currentTimeMillis()).coerceIn(60_000L, 6 * 60 * 60_000L)
        runCatching {
            check(scheduler.schedule(JobInfo.Builder(BOUNDARY_JOB, ComponentName(context, HomePublicationJob::class.java))
                .setMinimumLatency(delay).setOverrideDeadline(delay + 60_000L).setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS)
        }.onFailure { Log.w("LumeHome", "boundary_schedule_failed type=${it.javaClass.simpleName}") }
    }
}

class HomePublicationJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Int, kotlinx.coroutines.Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        running[params.jobId] = scope.launch {
            val result = withContext(Dispatchers.IO) { HomeChannels.publish(applicationContext) }
            jobFinished(params, false)
            running.remove(params.jobId)
            result?.let { HomeChannels.scheduleBoundary(applicationContext, it.nextUpdate) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class HomePublicationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Extras are deliberately ignored: only the provider's actual state can suppress a card.
        if (intent.action in setOf("android.media.tv.action.INITIALIZE_PROGRAMS", "android.media.tv.action.PREVIEW_PROGRAM_BROWSABLE_DISABLED",
                Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED))
            HomeChannels.schedule(context)
    }
}
