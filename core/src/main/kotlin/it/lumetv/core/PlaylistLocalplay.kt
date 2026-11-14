package it.lumetv.core

import java.util.Locale

/** A closed set of provider routes, never executable commands or relative network addresses. */
enum class PlaylistLocalplay(val resolverKey:String) {
    NOVE("Nove"), REAL_TIME("RealTime"), DMAX("DMAX"), GIALLO("Giallo"),
    MOTOR_TREND("Turbo"), HGTV("HGTV"), FOOD_NETWORK("FoodNetwork"),
    K2("K2"), FRISBEE("Frisbee"), DISCOVERY("Discovery");
    val address get()="localplay=$resolverKey"
    companion object {
        private val command=Regex("^localplay\\s*=",RegexOption.IGNORE_CASE)
        fun isCommand(value:String)=command.containsMatchIn(value)
        fun read(value:String):PlaylistLocalplay? {
            if(!isCommand(value))return null
            return when(value.substringAfter('=').trim().lowercase(Locale.ROOT)) {
                "nove"->NOVE;"realtime","real time"->REAL_TIME;"dmax"->DMAX;"giallo"->GIALLO
                "turbo","motortrend","motor trend"->MOTOR_TREND;"hgtv","hgtv italia"->HGTV
                "foodnetwork","food network"->FOOD_NETWORK;"k2"->K2;"frisbee"->FRISBEE;"discovery"->DISCOVERY
                else->null
            }
        }
    }
}

object PlaylistManifestTypes {
    fun mime(value:String?)=when(value?.trim()?.lowercase(Locale.ROOT)) {
        "mpd","dash"->"application/dash+xml"
        "hls","m3u8"->"application/x-mpegURL"
        else->null
    }
}
