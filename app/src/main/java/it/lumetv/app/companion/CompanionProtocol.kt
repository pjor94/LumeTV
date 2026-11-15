package it.lumetv.app.companion

import org.json.JSONObject
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Local, versioned control protocol. Pairing secrets never travel as request parameters. */
internal object CompanionProtocol {
    val NAVIGATION_KEYS=setOf("up","down","left","right","ok","back","home","options")
    const val SERVICE = "_lumetv._tcp."
    const val VERSION = 1
    const val MAX_BODY = 32 * 1024
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val random = SecureRandom()
    fun randomHex(bytes:Int=32):String = ByteArray(bytes).also(random::nextBytes).hex()
    fun code():String = buildString { repeat(16) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    fun normalizeCode(value:String):String = value.uppercase(java.util.Locale.ROOT).filterNot {it==' ' || it=='-'}
    fun validCode(value:String)=value.length==16 && value.all {it in ALPHABET}
    fun hex(value:String,bytes:Int=32)=value.length==bytes*2 && value.all {it in '0'..'9' || it in 'a'..'f'}
    fun uuid(value:String)=runCatching {UUID.fromString(value).toString()==value}.getOrDefault(false)
    fun digest(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).hex()
    fun same(a:String,b:String)=MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8),b.toByteArray(Charsets.UTF_8))
    fun proof(code:String,role:String,pin:String,clientNonce:String,serverNonce:String,identity:String,name:String=""):String {
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.US_ASCII),"HmacSHA256"))
        return mac.doFinal(listOf("lume-companion-v1",role,pin,clientNonce,serverNonce,identity,name)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)).hex()
    }
    fun display(value:String,max:Int=160):String = value.replace(Regex("(?i)https?://\\S+"),"…")
        .filter { !it.isISOControl() && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069' }.trim().take(max)
    /** Numeric private IPv4 only: no DNS, redirects, loopback, public or cellular endpoints. */
    fun localHost(value:String):Boolean {
        val parts=value.split('.')
        if(parts.size!=4 || parts.any {it.isEmpty() || it.length>3 || it.any {c->c !in '0'..'9'} || (it.length>1 && it[0]=='0')})return false
        val p=parts.map {it.toIntOrNull() ?: return false}
        if(p.any {it !in 0..255} || p[3]==0 || p[3]==255)return false
        return p[0]==10 || p[0]==192 && p[1]==168 || p[0]==172 && p[1] in 16..31 || p[0]==169 && p[1]==254
    }
    fun error(code:String)=JSONObject().put("error",code).put("version",VERSION)
    fun authorizeControl(available:Boolean,authorized:Boolean) {
        if(!available)throw CompanionFailure(503,"unavailable")
        if(!authorized)throw CompanionFailure(401,"not_authorized")
    }
    private fun ByteArray.hex()=joinToString("") {"%02x".format(it)}
}

internal data class CompanionEndpoint(val host:String,val port:Int,val id:String="",val name:String="TV") {
    init {require(CompanionProtocol.localHost(host) && port in 1..65535);require(id.isEmpty() || CompanionProtocol.uuid(id))}
    val base get()="https://$host:$port"
}

internal data class CompanionInvite(val endpoint:CompanionEndpoint,val pin:String,val code:String) {
    init {require(CompanionProtocol.hex(pin));require(CompanionProtocol.validCode(code))}
    fun encode()=listOf("LUME1",endpoint.host,endpoint.port.toString(),endpoint.id,pin,code).joinToString("|")
    companion object {
        fun decode(value:String):CompanionInvite {
            require(value.length<=512)
            val p=value.split('|');require(p.size==6 && p[0]=="LUME1")
            return CompanionInvite(CompanionEndpoint(p[1],p[2].toInt(),p[3]),p[4],p[5])
        }
    }
}

internal data class CompanionPeer(val id:String,val name:String,val tokenHash:String,val pairedAt:Long)
internal data class SavedTelevision(val endpoint:CompanionEndpoint,val pin:String,val token:String) {
    fun json()=JSONObject().put("host",endpoint.host).put("port",endpoint.port).put("id",endpoint.id)
        .put("name",endpoint.name).put("pin",pin).put("token",token)
    companion object {
        fun read(value:JSONObject):SavedTelevision {
            val pin=value.getString("pin");val token=value.getString("token")
            require(CompanionProtocol.hex(pin) && CompanionProtocol.hex(token))
            return SavedTelevision(CompanionEndpoint(value.getString("host"),value.getInt("port"),value.getString("id"),
                CompanionProtocol.display(value.getString("name"),80)),pin,token)
        }
    }
}
