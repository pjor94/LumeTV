package it.lumetv.app.data

import android.content.Context
import android.content.SharedPreferences
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class HiddenChannel(val id: String, val source: String, val name: String, val kind: MediaKind)
internal data class ChannelLayout(val order: List<String> = emptyList(), val hidden: List<HiddenChannel> = emptyList(),val owners:Map<String,String> = emptyMap()) {
    val hiddenIds: Set<String> = hidden.mapTo(hashSetOf()) {it.id}
    val ranks: Map<String,Int> = order.withIndex().associate {it.value to it.index}
}

/** The legacy favorite set remains compatible with Video and integrations. Layout is versioned separately. */
class ChannelPersonalization private constructor(private val app: Context) {
    companion object {
        private const val KEY = "channel_layout"
        private val instances = java.util.WeakHashMap<Context, ChannelPersonalization>()
        fun get(context: Context): ChannelPersonalization = synchronized(instances) {
            val app = context.applicationContext
            instances.getOrPut(app) { ChannelPersonalization(app) }
        }
    }
    private val prefs = app.getSharedPreferences("lume", Context.MODE_PRIVATE)
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private var lastText: String? = null
    private var cached = ChannelLayout()
    var storageError: String? = null; private set
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY || key == "favorites") changes.update {it+1}
    }
    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    @Synchronized private fun layout(): ChannelLayout {
        val text = prefs.getString(KEY, "").orEmpty()
        if (text == lastText) return cached
        cached = try {
            if (text.isEmpty()) ChannelLayout() else {
                require(text.length <= 8 * 1024 * 1024)
                val root = JSONObject(text); require(root.getInt("version") == 1)
                val order = root.getJSONArray("order")
                val hidden = root.getJSONArray("hidden")
                val owners=buildMap<String,String> {root.optJSONObject("owners")?.let {sources->sources.keys().forEach {source->
                    val ids=sources.getJSONArray(source);for(i in 0 until ids.length())put(ids.getString(i),source)
                }}}
                ChannelLayout((0 until order.length()).map { order.getString(it) }.filter(String::isNotBlank).distinct(),
                    (0 until hidden.length()).map { index -> hidden.getJSONObject(index).let {
                        HiddenChannel(it.getString("id"), it.getString("source"), it.getString("name").take(160), MediaKind.valueOf(it.getString("kind")))
                    } }.distinctBy { it.id },owners)
            }.also { storageError = null }
        } catch (_: Exception) {
            storageError = "Le personalizzazioni salvate non sono leggibili. I preferiti restano disponibili."
            ChannelLayout()
        }
        lastText = text
        return cached
    }
    val order: List<String> get() = layout().order
    val hidden: List<HiddenChannel> get() = layout().hidden
    val hiddenIds: Set<String> get() = layout().hiddenIds
    internal fun backupLayout():ChannelLayout=layout().also {storageError?.let {message->throw IOException(message)}}
    internal fun sourceIds(source:String):Set<String> = layout().let {value->value.owners.filterValues {it==source}.keys+value.hidden.filter {it.source==source}.map {it.id}}
    fun isHidden(channel: Channel): Boolean = !channel.isVod && channel.id in hiddenIds
    fun visible(channels: List<Channel>): List<Channel> {
        val ids = hiddenIds
        return channels.filterNot { !it.isVod && it.id in ids }
    }
    fun ordered(channels: List<Channel>): List<Channel> {
        val ranks = layout().ranks
        return if (ranks.isEmpty()) channels else channels.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
    }
    private fun save(value: ChannelLayout) {
        if (storageError != null) throw IOException(storageError)
        val owners=JSONObject().apply {value.owners.entries.filter {it.key in value.ranks}.groupBy({it.value},{it.key}).forEach {(source,ids)->put(source,JSONArray(ids))}}
        val text = JSONObject().put("version", 1).put("order", JSONArray(value.order)).put("owners",owners).put("hidden", JSONArray().apply {
            value.hidden.forEach { put(JSONObject().put("id", it.id).put("source", it.source).put("name", it.name).put("kind", it.kind.name)) }
        }).toString()
        if (text.length > 8 * 1024 * 1024) throw IOException("Le personalizzazioni occupano troppo spazio. Riduci i canali nascosti e riprova.")
        prefs.edit().putString(KEY, text).apply()
        IntegrationStore.changed(app)
    }
    fun hide(channel: Channel) = synchronized(IntegrationStore.cacheLock) {
        require(!channel.isVod)
        val old = layout()
        save(old.copy(hidden = old.hidden.filterNot { it.id == channel.id } + HiddenChannel(channel.id, channel.sourceId, channel.name.take(160), channel.kind),
            owners=if(channel.id in old.ranks)old.owners+(channel.id to channel.sourceId) else old.owners))
    }
    fun restore(id: String) = synchronized(IntegrationStore.cacheLock) {
        val old = layout(); save(old.copy(hidden = old.hidden.filterNot { it.id == id }))
    }
    /** Replace only the active subset's slots, retaining hidden, disabled and other-kind positions. */
    fun reorder(ids: List<String>, baseline: List<String>, expectedRevision: Long,owners:Map<String,String> = emptyMap()): Boolean = synchronized(IntegrationStore.cacheLock) {
        if (revision.value != expectedRevision || ids.size != ids.distinct().size) return@synchronized false
        val old = layout()
        val selected = ids.toSet()
        val merged = (old.order + baseline).distinct()
        if (!merged.containsAll(ids)) return@synchronized false
        val iterator = ids.iterator()
        save(old.copy(order = merged.map { if (it in selected) iterator.next() else it },owners=old.owners+owners))
        true
    }
    fun favoriteChanged(channel: Channel, added: Boolean) = synchronized(IntegrationStore.cacheLock) {
        if (channel.isVod) return@synchronized
        val old = layout()
        if (storageError != null || old.order.isEmpty()) return@synchronized
        save(old.copy(order = if (added) old.order.filterNot { it == channel.id } + channel.id else old.order - channel.id,
            owners=if(added)old.owners+(channel.id to channel.sourceId) else old.owners-channel.id))
    }
    internal fun removeSource(source: String, ids: Set<String>) = synchronized(IntegrationStore.cacheLock) {
        val old = layout()
        val removed=ids+sourceIds(source)
        if (storageError == null) save(old.copy(order = old.order - removed, hidden = old.hidden.filterNot { it.source == source || it.id in removed },owners=old.owners-removed))
    }
}
