package it.lumetv.app.data

import it.lumetv.core.PlaylistEntry
import java.io.File

/** Pick one schedule for the channel's retained horizon, independently of date/search filters. */
internal class PublicGuideFallback(directory:File) {
    val lume=CatalogGuideStore(directory)
    val supplement=CatalogGuideStore(directory,PublicGuideFeed.EPGSHARE)
    private fun selected(entry:PlaylistEntry,now:Long):CatalogGuideStore {
        val primary=lume.probe(entry,now)
        if(primary.conflict || primary.hasProgrammes)return lume
        return if(supplement.probe(entry,now).hasProgrammes)supplement else lume
    }
    fun window(entry:PlaylistEntry,from:Long,until:Long,now:Long):List<Programme> {
        val store=selected(entry,now)
        return store.window(entry,from,until).map {it.copy(guideOrigin=if(store===supplement)"epgshare" else "lume")}
    }
    fun onAir(entries: Map<String, PlaylistEntry>, now: Long): Map<String, Programme> {
        val primary = lume.onAir(entries, now)
        val fallback = supplement.onAir(entries.filterKeys { it !in primary }, now)
        return buildMap {
            primary.forEach { (id, programme) -> if (programme != null) put(id, programme.copy(guideOrigin = "lume")) }
            fallback.forEach { (id, programme) -> if (programme != null) put(id, programme.copy(guideOrigin = "epgshare")) }
        }
    }
    fun search(entry:PlaylistEntry,query:String,from:Long,until:Long,offset:Int,now:Long):Pair<List<Programme>,Int> {
        val store=selected(entry,now);val (shows,total)=store.search(entry,query,from,until,offset)
        return shows.map {it.copy(guideOrigin=if(store===supplement)"epgshare" else "lume")} to total
    }
    fun logos(entry:PlaylistEntry,catalogUrls:List<String>,now:Long):List<String> {
        val primary=lume.probe(entry,now)
        if(primary.conflict)return emptyList()
        val urls=(catalogUrls+listOfNotNull(primary.logo)).distinct()
        return urls.ifEmpty {listOfNotNull(supplement.probe(entry,now).logo)}
    }
    fun ready(now:Long)=lume.ready(now) || supplement.ready(now)
}
