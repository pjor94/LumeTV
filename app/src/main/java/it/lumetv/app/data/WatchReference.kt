package it.lumetv.app.data

import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import it.lumetv.core.PlaylistContentKind
import it.lumetv.core.PlaylistEntry
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Cross-device identities contain hashes, never provider URLs, headers or local source IDs. */
internal data class WatchReference(val builtin:String="",val source:String="",val lookup:String="",val entry:String="",
    val vod:Boolean=false,val kind:MediaKind=MediaKind.TV) {
    fun json()=JSONObject().put("builtin",builtin).put("source",source).put("lookup",lookup).put("entry",entry).put("vod",vod).put("kind",kind.name)
    override fun toString()="WatchReference(personal=${builtin.isEmpty()}, vod=$vod)"
    companion object {
        fun read(value:JSONObject):WatchReference {
            val result=WatchReference(value.getString("builtin"),value.getString("source"),value.getString("lookup"),value.getString("entry"),value.getBoolean("vod"),MediaKind.valueOf(value.getString("kind")))
            if(result.builtin.isNotEmpty())require(Regex("[a-f0-9]{24}").matches(result.builtin) && !result.vod && listOf(result.source,result.lookup,result.entry).all(String::isEmpty))
            else require(listOf(result.source,result.lookup,result.entry).all {Regex("[a-f0-9]{64}").matches(it)})
            return result
        }
    }
}
internal class WatchMatchException(val code:String):java.io.IOException(code)

/** Indexed entry lookup first; full file fingerprints stream once per immutable source snapshot. */
internal class WatchCatalog(private val store:PersonalPlaylistStore) {
    private val fingerprints=linkedMapOf<String,Pair<PlaylistStored,String>>()
    fun reference(stored:PlaylistStored,channel:Channel,check:()->Unit):WatchReference {
        val entry=store.entry(stored.source.id,channel.id) ?: throw WatchMatchException("watch_missing")
        return WatchReference(source=fingerprint(stored,check),lookup=hash("${entry.kind}|${entry.url}"),entry=entryHash(entry),vod=channel.isVod,kind=channel.kind)
    }
    fun resolve(sources:List<PlaylistStored>,reference:WatchReference,hidden:(Channel)->Boolean,check:()->Unit):Channel {
        val key=Base64.getEncoder().encodeToString(reference.lookup.chunked(2).map {it.toInt(16).toByte()}.toByteArray())
        var matched:Channel?=null
        for(stored in sources) {
            check()
            SQLiteDatabase.openDatabase(store.file(stored.source.id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
                db.rawQuery("SELECT id,payload FROM records WHERE identity2=? AND active=1",arrayOf(key)).use {cursor->
                    if(!cursor.moveToFirst() || fingerprint(stored,check)!=reference.source)return@use
                    do {
                        check()
                        val entry=store.decodeEntry(JSONObject(cursor.getString(1)),stored.descriptor)
                        if(entryHash(entry)!=reference.entry)continue
                        val channel=store.displayChannel(stored,cursor.getString(0)) ?: continue
                        if(channel.kind!=reference.kind || channel.isVod!=reference.vod || hidden(channel))continue
                        if(matched!=null)throw WatchMatchException("watch_ambiguous")
                        matched=channel
                    }while(cursor.moveToNext())
                }
            }
        }
        return matched ?: throw WatchMatchException("watch_missing")
    }
    private fun fingerprint(stored:PlaylistStored,check:()->Unit={}):String {
        fingerprints[stored.source.id]?.takeIf {it.first===stored}?.let {return it.second}
        val digest=MessageDigest.getInstance("SHA-256")
        fun add(value:String){digest.update(value.toByteArray(Charsets.UTF_8));digest.update(0)}
        add("lume-watch-source-v1");add(stored.source.location.name)
        val access=JSONObject()
        stored.settings.access.filterKeys {it!=PlaylistResource.EPG && it!=PlaylistResource.LOGO}.forEach {(role,rule)->
            access.put(role.name,JSONObject().put("headers",JSONObject(rule.headers)).put("origins",JSONArray(rule.origins.sorted())))
        }
        add(canonical(access));add(stored.settings.sessionCookies.toString())
        if(stored.source.location==PlaylistLocation.URL)add(stored.address)
        else SQLiteDatabase.openDatabase(store.file(stored.source.id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            db.rawQuery("SELECT identity2 FROM records WHERE active=1 ORDER BY identity2",null).use {cursor->while(cursor.moveToNext()){check();add(cursor.getString(0))}}
        }
        val value=digest.digest().hex();fingerprints[stored.source.id]=stored to value
        while(fingerprints.size>10)fingerprints.remove(fingerprints.keys.first())
        return value
    }
    private fun entryHash(entry:PlaylistEntry)=hash(canonical(JSONObject().put("url",entry.url).put("kind",entry.kind.name)
        .put("headers",JSONObject(entry.headers)).put("properties",JSONObject(entry.properties)).put("mime",entry.mimeType).put("localplay",entry.localplay?.name)))
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
    private fun ByteArray.hex()=joinToString(""){"%02x".format(it)}
    private fun canonical(value:Any?):String=when(value) {
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))}
        null,JSONObject.NULL->"null"
        is String->JSONObject.quote(value)
        else->value.toString()
    }
}

internal suspend fun ChannelRepository.watchReference(channel:Channel):WatchReference {
    val current=find(channel.id)?.takeIf {it.sourceId==channel.sourceId && it.kind==channel.kind && it.isVod==channel.isVod}
        ?: throw WatchMatchException("watch_missing")
    return if(current.isPersonal)personal.watchReference(current) else WatchReference(builtin=current.id,kind=current.kind)
}
internal suspend fun ChannelRepository.resolveWatch(reference:WatchReference):Channel {
    return if(reference.builtin.isNotEmpty())find(reference.builtin)?.takeIf {!it.isPersonal && !it.isVod && it.kind==reference.kind}
        ?: throw WatchMatchException("watch_missing") else personal.resolveWatch(reference)
}
