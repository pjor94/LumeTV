// SPDX-License-Identifier: GPL-3.0-or-later
// WorldRadio catalog supplied by WorldLiveTV / WLTV Group; Android import, 2026-11-11.
package it.lumetv.app.data

import android.content.Context
import android.util.AtomicFile
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.text.Normalizer

internal object RadioCatalog {
    const val SOURCE_URL = "https://www.worldtvlive.eu/world/files/worldradio/IT.bin"
    private const val LIMIT = 4 * 1024 * 1024
    private val priority = listOf("rai radio 1", "rai radio 2", "rai radio 3", "radio 24", "rds fm 103.0", "radio 105 fm", "r101", "virgin radio italy")
    private val cityNames = mapOf("Rome" to "Roma", "Florence" to "Firenze", "Milan" to "Milano", "Turin" to "Torino",
        "Venice" to "Venezia", "Naples" to "Napoli", "Genoa" to "Genova", "Padua" to "Padova", "Syracuse" to "Siracusa")
    private fun searchKey(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ITALY)
    fun matches(channel: Channel, query: String) = searchKey(channel.name + " " + channel.group).contains(searchKey(query.trim()))

    fun decode(encoded: String): String {
        if (encoded.length > LIMIT) throw SourceException("Catalogo radio troppo grande.")
        val bytes = encoded.trim().decodeBase64()?.toByteArray() ?: throw SourceException("Catalogo radio non valido.")
        val inflater = Inflater(true)
        try {
            return InflaterInputStream(ByteArrayInputStream(bytes), inflater).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > LIMIT) throw SourceException("Catalogo radio troppo grande.")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally { inflater.end() }
    }

    fun parse(text: String): List<Channel> {
        if (text.length > LIMIT) throw SourceException("Catalogo radio troppo grande.")
        val countries = JSONObject(text).optJSONArray("countries") ?: throw SourceException("Catalogo radio incompleto.")
        val stations = countries.objects().flatMap { country ->
            country.optJSONArray("places")?.objects().orEmpty().flatMap { place ->
                val originalCity = place.optString("title", place.optString("name")).take(160)
                val city = cityNames[originalCity] ?: originalCity
                place.optJSONArray("channels")?.objects().orEmpty().mapNotNull { item ->
                    val code = item.optString("code")
                    val name = item.optString("title").trim().take(160)
                    val source = item.optString("url").toHttpUrlOrNull()
                    if (!item.optBoolean("enabled", true) || name.isBlank() ||
                        !Regex("[A-Za-z0-9_-]{1,100}").matches(code) || source == null ||
                        source.username.isNotEmpty() || source.password.isNotEmpty()) return@mapNotNull null
                    val url = if (source.host == "radio.garden") source.newBuilder().scheme("https").build() else source
                    val id = MessageDigest.getInstance("SHA-256").digest("radio|$code".toByteArray())
                        .take(12).joinToString("") { "%02x".format(it) }
                    Channel(id, name, 9999, "", city, "", url.toString(), "direct", url.toString(), kind = MediaKind.RADIO)
                }
            }
        }.distinctBy { it.id }
        if (stations.isEmpty()) throw SourceException("Catalogo radio vuoto.")
        return stations.sortedWith(compareBy<Channel> {
            priority.indexOf(it.name.lowercase(Locale.ITALY)).let { index -> if (index < 0) Int.MAX_VALUE else index }
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { if (it.source.contains("radio.garden")) 1 else 0 })
            .distinctBy { it.name.lowercase(Locale.ITALY) + "|" + it.group.lowercase(Locale.ITALY) }
    }
}

internal class RadioRepository(context: Context, private val http: Transport) {
    companion object { private val cacheLock = Any() }
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("lume", Context.MODE_PRIVATE)
    private val cache = AtomicFile(File(app.filesDir, "radios.json"))
    private val mutex = Mutex()
    private val day = 24 * 60 * 60_000L

    suspend fun local(): List<Channel> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { synchronized(cacheLock) { cache.openRead().bufferedReader().use { RadioCatalog.parse(it.readText()) } } }.getOrNull()
                ?: app.assets.open("radios.json").bufferedReader().use { RadioCatalog.parse(it.readText()) }
        }
    }

    suspend fun refresh(force: Boolean): List<Channel>? = mutex.withLock {
        val now = System.currentTimeMillis()
        fun recent(key: String, interval: Long): Boolean {
            val saved = prefs.getLong(key, 0)
            return saved > 0 && now >= saved && now - saved < interval
        }
        if (!force && (recent("radios_updated", day) || recent("radios_attempted", 60 * 60_000L))) return null
        prefs.edit().putLong("radios_attempted", now).apply()
        val text = withContext(Dispatchers.Default) { RadioCatalog.decode(http.get(RadioCatalog.SOURCE_URL)) }
        val stations = withContext(Dispatchers.Default) { RadioCatalog.parse(text) }
        if (stations.size < 10) throw SourceException("Catalogo radio incompleto. Mantengo quello salvato.")
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) {
                val output = cache.startWrite()
                try { output.write(text.toByteArray()); cache.finishWrite(output) }
                catch (error: Exception) { cache.failWrite(output); throw error }
                prefs.edit().putLong("radios_updated", now).apply()
            }
        }
        stations
    }
}
