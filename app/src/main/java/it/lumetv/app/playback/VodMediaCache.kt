package it.lumetv.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Media bytes only. Manifests, encryption keys and licenses keep their existing network paths. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VodMediaCache(context:Context) {
    private val directory=File(context.cacheDir,"vod-media-v1")
    init {
        // A full device must be able to reclaim an abandoned cache even if caching is disabled.
        if(context.cacheDir.usableSpace<96L*1024*1024 && directory.exists())
            SimpleCache.delete(directory,StandaloneDatabaseProvider(context))
    }
    val quota:Long=(context.cacheDir.usableSpace/12).coerceAtMost(512L*1024*1024).also {require(it>=8L*1024*1024)}
    val cache=SimpleCache(directory,LeastRecentlyUsedCacheEvictor(quota),StandaloneDatabaseProvider(context))
    private val cleanup=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    // Only pre-existing keys: a new session may start while orphan cleanup is running.
    private val abandoned=cache.keys.toList()
    internal val startupCleanup=cleanup.launch {
        runCatching {purge(abandoned)}.onFailure {android.util.Log.w("LumePlayer","Pulizia cache video da completare al prossimo avvio")}
    }
    fun session()=Session()
    private suspend fun purge(keys:Collection<String>) {
        var failure:Exception?=null
        repeat(3){attempt->
            failure=null
            keys.forEach {key->try {cache.removeResource(key)}catch(error:Exception){failure=error}}
            if(failure==null)return
            delay(100L*(attempt+1))
        }
        throw requireNotNull(failure)
    }
    /** Retire immediately; delete only after every reader/writer has closed its cache hole. */
    inner class Session internal constructor():AutoCloseable {
        private val prefix=UUID.randomUUID().toString()+":"
        private val lock=Any()
        private var readers=0
        private var retiring=false
        private var scheduled=false
        val cleared=CompletableDeferred<Unit>()
        fun namespace(resource:String)=prefix+resource
        private fun begin()=synchronized(lock){checkOpen();readers++}
        private fun checkOpen(){if(retiring)throw IOException("Sessione video terminata.")}
        private fun check()=synchronized(lock){checkOpen()}
        private fun end()=synchronized(lock){readers--;scheduleCleanup()}
        private fun scheduleCleanup(){
            if(!retiring || readers!=0 || scheduled)return
            scheduled=true
            cleanup.launch {
                try {purge(cache.keys.filter {it.startsWith(prefix)});cleared.complete(Unit)}
                catch(error:Exception){cleared.completeExceptionally(error);android.util.Log.w("LumePlayer","Pulizia cache video da completare al prossimo avvio")}
            }
        }
        override fun close()=synchronized(lock){retiring=true;scheduleCleanup()}
        suspend fun <T> access(block:suspend ()->T):T {begin();try{return block()}finally {end()}}
        fun track(factory:DataSource.Factory):DataSource.Factory=DataSource.Factory {
            val source=factory.createDataSource()
            object:DataSource by source {
                private var opened=false
                override fun open(spec:DataSpec):Long {
                    begin();opened=true
                    try {return source.open(spec).also {check()}}
                    catch(error:Throwable){try {close()}catch(closing:Exception){error.addSuppressed(closing)};throw error}
                }
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {check();return source.read(buffer,offset,length).also {check()}}
                override fun close(){if(!opened)return;try {source.close()}finally {opened=false;end()}}
            }
        }
    }
    fun key(namespace:String,uri:Uri)=namespace+":"+digest(uri.toString())
    fun factory(upstream:DataSource.Factory,namespace:String,valid:()->Boolean):CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setCacheKeyFactory(CacheKeyFactory {key(namespace,it.uri)})
        .setUpstreamDataSourceFactory(DataSource.Factory {GuardedDataSource(upstream.createDataSource(),valid)})
        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(2*1024*1024))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    fun guarded(factory:DataSource.Factory,valid:()->Boolean)=DataSource.Factory {GuardedDataSource(factory.createDataSource(),valid)}
    private class GuardedDataSource(private val delegate:DataSource,private val valid:()->Boolean):DataSource {
        private fun check(){if(!valid())throw IOException("La lista non è più disponibile.")}
        override fun addTransferListener(listener:TransferListener)=delegate.addTransferListener(listener)
        override fun open(dataSpec:DataSpec):Long {check();return delegate.open(dataSpec)}
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {check();return delegate.read(buffer,offset,length)}
        override fun getUri()=delegate.uri
        override fun getResponseHeaders()=delegate.responseHeaders
        override fun close()=delegate.close()
    }
    companion object {
        @Volatile private var instance:VodMediaCache?=null
        fun get(context:Context):VodMediaCache=instance ?: synchronized(this){instance ?: VodMediaCache(context.applicationContext).also {instance=it}}
        fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    }
}
