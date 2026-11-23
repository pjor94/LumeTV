package it.lumetv.app.companion

import androidx.media3.common.C
import it.lumetv.app.playback.PlaybackTrackOption
import it.lumetv.app.playback.TvPlayer
import it.lumetv.core.PlaybackMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Main-thread adapter: never serializes Channel, provider errors, URLs, headers or DRM state. */
internal class CompanionPlayback(private val player:TvPlayer,private val zap:(Int)->Unit,private val navigate:(String)->Unit) {
    private val boot=UUID.randomUUID().toString()
    private var sequence=0L
    private var changing:String?=null
    private var changingUntil=0L
    private val replies=linkedMapOf<String,Pair<String,JSONObject>>()
    private val session get()="$boot:${player.snapshot.playbackRevision}"
    private fun transitioning():Boolean {
        if(changing!=session || android.os.SystemClock.elapsedRealtime()>=changingUntil)changing=null
        return changing!=null
    }
    fun state():JSONObject {
        val s=player.snapshot;val o=s.options
        fun tracks(values:List<PlaybackTrackOption>)=JSONArray().apply {values.take(24).forEach {
            put(JSONObject().put("key",it.key).put("label",CompanionProtocol.display(it.label,80)).put("selected",it.selected))
        }}
        return JSONObject().put("version",1).put("instance",boot).put("sequence",++sequence).put("session",session).put("navigationSupported",true).put("playlistTransferSupported",true).put("watchSupported",true).put("transitioning",transitioning()).put("active",s.channel!=null)
            .put("title",CompanionProtocol.display(s.title.ifBlank {s.channel?.name.orEmpty()}))
            .put("channel",CompanionProtocol.display(s.channel?.name.orEmpty()))
            .put("programme",CompanionProtocol.display(s.nowPlaying))
            .put("playing",s.playing).put("wantsToPlay",s.wantsToPlay).put("buffering",s.buffering).put("failed",s.error!=null)
            .put("mode",s.mode.name).put("seekable",s.seekable).put("position",s.position.coerceAtLeast(0))
            .put("duration",s.duration.coerceAtLeast(0)).put("nextEpisode",s.nextEpisode?.let {CompanionProtocol.display(it.title)} ?: "")
            .put("options",JSONObject().put("revision",o.revision).put("audio",tracks(o.audio)).put("subtitles",tracks(o.subtitles))
                .put("video",tracks(o.video)).put("audioAutomatic",o.audioAutomatic).put("subtitlesAutomatic",o.subtitlesAutomatic)
                .put("subtitlesDisabled",o.subtitlesDisabled)
                .put("videoAutomatic",o.videoAutomatic).put("sleepRemaining",o.sleepRemaining.coerceAtLeast(0)))
    }
    fun command(owner:String,body:JSONObject):JSONObject {
        val id=body.getString("id");require(CompanionProtocol.uuid(id))
        val commandKey=owner+":"+id
        val content=body.toString()
        replies[commandKey]?.let {(previous,result)->
            if(previous!=content)throw CompanionFailure(409,"command_conflict")
            return JSONObject(result.toString())
        }
        val navigation=body.getString("action")=="navigate"
        // Navigation belongs to this running TV, independently of the current player title.
        if(if(navigation)body.getString("session").substringBefore(':')!=boot else body.getString("session")!=session)
            throw CompanionFailure(409,"playback_changed")
        if(!navigation && transitioning() && body.getString("action")!="stop")throw CompanionFailure(409,"playback_changing")
        val args=body.getJSONObject("args");val s=player.snapshot
        when(body.getString("action")) {
            "navigate" -> {val key=args.getString("key");require(key in CompanionProtocol.NAVIGATION_KEYS);navigate(key)}
            "play" -> {if(s.channel==null)throw CompanionFailure(409,"no_playback");if(!s.wantsToPlay)player.resumePlayback()}
            "pause" -> {if(s.wantsToPlay)player.togglePause()}
            "stop" -> {changing=null;player.stop()}
            "channelNext","channelPrevious" -> {
                if(s.channel==null || s.mode!=PlaybackMode.LIVE)throw CompanionFailure(409,"not_live")
                changing=session;changingUntil=android.os.SystemClock.elapsedRealtime()+4000
                zap(if(body.getString("action")=="channelNext")1 else -1)
            }
            "seekBy" -> {
                val delta=args.getLong("delta");require(delta in setOf(-10_000L,30_000L))
                if(!s.seekable)throw CompanionFailure(409,"not_seekable")
                player.seekBy(delta)
            }
            "seek" -> {
                val position=args.getLong("position");require(position>=0 && position<=s.duration && s.duration>0)
                if(!s.seekable)throw CompanionFailure(409,"not_seekable")
                player.seek(position)
            }
            "live" -> {if(s.mode!=PlaybackMode.ARCHIVE)throw CompanionFailure(409,"not_archive");player.returnToLive()}
            "nextEpisode" -> {
                val next=s.nextEpisode ?: throw CompanionFailure(409,"no_next_episode")
                val current=s.channel?.id ?: throw CompanionFailure(409,"no_playback")
                changing=session;changingUntil=android.os.SystemClock.elapsedRealtime()+4000
                player.playFollowingEpisode(current,next.id)
            }
            "sleep" -> {if(s.channel==null)throw CompanionFailure(409,"no_playback");val minutes=args.getInt("minutes");require(minutes==0 || minutes in it.lumetv.app.playback.SleepTimer.MINUTES);player.setSleepTimer(minutes)}
            "track" -> {
                val type=args.getInt("type");require(type in setOf(C.TRACK_TYPE_AUDIO,C.TRACK_TYPE_VIDEO,C.TRACK_TYPE_TEXT))
                if(args.getLong("revision")!=s.options.revision)throw CompanionFailure(409,"tracks_changed")
                val key=args.getString("key")
                if(key=="auto") {
                    player.automaticTrack(type)
                    if(type==C.TRACK_TYPE_TEXT)player.setPlaybackPreferences(s.options.preferences.copy(subtitleMode=it.lumetv.app.playback.SubtitleMode.SYSTEM))
                }
                else {
                    require(key.matches(Regex("[0-9]{1,3}:[0-9]{1,3}")))
                    if(!player.chooseTrack(s.options.revision,type,key))throw CompanionFailure(409,"tracks_changed")
                }
            }
            "subtitlesOff" -> {
                if(args.getLong("revision")!=s.options.revision)throw CompanionFailure(409,"tracks_changed")
                player.setPlaybackPreferences(s.options.preferences.copy(subtitleMode=it.lumetv.app.playback.SubtitleMode.OFF))
            }
            else -> throw CompanionFailure(400,"unknown_command")
        }
        val result=state()
        replies[commandKey]=content to result
        while(replies.size>128)replies.remove(replies.keys.first())
        return JSONObject(result.toString())
    }
}
