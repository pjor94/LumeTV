package it.lumetv.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface Transport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun post(url: String, data: JSONObject, headers: Map<String, String> = emptyMap()): String
}

class SourceException(message: String, val status: Int = 0) : IOException(message)

class HttpClient(val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).build()) : Transport {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
        fun browserHeaders(host: String) = mapOf("User-Agent" to USER_AGENT, "Origin" to host, "Referer" to "$host/")
    }

    override suspend fun get(url: String, headers: Map<String, String>): String = read(request(url, headers)) { response ->
        val body = response.body ?: throw SourceException("L’emittente non ha restituito dati.")
        body.byteStream().use { input ->
            val bytes = input.readNBytesBounded(8 * 1024 * 1024)
            bytes.toString(Charsets.UTF_8)
        }
    }

    override suspend fun post(url: String, data: JSONObject, headers: Map<String, String>): String {
        val request = request(url, headers).newBuilder()
            .post(data.toString().toRequestBody("application/json".toMediaType())).build()
        return read(request) { response ->
            response.body?.byteStream()?.use { it.readNBytesBounded(8 * 1024 * 1024).toString(Charsets.UTF_8) }
                ?: throw SourceException("L’emittente non ha restituito dati.")
        }
    }

    fun request(url: String, headers: Map<String, String> = emptyMap()): Request = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT).header("Accept", "*/*")
        .apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    suspend fun <T> read(request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        if (!response.isSuccessful) throw SourceException(when (response.code) {
                            401, 403 -> "L’emittente non autorizza questo flusso dalla rete attuale."
                            404 -> "Questo canale non è attualmente disponibile."
                            429 -> "Il servizio richiede una pausa. Riprova più tardi."
                            else -> "Il servizio dell’emittente non risponde. Riprova."
                        }, response.code)
                        val value = consume(response)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }
}

private fun java.io.InputStream.readNBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw SourceException("Risposta del servizio troppo grande.")
        output.write(buffer, 0, count)
    }
}

fun org.json.JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
