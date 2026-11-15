package it.lumetv.app.companion

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Trust is provisional only during a nonce-bound proof. Saved sessions always pin the TV key. */
internal class CompanionClient(val endpoint:CompanionEndpoint,pin:String?=null,private val token:String?=null):AutoCloseable {
    init {require(token==null || pin!=null);require(pin==null || CompanionProtocol.hex(pin));require(token==null || CompanionProtocol.hex(token))}
    // Device certificates are self-signed: enrollment uses the secret-bound proof; all later calls
    // require an exact SPKI pin. System CA/hostname validation cannot authenticate these local keys.
    @android.annotation.SuppressLint("CustomX509TrustManager")
    private class Trust(private val expected:String?):X509TrustManager {
        @Volatile var observed:String?=null
        override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain:Array<out X509Certificate>?,authType:String?) {throw CertificateException()}
        @Synchronized override fun checkServerTrusted(chain:Array<out X509Certificate>?,authType:String?) {
            val cert=chain?.firstOrNull() ?: throw CertificateException()
            val pin=CompanionProtocol.digest(cert.publicKey.encoded)
            if(expected!=null && !CompanionProtocol.same(pin,expected) || observed!=null && !CompanionProtocol.same(pin,observed!!))throw CertificateException("TV identity changed")
            observed=pin
        }
    }
    private val trust=Trust(pin)
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    private val tls=SSLContext.getInstance("TLS").apply {init(null,arrayOf(trust),null)}
    private val http=OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,trust)
        // The certificate's public key is verified above; these device certificates have no DNS names.
        // A pooled socket may be closed while options are open. Retried commands retain the same
        // UUID and body, so the TV's command ledger prevents applying an action twice.
        .hostnameVerifier {_,_->true}.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1)).connectTimeout(4,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS)
        .callTimeout(8,TimeUnit.SECONDS).build()
    suspend fun getState()=exchange("/v1/state",null,true)
    suspend fun command(session:String,action:String,args:JSONObject=JSONObject()):JSONObject = exchange("/v1/command",JSONObject()
        .put("id",UUID.randomUUID().toString()).put("session",session).put("action",action).put("args",args),true)
    suspend fun pair(code:String,clientId:String,clientName:String,waiting:(String)->Unit):SavedTelevision {
        require(CompanionProtocol.validCode(code))
        val nonce=CompanionProtocol.randomHex()
        val challenge=exchange("/v1/pair/challenge",JSONObject().put("nonce",nonce),false)
        val pin=trust.observed ?: throw CompanionFailure(403,"identity_failed")
        val serverNonce=challenge.getString("nonce");val tvId=challenge.getString("id")
        require(CompanionProtocol.hex(serverNonce) && CompanionProtocol.uuid(tvId) && challenge.getInt("version")==1)
        if(endpoint.id.isNotEmpty() && endpoint.id!=tvId)throw CompanionFailure(403,"identity_failed")
        val expected=CompanionProtocol.proof(code,"server",pin,nonce,serverNonce,tvId)
        if(!CompanionProtocol.same(expected,challenge.getString("proof")))throw CompanionFailure(403,"pairing_failed")
        val name=CompanionProtocol.display(challenge.getString("name"),80)
        val reply=exchange("/v1/pair/request",JSONObject().put("challenge",challenge.getString("challenge"))
            .put("clientId",clientId).put("name",clientName).put("proof",CompanionProtocol.proof(code,"client",pin,nonce,serverNonce,clientId,clientName)),false)
        val status=reply.getString("status")
        require(status=="waiting" || status=="approved")
        if(status=="waiting")waiting(name)
        val ticket=reply.getString("ticket");require(CompanionProtocol.hex(ticket))
        repeat(130) {attempt->
            if(attempt>0 || status=="waiting")delay(700)
            val result=exchange("/v1/pair/status",JSONObject().put("ticket",ticket),false)
            when(result.getString("status")) {
                "approved" -> {
                    val token=result.getString("token");require(CompanionProtocol.hex(token))
                    return SavedTelevision(endpoint.copy(id=tvId,name=name),pin,token)
                }
                "denied" -> throw CompanionFailure(403,"pairing_denied")
                "waiting" -> Unit
                else -> throw CompanionFailure(400,"bad_response")
            }
        }
        throw CompanionFailure(403,"pairing_expired")
    }
    internal suspend fun exchange(path:String,body:JSONObject?,authenticated:Boolean):JSONObject = suspendCancellableCoroutine {continuation->
        if(closed.get()){continuation.resumeWithException(IOException("Connection closed"));return@suspendCancellableCoroutine}
        val request=Request.Builder().url(endpoint.base+path).apply {
            if(body!=null)post(body.toString().toRequestBody("application/json".toMediaType()))
            if(authenticated)header("Authorization","Bearer "+requireNotNull(token))
        }.build()
        val call=http.newCall(request)
        continuation.invokeOnCancellation {runCatching {http.dispatcher.executorService.execute {call.cancel()}}}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response) {
                val result=runCatching {response.use {
                    val source=it.body?.source() ?: throw IOException("Empty reply")
                    val buffer=okio.Buffer()
                    while(buffer.size<=CompanionProtocol.MAX_BODY) {
                        if(source.read(buffer,CompanionProtocol.MAX_BODY+1L-buffer.size)==-1L)break
                    }
                    val bytes=buffer.readByteArray()
                    if(bytes.size>CompanionProtocol.MAX_BODY)throw IOException("Large reply")
                    val data=JSONObject(String(bytes,Charsets.UTF_8))
                    if(!it.isSuccessful)throw CompanionFailure(it.code,data.optString("error","connection_failed"))
                    data
                }}
                if(continuation.isActive)result.fold({continuation.resume(it)},{continuation.resumeWithException(it)})
            }
        })
    }
    override fun close(){
        if(!closed.compareAndSet(false,true))return
        // Closing TLS can send close_notify. Cancellation and cleanup must also stay off the UI thread.
        http.dispatcher.executorService.execute {
            try {http.dispatcher.cancelAll();http.connectionPool.evictAll()}
            finally {http.dispatcher.executorService.shutdown()}
        }
    }
}
