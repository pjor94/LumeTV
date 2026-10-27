package it.lumetv.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class LogoDownload(val bytes: ByteArray?, val etag: String?, val modified: String?)
internal class LogoDownloadException(val status: Int = 0) : IOException("Logo unavailable")

internal class LogoFetcher(
    client: OkHttpClient,
    private val allowed: (HttpUrl) -> Boolean = { LogoPolicy.url(it.toString()) != null },
    private val redirectAllowed: (HttpUrl, HttpUrl) -> Boolean = LogoPolicy::redirect,
    private val addressAllowed: (InetAddress) -> Boolean = LogoPolicy::publicAddress,
) {
    private val upstreamDns = client.dns
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = upstreamDns.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !addressAllowed(it) }) throw UnknownHostException("Artwork destination is not public")
            }
        })
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun fetch(url: HttpUrl, etag: String? = null, modified: String? = null): LogoDownload = withTimeout(15_000) {
        require(allowed(url))
        var target = url
        repeat(4) {
            val request = Request.Builder().url(target).header("User-Agent", "WLTV ${HttpClient.USER_AGENT}")
                .header("Accept", "image/png,image/webp,image/jpeg,image/gif")
                .apply {
                    etag?.let { header("If-None-Match", it) }
                    modified?.let { header("If-Modified-Since", it) }
                }.build()
            response(request).use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let(target::resolve) ?: throw LogoDownloadException()
                    if (!allowed(next) || !redirectAllowed(target, next)) throw LogoDownloadException()
                    target = next
                } else {
                    if (response.code == 304) return@withTimeout LogoDownload(null, response.header("ETag") ?: etag, response.header("Last-Modified") ?: modified)
                    if (!response.isSuccessful) throw LogoDownloadException(response.code)
                    val body = response.body ?: throw LogoDownloadException()
                    if (body.contentLength() > LogoPolicy.MAX_BYTES) throw LogoDownloadException()
                    val bytes = body.byteStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > LogoPolicy.MAX_BYTES) throw LogoDownloadException()
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (!imageSignature(bytes)) throw LogoDownloadException()
                    return@withTimeout LogoDownload(bytes, response.header("ETag"), response.header("Last-Modified"))
                }
            }
        }
        throw LogoDownloadException()
    }

    private suspend fun response(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun imageSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun ascii(start: Int, end: Int) = bytes.copyOfRange(start, end).toString(Charsets.US_ASCII)
        return bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) ||
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) ||
            ascii(0, 6) in setOf("GIF87a", "GIF89a") || (ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP")
    }
}
