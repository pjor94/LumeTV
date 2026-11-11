package it.lumetv.app.playback

import android.view.InputDevice
import android.view.KeyEvent
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    private val tv = Channel("tv", "TV", 1, "tv", "", "", "https://example.com/live", "direct", "")
    private val radio = tv.copy(id = "radio", kind = MediaKind.RADIO)

    @Test fun `TV interface never continues outside the app including radio`() {
        assertFalse(PlaybackPolicy.canContinueInBackground(false, tv, false))
        assertFalse(PlaybackPolicy.canContinueInBackground(false, radio, true))
    }
    @Test fun `phone continues radio or explicitly selected TV audio only`() {
        assertTrue(PlaybackPolicy.canContinueInBackground(true, radio, true))
        assertTrue(PlaybackPolicy.canContinueInBackground(true, tv, true))
        assertFalse(PlaybackPolicy.canContinueInBackground(true, tv, false))
        assertFalse(PlaybackPolicy.canContinueInBackground(true, null, true))
    }

    @Test fun `untimed stop from clearing the previous TV item cannot cancel its replacement`() {
        assertTrue(PlaybackPolicy.ignoreSyntheticStop(false, true, KeyEvent.KEYCODE_MEDIA_STOP, 0, 0, 0))
    }

    @Test fun `real remote stops still work during a channel change`() {
        assertFalse(PlaybackPolicy.ignoreSyntheticStop(false, true, KeyEvent.KEYCODE_MEDIA_STOP, 123, 122, InputDevice.SOURCE_KEYBOARD))
        assertFalse(PlaybackPolicy.ignoreSyntheticStop(false, true, KeyEvent.KEYCODE_MEDIA_STOP, 123, 122, 0))
        assertFalse(PlaybackPolicy.ignoreSyntheticStop(false, true, KeyEvent.KEYCODE_MEDIA_STOP, 0, 0, InputDevice.SOURCE_KEYBOARD))
    }

    @Test fun `synthetic stops remain usable after the change and on phones`() {
        assertFalse(PlaybackPolicy.ignoreSyntheticStop(false, false, KeyEvent.KEYCODE_MEDIA_STOP, 0, 0, 0))
        assertFalse(PlaybackPolicy.ignoreSyntheticStop(true, true, KeyEvent.KEYCODE_MEDIA_STOP, 0, 0, 0))
    }

    @Test fun `play pause and channel keys are never suppressed`() {
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            assertFalse(PlaybackPolicy.ignoreSyntheticStop(false, true, key, 0, 0, 0))
    }

    @Test fun `a stop with either timestamp is treated as a user action`() {
        assertFalse(PlaybackPolicy.ignoreSyntheticStop(false, true, KeyEvent.KEYCODE_MEDIA_STOP, 1, 0, 0))
        assertFalse(PlaybackPolicy.ignoreSyntheticStop(false, true, KeyEvent.KEYCODE_MEDIA_STOP, 0, 1, 0))
    }
}
