package it.lumetv.core

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class VodMediaType(val apiName:String) { MOVIE("movie"), TV("tv") }

/** Only public presentation data leaves the playlist repository. */
data class VodLookupQuery(val title:String,val type:VodMediaType,val year:Int?=null,
    val tmdbId:Int?=null,val imdbId:String="") {
    val fingerprint:String get()=MessageDigest.getInstance("SHA-256")
        .digest("v1|${type.name}|${VodTitleMatcher.fold(title)}|$year|$tmdbId|$imdbId".toByteArray())
        .joinToString(""){"%02x".format(it)}
    override fun toString()="VodLookupQuery(type=$type)"
}

data class VodTitleCandidate(val id:Int,val type:VodMediaType,val title:String,val originalTitle:String="",val year:Int?=null) {
    override fun toString()="VodTitleCandidate(type=$type,id=$id)"
}

object VodTitleMatcher {
    private val yearInBrackets=Regex("(?:\\(|\\[)((?:19|20)\\d{2})(?:\\)|\\])")
    private val technical=Regex("(?i)[(\\[]\\s*(?:4k|uhd|fhd|hd|sd|720p|1080p|2160p|hdr|hdr10|dv|h[. ]?26[45]|x26[45]|hevc|ita|it|multi|dual)(?:[ ,+/_-]+(?:4k|uhd|fhd|hd|sd|720p|1080p|2160p|hdr|hdr10|dv|h[. ]?26[45]|x26[45]|hevc|ita|it|multi|dual))*\\s*[)\\]]")
    private val prefix=Regex("(?i)^(?:(?:it|ita|italian|multi|4k|uhd|fhd|hd|sd)\\s*\\|\\s*)+")
    private val spaces=Regex("\\s+")

    fun from(entry:PlaylistEntry):VodLookupQuery? {
        val metadata=VodMetadata.from(entry)
        val values=entry.metadata.defaults+entry.metadata.attributes
        val type=when(metadata.kind) {
            VodKind.MOVIE->VodMediaType.MOVIE
            VodKind.EPISODE->if(metadata.seriesTitle.isNotBlank())VodMediaType.TV else return null
            VodKind.VIDEO->when(values["tmdb-type"]?.lowercase(Locale.ROOT)){"movie"->VodMediaType.MOVIE;"tv"->VodMediaType.TV;else->return null}
        }
        val original=if(type==VodMediaType.TV)metadata.seriesTitle.ifBlank {metadata.title} else metadata.title
        fun year(value:String?)=value?.toIntOrNull()?.takeIf {it in 1888..2100}
        // An episode's release year is not the show's first-air year. Bare numbers can be part of a title.
        val release=if(type==VodMediaType.TV)year(values["series-year"]) else year(values["year"] ?: values["release-year"])
        val bracketYear=yearInBrackets.find(original)?.groupValues?.get(1)?.toIntOrNull()
        val title=clean(original)
        if(title.isBlank())return null
        return VodLookupQuery(title,type,release ?: bracketYear,
            (values["tmdb-id"] ?: values["tmdb_id"])?.toIntOrNull()?.takeIf {it>0},
            (values["imdb-id"] ?: values["imdb_id"]).orEmpty().takeIf {it.matches(Regex("tt\\d{7,10}"))}.orEmpty())
    }

    fun clean(value:String):String=value.take(300).replace(prefix,"").replace(technical," ")
        .replace(yearInBrackets," ").replace(spaces," ").trim(' ','.','_','-','|')

    fun fold(value:String):String=Normalizer.normalize(value,Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(spaces," ")

    fun ranked(query:VodLookupQuery,candidates:List<VodTitleCandidate>):List<VodTitleCandidate> = candidates
        .filter {it.id>0 && it.type==query.type}.distinctBy {it.id}
        .sortedByDescending {candidate->
            val names=listOf(candidate.title,candidate.originalTitle).map(::fold)
            val target=fold(query.title)
            val exact=target in names
            val words=target.split(' ').toSet()
            val overlap=names.maxOfOrNull {name->val tokens=name.split(' ').toSet();words.intersect(tokens).size.toDouble()/words.union(tokens).size.coerceAtLeast(1)} ?: 0.0
            (if(exact)100.0 else overlap*30)+(if(query.year!=null && query.year==candidate.year)20 else 0)
        }

    /** Similarity orders suggestions; only an unambiguous exact title can be accepted automatically. */
    fun automatic(query:VodLookupQuery,candidates:List<VodTitleCandidate>):VodTitleCandidate? {
        val title=fold(query.title)
        val exact=ranked(query,candidates).filter {title==fold(it.title) || title==fold(it.originalTitle)}
        if(query.year!=null) {
            exact.filter {it.year==query.year}.singleOrNull()?.let {return it}
            return exact.filter {it.year!=null && kotlin.math.abs(it.year-query.year)<=1}.singleOrNull()
        }
        return exact.singleOrNull()?.takeIf {title.length>=4}
    }
}
