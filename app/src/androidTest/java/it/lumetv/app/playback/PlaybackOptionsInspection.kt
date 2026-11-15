package it.lumetv.app.playback

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import it.lumetv.app.MobileActivity
import it.lumetv.app.TvActivity
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PersonalPlaybackStore
import it.lumetv.app.ui.PlaybackOptionsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Local multi-track fixtures on disposable emulators. No provider streams or physical devices. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlaybackOptionsInspection {
    fun run(runner: Instrumentation, args: Bundle): JSONObject {
        val root = requireNotNull(args.getString("fixtureRoot"))
        require(Uri.parse(root).host == "10.0.2.2" && Uri.parse(root).scheme == "http")
        val mobile = args.getString("mode") == "phone"
        val checks = mutableListOf<String>()
        fun <T> main(block: () -> T): T { val result = AtomicReference<Result<T>>(); runner.runOnMainSync { result.set(runCatching(block)) }; return result.get().getOrThrow() }
        fun <T> io(block: suspend () -> T) = runBlocking(Dispatchers.IO) { block() }
        fun record(label: String) { checks += label; File(runner.targetContext.cacheDir, "playback-options-progress.json").writeText(JSONArray(checks).toString()) }
        fun await(label: String, test: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < until) { if (main(test)) { record(label); return }; Thread.sleep(100) }
            error(label)
        }
        val repo = PersonalPlaylistRepository.get(runner.targetContext)
        io { repo.local(); repo.state.value.toList().forEach { repo.delete(it.id) } }
        val input = File(runner.targetContext.cacheDir, "playback-options.m3u").apply { writeText("""
            #EXTM3U
            #EXTINF:-1 type="live",Diretta di prova
            $root/master.m3u8
            #EXTINF:-1 type="live",Seconda diretta
            $root/master.m3u8?second=1
            #EXTINF:-1 radio="true",Radio di prova
            $root/audio-it.m3u8
            #EXTINF:-1 type="movie",Video di prova
            $root/master.m3u8?vod=1
        """.trimIndent()) }
        val source = io { repo.prepareFile(input, allowHttp = true).use { repo.save("Riproduzione", it) } }
        val channels = io { repo.catalog(it.lumetv.app.data.PersonalCatalogQuery(source = source, content = null)).rows }
        val live = channels.first { it.name == "Diretta di prova" }
        val second = channels.first { it.name == "Seconda diretta" }
        val radio = channels.first { it.isRadio }
        val vod = channels.first { it.isVod }
        val activity = runner.startActivitySync(Intent(runner.targetContext, if (mobile) MobileActivity::class.java else TvActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("lumetv://channel/${live.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        fun field(name: String) = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        val player = main { field(if (mobile) "player" else "tvPlayer") as TvPlayer }
        val panel = main { field("playbackOptions") as PlaybackOptionsPanel }
        val content = main { activity.findViewById<View>(android.R.id.content) }
        fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun click(label: String) { await("action: $label") {
            all(panel).filter { it.isShown }.lastOrNull { it.isClickable && it.isEnabled && (it is TextView && it.text.toString() == label || it.contentDescription?.toString() == label || it.contentDescription?.toString()?.startsWith("$label:") == true) }
                ?.let { if (!mobile) it.requestFocus(); it.performClick() } == true
        } }
        fun capture(name: String) {
            runner.waitForIdleSync(); Thread.sleep(if (name == "05-landscape") 900 else 200)
            runner.uiAutomation.takeScreenshot()?.let { bitmap -> File(runner.targetContext.cacheDir, "playback-options").apply { mkdirs() }
                .resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        }
        fun back() = main { activity.onBackPressedDispatcher.onBackPressed() }
        await("the native player discovers alternate audio, video and subtitles") {
            player.snapshot.playing && player.snapshot.options.audio.size >= 2 && player.snapshot.options.video.size >= 2 && player.snapshot.options.subtitles.isNotEmpty()
        }
        if(mobile)await("opening live TV enters fullscreen automatically") {
            activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                activity.requestedOrientation==ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
                androidx.core.view.ViewCompat.getRootWindowInsets(content)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars())==false
        }
        if (mobile) main {
            if (all(content).none { it.isShown && it.contentDescription == "Opzioni riproduzione" })
                all(content).first { it.isShown && it.contentDescription == "Diretta · Tocca per mostrare o nascondere i comandi" }.performClick()
            all(content).last { it.isShown && it.contentDescription == "Opzioni riproduzione" }.performClick()
        } else runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("playback options open through the real player controls") { panel.isShowing }
        capture("01-main")
        click("Lingua audio preferita"); click("Inglese")
        await("the preferred audio language is persisted and applied") { player.snapshot.options.preferences.audioLanguage == "en" && player.snapshot.options.audio.any { it.selected && (it.label.contains("English") || it.label.contains("Inglese")) } }
        click("Sottotitoli preferiti"); click("Italiano")
        await("preferred subtitles select an available text track") { player.snapshot.options.subtitles.any { it.selected } }
        capture("02-preferences")
        val previous = main { player.snapshot.options }
        main { check(player.chooseTrack(previous.revision, C.TRACK_TYPE_AUDIO, previous.audio.first { !it.selected }.key)) }
        await("a current-content audio choice is independent from the saved language") { !player.snapshot.options.audioAutomatic && player.snapshot.options.preferences.audioLanguage == "en" }
        click("Qualità video"); capture("03-quality"); click("Risparmio dati")
        await("quality changes preserve the manual audio choice") { !player.snapshot.options.audioAutomatic && player.snapshot.options.preferences.quality == PlaybackQuality.DATA_SAVER }
        main {
            val value = player.snapshot.options
            check(player.chooseTrack(value.revision, C.TRACK_TYPE_VIDEO, value.video.first().key))
        }
        await("a specific video quality selects one current variant") { !player.snapshot.options.videoAutomatic && player.snapshot.options.video.count { it.selected } == 1 }
        click("Qualità video")
        main { player.select(second, mobile) }
        await("changing content refreshes an open selection page and keeps preferences") {
            panel.isShowing && player.snapshot.channel?.id == second.id && player.snapshot.playing && player.snapshot.options.audioAutomatic &&
                player.snapshot.options.preferences.audioLanguage == "en" && all(panel).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Opzioni riproduzione" }
        }
        main { check(!player.chooseTrack(previous.revision, C.TRACK_TYPE_AUDIO, previous.audio.first().key)) }; record("stale choices cannot affect the new stream")
        click("Timer di arresto"); click("15 minuti")
        await("timer is active for the whole playback session") { player.snapshot.options.sleepRemaining in 1..900_000 }
        val remaining = main { player.snapshot.options.sleepRemaining }
        main { player.togglePause() }; Thread.sleep(1_100)
        await("pausing does not suspend the timer") { !player.snapshot.wantsToPlay && player.snapshot.options.sleepRemaining < remaining }
        main { player.togglePause() }
        capture("04-timer")
        if (mobile) {
            main { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("rotation keeps the active timer and panel") { panel.width > panel.height && panel.isShowing && player.snapshot.options.sleepRemaining > 0 }
            capture("05-landscape")
            main { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        } else {
            runner.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            await("D-pad focus stays inside the playback panel") { activity.currentFocus?.let { it in all(panel) } == true }
        }
        back(); await("system Back returns to the player") { !panel.isShowing }
        main { player.stop() }
        await("manual stop clears the session timer") { player.snapshot.channel == null && player.snapshot.options.sleepRemaining == 0L }
        main { player.select(vod, mobile) }
        await("VOD uses the same options and saved preferences") { player.snapshot.playing && player.snapshot.mode == it.lumetv.core.PlaybackMode.VOD && player.snapshot.options.preferences.audioLanguage == "en" }
        main { activity.javaClass.getDeclaredMethod("openPlayback").apply { isAccessible = true }.invoke(activity); player.seek(20_000) }
        await("VOD can seek before timer expiry") { player.snapshot.position >= 20_000 }
        main { player.setSleepTimer(15) }
        val service = main { TvPlayer::class.java.getDeclaredField("service").apply { isAccessible = true }.get(player) as PlaybackService }
        fun shortenTimer() = main {
            val timer = PlaybackService::class.java.getDeclaredField("sleepTimer").apply { isAccessible = true }.get(service)
            timer.javaClass.getDeclaredField("deadline").apply { isAccessible = true }.setLong(timer, SystemClock.elapsedRealtime() + 500)
        }
        shortenTimer()
        await("expiry stops VOD and preserves the resume position") { service.snapshot.channel == null && service.snapshot.options.sleepRemaining == 0L }
        val saveDeadline = SystemClock.elapsedRealtime() + 5_000
        while (io { PersonalPlaybackStore.get(runner.targetContext).read(source, it.lumetv.core.PlaybackTarget(vod.id, it.lumetv.core.PlaybackMode.VOD)).position } < 20_000) {
            check(SystemClock.elapsedRealtime() < saveDeadline); Thread.sleep(50)
        }
        record("the VOD resume position is saved on timer expiry")
        main { player.select(radio, mobile) }
        await("radio has playback options") { player.snapshot.playing && player.snapshot.channel?.isRadio == true }
        main { activity.javaClass.getDeclaredMethod("openPlayback").apply { isAccessible = true }.invoke(activity); panel.show(); player.setSleepTimer(15) }; capture("06-radio")
        check(main { service.player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode == androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED })
        record("an active radio timer keeps normal audio scheduling")
        main { player.setSleepTimer(0) }
        check(main { service.player.trackSelectionParameters.audioOffloadPreferences.audioOffloadMode == androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED })
        record("cancelling the timer restores normal offload preferences")
        main { player.setSleepTimer(15) }
        if (mobile) {
            back(); runner.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            await("the radio Activity leaves the foreground") { !activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
            await("radio continues with its timer in the background") { service.snapshot.playing && service.snapshot.options.sleepRemaining > 0 }
            shortenTimer()
            await("the service timer stops background radio") { service.snapshot.channel == null }
            record("background expiration does not require the Activity")
        } else {
            main { panel.hide() }; shortenTimer()
            await("expiry stops radio on TV") { service.snapshot.channel == null }
        }
        main { activity.finish() }
        return JSONObject().put("passed", true).put("mode", if (mobile) "phone" else "tv").put("checks", JSONArray(checks))
    }
}
