package it.lumetv.app.data

import android.content.Context
import it.lumetv.app.playback.PlaybackPreferenceStore
import it.lumetv.app.playback.SubtitleMode
import it.lumetv.core.MediaKind
import org.json.JSONArray
import org.json.JSONObject

internal object BackupPersonalData {
    val builtinSources=setOf(it.lumetv.core.BUILTIN_TV_SOURCE,it.lumetv.core.BUILTIN_RADIO_SOURCE)
    val preferenceKeys=setOf("data_saver","audio_language","subtitle_mode","subtitle_language","zap_favorites_only")
    val personalKeys=setOf("favorites","video_saved_series","channel_layout")
    fun preferences(context:Context):JSONObject {
        val prefs=context.getSharedPreferences("lume",Context.MODE_PRIVATE)
        val value=PlaybackPreferenceStore(context).read()
        return JSONObject().put("data_saver",value.quality==it.lumetv.app.playback.PlaybackQuality.DATA_SAVER)
            .put("audio_language",value.audioLanguage).put("subtitle_mode",value.subtitleMode.name)
            .put("subtitle_language",value.subtitleLanguage).put("zap_favorites_only",prefs.getBoolean("zap_favorites_only",false))
    }
    fun validatePreferences(value:JSONObject) {
        require(value.keys().asSequence().toSet()==preferenceKeys)
        require(value.get("data_saver") is Boolean && value.get("zap_favorites_only") is Boolean)
        for(key in listOf("audio_language","subtitle_language")) {
            val language=value.getString(key)
            require(language.isEmpty() || PlaybackPreferenceStore.language(language)==language)
        }
        SubtitleMode.valueOf(value.getString("subtitle_mode"))
    }
    fun capture(context:Context):JSONObject {
        val prefs=context.getSharedPreferences("lume",Context.MODE_PRIVATE)
        val layout=ChannelPersonalization.get(context).backupLayout()
        return JSONObject().put("favorites",JSONArray(prefs.getStringSet("favorites",emptySet()).orEmpty().toList()))
            .put("series",JSONArray(prefs.getStringSet("video_saved_series",emptySet()).orEmpty().toList()))
            .put("layout",layoutJson(layout)).put("owners",JSONObject())
            .put("metadata",if(context.getDatabasePath("video-metadata.sqlite").isFile)VideoMetadataStore(context).use {it.manualChoices()} else JSONArray())
    }
    fun references(value:JSONObject):Set<String> = buildSet {
        addAll(strings(value.getJSONArray("favorites")));addAll(strings(value.getJSONObject("layout").getJSONArray("order")))
        val hidden=value.getJSONObject("layout").getJSONArray("hidden")
        require(hidden.length()<=BackupIO.MAX_ITEMS)
        for(i in 0 until hidden.length())add(hidden.getJSONObject(i).getString("id"))
        val metadata=value.optJSONArray("metadata") ?: JSONArray();require(metadata.length()<=5000)
        for(i in 0 until metadata.length())metadata.getJSONObject(i).getString("key").takeIf {it.startsWith("i:")}?.let {add(it.substring(2))}
        require(size<=BackupIO.MAX_ITEMS && all(BackupIO::validChannel))
    }
    fun validate(value:JSONObject,sources:Set<String>) {
        VideoMetadataStore.validateChoices(value.optJSONArray("metadata") ?: JSONArray(),sources)
        val ids=references(value);val owners=value.getJSONObject("owners")
        require(owners.length()<=BackupIO.MAX_ITEMS && owners.keys().asSequence().toSet()==ids)
        ids.forEach {require(owners.getString(it) in builtinSources || owners.getString(it) in sources)}
        val choices=value.optJSONArray("metadata") ?: JSONArray()
        for(i in 0 until choices.length()) {
            val row=choices.getJSONObject(i);val key=row.getString("key")
            if(key.startsWith("i:"))require(owners.getString(key.substring(2))==row.getString("source"))
        }
        val layout=value.getJSONObject("layout");require(layout.getInt("version")==1)
        val hidden=layout.getJSONArray("hidden");val seen=hashSetOf<String>()
        for(i in 0 until hidden.length()) {
            val row=hidden.getJSONObject(i);val id=row.getString("id")
            require(seen.add(id) && row.getString("source")==owners.getString(id) && row.getString("name").length<=160)
            MediaKind.valueOf(row.getString("kind"))
        }
        strings(value.getJSONArray("series")).forEach {key->
            require(key.length<=1024 && key.substringBefore(':') in sources && ':' in key)
        }
    }
    fun resolveCaptured(value:JSONObject,owners:Map<String,String>,sources:Set<String>):JSONObject {
        val layout=value.getJSONObject("layout")
        val order=strings(layout.getJSONArray("order")).filter {it in owners}
        val hidden=layout.getJSONArray("hidden")
        val kept=JSONArray()
        for(i in 0 until hidden.length())hidden.getJSONObject(i).takeIf {owners[it.getString("id")]==it.getString("source")}?.let(kept::put)
        val resolved=JSONObject().put("favorites",JSONArray(strings(value.getJSONArray("favorites")).filter {it in owners}))
            .put("series",JSONArray(strings(value.getJSONArray("series")).filter {it.substringBefore(':') in sources}))
            .put("owners",JSONObject(owners))
            .put("layout",JSONObject().put("version",1).put("order",JSONArray(order)).put("hidden",kept).put("owners",JSONObject()))
        resolved.put("metadata",JSONArray().apply {
            val choices=value.optJSONArray("metadata") ?: JSONArray()
            for(i in 0 until choices.length()) {
                val row=choices.getJSONObject(i);val key=row.getString("key");val source=row.getString("source")
                if(source in sources && (key.startsWith("s:") || owners[key.removePrefix("i:")]==source))put(row)
            }
        })
        val used=references(resolved)
        return resolved.put("owners",JSONObject(owners.filterKeys {it in used}))
    }
    fun strings(value:JSONArray):List<String> {
        require(value.length()<=BackupIO.MAX_ITEMS)
        return (0 until value.length()).map {value.getString(it)}.also {require(it.size==it.distinct().size)}
    }
    fun layoutJson(value:ChannelLayout):JSONObject {
        val owners=JSONObject()
        val ordered=value.order.toHashSet()
        value.owners.entries.filter {it.key in ordered}.groupBy({it.value},{it.key}).forEach {(source,ids)->owners.put(source,JSONArray(ids))}
        return JSONObject().put("version",1).put("order",JSONArray(value.order)).put("owners",owners)
            .put("hidden",JSONArray().apply {value.hidden.forEach {put(JSONObject().put("id",it.id).put("source",it.source).put("name",it.name).put("kind",it.kind.name))}})
    }
}
