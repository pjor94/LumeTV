package it.lumetv.app.playback

import it.lumetv.core.PlaybackMode
import org.junit.Assert.*
import org.junit.Test

class PlaybackBufferPolicyTest {
    private val vod=PlaybackBufferPolicy.profile(PlaybackMode.VOD,64*1024*1024)
    @Test fun `VOD retains a reserve after a short network interruption`() {
        assertTrue(PlaybackBufferPolicy.loading(false,25_000,10_000,vod,1f))
        assertTrue(PlaybackBufferPolicy.loading(true,90_000,10_000,vod,1f))
        assertFalse(PlaybackBufferPolicy.loading(true,120_000,10_000,vod,1f))
        assertFalse(PlaybackBufferPolicy.loading(false,90_000,10_000,vod,1f))
    }
    @Test fun `memory pressure stops loading even below the time goal`() {
        assertFalse(PlaybackBufferPolicy.loading(true,8_000,vod.bytes,vod,1f))
        assertEquals(16*1024*1024,PlaybackBufferPolicy.budget(256,true))
        assertEquals(64*1024*1024,PlaybackBufferPolicy.budget(1024,false))
    }
    @Test fun `a depleted VOD buffer needs a stronger restart than its initial start`() {
        assertTrue(PlaybackBufferPolicy.ready(3_000,1,vod,1f,false))
        assertFalse(PlaybackBufferPolicy.ready(3_000,1,vod,1f,true))
        assertTrue(PlaybackBufferPolicy.ready(6_000,1,vod,1f,true))
    }
    @Test fun `very high bitrate cannot deadlock at the byte budget`() {
        assertTrue(PlaybackBufferPolicy.ready(1_500,vod.bytes,vod,1f,true))
        assertFalse(PlaybackBufferPolicy.ready(300,vod.bytes,vod,1f,false))
    }
    @Test fun `fast playback uses playout time and live keeps its fast start`() {
        assertFalse(PlaybackBufferPolicy.ready(5_000,1,vod,2f,false))
        val live=PlaybackBufferPolicy.profile(PlaybackMode.LIVE,vod.bytes)
        assertTrue(PlaybackBufferPolicy.ready(1_000,1,live,1f,false))
        assertFalse(PlaybackBufferPolicy.ready(1_000,1,live,1f,true))
    }
}
