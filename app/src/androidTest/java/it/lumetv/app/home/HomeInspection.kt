package it.lumetv.app.home

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentUris
import android.database.Cursor
import android.media.tv.TvContract
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Emulator-only inspection of the real provider as the owning app. Never included in the app APK. */
class HomeInspection : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle) { super.onCreate(arguments); this.arguments = arguments; start() }
    override fun onStart() {
        try {
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Only disposable emulators are supported" }
            check(Build.VERSION.SDK_INT >= 26)
            val context = targetContext
            if(arguments.getString("operation").orEmpty().startsWith("playlist-")) {
                val result=if(arguments.getString("operation")=="playlist-large")it.lumetv.app.playlists.LargePlaylistInspection.run(context,arguments)
                else if(arguments.getString("operation")=="playlist-continuous")it.lumetv.app.playlists.ContinuousCatalogInspection.run(this,arguments)
                else if(arguments.getString("operation")=="playlist-vod-detection")it.lumetv.app.playlists.VodDetectionInspection.run(this,arguments)
                else if(arguments.getString("operation")=="playlist-video-library")it.lumetv.app.playlists.VideoLibraryInspection.run(this,arguments)
                else if(arguments.getString("operation")=="playlist-video-navigation")it.lumetv.app.playlists.VideoNavigationInspection.run(this,arguments)
                else if(arguments.getString("operation")=="playlist-video-performance")it.lumetv.app.playlists.VideoPerformanceInspection.run(this,arguments)
                else if(arguments.getString("operation")=="playlist-pages")it.lumetv.app.playlists.PlaylistPagesInspection.run(this,arguments)
                else if(arguments.getString("operation")=="playlist-def")kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    it.lumetv.app.playlists.OnDemandInspection.run(context,requireNotNull(arguments.getString("fixtureRoot")),arguments.getString("mode")=="phone")
                } else it.lumetv.app.playlists.PlaylistInspection.run(context,arguments)
                finish(Activity.RESULT_OK,Bundle().apply {putString("snapshot",result.toString())});return
            }
            val store = AndroidHomeRowStore(context)
            when (arguments.getString("operation", "snapshot")) {
                "snapshot" -> Unit
                "publish" -> HomeChannels.publish(context)
                "remove-card" -> {
                    val key = arguments.getString("id") ?: error("Missing channel ID")
                    val program = store.rows().flatMap { store.programs(it.id) }.single { it.key == key }
                    store.deleteProgram(program.id)
                }
                "remove-row" -> store.rows().forEach { store.deleteRow(it.id) }
                else -> error("Unsupported inspection operation")
            }
            val rows = JSONArray()
            val allPrograms = JSONArray()
            store.rows().forEach { row ->
                rows.put(JSONObject().put("id", row.id).put("browsable", row.browsable))
                context.contentResolver.query(TvContract.buildPreviewProgramsUriForChannel(row.id),
                    arrayOf("_id", "channel_id", "internal_provider_id", "title", "short_description", "intent_uri", "poster_art_uri",
                        "browsable", "type", "live", "weight"), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val item = JSONObject()
                        cursor.columnNames.forEachIndexed { index, name ->
                            item.put(name, if (cursor.isNull(index)) JSONObject.NULL else when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                else -> cursor.getString(index)
                            })
                        }
                        allPrograms.put(item)
                    }
                }
            }
            val result = JSONObject().put("rows", rows).put("programs", allPrograms).put("summary", HomeChannels.summary(context))
            finish(Activity.RESULT_OK, Bundle().apply { putString("snapshot", result.toString()) })
        } catch (error: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("failure", error.javaClass.simpleName + ": " + error.message); putString("at", error.stackTrace.firstOrNull { it.className.startsWith("it.lumetv.") }?.toString()) })
        }
    }
}
