package it.lumetv.app.data

import it.lumetv.core.PlaylistEncoding
import it.lumetv.core.PlaylistFormat
import it.lumetv.core.PlaylistFormatException
import it.lumetv.core.PlaylistGroupProfile
import it.lumetv.core.PlaylistHeaders
import it.lumetv.core.PlaylistReadOptions
import it.lumetv.core.PlaylistContentKind
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class PlaylistResource(val label: String) {
    PLAYLIST("Download della lista"), MANIFEST("Manifest video"), MEDIA("Video e audio"),
    LICENSE("Licenze DRM"), KEY("Chiavi HLS"), LOGO("Loghi"), EPG("Guida programmi")
}

data class PlaylistAccessRule(val headers: Map<String, String> = emptyMap(), val origins: Set<String> = emptySet()) {
    override fun toString() = "PlaylistAccessRule()"
}

data class PlaylistSettings(
    val reading: PlaylistReadOptions = PlaylistReadOptions(),
    val access: Map<PlaylistResource, PlaylistAccessRule> = emptyMap(),
    val sessionCookies: Boolean = false,
    val artworkHttp: Boolean = false,
    val refreshMinutes: Int? = null,
    val guide: PersonalGuideSettings = PersonalGuideSettings(),
    val contentOverride:PlaylistContentKind?=null,
) {
    fun rule(role: PlaylistResource) = access[role] ?: PlaylistAccessRule()
    fun validated(): PlaylistSettings {
        if (refreshMinutes != null && refreshMinutes !in 15..10_080) throw PlaylistFormatException("Scegli un intervallo fra 15 e 10.080 minuti.")
        if(contentOverride==PlaylistContentKind.PLAYLIST)throw PlaylistFormatException("Scegli dirette o video come tipo dei contenuti.")
        return copy(guide=guide.validated(),access = access.mapValues { (_, rule) ->
            if (rule.origins.size > 16) throw PlaylistFormatException("Puoi autorizzare fino a 16 server per funzione.")
            PlaylistAccessRule(PlaylistHeaders.merge(rule.headers), rule.origins.map(::canonicalOrigin).toSet())
        }.filterValues { it.headers.isNotEmpty() || it.origins.isNotEmpty() })
    }
    internal fun json(): JSONObject = JSONObject().put("format", reading.format.name).put("encoding", reading.encoding.name)
        .put("groups", reading.groups.name).put("cookies", sessionCookies).put("artworkHttp", artworkHttp)
        .put("refreshMinutes", refreshMinutes).put("guide",guide.json()).put("contentOverride",contentOverride?.name).put("access", JSONObject().apply {
            access.forEach { (role, rule) -> put(role.name, JSONObject().put("headers", JSONObject(rule.headers)).put("origins", JSONArray(rule.origins.toList()))) }
        })
    override fun toString() = "PlaylistSettings(format=${reading.format}, encoding=${reading.encoding})"

    companion object {
        internal fun read(value: JSONObject?): PlaylistSettings {
            if (value == null) return PlaylistSettings()
            val access = value.optJSONObject("access") ?: JSONObject()
            require(access.length() <= PlaylistResource.entries.size)
            val rules = access.keys().asSequence().associate { name ->
                val rule = access.getJSONObject(name)
                val headers = rule.optJSONObject("headers") ?: JSONObject()
                require(headers.length() <= 32)
                val origins = rule.optJSONArray("origins") ?: JSONArray()
                require(origins.length() <= 16)
                PlaylistResource.valueOf(name) to PlaylistAccessRule(headers.keys().asSequence().associateWith { headers.getString(it) },
                    (0 until origins.length()).map { origins.getString(it) }.toSet())
            }
            return PlaylistSettings(PlaylistReadOptions(PlaylistFormat.valueOf(value.optString("format", "EXTENDED")),
                PlaylistEncoding.valueOf(value.optString("encoding", "UTF8")), PlaylistGroupProfile.valueOf(value.optString("groups", "CURRENT"))),
                rules, value.optBoolean("cookies"), value.optBoolean("artworkHttp"),
                if (value.isNull("refreshMinutes")) null else value.getInt("refreshMinutes"),PersonalGuideSettings.read(value.optJSONObject("guide")),
                if(value.isNull("contentOverride"))null else PlaylistContentKind.valueOf(value.getString("contentOverride"))).validated()
        }

        fun canonicalOrigin(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrNull()
            val url = raw.trim().toHttpUrlOrNull()
            if (uri == null || url == null || uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null || uri.path.orEmpty() !in setOf("", "/"))
                throw PlaylistFormatException("Indica il server con protocollo e porta, senza percorso o credenziali: per esempio https://cdn.example.com.")
            return origin(url)
        }
        internal fun origin(url: HttpUrl): String = "${url.scheme}://${if (':' in url.host) "[${url.host}]" else url.host}:${url.port}"

        fun parseHeaders(text: String): Map<String, String> {
            val rows = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (rows.size > 32) throw PlaylistFormatException("Puoi specificare fino a 32 header.")
            val headers = linkedMapOf<String, String>()
            rows.forEach { row ->
                if (':' !in row) throw PlaylistFormatException("Scrivi ogni header su una riga, nella forma Nome: valore.")
                headers[row.substringBefore(':').trim()] = row.substringAfter(':').trim()
            }
            return PlaylistHeaders.merge(headers)
        }
    }
}

/** A source policy owns its cookies and active calls. Replacement invalidates the entire context. */
class PersonalRequestContext internal constructor(
    settings: PlaylistSettings, val allowHttp: Boolean, val root: String,
    private val enabled: () -> Boolean = { true },
) {
    @Volatile var settings:PlaylistSettings=settings
        private set
    val token: String = UUID.randomUUID().toString()
    @Volatile var guideToken:String=UUID.randomUUID().toString()
        private set
    @Volatile private var active = true
    private val policyLock=Any()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val guideCalls=ConcurrentHashMap.newKeySet<Call>()
    private val jars = PlaylistResource.entries.associateWith { SessionCookies() }
    fun valid() = active && enabled()
    fun invalidate() = synchronized(policyLock) { active = false; calls.forEach(Call::cancel); jars.values.forEach(SessionCookies::clear) }
    internal fun <T> whileValid(action:()->T):T? = synchronized(policyLock) {if(valid())action() else null}
    internal fun invalidateGuide() = synchronized(policyLock) {
        guideToken=UUID.randomUUID().toString();guideCalls.forEach(Call::cancel);jars.getValue(PlaylistResource.EPG).clear()
    }
    internal fun updateGuideOnly(next:PlaylistSettings):Boolean = synchronized(policyLock) {
        val comparable=settings.copy(guide=next.guide,access=settings.access.filterKeys {it!=PlaylistResource.EPG}+next.access.filterKeys {it==PlaylistResource.EPG})
        if(comparable!=next)return@synchronized false
        if(settings!=next) {guideToken=UUID.randomUUID().toString();guideCalls.forEach(Call::cancel);jars.getValue(PlaylistResource.EPG).clear();settings=next}
        true
    }
    fun allowed(uri: URI, role: PlaylistResource): Boolean = valid() && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null &&
        (role!=PlaylistResource.LOGO || PersonalLogoPolicy.url(uri.toString(),settings.artworkHttp)!=null) &&
        PersonalPlaylistNetwork.allowed(uri, when(role) {PlaylistResource.LOGO->settings.artworkHttp;PlaylistResource.EPG->settings.guide.allowHttp;else->allowHttp})

    fun client(base: OkHttpClient, requestRoot: String, role: PlaylistResource, headerNames: Set<String>): OkHttpClient {
        val epoch=guideToken
        val rule = settings.rule(role)
        val initial = requireNotNull(requestRoot.toHttpUrlOrNull())
        fun trusted(url: HttpUrl) = PlaylistSettings.origin(url) == PlaylistSettings.origin(initial) || PlaylistSettings.origin(url) in rule.origins
        val cookies = object : CookieJar {
            override fun loadForRequest(url: HttpUrl) = if (settings.sessionCookies && valid() && trusted(url)) jars.getValue(role).loadForRequest(url) else emptyList()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                if (settings.sessionCookies && valid() && trusted(url)) jars.getValue(role).saveFromResponse(url, cookies)
            }
        }
        return PersonalPlaylistNetwork.client(base, requestRoot, headerNames, rule.origins, cookies) { (role!=PlaylistResource.EPG || epoch==guideToken) && allowed(it, role) }
            .newBuilder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls.add(call);if(role==PlaylistResource.EPG)guideCalls.add(call); if (!valid() || role==PlaylistResource.EPG && epoch!=guideToken) call.cancel() }
                override fun callEnd(call: Call) { calls.remove(call);guideCalls.remove(call) }
                override fun callFailed(call: Call, ioe: IOException) { calls.remove(call);guideCalls.remove(call) }
            }).build()
    }
    override fun toString() = "PersonalRequestContext(active=$active)"
}

private class SessionCookies : CookieJar {
    private val cookies = linkedMapOf<String, Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.entries.removeAll { it.value.expiresAt <= now }
        cookies.forEach { cookie ->
            val key = "${cookie.domain}|${cookie.path}|${cookie.name}"
            if (cookie.expiresAt <= now) this.cookies.remove(key) else this.cookies[key] = cookie
        }
        while (this.cookies.size > 128) this.cookies.remove(this.cookies.keys.first())
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.entries.removeAll { it.value.expiresAt <= System.currentTimeMillis() }
        return cookies.values.filter { it.matches(url) }
    }
    @Synchronized fun clear() { cookies.clear() }
}
