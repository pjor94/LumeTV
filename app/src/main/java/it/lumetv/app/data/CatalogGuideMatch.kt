package it.lumetv.app.data

import it.lumetv.core.Channel
import it.lumetv.core.PlaylistEntry
import java.text.Normalizer
import java.util.Locale

/** Exact identifiers and unambiguous names; regional and timeshift suffixes stay significant. */
internal object CatalogGuideMatch {
    fun id(value:String)=when(val key=value.trim().lowercase(Locale.ROOT).removeSuffix(".it")) {
        "rairadio2visualradio"->"rairadio2"
        "rai3tgr"->"rai3tgrveneto"
        else->key
    }
    fun name(value:String):String {
        val words=value.trim().split(Regex("\\s+")).toMutableList()
        while(words.lastOrNull()?.lowercase(Locale.ROOT) in setOf("hd","sd","fhd","uhd","4k","8k"))words.removeAt(words.lastIndex)
        return Normalizer.normalize(words.joinToString(" "),Normalizer.Form.NFKD).replace(Regex("\\p{M}+"),"")
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}+]"),"")
    }
    private fun qualifiers(value:String):Pair<Set<String>,String> {
        val key=name(value)
        val regions=if(key.contains("rai3"))setOf("abruzzo","basilicata","calabria","campania","emiliaromagna","friuliveneziagiulia",
            "lazio","liguria","lombardia","marche","molise","piemonte","puglia","sardegna","sicilia","toscana","trentino","valledaosta","veneto","bolzano","trento").filter {key.contains(it)}.toSet() else emptySet()
        return regions to key.substringAfter('+',"").takeWhile(Char::isDigit)
    }
    fun compatible(entry:PlaylistEntry,target:String,names:List<String>):Boolean {
        val targetQualifiers=qualifiers(id(target)+" "+names.joinToString(" "))
        return listOf(entry.tvgId,entry.metadata.tvgName,entry.name).all {value->
            val source=qualifiers(value)
            (source.first.isEmpty() || source.first.intersect(targetQualifiers.first).isNotEmpty()) &&
                (source.second.isEmpty() || source.second==targetQualifiers.second)
        }
    }
    fun builtin(entry:PlaylistEntry,channels:List<Channel>):Channel? {
        val exact=channels.filter {it.epgId==entry.tvgId && entry.tvgId.isNotBlank()}
        if(exact.size==1)return exact.single()
        if(exact.size>1)return null
        val identifier=id(entry.tvgId)
        if(identifier.isNotEmpty()) {
            val matches=channels.filter {id(it.epgId)==identifier}
            if(matches.isNotEmpty())return matches.singleOrNull()
        }
        for(value in listOf(entry.metadata.tvgName,entry.name).filter(String::isNotBlank).distinct()) {
            val key=name(value)
            val matches=channels.filter {name(it.name)==key || name(it.epgId.removeSuffix(".it"))==key}
            if(matches.isNotEmpty())return matches.singleOrNull()
        }
        return null
    }
}

internal enum class PublicGuideFeed(val filename:String) { LUME("catalog-guide.sqlite"), EPGSHARE("epgshare-guide.sqlite") }
internal data class CatalogGuideProbe(val channel:String?=null,val conflict:Boolean=false,val hasProgrammes:Boolean=false,val logo:String?=null)
internal data class CatalogGuideAlias(val playlistId:String,val playlistName:String,val feedId:String,val feedName:String)

/** Identifier/name pairs verified by the desktop integration, not approximate title matching. */
internal val epgShareAliases=listOf(
    CatalogGuideAlias("eurosport1.it","Eurosport 1","eurosport.italia.it","Eurosport Italia"),
    CatalogGuideAlias("eurosport2.it","Eurosport 2","eurosport.2.italia.it","Eurosport 2 Italia"),
    CatalogGuideAlias("dazneurosport1.it","DAZN Eurosport 1","eurosport.italia.it","Eurosport Italia"),
    CatalogGuideAlias("dazneurosport2.it","DAZN Eurosport 2","eurosport.2.italia.it","Eurosport 2 Italia"),
    CatalogGuideAlias("solocalciosportitaliafonte1.it","Solo Calcio | Sportitalia ( Fonte 1 )","solocalcio.it.it","Solocalcio"),
    CatalogGuideAlias("skyunoplus.it","Sky Uno Plus","sky.uno.+..it","Sky Uno +"),
)

internal fun PersonalEntrySnapshot.providesGuide(): Boolean = entry.providesGuide(settings.guide)

internal fun PlaylistEntry.providesGuide(guide: PersonalGuideSettings): Boolean =
    guide.urls.isNotEmpty() || !metadata["url-tvg"].isNullOrBlank() || !metadata["x-tvg-url"].isNullOrBlank()
