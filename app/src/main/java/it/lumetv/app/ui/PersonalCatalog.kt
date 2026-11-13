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
    private val positions=object:LinkedHashMap<PersonalCatalogQuery,Int>(16,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<PersonalCatalogQuery,Int>?)=size>12
    }
    // Retain the filter's identity, without keeping old copies of the favorites ID set.
    private fun positionKey(query:PersonalCatalogQuery)=query.copy(ids=query.ids?.let {emptySet()})
    val offset get()=data.anchor
    val total get()=data.size
    val rows get()=data.cached
    val loading get()=data.loading
    val error get()=if(data.hasError)"Impossibile caricare la lista. Riprova." else ""
    fun bind(query:PersonalCatalogQuery):Boolean {
        if(query!=lastQuery)lastQuery?.let {positions[positionKey(it)]=data.anchor}
        val position=restoredPosition ?: if(query==lastQuery)data.anchor else positions[positionKey(query)] ?: 0
        val reset=data.bind(query to repository.version.value,position) {offset->
            repository.catalog(query.copy(offset=offset,limit=data.blockSize)).let {it.rows to it.total}
        }
        if(reset){restoredPosition=null;lastQuery=query}
        return reset
    }
    fun hide()=Unit
    fun restore(value:Int){restoredPosition=value.coerceAtLeast(0)}
    fun resetPosition(){positions.clear();restoredPosition=0;data.close()}
}
