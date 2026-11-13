package it.lumetv.core

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

enum class VodKind { VIDEO, MOVIE, EPISODE }

/** Presentation metadata. Playback identity and the original title are never rewritten. */
data class VodMetadata(val title:String,val kind:VodKind,val seriesTitle:String="",val seriesKey:String="",
    val season:Int?=null,val episode:Int?=null,val year:Int?=null,val duration:Long=0,
    val description:String="",val genres:List<String> = emptyList(),val poster:String="",val backdrop:String="") {
    companion object {
        private val episodeName=Regex("^(.+?)[ ._\\-]+(?:[sS](\\d{1,2})[ ._\\-]*[eE](\\d{1,3})|([0-9]{1,2})[xX]([0-9]{1,3}))(?:\\b|_)(.*)$")
        private val releaseYear=Regex("(?:^|[ (\\[])(19[0-9]{2}|20[0-9]{2})(?:[)\\] ]|$)")
        private val movieTypes=setOf("movie","movies","film","films")
        private val episodeTypes=setOf("series","episode","episodes","tvshow")
        private val seriesGroups=setOf("serie","series","tvshow")
        private val tokens=Regex("[^\\p{L}\\p{N}]+")
        private val whitespace=Regex("\\s+")
        fun from(entry:PlaylistEntry):VodMetadata {
            val values=entry.metadata.defaults+entry.metadata.attributes
            fun value(vararg names:String)=names.firstNotNullOfOrNull {values[it]?.trim()?.takeIf(String::isNotEmpty)}.orEmpty()
            fun number(vararg names:String)=value(*names).toIntOrNull()?.takeIf {it in 0..9999}
            fun image(vararg names:String):String {
                val raw=value(*names)
                if(raw.isBlank())return ""
                return runCatching {val uri=URI(raw);(if(uri.isAbsolute)uri else entry.metadata.baseUrl?.let {PlaylistUris.resolve(URI(it),uri)} ?: uri)
                    .takeIf {it.scheme?.lowercase(Locale.ROOT) in setOf("http","https")}?.toString().orEmpty()}.getOrDefault("")
            }
            val match=episodeName.matchEntire(entry.name)
            val series=value("series-title","series-name","show-title","tvg-series").ifBlank {match?.groupValues?.get(1)?.trim().orEmpty()}.take(300)
            val season=number("season","season-number") ?: match?.let {(it.groupValues[2].ifEmpty {it.groupValues[4]}).toIntOrNull()}
            val episode=number("episode","episode-number") ?: match?.let {(it.groupValues[3].ifEmpty {it.groupValues[5]}).toIntOrNull()}
            val type=listOf(values["type"],values["content-type"]).mapNotNull {it?.trim()?.lowercase(Locale.ROOT)}.firstOrNull {it in movieTypes || it in episodeTypes || it in setOf("vod","video")}.orEmpty()
            val segments=runCatching {URI(entry.url).rawPath.orEmpty().lowercase(Locale.ROOT).split('/')}.getOrDefault(emptyList())
            val groupWords=entry.groups.flatMap {it.lowercase(Locale.ROOT).split(tokens)}.toSet()
            val kind=when {
                type in movieTypes->VodKind.MOVIE
                series.isNotEmpty() || type in episodeTypes || "series" in segments->VodKind.EPISODE
                "movie" in segments->VodKind.MOVIE
                groupWords.any {it in seriesGroups}->VodKind.EPISODE
                groupWords.any {it in movieTypes}->VodKind.MOVIE
                else->VodKind.VIDEO
            }
            val year=number("year","release-year")?.takeIf {it in 1888..2100} ?: releaseYear.find(entry.name)?.groupValues?.get(1)?.toIntOrNull()
            val key=if(series.isBlank() || kind!=VodKind.EPISODE)"" else MessageDigest.getInstance("SHA-256")
                .digest(value("series-id","show-id").takeIf(String::isNotBlank)?.let {"id:$it"}.orEmpty().ifBlank {"name:"+series.lowercase(Locale.ROOT).replace(whitespace," ")+"|"+value("series-year")}.toByteArray())
                .joinToString(""){"%02x".format(it)}
            val description=value("plot","description","tvg-description").replace(Regex("<[^>]{0,200}>"),"").take(4000)
            val genres=value("genre","genres").split(';',',').map(String::trim).filter(String::isNotBlank).distinct().take(20)
            val duration=entry.metadata.duration?.takeIf {it.isFinite() && it>0}?.times(1000)?.toLong() ?: 0
            return VodMetadata(entry.name,kind,series,key,season,episode,year,duration,description,genres,
                image("poster","poster-url","tvg-poster","cover","cover-url").ifBlank {entry.logo},image("backdrop","backdrop-url","fanart","fanart-url","tvg-background"))
        }
    }
    override fun toString()="VodMetadata(kind=$kind)"
}
