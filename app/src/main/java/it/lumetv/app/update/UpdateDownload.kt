package it.lumetv.app.update

import it.lumetv.app.BuildConfig
import it.lumetv.app.data.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal class GithubUpdateClient : UpdateFeed {
    private val http = HttpClient(okhttp3.OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(5, TimeUnit.MINUTES).followSslRedirects(false).build())

    private fun request(url: String) = http.request(url, mapOf("User-Agent" to "LumeTV/${BuildConfig.VERSION_NAME}",
        "Accept" to "application/vnd.github+json"))

    override suspend fun text(url: String, limit: Int): String = http.read(request(url)) { response ->
        val stream = response.body?.byteStream() ?: UpdatePolicy.invalid()
        stream.use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) UpdatePolicy.invalid()
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun download(info: UpdateInfo, directory: File, progress: (Int) -> Unit): File {
        val coroutine = currentCoroutineContext()
        directory.mkdirs()
        val partial = File.createTempFile("update-", ".part", directory)
        val complete = File(directory, "update.apk")
        complete.delete()
        try {
            http.read(request(info.apkUrl).newBuilder().header("Accept", "application/octet-stream").build()) { response ->
                val body = response.body ?: UpdatePolicy.invalid()
                if (body.contentLength() >= 0 && body.contentLength() != info.size) UpdatePolicy.invalid()
                body.byteStream().use { input ->
                    copyVerifiedApk(input, partial, info, { coroutine.ensureActive() }, progress)
                }
            }
            coroutine.ensureActive()
            if (!partial.renameTo(complete)) throw UpdateException("Non riesco a salvare l’aggiornamento sulla TV.")
            return complete
        } finally { partial.delete() }
    }
}

/** Stream to private storage, enforcing both the declared length and SHA-256. */
internal fun copyVerifiedApk(input: InputStream, output: File, info: UpdateInfo, active: () -> Unit, progress: (Int) -> Unit) {
    var copied = 0L
    var lastPercent = -1
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        output.outputStream().use { file ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                active()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > info.size) UpdatePolicy.invalid()
                digest.update(buffer, 0, count)
                file.write(buffer, 0, count)
                val percent = (copied * 100 / info.size).toInt()
                if (percent != lastPercent) { lastPercent = percent; progress(percent) }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (copied != info.size || hash != info.sha256) throw UpdateException("Il file scaricato non supera la verifica. Riprova il download.")
    } catch (error: Exception) { output.delete(); throw error }
}
