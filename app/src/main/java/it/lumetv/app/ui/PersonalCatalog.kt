package it.lumetv.app.ui

import it.lumetv.app.data.PersonalCatalogQuery
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.core.Channel
import kotlinx.coroutines.CoroutineScope

/** Shared lazy catalog for touch and D-pad; it has no pagination controls. */
class PersonalCatalog(scope:CoroutineScope,private val repository:PersonalPlaylistRepository,
    changed:(ContinuousCatalog.Change)->Unit) {
    val data=ContinuousCatalog<Channel>(scope,changed=changed)
    private var restoredPosition:Int?=null
    private var lastQuery:PersonalCatalogQuery?=null
    val offset get()=data.anchor
    val total get()=data.size
    val rows get()=data.cached
    val loading get()=data.loading
    val error get()=if(data.hasError)"Impossibile caricare la lista. Riprova." else ""
    fun bind(query:PersonalCatalogQuery):Boolean {
        val reset=data.bind(query to repository.version.value,restoredPosition ?: if(query==lastQuery)data.anchor else 0) {offset->
            repository.catalog(query.copy(offset=offset,limit=data.blockSize)).let {it.rows to it.total}
        }
        if(reset){restoredPosition=null;lastQuery=query}
        return reset
    }
    fun hide()=Unit
    fun restore(value:Int){restoredPosition=value.coerceAtLeast(0)}
}
