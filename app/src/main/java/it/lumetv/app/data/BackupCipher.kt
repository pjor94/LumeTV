package it.lumetv.app.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, authenticated frames bound memory on API 23 as well as newer devices.
 * Frame index, size and header are authenticated; an authenticated empty final frame
 * prevents truncation. Every frame has a distinct nonce, including the final frame.
 */
internal object BackupCipher {
    private val magic="LUMEBK01".toByteArray(Charsets.US_ASCII)
    private const val rounds=600_000
    private const val chunk=256*1024
    const val MAX_BYTES=2L*1024*1024*1024
    private const val overhead=1024*1024L
    private fun invalid():Nothing=throw IOException("Password errata o backup danneggiato.")

    fun encrypt(output:OutputStream,password:CharArray,check:()->Unit={},write:(OutputStream)->Unit) {
        requirePassword(password)
        val salt=ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce=ByteArray(8).also(SecureRandom()::nextBytes)
        val header=magic+salt+nonce
        val key=derive(password,salt,rounds,check)
        try {
            val data=DataOutputStream(output);data.write(header)
            val buffer=ByteArray(chunk);var size=0;var index=0;var total=0L
            fun frame(length:Int) {
                check();val spec=SecretKeySpec(key,"AES")
                val iv=nonce+ByteBuffer.allocate(4).putInt(index).array()
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE,spec,GCMParameterSpec(128,iv))
                cipher.updateAAD(header+ByteBuffer.allocate(8).putInt(index).putInt(length).array())
                data.writeInt(length);data.write(cipher.doFinal(buffer,0,length));index++
            }
            val framed=object:OutputStream() {
                override fun write(value:Int){write(byteArrayOf(value.toByte()))}
                override fun write(bytes:ByteArray,offset:Int,length:Int) {
                    var start=offset;var left=length
                    total+=length
                    if(total>MAX_BYTES)throw IOException("Il backup supera il limite di 2 GiB.")
                    while(left>0) {
                        val count=minOf(left,buffer.size-size)
                        bytes.copyInto(buffer,size,start,start+count);size+=count;start+=count;left-=count
                        if(size==buffer.size){frame(size);size=0}
                    }
                }
                override fun flush(){data.flush()}
                override fun close(){flush()} // The archive cannot finalize the encryption stream.
            }
            try {write(framed);if(size>0)frame(size);frame(0);data.flush()}finally {buffer.fill(0)}
        } finally {key.fill(0)}
    }

    fun decrypt(input:InputStream,output:OutputStream,password:CharArray,check:()->Unit={}) {
        requirePassword(password)
        val data=DataInputStream(input.buffered())
        val header=ByteArray(32)
        try {data.readFully(header)}catch(_:java.io.EOFException){invalid()}
        if(!header.copyOfRange(0,8).contentEquals(magic))throw IOException("Questo file non è un backup Lume supportato.")
        val key=derive(password,header.copyOfRange(8,24),rounds,check)
        try {
            var index=0;var total=0L
            while(true) {
                check()
                val length=try {data.readInt()}catch(_:java.io.EOFException){invalid()}
                if(length !in 0..chunk)invalid()
                total+=length+20
                if(total>MAX_BYTES+overhead)throw IOException("Il backup supera il limite di 2 GiB.")
                val bytes=ByteArray(length+16)
                try {data.readFully(bytes)}catch(_:java.io.EOFException){invalid()}
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),
                    GCMParameterSpec(128,header.copyOfRange(24,32)+ByteBuffer.allocate(4).putInt(index).array()))
                cipher.updateAAD(header+ByteBuffer.allocate(8).putInt(index).putInt(length).array())
                val plain=try {cipher.doFinal(bytes)}catch(_:java.security.GeneralSecurityException){invalid()}
                try {
                    if(length==0){if(data.read()!=-1)invalid();break}
                    output.write(plain)
                }finally {plain.fill(0)}
                index++
            }
        }finally {key.fill(0)}
    }

    fun requirePassword(password:CharArray) {
        if(password.size !in 8..128)throw IOException("Usa una password da 8 a 128 caratteri.")
    }

    /** PBKDF2-HMAC-SHA256, one 256-bit block. Mac is available on all supported APIs. */
    internal fun derive(password:CharArray,salt:ByteArray,iterations:Int,check:()->Unit={}):ByteArray {
        require(iterations>0)
        val encoded=Charsets.UTF_8.encode(CharBuffer.wrap(password))
        val bytes=ByteArray(encoded.remaining());encoded.get(bytes)
        if(encoded.hasArray())encoded.array().fill(0)
        val mac=Mac.getInstance("HmacSHA256")
        try {mac.init(SecretKeySpec(bytes,"HmacSHA256"))}finally {bytes.fill(0)}
        var current=mac.doFinal(salt+byteArrayOf(0,0,0,1));val result=current.clone()
        try {
            for(i in 1 until iterations) {
                if(i%1024==0)check()
                val previous=current;current=mac.doFinal(previous);previous.fill(0)
                for(j in result.indices)result[j]=(result[j].toInt() xor current[j].toInt()).toByte()
            }
            return result
        }catch(error:Exception){result.fill(0);throw error}finally {current.fill(0)}
    }
}
