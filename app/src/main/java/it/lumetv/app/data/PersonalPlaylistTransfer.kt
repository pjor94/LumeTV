package it.lumetv.app.data

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A single playlist, without favourites, history, device identities or executable database files. */
internal class ExportedPlaylist(val file:File,val name:String,val count:Int):Closeable {
    override fun close(){file.delete()}
    override fun toString()="ExportedPlaylist(count=$count)"
}

internal object PersonalPlaylistTransfer {
    const val MAX_BYTES=256L*1024*1024
    const val MAX_EXPANDED=1024L*1024*1024
    fun output(file:File):OutputStream {
        val output=LimitedOutput(file.outputStream(),MAX_BYTES)
        try {return LimitedOutput(GZIPOutputStream(output).buffered(),MAX_EXPANDED)}catch(error:Exception){output.close();throw error}
    }
    fun input(file:File):InputStream {
        require(file.length() in 1..MAX_BYTES)
        val input=file.inputStream().buffered()
        try {return LimitedInput(GZIPInputStream(input),MAX_EXPANDED).buffered()}catch(error:Exception){input.close();throw error}
    }
    private class LimitedOutput(output:OutputStream,private val limit:Long):FilterOutputStream(output) {
        private var count=0L
        private fun reserve(bytes:Int){if(bytes>limit-count)throw IOException("La lista supera il limite di trasferimento.");count+=bytes}
        override fun write(value:Int){reserve(1);out.write(value)}
        override fun write(bytes:ByteArray,offset:Int,length:Int){reserve(length);out.write(bytes,offset,length)}
    }
    private class LimitedInput(input:InputStream,private val limit:Long):FilterInputStream(input) {
        private var count=0L
        private fun received(bytes:Int):Int {if(bytes>0){count+=bytes;if(count>limit)throw IOException("La lista supera il limite di trasferimento.")};return bytes}
        override fun read():Int {val value=`in`.read();if(value>=0)received(1);return value}
        override fun read(bytes:ByteArray,offset:Int,length:Int)=received(`in`.read(bytes,offset,minOf(length.toLong(),limit-count+1).toInt()))
    }
}
