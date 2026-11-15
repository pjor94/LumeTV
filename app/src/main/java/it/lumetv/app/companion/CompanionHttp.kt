package it.lumetv.app.companion

import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

internal data class CompanionRequest(val method:String,val path:String,val headers:Map<String,String>,val body:JSONObject) {
    val token get()=headers["authorization"]?.takeIf {it.startsWith("Bearer ")}?.removePrefix("Bearer ").orEmpty()
}
internal data class CompanionResponse(val status:Int=200,val body:JSONObject=JSONObject())

/** Bounded HTTP/1.1 over TLS. No files, redirects, URL fetching, CORS, or arbitrary Android intents. */
internal class CompanionHttp(identity:CompanionIdentity,address:String,requestedPort:Int=0,private val handle:(CompanionRequest)->CompanionResponse):AutoCloseable {
    private val listener=(try {identity.tls.serverSocketFactory.createServerSocket(requestedPort,8,InetAddress.getByName("0.0.0.0"))}
        catch(error:java.net.BindException){if(requestedPort==0)throw error;identity.tls.serverSocketFactory.createServerSocket(0,8,InetAddress.getByName("0.0.0.0"))} as SSLServerSocket).apply {
        enabledProtocols=enabledProtocols.filter {it=="TLSv1.2" || it=="TLSv1.3"}.toTypedArray()
        enabledCipherSuites=enabledCipherSuites.filter {it.contains("_ECDHE_ECDSA_") || it.startsWith("TLS_AES_") || it=="TLS_CHACHA20_POLY1305_SHA256"}.toTypedArray()
    }
    val port get()=listener.localPort
    private val sockets=Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers=ThreadPoolExecutor(8,8,30,TimeUnit.SECONDS,ArrayBlockingQueue(8),{r->Thread(r,"lume-lan-request").apply {isDaemon=true}})
    @Volatile private var closed=false
    val isOpen get()=!closed && !listener.isClosed
    private val accept=Thread({
        while(!closed)try {
            val socket=listener.accept() as SSLSocket
            // Only the selected Wi-Fi/Ethernet interface and local processes may reach the protocol.
            // In particular, a simultaneous cellular interface must never expose the TV listener.
            if(socket.localAddress.hostAddress!=address && !socket.localAddress.isLoopbackAddress){socket.close();continue}
            socket.soTimeout=4_000;sockets+=socket
            try {workers.execute {serve(socket)}}catch(_:java.util.concurrent.RejectedExecutionException){sockets-=socket;socket.close()}
        } catch(_:Exception){if(!closed)close()}
    },"lume-lan-listener").apply {isDaemon=true;start()}
    private fun serve(socket:SSLSocket) {
        try {socket.use {
            socket.startHandshake()
            val input=BufferedInputStream(socket.inputStream)
            val output=socket.outputStream
            repeat(40) {
                if(closed)return
                val request=try {readRequest(input)}catch(_:EOFException){return}catch(_:java.net.SocketTimeoutException){return}catch(e:CompanionFailure){
                    write(output,CompanionResponse(e.status,CompanionProtocol.error(e.code)),false);return
                }catch(_:Exception){write(output,CompanionResponse(400,CompanionProtocol.error("bad_request")),false);return}
                val result=try {handle(request)}catch(e:CompanionFailure){CompanionResponse(e.status,CompanionProtocol.error(e.code))}
                    catch(_:Exception){CompanionResponse(400,CompanionProtocol.error("bad_request"))}
                val keep=request.headers["connection"]?.lowercase(java.util.Locale.ROOT)!="close" && it<39
                write(output,result,keep)
                if(!keep)return
            }
        }}catch(error:javax.net.ssl.SSLException){
            if(it.lumetv.app.BuildConfig.DEBUG)android.util.Log.w("LumeCompanion","TLS handshake failed",error)
        }catch(_:Exception){/* Disconnection, malformed requests and timeouts do not enter playback. */}
        finally {sockets-=socket}
    }
    override fun close() {
        closed=true;runCatching {listener.close()}
        val open=synchronized(sockets){sockets.toList().also {sockets.clear()}}
        cleanup.launch {
            open.forEach {runCatching {it.close()}}
            workers.shutdownNow()
        }
    }
    companion object {
        private val cleanup=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
        fun readRequest(input:InputStream):CompanionRequest {
            var bytes=0
            fun line():String {
                val value=java.io.ByteArrayOutputStream()
                while(true) {
                    val c=input.read();if(c<0)throw EOFException()
                    if(++bytes>8192 || value.size()>2048)throw CompanionFailure(431,"headers_too_large")
                    if(c==13){if(input.read()!=10)throw CompanionFailure(400,"bad_request");bytes++;return value.toString("US-ASCII")}
                    if(c<32 || c>126)throw CompanionFailure(400,"bad_request")
                    value.write(c)
                }
            }
            val start=line().split(' ')
            if(start.size!=3 || start[2]!="HTTP/1.1" || start[0] !in setOf("GET","POST") || !start[1].matches(Regex("/v1/[a-z/]+")))throw CompanionFailure(400,"bad_request")
            val headers=linkedMapOf<String,String>()
            while(true) {
                val value=line();if(value.isEmpty())break
                val colon=value.indexOf(':');if(colon<=0)throw CompanionFailure(400,"bad_request")
                val key=value.substring(0,colon).lowercase(java.util.Locale.ROOT)
                if(!key.matches(Regex("[a-z0-9-]+")) || key in headers)throw CompanionFailure(400,"bad_request")
                headers[key]=value.substring(colon+1).trim()
            }
            if("host" !in headers || "transfer-encoding" in headers || "origin" in headers || "expect" in headers)throw CompanionFailure(400,"bad_request")
            val length=headers["content-length"]?.let {it.toIntOrNull() ?: throw CompanionFailure(400,"bad_request")} ?: 0
            if(length<0 || length>CompanionProtocol.MAX_BODY)throw CompanionFailure(413,"body_too_large")
            if(start[0]=="GET" && length!=0 || start[0]=="POST" && headers["content-type"]?.substringBefore(';')!="application/json")throw CompanionFailure(400,"bad_request")
            val body=ByteArray(length);var position=0
            while(position<length){val read=input.read(body,position,length-position);if(read<0)throw EOFException();position+=read}
            return CompanionRequest(start[0],start[1],headers,if(length==0)JSONObject() else JSONObject(String(body,Charsets.UTF_8)))
        }
        private fun write(output:java.io.OutputStream,response:CompanionResponse,keep:Boolean) {
            val body=response.body.toString().toByteArray(Charsets.UTF_8)
            require(body.size<=CompanionProtocol.MAX_BODY)
            output.write(("HTTP/1.1 ${response.status} Response\r\nContent-Type: application/json; charset=utf-8\r\n"+
                "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: ${if(keep)"keep-alive" else "close"}\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(body);output.flush()
        }
    }
}
