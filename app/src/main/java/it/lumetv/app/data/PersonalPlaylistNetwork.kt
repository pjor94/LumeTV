package it.lumetv.app.data

import it.lumetv.core.PersonalM3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class PlaylistFetchException(val status: Int = 0, val retryAt: Long = 0, message: String) : IOException(message)
class PlaylistDownload(val text: String?, val finalUrl: String, val etag: String? = null, val modified: String? = null,
    private val input: InputStream? = null, private val release: (() -> Unit)? = null) : Closeable {
    private val closed = AtomicBoolean()
    val unchanged get() = text == null && input == null
    fun reader(progress: (Long) -> Unit = {}, encoding: it.lumetv.core.PlaylistEncoding = it.lumetv.core.PlaylistEncoding.UTF8,
               budget: PlaylistByteBudget = PlaylistByteBudget()): Reader {
        val source = input ?: text?.byteInputStream() ?: throw IOException("La lista è vuota.")
        return InputStreamReader(LimitedPlaylistInput(source, progress, budget), java.nio.charset.Charset.forName(encoding.charset).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    }
    override fun close() {
        if (closed.compareAndSet(false,true)) { runCatching { release?.invoke() }; runCatching { input?.close() } }
    }
    override fun toString() = "PlaylistDownload(unchanged=$unchanged)"
    companion object { fun stream(input: InputStream) = PlaylistDownload(null,"",input=input) }
}
fun interface PlaylistFetcher { suspend fun fetch(url: String, etag: String?, modified: String?): PlaylistDownload }

object PersonalPlaylistNetwork {
    fun allowed(uri: URI, allowHttp: Boolean = false): Boolean = uri.scheme.equals("https", true) ||
        allowHttp && uri.scheme.equals("http", true)

    /** Every request, redirect and HLS/DASH child request passes through this policy. */
    fun client(base: OkHttpClient, root: String, scopedHeaders: Set<String> = emptySet(),
               trustedOrigins: Set<String> = emptySet(), cookies: okhttp3.CookieJar = okhttp3.CookieJar.NO_COOKIES,
               policy: (URI) -> Boolean = { allowed(it) }): OkHttpClient {
        val origin = root.toHttpUrl().let { Triple(it.scheme, it.host, it.port) }
        val explicitCookie = ThreadLocal<Boolean>()
        val jar = object : okhttp3.CookieJar {
            override fun loadForRequest(url: okhttp3.HttpUrl) = if (explicitCookie.get() == true) emptyList() else cookies.loadForRequest(url)
            override fun saveFromResponse(url: okhttp3.HttpUrl, values: List<okhttp3.Cookie>) = cookies.saveFromResponse(url, values)
        }
        return base.newBuilder().apply { networkInterceptors().removeAll { it === BuiltinNetworkPolicy } }
            .cookieJar(jar).followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            var request = chain.request()
            var redirects = 0
            while (true) {
                val target = URI(request.url.toString())
                if (!policy(target) || target.rawUserInfo != null) throw IOException("La connessione richiede il consenso HTTP per questa lista.")
                if (request.url.let { Triple(it.scheme, it.host, it.port) } != origin && PlaylistSettings.origin(request.url) !in trustedOrigins) {
                    request = request.newBuilder().apply {
                        (scopedHeaders + setOf("Authorization", "Cookie")).filterNot { it.equals("User-Agent", true) }
                            .forEach(::removeHeader)
                    }.build()
                }
                explicitCookie.set(request.header("Cookie") != null)
                val response = try { chain.proceed(request) } finally { explicitCookie.remove() }
                if (response.code !in setOf(301, 302, 303, 307, 308)) return@addInterceptor response
                val code = response.code
                val next = response.header("Location")?.let(request.url::resolve)
                response.close()
                if (next == null || redirects++ >= 5) throw IOException("La sorgente ha restituito troppi reindirizzamenti.")
                if(request.body!=null && code in setOf(307,308) && PlaylistSettings.origin(next)!=PlaylistSettings.origin(request.url) &&
                    PlaylistSettings.origin(next) !in trustedOrigins)
                    throw IOException("Il reindirizzamento della richiesta richiede un server autorizzato nelle impostazioni della lista.")
                request = request.newBuilder().url(next).apply {
                    if ((code == 303 && request.method != "HEAD") || (code in setOf(301,302) && request.method == "POST")) {
                        method("GET",null)
                        removeHeader("Content-Length"); removeHeader("Content-Type"); removeHeader("Transfer-Encoding")
                    }
                }.build()
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }.build()
    }

    fun fetcher(http: OkHttpClient = HttpClient().client, allowHttp: Boolean = false,
                httpAllowed: () -> Boolean = { true }, context: PersonalRequestContext? = null): PlaylistFetcher = PlaylistFetcher { url, etag, modified ->
        val headers = context?.settings?.rule(PlaylistResource.PLAYLIST)?.headers.orEmpty()
        val request = Request.Builder().url(url).header("User-Agent", HttpClient.USER_AGENT)
            .apply { headers.forEach { (name, value) -> header(name, value) }; etag?.let { header("If-None-Match", it) }; modified?.let { header("If-Modified-Since", it) } }.build()
        val client = context?.client(http,context.root.ifBlank { url },PlaylistResource.PLAYLIST,headers.keys)
            ?: client(http,url,policy={ allowed(it,allowHttp && httpAllowed()) })
        val call=client.newBuilder().callTimeout(5,TimeUnit.MINUTES).build().newCall(request)
        val response=call.awaitResponse()
        try {
            if (response.code == 304) {
                val download=PlaylistDownload(null,response.request.url.toString(),response.header("ETag"),response.header("Last-Modified"))
                response.close();return@PlaylistFetcher download
            }
            if (!response.isSuccessful) {
                val retry = retryAfter(response.header("Retry-After"), System.currentTimeMillis())
                throw PlaylistFetchException(response.code, retry, when (response.code) {
                    401, 403 -> "Accesso alla lista negato. Controlla l’indirizzo e le credenziali."
                    404, 410 -> "La lista non è più disponibile a questo indirizzo."
                    429 -> "Il servizio richiede una pausa. Riprova più tardi."
                    else -> "Il servizio della lista non risponde. La copia salvata resta disponibile."
                })
            }
            val input=response.body?.byteStream() ?: throw IOException("La lista è vuota.")
            PlaylistDownload(null,response.request.url.toString(),response.header("ETag"),response.header("Last-Modified"),input) { call.cancel();response.close() }
        } catch(error:Exception) { response.close();throw error }
    }

    suspend fun detectMime(url: String, headers: Map<String, String>, http: OkHttpClient, allowHttp: Boolean = false,
                           context: PersonalRequestContext? = null): String? {
        val path = URI(url).path.orEmpty().lowercase()
        if (path.endsWith(".m3u8")) return "application/x-mpegURL"
        if (path.endsWith(".mpd")) return "application/dash+xml"
        if (listOf(".mp4", ".ts", ".mp3", ".aac", ".m4a", ".ogg", ".webm", ".mkv").any(path::endsWith)) return null
        val probe = (context?.client(http,url,PlaylistResource.MANIFEST,headers.keys)
            ?: client(http, url, headers.keys, policy = { allowed(it,allowHttp) })).newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url).apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        return probe.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw PlaylistFetchException(response.code, message = "Il canale personale non autorizza la riproduzione.")
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
            when {
                contentType.contains("mpegurl") -> "application/x-mpegURL"
                contentType == "application/dash+xml" -> contentType
                else -> {
                    val beginning = withContext(Dispatchers.IO) { response.peekBody(2048).string().trimStart('\uFEFF', ' ', '\n', '\r') }
                    when { beginning.startsWith("#EXTM3U") -> "application/x-mpegURL"; "<MPD" in beginning -> "application/dash+xml"; else -> null }
                }
            }
        }
    }
}

internal fun retryAfter(value: String?, now: Long): Long {
    if (value == null) return 0
    value.trim().toLongOrNull()?.takeIf { it >= 0 }?.let { return now + it.coerceAtMost(86_400) * 1000 }
    val date = runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return 0
    return date.coerceIn(now, now + 86_400_000)
}

class PlaylistByteBudget { internal var bytes = 0L }
internal class LimitedPlaylistInput(private val input:InputStream,private val progress:(Long)->Unit={}, private val budget: PlaylistByteBudget = PlaylistByteBudget()) : InputStream() {
    private var size=0L
    private fun count(value:Int):Int {
        if(value>0) {
            size+=value
            budget.bytes+=value
            if(budget.bytes>PersonalM3uParser.MAX_BYTES)throw IOException("La lista supera il limite complessivo di 64 MiB, comprese le liste incluse.")
            progress(budget.bytes)
        }
        return value
    }
    override fun read():Int { val byte=input.read();if(byte>=0)count(1);return byte }
    override fun read(buffer:ByteArray,offset:Int,length:Int)=count(input.read(buffer,offset,length))
    override fun close()=input.close()
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
