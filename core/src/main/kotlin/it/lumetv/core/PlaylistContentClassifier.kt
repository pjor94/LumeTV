package it.lumetv.core

import java.net.URI
import java.text.Normalizer
import java.util.Locale

data class PlaylistClassification(val kind:PlaylistContentKind,val reason:PlaylistClassificationReason,val conflicting:Boolean=false)

/** One local, deterministic classifier for imports and saved catalogs. Never probes stream URLs. */
class PlaylistContentClassifier(options:PlaylistReadOptions=PlaylistReadOptions()) {
    private val settings=options.validated()
    private val rules=settings.groupContent.mapKeys {it.key.lowercase(Locale.ROOT)}

    fun classify(attributes:Map<String,String>,defaults:Map<String,String>,duration:Double?,uri:URI?,groups:List<String>,
                 marker:PlaylistVodMarker=PlaylistVodMarker.NONE,legacyVod:Boolean=false):PlaylistClassification {
        fun type(value:String?)=when(value?.trim()?.lowercase(Locale.ROOT)) {
            "playlist"->PlaylistContentKind.PLAYLIST
            "vod","movie","movies","film","films","video","series","episode","episodes","tvshow"->PlaylistContentKind.VOD
            "live","linear"->PlaylistContentKind.LIVE
            else->null
        }
        val primary=type(attributes["type"]);val secondary=type(attributes["content-type"])
        val own=primary ?: secondary
        val declaration=own ?: (type(defaults["type"]) ?: type(defaults["content-type"]))?.takeUnless {it==PlaylistContentKind.PLAYLIST}
        val effective=defaults+attributes
        val media=when(effective["media"]?.trim()?.lowercase(Locale.ROOT)) {
            "true","1","yes"->PlaylistContentKind.VOD
            "false","0","no"->PlaylistContentKind.LIVE
            else->null
        }
        val conflict=primary!=null && secondary!=null && primary!=secondary || declaration!=null && media!=null && declaration!=media
        fun result(kind:PlaylistContentKind,reason:PlaylistClassificationReason)=PlaylistClassification(kind,reason,conflict)
        // Includes are structural, and cannot be converted into playable media by a group rule.
        if(own==PlaylistContentKind.PLAYLIST)return result(own,PlaylistClassificationReason.TYPE)
        if(rules.isNotEmpty()) {
            val choices=groups.mapNotNull {rules[it.lowercase(Locale.ROOT)]}.toSet()
            if(choices.isNotEmpty())return PlaylistClassification(if(PlaylistContentKind.LIVE in choices)PlaylistContentKind.LIVE else PlaylistContentKind.VOD,
                PlaylistClassificationReason.GROUP_RULE,conflict || choices.size>1)
        }
        declaration?.let {return result(it,PlaylistClassificationReason.TYPE)}
        media?.let {return result(it,PlaylistClassificationReason.MEDIA)}
        if(marker!=PlaylistVodMarker.NONE)return result(PlaylistContentKind.VOD,PlaylistClassificationReason.KODI)
        if(!effective["media-dir"].isNullOrBlank())return result(PlaylistContentKind.VOD,PlaylistClassificationReason.MEDIA_DIRECTORY)
        if((effective["media-size"]?.trim()?.toLongOrNull() ?: 0)>0)return result(PlaylistContentKind.VOD,PlaylistClassificationReason.MEDIA_SIZE)
        if(duration!=null && duration.isFinite() && duration>0)return result(PlaylistContentKind.VOD,PlaylistClassificationReason.DURATION)
        val segments=uri?.rawPath.orEmpty().lowercase(Locale.ROOT).split('/').filter(String::isNotEmpty)
        // Match provider-style path components, never query parameters, host names or arbitrary substrings.
        val livePath=segments.any {it in liveSegments}
        if(!livePath && segments.size>=4 && segments[segments.size-4] in vodSegments && numericId.matches(segments.last()))
            return result(PlaylistContentKind.VOD,PlaylistClassificationReason.PROVIDER_PATH)
        // Old archives did not retain Kodi marker scope. Keep their prior VOD decision until a full reread.
        if(legacyVod)return result(PlaylistContentKind.VOD,PlaylistClassificationReason.LEGACY_VOD)
        if(settings.vodDetection==PlaylistVodDetection.EXTENDED && !livePath &&
            effective["catchup"].isNullOrBlank() && effective["catchup-type"].isNullOrBlank() && effective["timeshift"].isNullOrBlank() &&
            segments.lastOrNull()?.substringAfterLast('.',"") in videoExtensions && groups.any(::videoGroup))
            return result(PlaylistContentKind.VOD,PlaylistClassificationReason.GROUP_FILE)
        return result(PlaylistContentKind.LIVE,PlaylistClassificationReason.DEFAULT_LIVE)
    }

    companion object {
        const val VERSION=1
        private val numericId=Regex("[0-9]+(?:\\.[a-z0-9]{1,8})?")
        private val words=Regex("[^\\p{L}\\p{N}]+")
        private val accents=Regex("\\p{M}+")
        private val vodSegments=setOf("movie","series")
        private val liveSegments=setOf("live","timeshift","catchup","archive")
        private val videoExtensions=setOf("mp4","mkv","avi","mov","m4v","webm","mpg","mpeg")
        private val videoWords=setOf("vod","film","films","movie","movies","serie","series","episodi","episodes")
        private val liveWords=setOf("live","diretta","dirette","24h")
        private fun videoGroup(group:String):Boolean {
            val tokens=Normalizer.normalize(group,Normalizer.Form.NFD).replace(accents,"").lowercase(Locale.ROOT).split(words).toSet()
            return tokens.any {it in videoWords} && tokens.none {it in liveWords}
        }
    }
}
