package it.lumetv.app.data

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.ext.DefaultHandler2
import java.io.FilterInputStream
import java.io.InputStream
import java.io.StringReader
import java.io.BufferedInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.xml.parsers.SAXParserFactory

internal class XmlChannel(val id:String,val names:List<String>,val icon:String) {
    override fun toString()="XmlChannel()"
}
internal class XmlProgramme(val channel:String,val title:String,val description:String,val start:Long,val end:Long,val catchupId:String) {
    override fun toString()="XmlProgramme(start=$start,end=$end)"
}

/** SAX character callbacks keep descriptions bounded even when a feed puts everything on one line. */
internal object PersonalXmlTv {
    const val MAX_BYTES=256L*1024*1024
    fun parse(input:InputStream,language:String,zone:ZoneId,active:()->Unit={},
              channel:(XmlChannel)->Unit,programme:(XmlProgramme)->Unit) {
        var depth=0;var count=0;var root=false;var programmeSeen=false
        var channelId="";val names=mutableListOf<String>();var icon=""
        var inProgramme=false;var wireChannel="";var start=0L;var end=0L;var catchup=""
        var title="";var description="";var titleRank=-1;var descRank=-1
        var capture="";var captureDepth=0;var rank=0;var limit=0;val text=StringBuilder()
        fun weight(lang:String?):Int=when {
            lang?.replace('_','-')?.substringBefore('-')?.lowercase()==language.substringBefore('-').substringBefore('_').lowercase()->3
            lang.isNullOrBlank()->2;else->1
        }
        val handler=object:DefaultHandler2() {
            override fun startElement(uri:String?,localName:String?,qName:String,attrs:Attributes) {
                if(++depth>32)throw SourceException("La struttura XMLTV supera la profondità consentita.")
                if(++count%256==0)active()
                val tag=qName.substringAfter(':')
                if(depth==1) {if(tag!="tv")throw SourceException("Il documento ricevuto non è una guida XMLTV.");root=true}
                when {
                    tag=="channel" && depth==2 -> {
                        if(programmeSeen)throw SourceException("I canali XMLTV devono precedere i programmi.")
                        channelId=attrs.getValue("id").orEmpty().take(300);names.clear();icon=""
                    }
                    tag=="programme" && depth==2 -> {
                        programmeSeen=true;inProgramme=true;wireChannel=attrs.getValue("channel").orEmpty().take(300)
                        start=timestamp(attrs.getValue("start"),zone);end=timestamp(attrs.getValue("stop"),zone)
                        catchup=attrs.getValue("catchup-id").orEmpty().take(8192);title="";description="";titleRank=-1;descRank=-1
                    }
                    tag=="icon" && channelId.isNotEmpty() && !inProgramme -> icon=attrs.getValue("src").orEmpty().take(8192)
                    tag=="display-name" && channelId.isNotEmpty() && !inProgramme || tag in setOf("title","desc") && inProgramme && depth==3 -> {
                        capture=tag;captureDepth=depth;text.setLength(0);rank=weight(attrs.getValue("lang"));limit=if(tag=="desc")1600 else 300
                    }
                }
            }
            override fun characters(ch:CharArray,offset:Int,length:Int) {
                if(capture.isNotEmpty() && text.length<limit)text.append(ch,offset,minOf(length,limit-text.length))
            }
            override fun endElement(uri:String?,localName:String?,qName:String) {
                val tag=qName.substringAfter(':')
                if(capture.isNotEmpty() && depth==captureDepth) {
                    val value=text.toString().trim()
                    when(capture) {
                        "display-name" -> if(value.isNotEmpty() && names.size<10)names+=value
                        "title" -> if(value.isNotEmpty() && rank>titleRank){title=value;titleRank=rank}
                        "desc" -> if(value.isNotEmpty() && rank>descRank){description=value;descRank=rank}
                    }
                    capture=""
                }
                if(tag=="channel" && depth==2) {if(channelId.isNotEmpty())channel(XmlChannel(channelId,names.toList(),icon));channelId=""}
                if(tag=="programme" && depth==2) {
                    if(wireChannel.isNotEmpty() && title.isNotEmpty() && start>0)programme(XmlProgramme(wireChannel,title,description,start,end,catchup))
                    inProgramme=false
                }
                depth--
            }
            override fun resolveEntity(publicId:String?,systemId:String?)=InputSource(StringReader(""))
            override fun resolveEntity(name:String?,publicId:String?,baseURI:String?,systemId:String?)=InputSource(StringReader(""))
            override fun internalEntityDecl(name:String?,value:String?) {throw SourceException("Le entità interne XMLTV non sono supportate.")}
        }
        val reader=SAXParserFactory.newInstance().apply {isNamespaceAware=false;isValidating=false}.newSAXParser().xmlReader
        for(feature in listOf("http://xml.org/sax/features/external-general-entities","http://xml.org/sax/features/external-parameter-entities","http://apache.org/xml/features/nonvalidating/load-external-dtd"))
            runCatching {reader.setFeature(feature,false)}
        reader.contentHandler=handler;reader.errorHandler=handler;reader.entityResolver=handler
        runCatching {reader.setProperty("http://xml.org/sax/properties/declaration-handler",handler)}
        val buffered=BufferedInputStream(input);buffered.mark(4)
        val lead=ByteArray(4);var read=0
        while(read<lead.size){val n=buffered.read(lead,read,lead.size-read);if(n<0)break;if(n>0)read+=n}
        buffered.reset()
        val encoding=when {
            read>=4 && (lead.contentEquals(byteArrayOf(0,0,0xfe.toByte(),0xff.toByte())) || lead.contentEquals(byteArrayOf(0xff.toByte(),0xfe.toByte(),0,0)) || lead.contentEquals(byteArrayOf(0,0,0,60)))->throw SourceException("Converti la guida XMLTV da UTF-32 a UTF-8 o UTF-16.")
            read>=2 && (lead[0]==0xff.toByte() && lead[1]==0xfe.toByte() || lead[0]==60.toByte() && lead[1]==0.toByte())->1
            read>=2 && (lead[0]==0xfe.toByte() && lead[1]==0xff.toByte() || lead[0]==0.toByte() && lead[1]==60.toByte())->2
            else->0
        }
        reader.parse(InputSource(Guard(buffered,active,encoding)))
        if(!root || depth!=0)throw SourceException("La guida XMLTV è incompleta.")
    }

    fun timestamp(raw:String?,zone:ZoneId=ZoneOffset.UTC):Long {
        if(raw.isNullOrBlank())return 0
        return runCatching {
            val match=Regex("(\\d{4}(?:\\d{2}){0,5})(?:\\s+([^\\s]+))?").matchEntire(raw.trim()) ?: return 0
            val s=match.groupValues[1]
            fun part(from:Int,default:Int)=if(s.length>=from+2)s.substring(from,from+2).toInt() else default
            val time=LocalDateTime.of(s.take(4).toInt(),part(4,1),part(6,1),part(8,0),part(10,0),part(12,0))
            val offset=match.groupValues[2]
            if(offset.isEmpty())time.atZone(zone).toInstant().toEpochMilli()
            else time.toInstant(if(offset in setOf("Z","UTC","GMT"))ZoneOffset.UTC else ZoneOffset.of(offset)).toEpochMilli()
        }.getOrDefault(0)
    }

    /** Blocks internal DTD subsets before parsing and bounds markup, including giant attributes. */
    private class Guard(input:InputStream,private val active:()->Unit,private val encoding:Int):FilterInputStream(input) {
        private var bytes=0L;private var mode=0;private var quote=0;private var tagSize=0
        private var half=-1
        private val prefix=StringBuilder();private var tail=""
        private fun inspect(buffer:ByteArray,offset:Int,n:Int) {
            bytes+=n;if(bytes>MAX_BYTES)throw SourceException("La guida decompressa supera il limite di 256 MiB.")
            active()
            for(i in offset until offset+n) {
                val byte=buffer[i].toInt() and 255
                val c=if(encoding==0)byte else {
                    if(half<0){half=byte;continue}
                    val unit=if(encoding==1)half or (byte shl 8) else (half shl 8) or byte
                    half=-1;unit
                }
                when(mode) {
                    0 -> if(c==60){mode=1;quote=0;tagSize=1;prefix.setLength(0);prefix.append('<')}
                    2,3 -> {tail=(tail+c.toChar()).takeLast(3);if(tail==if(mode==2)"-->" else "]]>") {mode=0;tail=""}}
                    else -> {
                        if(++tagSize>64*1024)throw SourceException("Una dichiarazione XMLTV supera il limite previsto.")
                        if(prefix.length<10)prefix.append(c.toChar())
                        if(prefix.length==4 && prefix.toString()=="<!--"){mode=2;tail=""}
                        else if(prefix.length==9 && prefix.toString()=="<![CDATA["){mode=3;tail=""}
                        else {
                            if(prefix.startsWith("<!DOCTYPE"))mode=4
                            if(quote!=0) {if(c==quote)quote=0}
                            else if(c==34 || c==39)quote=c
                            else if(mode==4 && c==91)throw SourceException("Le dichiarazioni XMLTV con entità interne non sono supportate.")
                            else if(c==62)mode=0
                        }
                    }
                }
            }
        }
        override fun read():Int {val value=`in`.read();if(value>=0)inspect(byteArrayOf(value.toByte()),0,1);return value}
        override fun read(b:ByteArray,off:Int,len:Int):Int {val n=`in`.read(b,off,len);if(n>0)inspect(b,off,n);return n}
    }
}
