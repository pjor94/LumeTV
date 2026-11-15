package it.lumetv.app.data

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object BackupIO {
    const val MAX_JSON=16*1024*1024
    const val MAX_ROW=2*1024*1024
    const val MAX_ITEMS=200_000
    private val idPattern=Regex("[a-f0-9]{24}")
    fun validChannel(id:String)=idPattern.matches(id)
    fun json(input:InputStream,limit:Int=MAX_JSON):JSONObject {
        val bytes=ByteArrayOutputStream();val buffer=ByteArray(32*1024)
        while(true){val count=input.read(buffer);if(count<0)break
            if(bytes.size()+count>limit)throw IOException("Il backup contiene dati troppo grandi.")
            bytes.write(buffer,0,count)}
        return JSONObject(text(bytes.toByteArray()))
    }
    fun writeJson(output:OutputStream,value:JSONObject,limit:Int=MAX_JSON) {
        val text=value.toString()
        if(text.length>limit)throw IOException("Le personalizzazioni superano il limite del backup.")
        val bytes=text.toByteArray(Charsets.UTF_8)
        if(bytes.size>limit)throw IOException("Le personalizzazioni superano il limite del backup.")
        output.write(bytes)
    }
    fun line(input:InputStream):JSONObject? {
        val bytes=ByteArrayOutputStream()
        while(true) {
            val next=input.read()
            if(next<0){if(bytes.size()==0)return null;break}
            if(next==10)break
            if(bytes.size()>=MAX_ROW)throw IOException("Il backup contiene una voce troppo grande.")
            bytes.write(next)
        }
        if(bytes.size()==0)throw IOException("Il backup contiene una voce vuota.")
        return JSONObject(text(bytes.toByteArray()))
    }
    fun writeLine(output:OutputStream,value:JSONObject) {
        writeJson(output,value,MAX_ROW);output.write(10)
    }
    private fun text(bytes:ByteArray)=Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
