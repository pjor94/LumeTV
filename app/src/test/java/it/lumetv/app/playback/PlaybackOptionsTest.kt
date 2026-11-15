package it.lumetv.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackOptionsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun group(language: String, support: Int = C.FORMAT_HANDLED) = Tracks.Group(
        TrackGroup(language, Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language).build()),
        false, intArrayOf(support), booleanArrayOf(true))

    @Test fun `legacy data saver preference survives and audio-only preserves saved settings`() {
        context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().clear().putBoolean("data_saver", true).commit()
        val player = ExoPlayer.Builder(context).build()
        try {
            val controller = PlaybackTrackController(context, player)
            controller.apply(true)
            assertEquals(800_000, player.trackSelectionParameters.maxVideoBitrate)
            assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
            controller.apply(false)
            assertFalse(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
            assertEquals(PlaybackQuality.DATA_SAVER, controller.snapshot(0).preferences.quality)
        } finally { player.release() }
    }

    @Test fun `languages and captions persist while current-stream overrides do not`() {
        val store = PlaybackPreferenceStore(context)
        store.write(PlaybackPreferences(audioLanguage = "en", subtitleMode = SubtitleMode.LANGUAGE, subtitleLanguage = "it"))
        val player = ExoPlayer.Builder(context).build()
        var tracks = Tracks(ImmutableList.of(group("fr")))
        try {
            val controller = PlaybackTrackController(context, player) { tracks }
            controller.apply()
            val state = controller.snapshot(0)
            assertEquals(listOf("en"), player.trackSelectionParameters.preferredAudioLanguages)
            assertEquals(listOf("it"), player.trackSelectionParameters.preferredTextLanguages)
            assertTrue(controller.choose(state.revision, C.TRACK_TYPE_AUDIO, state.audio.single().key))
            assertFalse(controller.snapshot(0).audioAutomatic)
            controller.reset(); tracks = Tracks.EMPTY; controller.apply()
            assertTrue(player.trackSelectionParameters.overrides.isEmpty())
            assertEquals("en", store.read().audioLanguage)
            val recreated = PlaybackTrackController(context, player)
            recreated.apply()
            assertEquals(SubtitleMode.LANGUAGE, recreated.snapshot(0).preferences.subtitleMode)
        } finally { player.release() }
    }

    @Test fun `stale selections never choose a track from another content or new manifest`() {
        val player = ExoPlayer.Builder(context).build()
        var tracks = Tracks(ImmutableList.of(group("en")))
        try {
            val controller = PlaybackTrackController(context, player) { tracks }
            val old = controller.snapshot(0)
            tracks = Tracks(ImmutableList.of(group("it")))
            assertFalse(controller.choose(old.revision, C.TRACK_TYPE_AUDIO, old.audio.single().key))
            val fresh = controller.snapshot(0)
            controller.reset()
            assertFalse(controller.choose(fresh.revision, C.TRACK_TYPE_AUDIO, fresh.audio.single().key))
            assertTrue(player.trackSelectionParameters.overrides.isEmpty())
        } finally { player.release() }
    }

    @Test fun `unsupported tracks and wrong resource types cannot be selected`() {
        val player = ExoPlayer.Builder(context).build()
        val tracks = Tracks(ImmutableList.of(group("it", C.FORMAT_UNSUPPORTED_TYPE), group("en")))
        try {
            val controller = PlaybackTrackController(context, player) { tracks }
            val state = controller.snapshot(0)
            assertEquals(1, state.audio.size)
            assertFalse(controller.choose(state.revision, C.TRACK_TYPE_AUDIO, "0:0"))
            assertFalse(controller.choose(state.revision, C.TRACK_TYPE_TEXT, "1:0"))
            assertFalse(controller.choose(state.revision, C.TRACK_TYPE_AUDIO, "-1:9999"))
        } finally { player.release() }
    }

    @Test fun `changing quality preserves the current audio choice and saved subtitle policy`() {
        val player = ExoPlayer.Builder(context).build()
        val tracks = Tracks(ImmutableList.of(group("it")))
        try {
            val controller = PlaybackTrackController(context, player) { tracks }
            val state = controller.snapshot(0)
            controller.choose(state.revision, C.TRACK_TYPE_AUDIO, state.audio.single().key)
            controller.setPreferences(state.preferences.copy(quality = PlaybackQuality.DATA_SAVER, subtitleMode = SubtitleMode.OFF))
            assertTrue(player.trackSelectionParameters.overrides.containsKey(tracks.groups.single().mediaTrackGroup))
            assertTrue(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
        } finally { player.release() }
    }

    @Test fun `invalid stored language values are ignored`() {
        context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().putString("audio_language", "https://private.example/token").commit()
        assertEquals("", PlaybackPreferenceStore(context).read().audioLanguage)
    }

    @Test fun `video format matching tolerates HLS container enrichment without selecting all adaptive variants`() {
        val selected = Format.Builder().setId("one").setWidth(640).setHeight(360).setAverageBitrate(300_000).build()
        val enriched = selected.buildUpon().setContainerMimeType(MimeTypes.APPLICATION_M3U8).build()
        assertTrue(matchesVideoFormat(enriched, selected))
        assertFalse(matchesVideoFormat(enriched, selected.buildUpon().setId("two").setAverageBitrate(500_000).build()))
        assertFalse(matchesVideoFormat(null, selected))
    }

    @Test fun `a session timer keeps normal audio scheduling until it is cancelled`() {
        val player = ExoPlayer.Builder(context).build()
        try {
            val controller = PlaybackTrackController(context, player)
            controller.apply(audio = true, timed = true)
            assertEquals(androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode)
            controller.setPreferences(PlaybackPreferences(audioLanguage = "it"))
            assertEquals(androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode)
            controller.apply(audio = true, timed = false)
            assertEquals(androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
                player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode)
        } finally { player.release() }
    }
}
