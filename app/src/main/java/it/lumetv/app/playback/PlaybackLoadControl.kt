package it.lumetv.app.playback

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.upstream.DefaultAllocator
import it.lumetv.core.PlaybackMode

/** One allocator for the lifetime of the player, with mode-specific loading hysteresis. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackLoadControl private constructor(
    private val allocator:DefaultAllocator,
    private val budget:Int,
    private val base:DefaultLoadControl,
):LoadControl {
    @Volatile var mode=PlaybackMode.LIVE
    private val live=DefaultLoadControl.Builder().setAllocator(allocator).setBufferDurationsMs(12_000,40_000,1_000,2_000)
        .setTargetBufferBytes(24*1024*1024).setPrioritizeTimeOverSizeThresholds(true).build()
    fun preloadReserveMs(bitrate:Int):Long=if(bitrate>0)(budget.toLong()*8_000/bitrate/2).coerceIn(8_000,30_000) else 30_000
    private var loading=false
    constructor(context:Context):this(
        DefaultAllocator(true,C.DEFAULT_BUFFER_SEGMENT_SIZE),
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let {PlaybackBufferPolicy.budget(it.memoryClass,it.isLowRamDevice)}
    )
    private constructor(allocator:DefaultAllocator,budget:Int):this(allocator,budget,
        DefaultLoadControl.Builder().setAllocator(allocator).setTargetBufferBytes(budget)
            .setBufferDurationsMs(30_000,120_000,3_000,6_000).setPrioritizeTimeOverSizeThresholds(false)
            // A fixed RAM back-buffer can consume a live stream's entire byte budget after a
            // mode change. Recently watched VOD bytes are retained by the disk cache instead.
            .setBackBuffer(0,false).build())
    override fun onPrepared(playerId:PlayerId){loading=false;base.onPrepared(playerId);live.onPrepared(playerId)}
    override fun getAllocator(playerId:PlayerId)=allocator
    override fun getBackBufferDurationUs(playerId:PlayerId)=base.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId:PlayerId)=base.retainBackBufferFromKeyframe(playerId)
    override fun onTracksSelected(parameters:LoadControl.Parameters,trackGroups:androidx.media3.exoplayer.source.TrackGroupArray,
        trackSelections:Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>){
        live.onTracksSelected(parameters,trackGroups,trackSelections);base.onTracksSelected(parameters,trackGroups,trackSelections)
    }
    override fun onStopped(playerId:PlayerId){loading=false;base.onStopped(playerId);live.onStopped(playerId)}
    override fun onReleased(playerId:PlayerId){loading=false;base.onReleased(playerId);live.onReleased(playerId)}
    override fun shouldContinueLoading(parameters:LoadControl.Parameters):Boolean {
        if(mode==PlaybackMode.LIVE)return live.shouldContinueLoading(parameters)
        loading=PlaybackBufferPolicy.loading(loading,parameters.bufferedDurationUs/1_000,allocator.totalBytesAllocated,
            PlaybackBufferPolicy.profile(mode,budget),parameters.playbackSpeed)
        return loading
    }
    override fun shouldStartPlayback(parameters:LoadControl.Parameters)=if(mode==PlaybackMode.LIVE)live.shouldStartPlayback(parameters) else PlaybackBufferPolicy.ready(
        parameters.bufferedDurationUs/1_000,allocator.totalBytesAllocated,PlaybackBufferPolicy.profile(mode,budget),
        parameters.playbackSpeed,parameters.rebuffering)
    override fun shouldContinuePreloading(playerId:PlayerId,timeline:Timeline,mediaPeriodId:MediaPeriodId,bufferedDurationUs:Long)=false
}
