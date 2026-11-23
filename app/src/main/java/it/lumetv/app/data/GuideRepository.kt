package it.lumetv.app.data

import android.content.Context
import android.util.AtomicFile
import it.lumetv.app.integration.IntegrationStore
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async

class GuideRepository(context: Context, private val http: HttpClient, private val onLogos: (Map<String, String>) -> Unit = {},
    private val supplementRefresh:suspend (Boolean)->Unit = {EpgShareRepository.get(context).refresh(it)}) {
    private val app = context.applicationContext
    val personal=PersonalGuideRepository.get(app)
    private var builtin:Map<String,List<Programme>>?=null
    private val prefs = context.getSharedPreferences("guide", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(context.filesDir, "guide.json"))
    private val catalog = CatalogGuideStore(context.filesDir)
    private var pending:CatalogGuideImport?=null
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
        override fun read(): Map<String, List<Programme>> = synchronized(IntegrationStore.cacheLock) {
            if (!cache.baseFile.exists() && !File(cache.baseFile.path + ".bak").exists()) return@synchronized emptyMap()
            val root = JSONObject(cache.openRead().bufferedReader().use { it.readText() })
            root.keys().asSequence().associateWith { key -> root.getJSONArray(key).objects().map { programme ->
                Programme(programme.getString("title"), programme.getLong("start"), programme.getLong("end"),
                    programme.optString("description").take(1600))
            } }
        }
        override fun write(programmes: Map<String, List<Programme>>) = synchronized(IntegrationStore.cacheLock) {
            val root = JSONObject()
            programmes.forEach { (id, shows) -> root.put(id, JSONArray().apply { shows.forEach { show ->
                put(JSONObject().put("title", show.title).put("start", show.start).put("end", show.end).apply {
                    if (show.description.isNotBlank()) put("description", show.description)
                })
            } }) }
            val output = cache.startWrite()
            try {
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                pending?.let {catalog.commit(it)}
                cache.finishWrite(output)
            }
            catch (error: Exception) { cache.failWrite(output); throw error }
        }
    }
    private val sync = GuideSync(store, GuideSource { wanted, now ->
        val task=kotlinx.coroutines.currentCoroutineContext()
        guideHttp.read(guideHttp.request("https://api.epg-guide.com/get/wltv.xz", mapOf("User-Agent" to "WLTV ${HttpClient.USER_AGENT}"))) { response ->
            val input = response.body?.byteStream() ?: throw SourceException("Guida TV non disponibile.")
            input.use { compressed ->
                XZInputStream(LimitedInputStream(compressed, 32L * 1024 * 1024), 64 * 1024).use { xz ->
                    catalog.stage(LimitedInputStream(xz, 350L * 1024 * 1024),wanted,now,
                        active={task.ensureActive()},onLogos=onLogos).also {pending=it}.programmes
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
        // Import channel artwork once on upgrade, retaining any server retry deadline.
        if (prefs.getInt("logo_metadata_version", 0) < 1) {
            prefs.edit().putInt("logo_metadata_version", 1).putLong("updated", 0).apply()
        }
        if(prefs.getInt("catalog_metadata_version",0)<2) {
            prefs.edit().putInt("catalog_metadata_version",2).putLong("updated",0).apply()
        }
    }
    val lastUpdated: Long get() = store.updated
    val nextRetry: Long get() = store.retryAfter
    val failure: String get() = store.failure
    suspend fun local(): Map<String, List<Programme>> {
        val shows=sync.local();builtin=shows;IntegrationStore.guideChanged(app,shows,lastUpdated)
        val channels=PersonalPlaylistRepository.get(app).local().filter {!it.isVod}.take(100)
        val now=System.currentTimeMillis()
        return shows+personal.window(channels,now,now+GuideSchedule.WINDOW)
    }
    suspend fun window(channels:List<Channel>,from:Long,until:Long):Map<String,List<Programme>> =
        (builtin ?: sync.local().also {builtin=it})+personal.window(channels,from,until)
    suspend fun refresh(channels: List<Channel>, force: Boolean = false): Map<String, List<Programme>>? {
      refreshCatalog(channels,force)
      personal.refresh(force)
      val now=System.currentTimeMillis()
      return window(channels.filter {it.isPersonal}.take(100),now,now+GuideSchedule.WINDOW)
    }
    internal suspend fun refreshCatalog(force:Boolean=false) = refreshCatalog(ChannelRepository(app,http).backupBuiltins(),force)
    internal suspend fun publicGuideAvailable():Boolean=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        PublicGuideFallback(app.filesDir).ready(System.currentTimeMillis())
    }
    private suspend fun refreshCatalog(channels:List<Channel>,force:Boolean)=kotlinx.coroutines.coroutineScope {
        val extra=if(PersonalPlaylistRepository.get(app).state.value.any {it.enabled})async {supplementRefresh(force)} else null
        refreshLumeCatalog(channels,force)
        extra?.await()
        Unit
    }
    private suspend fun refreshLumeCatalog(channels:List<Channel>,force:Boolean) {
      try {
        sync.refresh(channels.filter {!it.isPersonal && !it.isVod}.map { it.epgId }.filter { it.isNotBlank() }.toSet(), force)?.also { shows ->
            builtin=shows
            IntegrationStore.guideChanged(app, shows, lastUpdated)
            Log.i("LumeGuide", "imported channels=${shows.size} programmes=${shows.values.sumOf { it.size }}")
        }
      } catch (error: Exception) {
        if (error !is kotlinx.coroutines.CancellationException) {
            // Protocol-policy exceptions contain host/protocol details, never a stream URL or token.
            val protocol = if (error is java.net.UnknownServiceException) " reason=${error.message?.replace('\n', ' ')?.take(220)}" else ""
            Log.w("LumeGuide", "refresh_failed type=${error.javaClass.simpleName} status=${(error as? SourceException)?.status ?: 0}$protocol")
        }
        if(error is kotlinx.coroutines.CancellationException)throw error
      } finally {
        pending?.close();pending=null
      }
    }
}

internal object XmlTvGuideParser {
    const val MAX_PER_CHANNEL = 96
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z", Locale.ROOT)
    fun parse(input: InputStream, wanted: Set<String>, now: Long, parser: XmlPullParser = Xml.newPullParser(),records:CatalogGuideSink?=null,
              onLogos: (Map<String, String>) -> Unit = {}): Map<String, List<Programme>> {
        val result = mutableMapOf<String, MutableList<Programme>>()
        val canonical = wanted.groupBy { it.lowercase(Locale.ROOT).removeSuffix(".it") }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        // These are explicit IDs/display names observed in the WLTV feed, not fuzzy matches.
        val aliases = mapOf("rairadio2visualradio" to "rairadio2", "Rai3TGR.it" to "rai3tgrveneto")
        val logos = linkedMapOf<String, String>()
        val logoWanted = wanted + setOf("rai3", "rairadio2visualradio")
        val logoCanonical = logoWanted.groupBy { it.lowercase(Locale.ROOT).removeSuffix(".it") }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        var logoId = ""
        var channelId="";var channelIcon="";val names=mutableListOf<String>();var wireProgramme="";var root=false;var closed=false
        // Some pull parsers do not expose this optional feature; their default is already false.
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        // EPGShare's public UTF-8 document starts with a BOM; explicit parser encoding does not consume it.
        val xml=java.io.PushbackInputStream(input,3)
        val prefix=ByteArray(3);var count=0
        while(count<prefix.size){val read=xml.read(prefix,count,prefix.size-count);if(read<0)break;if(read>0)count+=read}
        if(count!=3 || !prefix.contentEquals(byteArrayOf(0xef.toByte(),0xbb.toByte(),0xbf.toByte())))if(count>0)xml.unread(prefix,0,count)
        parser.setInput(xml, "UTF-8")
        var id = ""
        var start = 0L
        var end = 0L
        var title = ""
        var description = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if(event==XmlPullParser.START_TAG && parser.depth==1) {
                if(parser.name!="tv")throw SourceException("Il documento ricevuto non è una guida XMLTV.")
                root=true
            }
            if(event==XmlPullParser.END_TAG && parser.depth==1 && parser.name=="tv")closed=true
            if (event == XmlPullParser.START_TAG && parser.name == "channel" && parser.depth==2) {
                val wireId = parser.getAttributeValue(null, "id").orEmpty()
                channelId=wireId.take(300);channelIcon="";names.clear()
                logoId = when {
                    wireId in logoWanted -> wireId
                    aliases[wireId] in logoWanted -> aliases.getValue(wireId)
                    else -> logoCanonical[wireId.lowercase(Locale.ROOT).removeSuffix(".it")].orEmpty()
                }
            } else if(event==XmlPullParser.START_TAG && parser.name=="display-name" && channelId.isNotEmpty() && parser.depth==3) {
                val name=parser.nextText().trim().take(300)
                if(name.isNotEmpty() && names.size<10)names+=name
            } else if (event == XmlPullParser.START_TAG && parser.name == "icon" && channelId.isNotEmpty() && parser.depth==3) {
                LogoPolicy.url(parser.getAttributeValue(null, "src").orEmpty())?.let {
                    if(channelIcon.isEmpty())channelIcon=it.toString()
                    if(logoId.isNotEmpty())logos.putIfAbsent(logoId,it.toString())
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "channel" && parser.depth==2) {
                if(channelId.isNotEmpty())records?.channel(XmlChannel(channelId,names.toList(),channelIcon))
                logoId = "";channelId=""
            } else if (event == XmlPullParser.START_TAG && parser.name == "programme" && parser.depth==2) {
                val wireId = parser.getAttributeValue(null, "channel").orEmpty()
                wireProgramme=wireId.take(300)
                id = when {
                    wireId in wanted -> wireId
                    aliases[wireId] in wanted -> aliases.getValue(wireId)
                    else -> canonical[wireId.lowercase(Locale.ROOT).removeSuffix(".it")].orEmpty()
                }
                start = timestamp(parser.getAttributeValue(null, "start"))
                end = timestamp(parser.getAttributeValue(null, "stop"))
                title = ""
                description = ""
            } else if (event == XmlPullParser.START_TAG && parser.name == "title" && (id in wanted || records!=null) && end > now) {
                val italian = parser.getAttributeValue(null, "lang") == "it"
                val text = parser.nextText().take(300)
                if (title.isBlank() || italian) title = text
            } else if (event == XmlPullParser.START_TAG && parser.name == "desc" && (id in wanted || records!=null) && end > now) {
                val italian = parser.getAttributeValue(null, "lang") == "it"
                val text = parser.nextText().take(1600).trim()
                if (description.isBlank() || italian) description = text
            } else if (event == XmlPullParser.END_TAG && parser.name == "programme" && parser.depth==2 && title.isNotBlank() && start > 0 && end > start && end > now) {
              if(wireProgramme.isNotEmpty() && start<now+CatalogGuideStore.FUTURE)
                records?.programme(XmlProgramme(wireProgramme,title,description,start,end,""))
              if(id in wanted && start<now+86_400_000) {
                val shows = result.getOrPut(id) { mutableListOf() }
                val show = Programme(title, start, end, description)
                val duplicate = shows.indexOfFirst { it.start == start && it.end == end && it.title == title }
                if (duplicate < 0) shows += show
                else if (show.description.length > shows[duplicate].description.length) shows[duplicate] = show
                shows.sortBy { it.start }
                while (shows.size > MAX_PER_CHANNEL) shows.removeAt(shows.lastIndex)
              }
            }
            event = parser.next()
        }
        if(!root || !closed)throw SourceException("La guida XMLTV è incompleta.")
        onLogos(logos)
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
