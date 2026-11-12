package it.lumetv.app.data

import it.lumetv.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl
import java.io.IOException

/** Preserve the existing built-in HTTP exceptions when Android permits user-selected HTTP hosts. */
internal object BuiltinNetworkPolicy : Interceptor {
    fun allowed(url:HttpUrl):Boolean = url.isHttps || url.scheme=="http" && (
        url.host=="streaming.bitonlive.net" || url.host.endsWith(".streaming.bitonlive.net") ||
        url.host in setOf("api.epg-guide.com","epg-guide.com","www.epg-guide.com","116.202.210.205","www.man-ga.it") ||
        BuildConfig.DEBUG && url.host=="10.0.2.2")
    override fun intercept(chain:Interceptor.Chain):Response {
        if(!allowed(chain.request().url))throw IOException("Il servizio integrato richiede una connessione HTTPS.")
        return chain.proceed(chain.request())
    }
}
