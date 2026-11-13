package it.lumetv.app.data

import it.lumetv.core.PlaylistDescriptor
import it.lumetv.core.PlaylistUris
import java.net.URI

internal class GuideCandidate(val id:String,val epgKey:String,val tvgId:String,val tvgName:String,val name:String,
    val document:String,val shift:Long,val feeds:List<String>) {
    override fun toString()="GuideCandidate()"
}
internal class PersonalGuideInput(val source:PersonalPlaylistSource,val settings:PlaylistSettings,val context:PersonalRequestContext,
    val descriptor:PlaylistDescriptor,val candidates:List<GuideCandidate>,val error:String?=null) {
    override fun toString()="PersonalGuideInput(channels=${candidates.size})"
}
object PersonalGuideKeys {
    fun channel(source:String,id:String)="personal:$source:$id"
}
internal fun guideUrls(attributes:Map<String,String>,base:String?):List<String> =
    (attributes["url-tvg"] ?: attributes["x-tvg-url"]).orEmpty().split(',').map(String::trim).filter(String::isNotBlank).take(16).mapNotNull {raw->
        runCatching {val uri=URI(raw);if(uri.isAbsolute)raw else base?.let {PlaylistUris.resolve(URI(it),uri).toString()}}.getOrNull()
    }
