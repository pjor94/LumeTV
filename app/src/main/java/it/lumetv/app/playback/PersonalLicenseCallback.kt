package it.lumetv.app.playback

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import it.lumetv.core.DrmSpec
import it.lumetv.core.DrmScheme
import it.lumetv.core.LicenseChallengeEncoding
import it.lumetv.core.LicenseRequestSpec
import it.lumetv.core.LicenseResponseEncoding
import it.lumetv.app.data.HttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID

/** Fixed transformations only. No provider script or expression is evaluated. */
internal object LicenseTransforms {
    const val MAX_BYTES = 1024 * 1024
    fun request(challenge: ByteArray, spec: LicenseRequestSpec): ByteArray {
        if(challenge.size>256*1024)throw IOException("Il challenge della licenza supera il limite previsto.")
        var data=when(spec.challenge) {
            LicenseChallengeEncoding.RAW -> challenge
            LicenseChallengeEncoding.BASE64 -> encoded(challenge).toByteArray(Charsets.US_ASCII)
            LicenseChallengeEncoding.BASE64_URL -> url(encoded(challenge)).toByteArray(Charsets.US_ASCII)
        }
        spec.template?.let { template ->
            data=if(template=="{CHA-RAW}")challenge else template.replace("{CHA-B64}",encoded(challenge))
                .replace("{CHA-B64U}",url(encoded(challenge))).toByteArray(Charsets.UTF_8)
        }
        spec.wrappers.forEach { operation ->
            data=when(operation) { "base64" -> encoded(data).toByteArray(Charsets.US_ASCII); "urlenc" -> url(data.toString(Charsets.UTF_8)).toByteArray(Charsets.US_ASCII)
                else -> throw IOException("La trasformazione della richiesta non è supportata.") }
            bounded(data)
        }
        return bounded(data)
    }
    fun response(bytes:ByteArray,spec:LicenseRequestSpec):ByteArray {
        var data=bounded(bytes)
        data=when(spec.response) {
            LicenseResponseEncoding.RAW -> data
            LicenseResponseEncoding.BASE64 -> decoded(data)
            LicenseResponseEncoding.JSON -> extracted(data,spec.responsePath)
            LicenseResponseEncoding.JSON_BASE64 -> decoded(extracted(data,spec.responsePath))
            LicenseResponseEncoding.BASE64_JSON -> extracted(decoded(data),spec.responsePath)
        }
        spec.unwrappers.forEach { operation ->
            data=when(operation) { "base64" -> decoded(data); "json" -> extracted(data,spec.responsePath)
                else -> throw IOException("La trasformazione della risposta non è supportata.") }
        }
        return bounded(data)
    }
    private fun encoded(data:ByteArray)=Base64.encodeToString(data,Base64.NO_WRAP)
    private fun decoded(data:ByteArray):ByteArray = try { bounded(Base64.decode(data,Base64.DEFAULT)) }
        catch(_:IllegalArgumentException) {throw IOException("La risposta della licenza non contiene Base64 valido.")}
    private fun url(text:String)=URLEncoder.encode(text,"UTF-8")
    private fun bounded(data:ByteArray)=data.also {if(it.size>MAX_BYTES)throw IOException("La risposta della licenza supera il limite previsto.")}
    private fun extracted(data:ByteArray,path:List<String>):ByteArray {
        try {
            val text=data.toString(Charsets.UTF_8)
            var depth=0;var quoted=false;var escaped=false
            text.forEach { c ->
                if(escaped)escaped=false else if(quoted && c=='\\')escaped=true else if(c=='\"')quoted=!quoted
                else if(!quoted) {if(c=='{' || c=='[')depth++;if(c=='}' || c==']')depth--;if(depth !in 0..16)throw IOException("La risposta JSON della licenza supera il limite previsto.")}
            }
            var value:Any=JSONObject(text)
            path.forEach { name -> value=(value as? JSONObject)?.get(name) ?: throw IOException("Il percorso della licenza non è presente nella risposta.") }
            return when(value) {is String -> (value as String).toByteArray(Charsets.UTF_8);is JSONObject -> value.toString().toByteArray(Charsets.UTF_8)
                else -> throw IOException("La risposta della licenza non contiene dati validi.")}
        } catch(_:org.json.JSONException) {throw IOException("La risposta JSON della licenza non è valida.")}
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PersonalLicenseCallback(private val config:DrmSpec,private val client:OkHttpClient) : MediaDrmCallback {
    private val provisioning=HttpMediaDrmCallback(config.licenseUrl,true,PersonalMediaSources.http(client,emptyMap()))
    override fun executeProvisionRequest(uuid:UUID,request:ExoMediaDrm.ProvisionRequest):MediaDrmCallback.Response = provisioning.executeProvisionRequest(uuid,request)
    override fun executeKeyRequest(uuid:UUID,request:ExoMediaDrm.KeyRequest):MediaDrmCallback.Response {
        val uri=Uri.parse(config.licenseUrl)
        val spec=DataSpec.Builder().setUri(uri).build()
        try {
            val builder=Request.Builder().url(config.licenseUrl)
                .header("User-Agent",HttpClient.USER_AGENT)
                .header("Content-Type",if(config.scheme==DrmScheme.CLEARKEY)"application/json" else "application/octet-stream")
            config.headers.forEach { (name,value) -> builder.header(name,value) }
            if(config.request.method=="GET")builder.get() else builder.post(LicenseTransforms.request(request.data,config.request).toRequestBody())
            return client.newCall(builder.build()).execute().use { response ->
                if(!response.isSuccessful)throw HttpDataSource.InvalidResponseCodeException(response.code,response.message,null,response.headers.toMultimap(),spec,byteArrayOf())
                val body=response.body ?: throw IOException("La licenza non contiene una risposta.")
                if(body.contentLength()>LicenseTransforms.MAX_BYTES)throw IOException("La risposta della licenza supera il limite previsto.")
                val bytes=body.byteStream().use { input ->
                    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) {val n=input.read(buffer);if(n<0)break;if(output.size()+n>LicenseTransforms.MAX_BYTES)throw IOException("La risposta della licenza supera il limite previsto.");output.write(buffer,0,n)}
                    output.toByteArray()
                }
                MediaDrmCallback.Response(LicenseTransforms.response(bytes,config.request))
            }
        } catch(error:Exception) {throw MediaDrmCallbackException(spec,uri,emptyMap(),0,error)}
    }
}
