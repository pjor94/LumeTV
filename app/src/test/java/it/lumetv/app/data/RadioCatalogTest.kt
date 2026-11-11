package it.lumetv.app.data

import it.lumetv.core.MediaKind
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class RadioCatalogTest {
    private fun catalog(entries: String) = """{"countries":[{"places":[{"name":"Roma","channels":[$entries]}]}]}"""
    private fun entry(code: String = "station-1", name: String = "Una radio", url: String = "http://radio.garden/api/ara/content/listen/station-1/channel.mp3") =
        """{"code":"$code","title":"$name","url":"$url","enabled":true}"""

    @Test fun `WLTV raw deflate and base64 are decoded before parsing`() {
        val text = catalog(entry())
        val output = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try { DeflaterOutputStream(output, deflater).use { it.write(text.toByteArray()) } }
        finally { deflater.end() }
        val parsed = RadioCatalog.parse(RadioCatalog.decode(Base64.getEncoder().encodeToString(output.toByteArray())))
        assertEquals("Una radio", parsed.single().name)
        assertEquals("Roma", parsed.single().group)
        assertEquals(MediaKind.RADIO, parsed.single().kind)
        assertTrue(parsed.single().source.startsWith("https://radio.garden/"))
        assertTrue(parsed.single().id.matches(Regex("[a-f0-9]{24}")))
    }

    @Test fun `identity survives name and stream address updates`() {
        val old = RadioCatalog.parse(catalog(entry())).single()
        val updated = RadioCatalog.parse(catalog(entry(name = "Nuovo nome", url = "https://radio.example/new.mp3"))).single()
        assertEquals(old.id, updated.id)
    }

    @Test fun `city search uses Italian names and accepts missing accents`() {
        val radio = RadioCatalog.parse(catalog(entry(name = "Radio Città")).replace("Roma", "Rome")).single()
        assertEquals("Roma", radio.group)
        assertTrue(RadioCatalog.matches(radio, "roma"))
        assertTrue(RadioCatalog.matches(radio, "citta"))
    }

    @Test fun `disabled entries invalid addresses and duplicate codes do not enter the catalog`() {
        val text = catalog(listOf(entry(), entry(), entry("bad-url", url = "file:///etc/passwd"),
            entry("disabled").replace("true", "false"), entry("credentials", url = "https://user:secret@example.com/live")).joinToString(","))
        assertEquals(1, RadioCatalog.parse(text).size)
    }

    @Test fun `direct broadcaster source is preferred to an identical directory entry`() {
        val text = catalog(entry("garden", "Radio 24") + "," + entry("direct", "Radio 24", "https://example.com/live.m3u8"))
        assertEquals("https://example.com/live.m3u8", RadioCatalog.parse(text).single().source)
    }

    @Test fun `bundled catalog contains real radio identities without TV guide ids`() {
        val text = javaClass.classLoader!!.getResourceAsStream("radios.json")!!.bufferedReader().use { it.readText() }
        val radios = RadioCatalog.parse(text)
        assertTrue(radios.size > 1_000)
        assertTrue(radios.all { it.isRadio && it.epgId.isEmpty() && it.number == 9999 })
        assertEquals("Rai Radio 1", radios.first().name)
    }

    @Test(expected = Exception::class) fun `truncated compressed catalogs are rejected`() {
        RadioCatalog.decode("not a base64 catalog")
    }

    @Test(expected = SourceException::class) fun `decompression is bounded`() {
        val output = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try { DeflaterOutputStream(output, deflater).use { it.write(ByteArray(4 * 1024 * 1024 + 1) { 32 }) } }
        finally { deflater.end() }
        RadioCatalog.decode(Base64.getEncoder().encodeToString(output.toByteArray()))
    }
}
