package it.lumetv.app.playback

import it.lumetv.core.PlaybackMode

/** Durations are goals; encoded samples must also fit in the device's memory budget. */
internal data class PlaybackBufferProfile(val minimumMs:Long,val maximumMs:Long,val startMs:Long,val resumeMs:Long,val bytes:Int)

internal object PlaybackBufferPolicy {
    private const val MIB=1024*1024
    fun budget(memoryClass:Int,lowRam:Boolean):Int = if(lowRam)16*MIB else (memoryClass/4).coerceIn(24,64)*MIB
    fun profile(mode:PlaybackMode,budget:Int):PlaybackBufferProfile = when(mode) {
        PlaybackMode.LIVE -> PlaybackBufferProfile(12_000,40_000,1_000,2_000,minOf(budget,24*MIB))
        else -> PlaybackBufferProfile(30_000,120_000,3_000,6_000,budget)
    }
    fun loading(wasLoading:Boolean,bufferedMs:Long,allocated:Int,profile:PlaybackBufferProfile,speed:Float):Boolean {
        val minimum=(profile.minimumMs*speed.coerceAtLeast(1f)).toLong().coerceAtMost(profile.maximumMs)
        return when {
            allocated>=profile.bytes || bufferedMs>=profile.maximumMs -> false
            bufferedMs<minimum -> true
            else -> wasLoading
        }
    }
    fun ready(bufferedMs:Long,allocated:Int,profile:PlaybackBufferProfile,speed:Float,rebuffering:Boolean):Boolean {
        val playable=(bufferedMs/speed.coerceAtLeast(.1f)).toLong()
        // At unusually high bitrates, a byte-limited buffer must still be able to start draining.
        return playable >= (if(rebuffering)profile.resumeMs else profile.startMs) ||
            allocated>=profile.bytes && playable>=1_000
    }
}
