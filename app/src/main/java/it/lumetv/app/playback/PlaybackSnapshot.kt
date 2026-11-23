package it.lumetv.app.playback

import android.view.KeyEvent
import it.lumetv.core.Channel
import it.lumetv.core.PlaybackMode

data class PlaybackEpisode(val source:String,val id:String,val title:String,val season:Int?=null,val episode:Int?=null)
/** UI state contains catalog identity, never temporary stream URLs or DRM credentials. */
data class PlaybackSnapshot(
    val channel: Channel? = null,
    val playing: Boolean = false,
    val wantsToPlay: Boolean = false,
    val buffering: Boolean = false,
    val ready: Boolean = false,
    val audioOnly: Boolean = false,
    val backgroundAllowed: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val nowPlaying: String = "",
    val mode:PlaybackMode=PlaybackMode.LIVE,
    val title:String="",
    val position:Long=0,
    val duration:Long=0,
    val seekable:Boolean=false,
    val ended:Boolean=false,
    val options: PlaybackOptionsState = PlaybackOptionsState(),
    val nextEpisode:PlaybackEpisode?=null,
    val nextEpisodeLoading:Boolean=false,
    val nextEpisodeError:Boolean=false,
    val playbackRevision:Long=0,
    val bufferedPosition:Long=0,
    val bufferedDuration:Long=0,
    val rebufferCount:Int=0,
    val rebufferDuration:Long=0,
    val downloadBitrate:Long=0,
    val chapters:List<VodChapter> = emptyList(),
    val preloadedBytes:Long=0,
)

data class VodChapter(val position:Long,val title:String)

internal object PlaybackPolicy {
    fun canContinueInBackground(mobile: Boolean, channel: Channel?, audioOnly: Boolean): Boolean =
        mobile && channel != null && (channel.isRadio || audioOnly)

    fun ignoreSyntheticStop(mobile: Boolean, changingChannel: Boolean, keyCode: Int,
                            eventTime: Long, downTime: Long, source: Int): Boolean =
        !mobile && changingChannel && keyCode == KeyEvent.KEYCODE_MEDIA_STOP &&
            eventTime == 0L && downTime == 0L && source == 0
}
