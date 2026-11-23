package it.lumetv.app.playback

import java.net.URI

internal data class VodPreviewCue(val start:Long,val end:Long,val url:String,val crop:List<Int> = emptyList())
/** A bounded WebVTT reader for thumbnail/storyboard and chapter sidecars. */
internal object VodSidecars {
    private val timing=Regex("^([0-9:.]+)\\s+-->\\s+([0-9:.]+)(?:\\s.*)?$")
    private fun time(raw:String):Long?=runCatching {
        val p=raw.split(':');require(p.size in 2..3)
        val seconds=p.last().toDouble();val minutes=p[p.lastIndex-1].toInt()
        require(seconds.isFinite() && seconds>=0 && seconds<60 && minutes in 0..59)
        val hours=if(p.size==3)p[0].toInt().also {require(it in 0..999)} else 0
        ((hours*3600+minutes*60)*1000+seconds*1000).toLong()
    }.getOrNull()
    private fun cues(text:String):List<Triple<Long,Long,String>> {
        if(text.length>512*1024 || !text.trimStart('\uFEFF',' ','\n','\r').startsWith("WEBVTT"))return emptyList()
        return text.replace("\r","").split(Regex("\n\\s*\n")).asSequence().take(2400).mapNotNull {block->
            val lines=block.lines();val i=lines.indexOfFirst {timing.matches(it.trim())};if(i<0)return@mapNotNull null
            val match=timing.matchEntire(lines[i].trim()) ?: return@mapNotNull null
            val start=time(match.groupValues[1]) ?: return@mapNotNull null
            val end=time(match.groupValues[2]) ?: return@mapNotNull null
            val payload=lines.drop(i+1).joinToString(" ").trim().take(2048)
            if(end<=start || payload.isBlank())null else Triple(start,end,payload)
        }.sortedBy {it.first}.take(2000).toList()
    }
    fun previews(text:String,base:String):List<VodPreviewCue> = cues(text).mapNotNull {(start,end,payload)->runCatching {
        val uri=URI(base).resolve(payload);require(uri.scheme in setOf("http","https") && uri.host!=null && uri.rawUserInfo==null)
        val crop=uri.fragment?.removePrefix("xywh=")?.split(',')?.map {it.toInt()}.orEmpty()
        require(crop.isEmpty() || crop.size==4 && crop[0]>=0 && crop[1]>=0 && crop[2]>0 && crop[3]>0 && crop.all {it<=8192})
        VodPreviewCue(start,end,uri.toString().substringBefore('#'),crop)
    }.getOrNull()}
    fun chapters(text:String):List<VodChapter> = cues(text).map {(start,_,title)->
        VodChapter(start,title.replace(Regex("<[^>]*>"),"").take(160))
    }.distinctBy {it.position}.take(200)
}
