package it.lumetv.app

import android.os.Bundle
import android.util.AtomicFile
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import it.lumetv.app.data.XmlTvGuideParser
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.HttpClient
import it.lumetv.core.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File

/** Debug only. Input is a fixed private file staged with adb run-as, never an external URI. */
class GuideReplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val source = File(filesDir, "guide-replay.xz")
                    require(source.length() in 1..(2 * 1024 * 1024))
                    val wanted = M3uParser.parse(assets.open("channels.m3u").bufferedReader().use { it.readText() })
                        .filter { it.supported }.map { it.epgId }.toSet()
                    val now = System.currentTimeMillis()
                    val logos = ChannelLogoRepository(this@GuideReplayActivity, HttpClient(), lifecycleScope)
                    logos.local()
                    val shows = XZInputStream(source.inputStream(), 64 * 1024).use { XmlTvGuideParser.parse(it, wanted, now, onLogos = logos::updateSources) }
                    require(shows["rai1"].orEmpty().any { it.start <= now && it.end > now })
                    require(shows["canale5"].orEmpty().any { it.start <= now && it.end > now })
                    val root = JSONObject()
                    shows.forEach { (id, programmes) -> root.put(id, JSONArray().apply { programmes.forEach { p ->
                        put(JSONObject().put("title", p.title).put("start", p.start).put("end", p.end)
                            .put("description", p.description))
                    } }) }
                    val cache = AtomicFile(File(filesDir, "guide.json"))
                    val output = cache.startWrite()
                    try { output.write(root.toString().toByteArray()); cache.finishWrite(output) }
                    catch (error: Exception) { cache.failWrite(output); throw error }
                    // Keep any server retry deadline; replay must not trigger more network requests.
                    getSharedPreferences("guide", MODE_PRIVATE).edit().putLong("updated", now).putString("failure", "").commit()
                    source.delete()
                    Log.i("LumeGuide", "native_replay channels=${shows.size} programmes=${shows.values.sumOf { it.size }}")
                }
            } catch (error: Exception) {
                Log.e("LumeGuide", "native_replay_failed type=${error.javaClass.simpleName}")
            } finally { finish() }
        }
    }
}
