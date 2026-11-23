package it.lumetv.app.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import it.lumetv.app.data.TrailerStream
import it.lumetv.app.data.YoutubeTrailerResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/** YouTube's media transport, separate from playlist headers and persistent VOD caches. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object TrailerMediaSource {
    fun create(stream: TrailerStream): MediaSource {
        fun source(url: String, mime: String, manifest: String?): MediaSource {
            val item = MediaItem.Builder().setUri(url).setMimeType(mime).build()
            val transport = transport(manifest != null)
            if (manifest == null) return ProgressiveMediaSource.Factory(transport).createMediaSource(item)
            val dash = manifest.byteInputStream().use { DashManifestParser().parse(Uri.parse(url), it) }
            return DashMediaSource.Factory(transport).createMediaSource(dash, item)
        }
        val picture = source(stream.videoUrl, stream.videoMimeType, stream.videoManifest)
        return stream.audioUrl?.let {
            MergingMediaSource(true, picture, source(it, stream.audioMimeType ?: "audio/mp4", stream.audioManifest))
        } ?: picture
    }

    private fun transport(dash: Boolean): DataSource.Factory {
        val requests = AtomicLong()
        return ResolvingDataSource.Factory(DefaultHttpDataSource.Factory()
            .setUserAgent(YoutubeTrailerResolver.USER_AGENT)
            .setConnectTimeoutMs(5_000).setReadTimeoutMs(10_000)) { spec ->
            val url = spec.uri.toString()
            if (!YoutubeTrailerResolver.safeMediaUrl(url)) return@Factory spec
            val requestUrl = requireNotNull(url.toHttpUrlOrNull()).newBuilder()
                .setQueryParameter("rn", requests.getAndIncrement().toString())
            // DASH chunks have finite ranges. Keep the byte offset in the URL so a 200 response
            // is not treated as a full file and skipped again by DefaultHttpDataSource.
            if (dash && (spec.position != 0L || spec.length != C.LENGTH_UNSET.toLong())) {
                val end = if (spec.length == C.LENGTH_UNSET.toLong()) "" else (spec.position + spec.length - 1).toString()
                requestUrl.setQueryParameter("range", "${spec.position}-$end")
            }
            val headers = spec.httpRequestHeaders.toMutableMap()
            headers["User-Agent"] = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url))
                YoutubeParsingHelper.getVisionOsUserAgent(null) else YoutubeTrailerResolver.USER_AGENT
            headers["TE"] = "trailers"
            if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
                headers["Origin"] = "https://www.youtube.com"
                headers["Referer"] = "https://www.youtube.com"
                headers["Sec-Fetch-Dest"] = "empty"
                headers["Sec-Fetch-Mode"] = "cors"
                headers["Sec-Fetch-Site"] = "cross-site"
            }
            spec.buildUpon().setUri(requestUrl.build().toString())
                .setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(byteArrayOf(0x78, 0))
                .setHttpRequestHeaders(headers).setPosition(if (dash) 0 else spec.position).build()
        }
    }
}
