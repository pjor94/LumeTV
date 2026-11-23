package it.lumetv.app.data

import android.content.Context
import it.lumetv.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resumeWithException

/** Public Italian supplement used by the desktop app, isolated from all provider sessions. */
internal class EpgShareRepository(
    context:Context,client:OkHttpClient=publicClient(),private val endpoint:String=FEED,
    private val now:()->Long=System::currentTimeMillis,
) {
    companion object {
        const val FEED="https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"
        const val FRESH_FOR=4*3_600_000L
        const val REINDEX_AFTER=12*3_600_000L
        private val instances=java.util.WeakHashMap<Context,EpgShareRepository>()
        fun get(context:Context):EpgShareRepository=synchronized(instances) {
            val app=context.applicationContext;instances.getOrPut(app){EpgShareRepository(app)}
        }
        private fun publicClient()=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(object:Dns {
            override fun lookup(hostname:String):List<InetAddress> = Dns.SYSTEM.lookup(hostname).also {addresses->
                if(addresses.isEmpty() || addresses.any {!LogoPolicy.publicAddress(it)})throw UnknownHostException("EPG destination is not public")
            }
        }).connectTimeout(5,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build()
    }
    private val prefs=context.applicationContext.getSharedPreferences("epgshare_guide",Context.MODE_PRIVATE)
    private val store=CatalogGuideStore(context.applicationContext.filesDir,PublicGuideFeed.EPGSHARE)
    private val http=client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).build()
    private val mutex=Mutex()
    val updated:Long get()=prefs.getLong("updated",0)
    val nextRetry:Long get()=prefs.getLong("retry",0)
    val failure:String get()=prefs.getString("error","").orEmpty()
    private class RateLimit(val delay:Long):IOException()
    private data class Download(val redirect:HttpUrl?=null,val changed:Boolean=false,val etag:String="",val modified:String="")

    suspend fun refresh(force:Boolean=false)=withContext(Dispatchers.IO) {mutex.withLock {
        val time=now();val attempted=prefs.getLong("attempted",0);val checked=prefs.getLong("checked",0)
        if(time<nextRetry || attempted>0 && time>=attempted && time-attempted<30_000)return@withLock
        val ready=store.ready(time)
        if(!force && ready && checked>0 && time>=checked && time-checked<FRESH_FOR)return@withLock
        prefs.edit().putLong("attempted",time).putLong("retry",time+60_000).commit()
        try {
            val conditional=ready && updated>0 && time>=updated && time-updated<REINDEX_AFTER
            val etag=if(conditional)prefs.getString("etag","").orEmpty() else ""
            val modified=if(conditional)prefs.getString("modified","").orEmpty() else ""
            val origin=endpoint.toHttpUrl();var target=origin;var result:Download?=null
            val task=currentCoroutineContext()
            for(hop in 0..3) {
                val request=Request.Builder().url(target).header("User-Agent","LumeTV/${BuildConfig.VERSION_NAME}")
                    .header("Accept-Encoding","identity").apply {
                        if(etag.isNotBlank())header("If-None-Match",etag)
                        if(modified.isNotBlank())header("If-Modified-Since",modified)
                    }.build()
                val downloaded=read(request){response->
                    task.ensureActive()
                    when {
                        response.code in setOf(301,302,303,307,308) -> {
                            val next=response.header("Location")?.let(target::resolve) ?: throw IOException()
                            if(hop==3 || next.scheme!=origin.scheme || next.host!=origin.host || next.port!=origin.port ||
                                next.username.isNotEmpty() || next.password.isNotEmpty() || next.fragment!=null)throw IOException()
                            Download(redirect=next)
                        }
                        response.code==429 -> throw RateLimit(retryDelay(response.header("Retry-After"),time))
                        response.code==304 -> {
                            if(!conditional || etag.isBlank() && modified.isBlank())throw IOException()
                            Download(etag=etag,modified=modified)
                        }
                        else -> {
                            if(!response.isSuccessful)throw IOException()
                            val body=response.body ?: throw IOException()
                            if(body.contentLength()>32L*1024*1024)throw IOException()
                            GZIPInputStream(Bounded(body.byteStream(),32L*1024*1024){task.ensureActive()}).use {gzip->
                                store.stage(Bounded(gzip,350L*1024*1024){task.ensureActive()},emptySet(),time,
                                    active={task.ensureActive()}).use {draft->task.ensureActive();store.commit(draft)}
                            }
                            Download(changed=true,etag=validator(response.header("ETag")),modified=validator(response.header("Last-Modified")))
                        }
                    }
                }
                if(downloaded.redirect!=null)target=downloaded.redirect else {result=downloaded;break}
            }
            val saved=result ?: throw IOException()
            prefs.edit().putLong("checked",time).putLong("retry",0).putString("error","")
                .putString("etag",saved.etag).putString("modified",saved.modified).apply {
                    if(saved.changed)putLong("updated",time)
                }.commit()
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception) {
            val limited=error as? RateLimit
            prefs.edit().putLong("retry",now()+(limited?.delay ?: 300_000))
                .putString("error",if(limited!=null)"rate_limit" else "unavailable").commit()
        }
    }}

    private fun validator(raw:String?)=raw.orEmpty().takeIf {it.length<=1024 && it.none(Char::isISOControl)}.orEmpty()
    private fun retryDelay(raw:String?,time:Long):Long {
        val seconds=raw?.trim()?.toLongOrNull()?.coerceIn(60,86_400)?.times(1000)
        if(seconds!=null)return seconds
        return runCatching {(java.time.ZonedDateTime.parse(raw,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-time).coerceIn(60_000,86_400_000)}.getOrDefault(3_600_000)
    }
    private suspend fun <T> read(request:Request,consume:(Response)->T):T=suspendCancellableCoroutine {continuation->
        val call=http.newCall(request);continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object:okhttp3.Callback {
            override fun onFailure(call:okhttp3.Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:okhttp3.Call,response:Response){response.use {
                if(!continuation.isActive)return
                try {val value=consume(response);if(continuation.isActive)continuation.resumeWith(Result.success(value))}
                catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
            }}
        })
    }
    private class Bounded(input:InputStream,private val maximum:Long,private val active:()->Unit):FilterInputStream(input) {
        private var bytes=0L
        private fun count(size:Int){active();if(size>0){bytes+=size;if(bytes>maximum)throw IOException()}}
        override fun read()=`in`.read().also {if(it>=0)count(1)}
        override fun read(b:ByteArray,o:Int,l:Int)=`in`.read(b,o,l).also(::count)
    }
}
