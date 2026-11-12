package it.lumetv.app.data

import it.lumetv.core.PlaylistFormatException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.ZoneId

data class PersonalGuideSettings(
    val enabled:Boolean=true,val allowHttp:Boolean=false,val urls:List<String> = emptyList(),
    val historyDays:Int=7,val futureHours:Int=48,val language:String="it",val xmlZone:String="UTC",
    val shiftHours:Double?=null,val archiveZone:String="",val archiveDays:Int?=null,val ongoing:Boolean=false,
    val startBufferSeconds:Int=0,val endBufferSeconds:Int=0,
) {
    fun validated():PersonalGuideSettings {
        if(urls.size>16)invalid("Puoi configurare fino a 16 guide per lista.")
        urls.forEach {raw ->
            val uri=runCatching {URI(raw)}.getOrNull()
            if(uri==null || uri.host.isNullOrBlank() || uri.scheme?.lowercase() !in setOf("http","https") || uri.rawUserInfo!=null || uri.rawFragment!=null || raw.length>8192)
                invalid("Inserisci indirizzi HTTP o HTTPS validi per la guida, senza credenziali prima del server.")
        }
        if(historyDays !in 1..7 || futureHours !in 1..48)invalid("La guida conserva fino a 7 giorni precedenti e 48 ore successive.")
        if(!Regex("[a-zA-Z_-]{2,16}").matches(language))invalid("Indica una lingua valida per la guida, per esempio it.")
        if(shiftHours?.let {!it.isFinite() || it !in -24.0..24.0}==true)invalid("La correzione della guida deve essere fra -24 e 24 ore.")
        for(zone in listOf(xmlZone,archiveZone).filter(String::isNotBlank))if(runCatching {ZoneId.of(zone)}.isFailure)invalid("Il fuso orario indicato non è valido. Usa per esempio Europe/Rome.")
        if(archiveDays!=null && archiveDays !in 1..365)invalid("La finestra dell’archivio deve essere fra 1 e 365 giorni.")
        if(startBufferSeconds !in 0..300 || endBufferSeconds !in 0..300)invalid("I margini dell’archivio devono essere fra 0 e 300 secondi.")
        return copy(urls=urls.distinct(),language=language.lowercase())
    }
    internal fun json()=JSONObject().put("enabled",enabled).put("http",allowHttp).put("urls",JSONArray(urls))
        .put("history",historyDays).put("future",futureHours).put("language",language).put("xmlZone",xmlZone).put("shift",shiftHours)
        .put("archiveZone",archiveZone).put("archiveDays",archiveDays).put("ongoing",ongoing).put("before",startBufferSeconds).put("after",endBufferSeconds)
    override fun toString()="PersonalGuideSettings(enabled=$enabled, historyDays=$historyDays)"
    companion object {
        internal fun read(v:JSONObject?):PersonalGuideSettings {
            if(v==null)return PersonalGuideSettings()
            val urls=v.optJSONArray("urls") ?: JSONArray();require(urls.length()<=16)
            return PersonalGuideSettings(v.optBoolean("enabled",true),v.optBoolean("http"),(0 until urls.length()).map {urls.getString(it)},
                v.optInt("history",7),v.optInt("future",48),v.optString("language","it"),v.optString("xmlZone","UTC"),
                if(v.isNull("shift"))null else v.getDouble("shift"),v.optString("archiveZone"),
                if(v.isNull("archiveDays"))null else v.getInt("archiveDays"),v.optBoolean("ongoing"),v.optInt("before"),v.optInt("after")).validated()
        }
        private fun invalid(message:String):Nothing=throw PlaylistFormatException(message)
    }
}
