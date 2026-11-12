package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.MediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalPlaylistRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var time = 1_000_000L
    private val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" tvg-chno=\"5\",Uno\nhttps://example.com/live.m3u8?token=private\n"
    private var answer: suspend (String, String?, String?) -> PlaylistDownload = { url, _, _ -> PlaylistDownload(text, url, "v1") }
    private fun repository() = PersonalPlaylistRepository(context, PlaylistFetcher { url, tag, modified -> answer(url, tag, modified) }, { time })

    @Before fun reset() {
        File(context.filesDir, "personal-playlists").deleteRecursively()
        context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `private playback details are absent from catalog and IDs survive a fresh repository`() = runTest {
        val repo = repository()
        val draft = repo.prepareUrl("https://example.com/list?password=secret")
        val id = repo.save("Casa", draft)
        val channel = repo.local().single()
        assertEquals(id, channel.sourceId); assertTrue(channel.isPersonal)
        assertEquals("", channel.source); assertEquals("", channel.epgId); assertTrue(channel.headers.isEmpty())
        assertFalse(channel.toString().contains("private")); assertFalse(repo.state.value.toString().contains("secret"))
        val restored = repository()
        assertEquals(channel, restored.local().single())
        assertEquals("https://example.com/list?password=secret", restored.address(id))
    }

    @Test fun `a new URL and title preserve identity and disabled sources restore on enabling`() = runTest {
        val repo = repository()
        val id = repo.save("Prima", repo.prepareFile(text.toByteArray()))
        val original = repo.local().single().id
        val changed = text.replace("Uno", "Nuovo nome").replace("token=private", "token=renewed")
        repo.save("Dopo", repo.prepareFile(changed.toByteArray()), id)
        assertEquals(original, repo.local().single().id)
        repo.setEnabled(id, false); assertTrue(repo.local().isEmpty())
        val restored = repository(); assertTrue(restored.local().isEmpty())
        restored.setEnabled(id, true); assertEquals(original, restored.local().single().id)
    }

    @Test fun `a late response cannot resurrect a deleted source or its files`() = runTest {
        val repo = repository()
        val id = repo.save("Casa", repo.prepareUrl("https://example.com/list"))
        time += 31_000
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<PlaylistDownload>()
        answer = { _, _, _ -> started.complete(Unit); response.await() }
        val updating = async { repo.refresh(id) }
        started.await(); repo.delete(id)
        response.complete(PlaylistDownload(text.replace("Uno", "Risposta tardiva"), "https://example.com/list"))
        assertFalse(updating.await()); assertTrue(repo.local().isEmpty())
        assertFalse(File(context.filesDir, "personal-playlists/$id.json").exists())
        assertTrue(repository().local().isEmpty())
    }

    @Test fun `a configuration change invalidates a refresh that is already downloading`() = runTest {
        val repo = repository()
        val id = repo.save("Casa", repo.prepareUrl("https://example.com/list"))
        time += 31_000
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<PlaylistDownload>()
        answer = { _, _, _ -> started.complete(Unit); response.await() }
        val updating = async { repo.refresh(id) }
        started.await(); repo.setEnabled(id, false)
        response.complete(PlaylistDownload(text.replace("Uno", "Tardivo"), "https://example.com/list"))
        assertFalse(updating.await()); assertFalse(repo.state.value.single().enabled)
        repo.setEnabled(id, true); assertEquals("Uno", repo.local().single().name)
    }

    @Test fun `a 304 preserves channels and content timestamp while updating the check time`() = runTest {
        val repo = repository()
        val id = repo.save("Casa", repo.prepareUrl("https://example.com/list"))
        val original = repo.local(); val updated = repo.state.value.single().updated
        time += 31_000
        answer = { url, tag, _ -> assertEquals("v1", tag); PlaylistDownload(null, url) }
        assertTrue(repo.refresh(id))
        assertEquals(original, repo.local()); assertEquals(updated, repo.state.value.single().updated)
        assertEquals(time, repo.state.value.single().checked)
    }

    @Test fun `truncated refresh retains the previous catalog and reports a bounded safe error`() = runTest {
        val repo = repository()
        val id = repo.save("Casa", repo.prepareUrl("https://example.com/list"))
        val old = repo.local()
        time += 31_000
        answer = { url, _, _ -> PlaylistDownload("#EXTM3U\n#EXTINF:-1,Incomplete", url) }
        try { repo.refresh(id); fail("Truncated list accepted") } catch (_: it.lumetv.core.PlaylistFormatException) { }
        assertEquals(old, repo.local()); assertTrue(repo.state.value.single().error.isNotEmpty())
        assertFalse(repo.state.value.single().refreshing)
        assertEquals(old, repository().local())
    }

    @Test fun `sources are independent and deletion clears only their favorites and recents`() = runTest {
        val repo = repository()
        val a = repo.save("A", repo.prepareFile(text.toByteArray()))
        val b = repo.save("B", repo.prepareFile(text.toByteArray(), MediaKind.RADIO))
        val ca = repo.local().single { it.sourceId == a }; val cb = repo.local().single { it.sourceId == b }
        assertNotEquals(ca.id, cb.id)
        val prefs = context.getSharedPreferences("lume", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorites", setOf(ca.id, cb.id, "official")).putString("recent_channels", "${ca.id},${cb.id},official")
            .putString("last_channel", ca.id).putString("last_tv_channel", "official").commit()
        repo.delete(a)
        assertEquals(setOf(cb.id, "official"), prefs.getStringSet("favorites", emptySet()))
        assertEquals("${cb.id},official", prefs.getString("recent_channels", "")); assertFalse(prefs.contains("last_channel"))
        assertEquals("official", prefs.getString("last_tv_channel", null))
    }

    @Test fun `an unreadable source is isolated and an unreadable index is never overwritten`() = runTest {
        val repo = repository()
        val a = repo.save("A", repo.prepareFile(text.toByteArray()))
        val b = repo.save("B", repo.prepareFile(text.toByteArray()))
        File(context.filesDir, "personal-playlists/$a.json").writeText("broken")
        val restored = repository()
        assertEquals(b, restored.local().single().sourceId)
        assertEquals(2, restored.state.value.size)
        val index = File(context.filesDir, "personal-playlists/index.json")
        index.writeText("broken index")
        val blocked = repository(); assertTrue(blocked.local().isEmpty())
        assertNotNull(blocked.storageError)
        try { blocked.save("C", blocked.prepareFile(text.toByteArray())); fail("Corrupt index overwritten") } catch (_: java.io.IOException) { }
        assertEquals("broken index", index.readText())
    }
}
