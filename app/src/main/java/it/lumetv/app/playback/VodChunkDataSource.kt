package it.lumetv.app.playback

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations

/** Finite cache holes let playback and look-ahead own different parts of a progressive file. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VodChunkDataSource(
    private val factory:DataSource.Factory,private val store:VodMediaCache,private val namespace:String,
):DataSource {
    private var spec:DataSpec?=null
    private var delegate:DataSource?=null
    private var cursor=0L
    private var end=Long.MAX_VALUE
    private var chunkRemaining=0L
    private var continuous=false
    private val listeners=mutableListOf<TransferListener>()
    private var responseHeaders:Map<String,List<String>> = emptyMap()
    override fun addTransferListener(listener:TransferListener){listeners+=listener;delegate?.addTransferListener(listener)}
    override fun open(dataSpec:DataSpec):Long {
        spec=dataSpec;cursor=dataSpec.position;continuous=false
        val metadata=store.cache.getContentMetadata(store.key(namespace,dataSpec.uri))
        val total=ContentMetadata.getContentLength(metadata)
        end=if(dataSpec.length!=C.LENGTH_UNSET.toLong())cursor+dataSpec.length else if(total>=0)total else Long.MAX_VALUE
        if(metadata.get(RANGE_SUPPORT,-1L)==0L)openContinuous() else openChunk()
        return if(end==Long.MAX_VALUE)C.LENGTH_UNSET.toLong() else (end-cursor).coerceAtLeast(0)
    }
    private fun openChunk() {
        val original=requireNotNull(spec)
        delegate?.close();delegate=null
        if(cursor>=end){chunkRemaining=0;return}
        chunkRemaining=minOf(CHUNK_BYTES-cursor%CHUNK_BYTES,end-cursor)
        val data=factory.createDataSource();listeners.forEach(data::addTransferListener);delegate=data
        try {
            data.open(original.buildUpon().setPosition(cursor).setLength(chunkRemaining).build())
            responseHeaders=data.responseHeaders
            val range=responseHeaders.entries.firstOrNull {it.key.equals("Content-Range",true)}?.value?.firstOrNull()
            val total=range?.substringAfterLast('/')?.toLongOrNull() ?: if(range==null)
                responseHeaders.entries.firstOrNull {it.key.equals("Content-Length",true)}?.value?.firstOrNull()?.toLongOrNull() else null
            if(total!=null && total>=cursor){
                store.cache.applyContentMetadataMutations(store.key(namespace,original.uri),ContentMetadataMutations().also {ContentMetadataMutations.setContentLength(it,total)})
                end=minOf(end,total);chunkRemaining=minOf(chunkRemaining,end-cursor)
            }
            if(responseHeaders.isNotEmpty() && range==null){
                store.cache.applyContentMetadataMutations(store.key(namespace,original.uri),ContentMetadataMutations().set(RANGE_SUPPORT,0L))
                openContinuous()
            }else if(range!=null)store.cache.applyContentMetadataMutations(store.key(namespace,original.uri),ContentMetadataMutations().set(RANGE_SUPPORT,1L))
        }catch(error:HttpDataSource.InvalidResponseCodeException){
            if(error.responseCode==416 && cursor>0){end=cursor;chunkRemaining=0;data.close();delegate=null}else throw error
        }
    }
    private fun openContinuous(){
        val original=requireNotNull(spec)
        delegate?.close();delegate=null;continuous=true
        val data=factory.createDataSource();listeners.forEach(data::addTransferListener);delegate=data
        val available=data.open(original.subrange(cursor-original.position))
        responseHeaders=data.responseHeaders
        if(available>=0)end=minOf(end,cursor+available)
        chunkRemaining=(end-cursor).coerceAtLeast(0)
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        if(length==0)return 0
        if(cursor>=end)return C.RESULT_END_OF_INPUT
        if(chunkRemaining==0L && !continuous)openChunk()
        val data=delegate ?: return C.RESULT_END_OF_INPUT
        val count=data.read(buffer,offset,minOf(length.toLong(),chunkRemaining).toInt())
        if(count==C.RESULT_END_OF_INPUT){
            end=cursor
            spec?.let {store.cache.applyContentMetadataMutations(store.key(namespace,it.uri),ContentMetadataMutations().also {m->ContentMetadataMutations.setContentLength(m,cursor)})}
            return count
        }
        cursor+=count;chunkRemaining-=count
        return count
    }
    override fun getUri()=delegate?.uri ?: spec?.uri
    override fun getResponseHeaders()=responseHeaders
    override fun close(){try {delegate?.close()}finally {delegate=null;spec=null;responseHeaders=emptyMap()}}
    companion object {const val CHUNK_BYTES=2L*1024*1024;const val RANGE_SUPPORT="custom_lume_ranges"}
}
