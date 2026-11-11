package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val saved = """{"countries":[{"places":[{"name":"Roma","channels":[{"code":"saved","title":"Radio salvata","url":"https://example.com/live.mp3"}]}]}]}"""
    private fun encoded(text: String): String {
        val bytes = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try { DeflaterOutputStream(bytes, deflater).use { it.write(text.toByteArray()) } }
        finally { deflater.end() }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    private fun transport(answer: suspend () -> String) = object : Transport {
        override suspend fun get(url: String, headers: Map<String, String>) = answer()
        override suspend fun post(url: String, data: JSONObject, headers: Map<String, String>): String = error("Unexpected POST")
    }

    @Before fun reset() {
        context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "radios.json").delete()
        File(context.filesDir, "radios.json.bak").delete()
    }

    @Test fun `invalid radio refresh preserves the last usable cache and TV refresh metadata`() = runTest {
        File(context.filesDir, "radios.json").writeText(saved)
        val prefs = context.getSharedPreferences("lume", Context.MODE_PRIVATE)
        prefs.edit().putLong("catalog_updated", 1234).commit()
        val repository = RadioRepository(context, transport { encoded(saved) })
        try { repository.refresh(true); fail("Incomplete catalog accepted") } catch (_: SourceException) { }
        assertEquals("Radio salvata", repository.local().single().name)
        assertEquals(saved, File(context.filesDir, "radios.json").readText())
        assertEquals(1234L, prefs.getLong("catalog_updated", 0))
        assertEquals(0L, prefs.getLong("radios_updated", 0))
    }

    @Test fun `radio listening preserves the TV last channel used by AURA and keeps unique recents`() {
        val tv = Channel("tv-id", "TV", 1, "", "", "", "https://example.com/tv", "direct", "")
        val radio = tv.copy(id = "radio-id", kind = MediaKind.RADIO)
        val repository = ChannelRepository(context, transport { error("No network expected") })
        repository.recordPlayback(tv)
        repository.recordPlayback(radio)
        repository.recordPlayback(radio)
        val prefs = context.getSharedPreferences("lume", Context.MODE_PRIVATE)
        assertEquals("radio-id", repository.lastChannelId)
        assertEquals("tv-id", prefs.getString("last_tv_channel", null))
        assertEquals(listOf("radio-id", "tv-id"), repository.recentIds)
        assertEquals(listOf(radio, tv), repository.recent(listOf(tv, radio)))
    }
}
