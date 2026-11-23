package it.lumetv.app.playback

import androidx.media3.exoplayer.ExoPlayer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackLoadControlTest {
    @Test fun `the mode-specific load control implements the actual player lifecycle contract`() {
        val context=RuntimeEnvironment.getApplication()
        ExoPlayer.Builder(context).setLoadControl(PlaybackLoadControl(context)).build().release()
    }
}
