package it.lumetv.app.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import android.util.Xml
import it.lumetv.core.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class GuideRepository(context: Context, private val http: HttpClient) {
    private val prefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(context.filesDir, "guide.json"))
    // A slow TV may need longer to parse the guide than a stream session request.
    private val guideHttp = HttpClient(http.client.newBuilder().callTimeout(90, TimeUnit.SECONDS).build())
    private val store = object : GuideStore {
        override var updated: Long
            get() = prefs.getLong("updated", 0)
            set(value) { prefs.edit().putLong("updated", value).apply() }
        override var attempted: Long
            get() = prefs.getLong("attempted", 0)
            set(value) { prefs.edit().putLong("attempted", value).apply() }
        override var retryAfter: Long
            get() = prefs.getLong("retry_after", 0)
            set(value) { prefs.edit().putLong("retry_after", value).apply() }
        override var failure: String
            get() = prefs.getString("failure", "").orEmpty()
            set(value) { prefs.edit().putString("failure", value).apply() }
        override fun read(): Map<String, List<Programme>> {
            if (!cache.baseFile.exists() && !File(cache.baseFile.path + ".bak").exists()) return emptyMap()
            val root = JSONObject(cache.openRead().bufferedReader().use { it.readText() })
            return root.keys().asSequence().associateWith { key -> root.getJSONArray(key).objects().map { programme ->
                Programme(programme.getString("title"), programme.getLong("start"), programme.getLong("end"))
            } }
        }
        override fun write(programmes: Map<String, List<Programme>>) {
            val root = JSONObject()
            programmes.forEach { (id, shows) -> root.put(id, JSONArray().apply { shows.forEach { show ->
                put(JSONObject().put("title", show.title).put("start", show.start).put("end", show.end))
            } }) }
            val output = cache.startWrite()
            try { output.write(root.toString().toByteArray(Charsets.UTF_8)); cache.finishWrite(output) }
            catch (error: Exception) { cache.failWrite(output); throw error }
        }
    }
    private val sync = GuideSync(store, GuideSource { wanted, now ->
        guideHttp.read(guideHttp.request("https://api.epg-guide.com/get/wltv.xz", mapOf("User-Agent" to "WLTV ${HttpClient.USER_AGENT}"))) { response ->
            val input = response.body?.byteStream() ?: throw SourceException("Guida TV non disponibile.")
            input.use { compressed ->
                XZInputStream(LimitedInputStream(compressed, 32L * 1024 * 1024), 64 * 1024).use { xz ->
                    XmlTvGuideParser.parse(LimitedInputStream(xz, 350L * 1024 * 1024), wanted, now)
                }
            }
        }
    })

    init {
        // One new attempt after upgrading from the policy that could leave the EPG empty indefinitely.
        if (prefs.getInt("policy_version", 0) < 1) {
            prefs.edit().putInt("policy_version", 1).putLong("updated", 0)
                .putLong("retry_after", 0).putLong("attempted", 0).remove("failure").apply()
        }
    }
    val lastUpdated: Long get() = store.updated
    val nextRetry: Long get() = store.retryAfter
    val failure: String get() = store.failure
    suspend fun local(): Map<String, List<Programme>> = sync.local()
    suspend fun refresh(channels: List<Channel>, force: Boolean = false): Map<String, List<Programme>>? = try {
        sync.refresh(channels.map { it.epgId }.filter { it.isNotBlank() }.toSet(), force)?.also { shows ->
            Log.i("LumeGuide", "imported channels=${shows.size} programmes=${shows.values.sumOf { it.size }}")
        }
    } catch (error: Exception) {
        if (error !is kotlinx.coroutines.CancellationException) {
            // Protocol-policy exceptions contain host/protocol details, never a stream URL or token.
            val protocol = if (error is java.net.UnknownServiceException) " reason=${error.message?.replace('\n', ' ')?.take(220)}" else ""
            Log.w("LumeGuide", "refresh_failed type=${error.javaClass.simpleName} status=${(error as? SourceException)?.status ?: 0}$protocol")
        }
        throw error
    }
}

internal object XmlTvGuideParser {
    const val MAX_PER_CHANNEL = 96
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z", Locale.ROOT)
    fun parse(input: InputStream, wanted: Set<String>, now: Long, parser: XmlPullParser = Xml.newPullParser()): Map<String, List<Programme>> {
        val result = mutableMapOf<String, MutableList<Programme>>()
        val canonical = wanted.groupBy { it.lowercase(Locale.ROOT).removeSuffix(".it") }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        // These are explicit IDs/display names observed in the WLTV feed, not fuzzy matches.
        val aliases = mapOf("rairadio2visualradio" to "rairadio2", "Rai3TGR.it" to "rai3tgrveneto")
        // Some pull parsers do not expose this optional feature; their default is already false.
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(input, "UTF-8")
        var id = ""
        var start = 0L
        var end = 0L
        var title = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val wireId = parser.getAttributeValue(null, "channel").orEmpty()
                id = when {
                    wireId in wanted -> wireId
                    aliases[wireId] in wanted -> aliases.getValue(wireId)
                    else -> canonical[wireId.lowercase(Locale.ROOT).removeSuffix(".it")].orEmpty()
                }
                start = timestamp(parser.getAttributeValue(null, "start"))
                end = timestamp(parser.getAttributeValue(null, "stop"))
                title = ""
            } else if (event == XmlPullParser.START_TAG && parser.name == "title" && id in wanted && end > now) {
                val italian = parser.getAttributeValue(null, "lang") == "it"
                val text = parser.nextText().take(300)
                if (title.isBlank() || italian) title = text
            } else if (event == XmlPullParser.END_TAG && parser.name == "programme" && id in wanted && title.isNotBlank() && start > 0 && end > start && end > now && start < now + 86_400_000) {
                val shows = result.getOrPut(id) { mutableListOf() }
                shows += Programme(title, start, end)
                shows.sortBy { it.start }
                while (shows.size > MAX_PER_CHANNEL) shows.removeAt(shows.lastIndex)
            }
            event = parser.next()
        }
        return result
    }

    private fun timestamp(value: String?): Long = try {
        OffsetDateTime.parse(value?.trim(), formatter).toInstant().toEpochMilli()
    } catch (_: Exception) { 0 }
}

private class LimitedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
    private var consumed = 0L
    override fun read(): Int = super.read().also { if (it >= 0) checkLimit(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { if (it > 0) checkLimit(it) }
    private fun checkLimit(count: Int) {
        consumed += count
        if (consumed > maximum) throw SourceException("Il file della guida supera i limiti previsti.")
    }
}
