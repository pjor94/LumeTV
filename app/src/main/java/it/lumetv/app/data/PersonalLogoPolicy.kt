package it.lumetv.app.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.URI

internal class PersonalArtwork(val url:String,val cacheKey:String,val headers:Map<String,String>,val context:PersonalRequestContext) {
    override fun toString()="PersonalArtwork()"
}

/** Personal artwork can use a provider port and explicit HTTP, while retaining the public-address guard. */
internal object PersonalLogoPolicy {
    fun url(raw:String,allowHttp:Boolean):HttpUrl? {
        if(raw.length>8192)return null
        val uri=runCatching {URI(raw)}.getOrNull() ?: return null
        val url=raw.toHttpUrlOrNull() ?: return null
        if(uri.rawUserInfo!=null || uri.rawFragment!=null || !PersonalPlaylistNetwork.allowed(uri,allowHttp))return null
        val host=url.host.lowercase()
        if(host in setOf("localhost","localhost.localdomain") || listOf(".local",".localhost",".internal",".test",".invalid").any(host::endsWith))return null
        if(host.contains(':') || host.all {it.isDigit() || it=='.'}) {
            val literal=runCatching {InetAddress.getByName(host)}.getOrNull() ?: return null
            if(!LogoPolicy.publicAddress(literal))return null
        } else if('.' !in host)return null
        return url
    }
}
