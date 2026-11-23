package it.lumetv.app.ui

import android.app.Activity
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import it.lumetv.app.playback.PlaybackSnapshot
import it.lumetv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OnDemandControlsTest {
    private fun snapshot()=PlaybackSnapshot(channel=Channel("vod","Film",1,"","","","https://video.test/movie.mp4","personal","",sourceId="user_test",contentKind=PlaylistContentKind.VOD),
        playing=true,wantsToPlay=true,ready=true,mode=PlaybackMode.VOD,title="Film",position=20_000,duration=180_000,seekable=true,bufferedPosition=80_000)
    private fun controls():OnDemandControls {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        return OnDemandControls(activity).also {activity.setContentView(it);it.render(snapshot())}
    }
    @Test fun `ordinary progress updates cannot keep the panel visible or reopen it`() {
        val view=controls();val state=snapshot()
        repeat(65){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));view.render(state.copy(position=20_000+it*100L))}
        assertEquals(View.GONE,view.visibility)
        view.showControls(true);assertEquals(View.VISIBLE,view.visibility)
    }
    @Test fun `D-pad exploration never seeks until OK and Back cancels`() {
        val view=controls();val seeks=mutableListOf<Long>();view.onSeek={seeks+=it}
        view.movePreview(1);view.movePreview(1);assertTrue(seeks.isEmpty());assertTrue(view.isScrubbing)
        view.render(snapshot().copy(position=23_000));assertEquals(40_000L,view.timeline.preview)
        assertTrue(view.handleBack());assertTrue(seeks.isEmpty());assertFalse(view.isScrubbing)
        view.movePreview(1);view.timeline.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf(33_000L),seeks)
    }
    @Test fun `paused controls stay available but an explicit dismissal stays dismissed`() {
        val view=controls();val paused=snapshot().copy(playing=false,wantsToPlay=false)
        view.render(paused);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertEquals(View.VISIBLE,view.visibility)
        assertTrue(view.handleBack());view.render(paused);assertEquals(View.GONE,view.visibility)
    }
    @Test fun `buffer and content identity update without stale preview or catalog overlays`() {
        val view=controls();view.movePreview(1)
        view.render(snapshot().copy(playbackRevision=2,bufferedPosition=120_000))
        assertFalse(view.isScrubbing);assertEquals(120_000L,view.timeline.buffered)
        view.setPlayerVisible(false);view.render(snapshot());assertEquals(View.GONE,view.visibility)
    }
    @Test fun `unknown duration cannot enter a seek that cannot be confirmed`() {
        val view=controls();var previews=0;view.onScrubbingChanged={if(it)previews++}
        view.render(snapshot().copy(duration=0));view.movePreview(1)
        assertFalse(view.isScrubbing);assertEquals(0,previews)
    }
    @Test fun `accessible click confirms a staged scene once and cannot operate a disabled timeline`() {
        val view=controls();val seeks=mutableListOf<Long>();view.onSeek={seeks+=it}
        view.movePreview(1)
        assertTrue(view.timeline.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null))
        assertEquals(listOf(30_000L),seeks);assertFalse(view.isScrubbing)
        view.render(snapshot().copy(seekable=false))
        assertFalse(view.timeline.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,null))
        assertEquals(1,seeks.size)
    }
}
