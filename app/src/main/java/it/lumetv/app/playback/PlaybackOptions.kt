package it.lumetv.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.DefaultTrackNameProvider
import java.util.Locale

enum class PlaybackQuality(val label: String) { AUTO("Automatica"), DATA_SAVER("Risparmio dati") }
enum class SubtitleMode { SYSTEM, OFF, LANGUAGE }

data class PlaybackPreferences(
    val quality: PlaybackQuality = PlaybackQuality.AUTO,
    val audioLanguage: String = "",
    val subtitleMode: SubtitleMode = SubtitleMode.SYSTEM,
    val subtitleLanguage: String = "",
)

/** Contains display names and ephemeral selection keys, never media URLs or DRM configuration. */
data class PlaybackTrackOption(val key: String, val label: String, val selected: Boolean)
data class PlaybackOptionsState(
    val revision: Long = 0,
    val audio: List<PlaybackTrackOption> = emptyList(),
    val subtitles: List<PlaybackTrackOption> = emptyList(),
    val video: List<PlaybackTrackOption> = emptyList(),
    val videoFormat: String = "",
    val preferences: PlaybackPreferences = PlaybackPreferences(),
    val audioAutomatic: Boolean = true,
    val subtitlesAutomatic: Boolean = true,
    val subtitlesDisabled: Boolean = false,
    val videoAutomatic: Boolean = true,
    val sleepRemaining: Long = 0,
)

internal class PlaybackPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lume", Context.MODE_PRIVATE)
    fun read(): PlaybackPreferences = PlaybackPreferences(
        quality = if (prefs.getBoolean("data_saver", false)) PlaybackQuality.DATA_SAVER else PlaybackQuality.AUTO,
        audioLanguage = language(prefs.getString("audio_language", "").orEmpty()),
        subtitleMode = SubtitleMode.entries.firstOrNull { it.name == prefs.getString("subtitle_mode", "") } ?: SubtitleMode.SYSTEM,
        subtitleLanguage = language(prefs.getString("subtitle_language", "").orEmpty()),
    )
    fun write(value: PlaybackPreferences) {
        prefs.edit().putBoolean("data_saver", value.quality == PlaybackQuality.DATA_SAVER)
            .putString("audio_language", language(value.audioLanguage))
            .putString("subtitle_mode", value.subtitleMode.name)
            .putString("subtitle_language", language(value.subtitleLanguage)).apply()
    }
    companion object {
        fun language(value: String): String = value.takeIf { it.length <= 35 && it.matches(Regex("[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*")) }.orEmpty()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackTrackController(private val context: Context, private val player: ExoPlayer,
    private val tracks: () -> androidx.media3.common.Tracks = { player.currentTracks }) {
    private val store = PlaybackPreferenceStore(context)
    private val names = DefaultTrackNameProvider(context.resources)
    private val defaults = player.trackSelectionParameters
    private var preferences = store.read()
    private var revision = 0L
    private var groups = emptyList<TrackGroup>()
    private val overrides = mutableMapOf<Int, TrackSelectionOverride>()
    private var audioOnly = false
    private var timerActive = false

    fun reset() {
        revision++
        groups = emptyList()
        overrides.clear()
    }

    fun apply(audio: Boolean = audioOnly, timed: Boolean = timerActive) {
        audioOnly = audio
        timerActive = timed
        val saver = preferences.quality == PlaybackQuality.DATA_SAVER
        val parameters = defaults.buildUpon().clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .setMaxVideoBitrate(if (saver) 800_000 else Int.MAX_VALUE)
            .setMaxAudioBitrate(if (saver) 96_000 else Int.MAX_VALUE)
            .setForceLowestBitrate(saver)
            .setAudioOffloadPreferences(TrackSelectionParameters.AudioOffloadPreferences.Builder()
                // Offload can suspend the player's loop and release its wake lock. Keep regular
                // audio scheduling while a session timer must be delivered with the screen off.
                .setAudioOffloadMode(if (audioOnly && !timerActive) TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                    else TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build())
        if (preferences.audioLanguage.isNotEmpty()) parameters.setPreferredAudioLanguage(preferences.audioLanguage)
        when (preferences.subtitleMode) {
            SubtitleMode.SYSTEM -> Unit
            SubtitleMode.OFF -> parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            SubtitleMode.LANGUAGE -> parameters.setPreferredTextLanguage(preferences.subtitleLanguage)
        }
        overrides.forEach { (type, value) -> parameters.setTrackTypeDisabled(type, type == C.TRACK_TYPE_VIDEO && audioOnly).setOverrideForType(value) }
        player.trackSelectionParameters = parameters.build()
    }

    fun refresh() {
        val latest = store.read()
        if (latest.quality != preferences.quality) overrides.remove(C.TRACK_TYPE_VIDEO)
        if (latest.audioLanguage != preferences.audioLanguage) overrides.remove(C.TRACK_TYPE_AUDIO)
        if (latest.subtitleMode != preferences.subtitleMode || latest.subtitleLanguage != preferences.subtitleLanguage) overrides.remove(C.TRACK_TYPE_TEXT)
        preferences = latest
        apply()
    }

    fun setPreferences(value: PlaybackPreferences) { store.write(value); refresh() }

    fun automatic(type: Int) {
        if (type !in setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_TEXT)) return
        overrides.remove(type)
        apply()
    }

    fun choose(expectedRevision: Long, type: Int, key: String): Boolean {
        syncGroups()
        if (revision != expectedRevision) return false
        val parts = key.split(':')
        val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val index = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val group = tracks().groups.getOrNull(groupIndex) ?: return false
        if (group.type != type || index !in 0 until group.length || !group.isTrackSupported(index)) return false
        overrides[type] = TrackSelectionOverride(group.mediaTrackGroup, index)
        apply()
        return true
    }

    private fun syncGroups() {
        val current = tracks().groups.map { it.mediaTrackGroup }
        if (groups != current) {
            groups = current
            revision++
            overrides.entries.removeAll { it.value.mediaTrackGroup !in current }
        }
    }

    fun snapshot(remaining: Long): PlaybackOptionsState {
        syncGroups()
        fun options(type: Int): List<PlaybackTrackOption> = buildList {
            tracks().groups.forEachIndexed { groupIndex, group ->
                if (group.type == type) for (index in 0 until group.length) {
                    if (size >= 100) break
                    val format = group.getTrackFormat(index)
                    if (group.isTrackSupported(index)) add(PlaybackTrackOption("$groupIndex:$index",
                        names.getTrackName(format).take(160),
                        if (type == C.TRACK_TYPE_VIDEO) group.isTrackSelected(index) && matchesVideoFormat(player.videoFormat, format) else group.isTrackSelected(index)))
                }
            }
        }
        val format = player.videoFormat?.let { if (it.height > 0) "${it.height}p" else "" }.orEmpty()
        return PlaybackOptionsState(revision, options(C.TRACK_TYPE_AUDIO), options(C.TRACK_TYPE_TEXT), options(C.TRACK_TYPE_VIDEO),
            format, preferences, C.TRACK_TYPE_AUDIO !in overrides, C.TRACK_TYPE_TEXT !in overrides,
            player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), C.TRACK_TYPE_VIDEO !in overrides, remaining)
    }
}

/** HLS merges container information into the input format, so full Format equality is too strict. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun matchesVideoFormat(actual: androidx.media3.common.Format?, candidate: androidx.media3.common.Format): Boolean = actual != null &&
    (actual.id != null && actual.id == candidate.id || actual.width > 0 && actual.width == candidate.width &&
        actual.height == candidate.height && actual.bitrate == candidate.bitrate)

fun playbackLanguageLabel(language: String): String = if (language.isBlank()) "Come da sistema"
    else Locale.forLanguageTag(language).getDisplayName(Locale.ITALIAN).replaceFirstChar { it.titlecase(Locale.ITALIAN) }

fun sleepTimerLabel(remaining: Long): String = if (remaining <= 0) "Disattivato" else {
    val seconds = (remaining + 999) / 1000
    String.format(Locale.ROOT, "%d:%02d rimanenti", seconds / 60, seconds % 60)
}
