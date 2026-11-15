package it.lumetv.app.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

internal class CompanionIdentity(context:Context) {
    private val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
    private val alias="lume-companion-tls-ec-v1"
    val certificate:X509Certificate
    val pin:String
    val tls:SSLContext
    init {
        if(!store.containsAlias(alias)) {
            val end=Calendar.getInstance().apply {add(Calendar.YEAR,30)}.time
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    // Conscrypt hashes the TLS transcript itself, then signs the digest in Keystore.
                    .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(X500Principal("CN=Lume TV local companion"))
                    .setCertificateNotBefore(java.util.Date(0)).setCertificateNotAfter(end).build())
                generateKeyPair()
            }
        }
        certificate=store.getCertificate(alias) as X509Certificate
        pin=CompanionProtocol.digest(certificate.publicKey.encoded)
        val manager=object:X509ExtendedKeyManager() {
            override fun getPrivateKey(value:String?)=if(value==alias)store.getKey(alias,null) as PrivateKey else null
            override fun getCertificateChain(value:String?)=if(value==alias)arrayOf(certificate) else null
            override fun chooseServerAlias(type:String?,issuers:Array<out java.security.Principal>?,socket:Socket?)=alias.takeIf {type=="EC"}
            override fun getServerAliases(type:String?,issuers:Array<out java.security.Principal>?)=if(type=="EC")arrayOf(alias) else null
            override fun chooseClientAlias(types:Array<out String>?,issuers:Array<out java.security.Principal>?,socket:Socket?):String?=null
            override fun getClientAliases(type:String?,issuers:Array<out java.security.Principal>?):Array<String>?=null
            override fun chooseEngineServerAlias(type:String?,issuers:Array<out java.security.Principal>?,engine:SSLEngine?)=alias.takeIf {type=="EC"}
        }
        tls=SSLContext.getInstance("TLS").apply {init(arrayOf(manager),null,null)}
    }
}

/** Deliberately outside the portable backup preferences. Private trust stays on this installation. */
internal class CompanionStore(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("lume-companion",Context.MODE_PRIVATE)
    val id:String=prefs.getString("id",null) ?: UUID.randomUUID().toString().also {prefs.edit().putString("id",it).apply()}
    var enabled:Boolean get()=prefs.getBoolean("enabled",false);set(value){prefs.edit().putBoolean("enabled",value).apply()}
    var port:Int get()=prefs.getInt("port",0).takeIf {it in 1024..65535} ?: 0;set(value){prefs.edit().putInt("port",value).apply()}
    var lastTelevision:String get()=prefs.getString("last_television","").orEmpty();set(value){prefs.edit().putString("last_television",value).apply()}
    @Synchronized fun peers():List<CompanionPeer> = runCatching {
        val data=JSONArray(prefs.getString("peers","[]"));(0 until data.length()).take(8).map {i->data.getJSONObject(i).let {
            CompanionPeer(it.getString("id"),it.getString("name"),it.getString("hash"),it.getLong("at"))}}
    }.getOrDefault(emptyList())
    @Synchronized fun remember(peer:CompanionPeer) {
        val peers=peers().filterNot {it.id==peer.id}
        if(peers.size>=8)throw CompanionFailure(409,"too_many_devices")
        writePeers(peers+peer)
    }
    @Synchronized fun revoke(id:String){writePeers(peers().filterNot {it.id==id})}
    @Synchronized fun authorized(token:String):Boolean {
        if(!CompanionProtocol.hex(token))return false
        val hash=CompanionProtocol.digest(token.toByteArray())
        return peers().any {CompanionProtocol.same(it.tokenHash,hash)}
    }
    private fun writePeers(peers:List<CompanionPeer>) {
        prefs.edit().putString("peers",JSONArray().apply {peers.forEach {put(JSONObject().put("id",it.id).put("name",it.name).put("hash",it.tokenHash).put("at",it.pairedAt))}}.toString()).apply()
    }
    @Synchronized fun saved():List<SavedTelevision> = runCatching {
        val raw=prefs.getString("televisions",null) ?: return emptyList()
        val parts=raw.split(':');require(parts.size==2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,secret(),GCMParameterSpec(128,android.util.Base64.decode(parts[0],2)))
        val data=JSONArray(String(cipher.doFinal(android.util.Base64.decode(parts[1],2)),Charsets.UTF_8))
        (0 until data.length()).take(8).map {SavedTelevision.read(data.getJSONObject(it))}
    }.getOrDefault(emptyList())
    @Synchronized fun save(tv:SavedTelevision){writeSaved((saved().filterNot {it.endpoint.id==tv.endpoint.id}+tv).takeLast(8))}
    @Synchronized fun forget(id:String){writeSaved(saved().filterNot {it.endpoint.id==id});if(lastTelevision==id)lastTelevision=""}
    private fun writeSaved(values:List<SavedTelevision>) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret())
        val data=JSONArray().apply {values.forEach {put(it.json())}}.toString().toByteArray(Charsets.UTF_8)
        prefs.edit().putString("televisions",android.util.Base64.encodeToString(cipher.iv,2)+":"+
            android.util.Base64.encodeToString(cipher.doFinal(data),2)).apply()
    }
    private fun secret():SecretKey {
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val alias="lume-companion-client-v1"
        return (keys.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
