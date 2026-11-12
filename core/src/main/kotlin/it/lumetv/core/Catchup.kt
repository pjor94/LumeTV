package it.lumetv.core

import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PlaybackMode { LIVE, VOD, ARCHIVE }

/** Only opaque catalog/event identities cross the presentation and playback boundary. */
data class PlaybackTarget(val channelId:String,val mode:PlaybackMode=PlaybackMode.LIVE,val eventId:String="")

enum class CatchupMode { DEFAULT, APPEND, SHIFT, FLUSSONIC_HLS, FLUSSONIC_TS, FLUSSONIC_DASH, XC, VOD }
class CatchupSpec(val mode:CatchupMode,val template:String,val days:Double,val correctionMillis:Long) {
    override fun toString()="CatchupSpec(mode=$mode, days=$days)"
}
class ArchiveEvent(val start:Long,val end:Long,val catchupId:String="") {
    override fun toString()="ArchiveEvent(start=$start, end=$end)"
}
class ArchiveStream(val url:String,val duration:Long,val offset:Long,val canRegenerate:Boolean) {
    override fun toString()="ArchiveStream(duration=$duration, offset=$offset)"
}

/** Translates playlist conventions. It never fetches resources or evaluates provider code. */
object CatchupUrls {
    fun spec(metadata:PlaylistMetadata,overrideDays:Int?=null):CatchupSpec? {
        val c=metadata.catchup
        val raw=c.mode?.trim()?.lowercase(Locale.ROOT)
        val inferred=when {
            !c.source.isNullOrBlank() -> "default"
            c.timeshift?.toDoubleOrNull()?.let {it>0}==true || c.recording?.toDoubleOrNull()?.let {it>1}==true -> "shift"
            else -> ""
        }
        val mode=when(raw?.takeIf(String::isNotBlank) ?: inferred.takeIf(String::isNotBlank) ?: return null) {
            "disabled","none","off","0" -> return null
            "default" -> CatchupMode.DEFAULT;"append" -> CatchupMode.APPEND;"shift","timeshift" -> CatchupMode.SHIFT
            "flussonic","flussonic-hls" -> CatchupMode.FLUSSONIC_HLS;"flussonic-ts","fs" -> CatchupMode.FLUSSONIC_TS
            "flussonic-dash" -> CatchupMode.FLUSSONIC_DASH;"xc" -> CatchupMode.XC;"vod" -> CatchupMode.VOD
            else -> throw PlaylistFormatException("La modalità archivio di questo canale non è supportata.")
        }
        val days=overrideDays?.toDouble() ?: c.days ?: c.legacyTime?.toDoubleOrNull()?.div(86_400)
            ?: c.timeshift?.toDoubleOrNull() ?: c.recording?.toDoubleOrNull()?.takeIf {it>1} ?: 0.0
        if(!days.isFinite() || days !in 0.0..365.0)throw PlaylistFormatException("La finestra dell’archivio non è valida.")
        return CatchupSpec(mode,c.source.orEmpty(),days,((c.correctionHours ?: 0.0)*3_600_000).toLong())
    }

    fun available(spec:CatchupSpec?,event:ArchiveEvent,now:Long,ongoing:Boolean=false):Boolean = spec!=null && spec.days>0 &&
        event.start>0 && event.end>event.start && event.start<now && event.start>=now-(spec.days*86_400_000).toLong() &&
        (event.end<=now || ongoing) && (spec.mode!=CatchupMode.VOD || event.catchupId.isNotBlank())

    fun build(live:String,spec:CatchupSpec,event:ArchiveEvent,now:Long,offset:Long=0,zone:ZoneId=ZoneId.systemDefault(),
              ongoing:Boolean=false,startBufferSeconds:Int=0,endBufferSeconds:Int=0):ArchiveStream {
        if(!available(spec,event,now,ongoing))throw PlaylistFormatException("Il programma non è disponibile nella finestra dell’archivio.")
        val begin=maxOf(event.start-startBufferSeconds.coerceIn(0,300)*1000L,now-(spec.days*86_400_000).toLong())
        val finish=minOf(event.end+endBufferSeconds.coerceIn(0,300)*1000L,now)
        val duration=(finish-begin).coerceAtLeast(1)
        val position=offset.coerceIn(0,(duration-1).coerceAtLeast(0))
        val start=begin+position+spec.correctionMillis
        val end=finish+spec.correctionMillis
        val uri=URI(live)
        fun append(query:String):String = live+(if(uri.rawQuery==null)"?" else if(live.endsWith('?') || live.endsWith('&'))"" else "&")+query.trimStart('?','&')
        val explicit=spec.template
        var template=when {
            explicit.isNotBlank() && spec.mode==CatchupMode.APPEND -> append(explicit)
            explicit.isNotBlank() && (explicit.startsWith('?') || explicit.startsWith('&')) -> append(explicit)
            explicit.isNotBlank() -> explicit
            spec.mode==CatchupMode.SHIFT || spec.mode in setOf(CatchupMode.DEFAULT,CatchupMode.APPEND) -> append("utc={utc}&lutc={lutc}")
            spec.mode==CatchupMode.FLUSSONIC_HLS -> root(uri)+uri.rawPath.substringBeforeLast('/')+"/timeshift_rel-{offset:1}.m3u8"+query(uri)
            spec.mode==CatchupMode.FLUSSONIC_TS -> root(uri)+uri.rawPath.substringBeforeLast('/')+"/timeshift_abs-{utc}.ts"+query(uri)
            spec.mode==CatchupMode.FLUSSONIC_DASH -> root(uri)+uri.rawPath.substringBeforeLast('/')+"/archive-{utc}-{duration}.mpd"+query(uri)
            spec.mode==CatchupMode.XC -> {
                val match=Regex("/(?:live/)?([^/]+)/([^/]+)/([^/.]+)(?:\\.(ts|m3u8))?").matchEntire(uri.rawPath)
                    ?: throw PlaylistFormatException("L’indirizzo live non consente di costruire l’archivio Xtream.")
                val date=DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm",Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(start))
                root(uri)+"/timeshift/${match.groupValues[1]}/${match.groupValues[2]}/${maxOf(1,(end-start+59_999)/60_000)}/$date/${match.groupValues[3]}."+
                    match.groupValues[4].ifBlank {"ts"}+query(uri)
            }
            spec.mode==CatchupMode.VOD -> "{catchup-id}"
            else -> throw PlaylistFormatException("Manca il modello dell’indirizzo dell’archivio.")
        }
        val regenerative=Regex("\\$?\\{(?:utc|start|offset|Y|m|d|H|M|S)(?::[^}]*)?\\}").containsMatchIn(template) || spec.mode==CatchupMode.XC
        if(template in setOf("{catchup-id}","\u0024{catchup-id}"))template=event.catchupId
        else template=Regex("\\$?\\{([^{}]+)\\}").replace(template) {match ->
            val name=match.groupValues[1].substringBefore(':');val format=match.groupValues[1].substringAfter(':',"")
            val value=when(name) {
                "utc","start" -> timestamp(start,format,zone)
                "lutc","now","timestamp" -> timestamp(now,format,zone)
                "utcend","end" -> timestamp(end,format,zone)
                "duration" -> ((end-start)/1000/divisor(format)).toString()
                "offset" -> ((now-start).coerceAtLeast(0)/1000/divisor(format)).toString()
                "catchup-id" -> encode(event.catchupId)
                "Y","m","d","H","M","S" -> date(start,name,zone)
                "start-year" -> date(start,"Y",zone);"start-mon" -> date(start,"m",zone);"start-day" -> date(start,"d",zone)
                "start-hour" -> date(start,"H",zone);"start-min" -> date(start,"M",zone);"start-sec" -> date(start,"S",zone)
                else -> throw PlaylistFormatException("Il modello dell’archivio contiene un segnaposto non supportato.")
            }
            value
        }
        val result=runCatching {PlaylistUris.resolve(uri,URI(template))}.getOrNull()
        if(result==null || result.host.isNullOrBlank() || result.rawUserInfo!=null || result.rawFragment!=null || result.scheme?.lowercase(Locale.ROOT) !in setOf("http","https") || result.toString().length>8192)
            throw PlaylistFormatException("L’indirizzo dell’archivio non è valido.")
        return ArchiveStream(result.toString(),duration,position,regenerative)
    }
    private fun timestamp(value:Long,format:String,zone:ZoneId)=if(format.isBlank())(value/1000).toString() else date(value,format,zone)
    private fun divisor(value:String)=if(value.isBlank())1 else value.toLongOrNull()?.takeIf {it in 1..86_400_000}
        ?: throw PlaylistFormatException("Il divisore temporale dell’archivio non è valido.")
    private fun date(value:Long,format:String,zone:ZoneId):String {
        if(format.length>80)throw PlaylistFormatException("Il formato della data dell’archivio non è valido.")
        val t=Instant.ofEpochMilli(value).atZone(zone)
        return buildString {format.forEach {c -> append(when(c) {
            'Y' -> "%04d".format(Locale.ROOT,t.year);'m' -> "%02d".format(Locale.ROOT,t.monthValue);'d' -> "%02d".format(Locale.ROOT,t.dayOfMonth)
            'H' -> "%02d".format(Locale.ROOT,t.hour);'M' -> "%02d".format(Locale.ROOT,t.minute);'S' -> "%02d".format(Locale.ROOT,t.second)
            '-',':','_','.','T' -> c.toString();' ' -> "%20"
            else -> throw PlaylistFormatException("Il formato della data dell’archivio non è supportato.")
        })}}
    }
    private fun root(uri:URI)=uri.scheme+"://"+uri.rawAuthority
    private fun query(uri:URI)=uri.rawQuery?.let {"?$it"}.orEmpty()
    private fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
}
