package it.lumetv.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse

/** Temporary URLs stay in this session, outside playlist storage, backups and logs. */
internal data class TrailerStream(
    val trailer: TmdbTrailer,
    val videoUrl: String,
    val videoMimeType: String,
    val audioUrl: String? = null,
    val audioMimeType: String? = null,
    val videoManifest: String? = null,
    val audioManifest: String? = null
) {
    override fun toString() = "TrailerStream(language=${trailer.language})"
}

internal fun interface TrailerResolver {
    suspend fun resolve(trailer: TmdbTrailer): TrailerStream
}

internal class TrailerUnavailableException : IOException("Trailer unavailable")

internal object YoutubeTrailerResolver : TrailerResolver {
    const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val downloader = TrailerDownloader()
    private val initialized by lazy { NewPipe.init(downloader, Localization("it", "IT")); true }

    override suspend fun resolve(trailer: TmdbTrailer): TrailerStream {
        if (trailer.site != "YouTube" || !Regex("[A-Za-z0-9_-]{11}").matches(trailer.key)) throw TrailerUnavailableException()
        return suspendCancellableCoroutine { continuation ->
            val session = ExtractionSession()
            continuation.invokeOnCancellation { session.cancel() }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                try {
                    session.checkActive()
                    check(initialized)
                    val stream = downloader.inSession(session) {
                        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${trailer.key}")
                        select(info, trailer)
                    }
                    if (continuation.isActive) continuation.resume(stream)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(TrailerUnavailableException())
                }
            })
        }
    }

    internal fun select(info: StreamInfo, trailer: TmdbTrailer): TrailerStream {
        fun videos(values: List<VideoStream>) = values.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.format == MediaFormat.MPEG_4 && safeMediaUrl(it.content) &&
                (it.codec.isNullOrBlank() || it.codec.startsWith("avc1")) &&
                it.height in 1..1080 && it.fps <= 60
        }.sortedByDescending { it.height }
        // Prefer AVC with the requested audio track. Progressive audio+video is the fallback.
        val audio = info.audioStreams.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                it.format == MediaFormat.M4A && safeMediaUrl(it.content) &&
                (it.audioLocale==null || it.audioLocale?.language==trailer.language)
        }.sortedWith(compareByDescending<AudioStream> { it.audioLocale?.language == trailer.language }
            .thenByDescending { it.averageBitrate })
            .firstOrNull()
        val videoOnly = videos(info.videoOnlyStreams).firstOrNull()
        fun manifest(stream: Stream): String? = try {
            stream.itagItem?.let {
                YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, it, info.duration)
            }
        } catch (_: Exception) { null }
        if (videoOnly != null && audio != null) return TrailerStream(trailer, videoOnly.content,
            "video/mp4", audio.content, "audio/mp4", manifest(videoOnly), manifest(audio))
        val combined = videos(info.videoStreams).firstOrNull() ?: throw TrailerUnavailableException()
        return TrailerStream(trailer, combined.content, "video/mp4")
    }

    internal fun safeMediaUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
            (it.host == "googlevideo.com" || it.host.endsWith(".googlevideo.com"))
    } == true
}

private class ExtractionSession {
    private val cancelled = AtomicBoolean()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    fun checkActive() { if (cancelled.get()) throw TrailerUnavailableException() }
    fun add(call: Call) { calls.add(call); if (cancelled.get()) { call.cancel(); checkActive() } }
    fun remove(call: Call) { calls.remove(call) }
    fun cancel() { cancelled.set(true); calls.forEach { it.cancel() } }
}

/** A dedicated, cookie-free transport. It never sees playlist headers or the TMDB token. */
private class TrailerDownloader : Downloader() {
    private val current = ThreadLocal<ExtractionSession>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            val allowed = listOf("youtube.com", "youtu.be", "googlevideo.com", "googleapis.com").any {
                url.host == it || url.host.endsWith(".$it")
            } || url.host == "consent.google.com"
            if (!url.isHttps || !allowed || url.username.isNotEmpty() || url.password.isNotEmpty()) throw TrailerUnavailableException()
            chain.proceed(chain.request())
        }.build()

    fun <T> inSession(session: ExtractionSession, block: () -> T): T {
        current.set(session)
        return try { block() } finally { current.remove() }
    }

    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val session = current.get() ?: throw TrailerUnavailableException()
        session.checkActive()
        val builder = okhttp3.Request.Builder().url(request.url())
            .header("User-Agent", YoutubeTrailerResolver.USER_AGENT)
        request.headers().forEach { (key, values) ->
            builder.removeHeader(key)
            values.forEach { builder.addHeader(key, it) }
        }
        val method = request.httpMethod()
        val bytes = request.dataToSend()
        val body = if (bytes != null) bytes.toRequestBody() else if (method in listOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        val call = client.newCall(builder.method(method, body).build())
        session.add(call)
        return try {
            call.execute().use { response ->
                if (response.code == 429) throw TrailerUnavailableException()
                val source = response.body?.source()
                if (source != null && source.request(12L * 1024 * 1024 + 1)) throw TrailerUnavailableException()
                session.checkActive()
                ExtractorResponse(response.code, response.message, response.headers.toMultimap(),
                    source?.readUtf8().orEmpty(), response.request.url.toString())
            }
        } finally { session.remove(call) }
    }
}
