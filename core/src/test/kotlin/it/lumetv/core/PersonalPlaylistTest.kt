package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class PersonalPlaylistTest {
    @Test fun `fifty thousand entries retain their order and oversized entry counts are rejected`() {
        val text = "#EXTM3U\n" + (1..50000).joinToString("\n") { "#EXTINF:-1 tvg-id=\"id$it\" group-title=\"Gruppo ${it % 10}\",Canale $it\nhttps://example.com/$it.m3u8" }
        val entries = PersonalM3uParser.parse(text).entries
        assertEquals(50000, entries.size); assertEquals("Canale 50000", entries.last().name)
        val records = PlaylistIdentity.reconcile(entries, emptyList(), 1)
        assertEquals(50000, records.map { it.id }.toSet().size)
        assertEquals(records.map { it.id }, PlaylistIdentity.reconcile(entries, records, 2).map { it.id })
        val budget=PlaylistScanBudget().apply { this.entries=PersonalM3uParser.MAX_ENTRIES }
        try { PersonalM3uParser.scan("#EXTM3U\n#EXTINF:-1,Extra\nhttps://example.com/extra".reader(),budget=budget,accept={null}); fail("Count limit ignored") }
        catch (_: PlaylistFormatException) { }
    }
    private fun playlist(vararg entries: String) = "#EXTM3U\n" + entries.joinToString("\n") + "\n"
    private fun entry(name: String, url: String = "https://example.com/${name.replace(" ", "").replace(",", "")}.m3u8", extra: String = "") = "#EXTINF:-1 $extra,$name\n$url"

    @Test fun `imports quoted commas groups radio and scoped headers without sorting`() {
        val text = "\uFEFF" + playlist(
            entry("Cinema, Italia", extra = "tvg-name=\"Cinema, Italia\" tvg-id=\"cinema\" tvg-chno=\"80\" group-title=\"Film;Italia\""),
            "#EXTINF:-1 tvg-chno=\"2\" radio=\"true\",Radio\r\n#EXTVLCOPT:http-user-agent=Test TV\r\nhttps://example.com/radio.mp3|Referer=https%3A%2F%2Fexample.com%2F")
        val report = PersonalM3uParser.parse(text)
        assertEquals(listOf("Cinema, Italia", "Radio"), report.entries.map { it.name })
        assertEquals(listOf("Film", "Italia"), report.entries.first().groups)
        assertEquals(MediaKind.RADIO, report.entries.last().kind)
        assertEquals("Test TV", report.entries.last().headers["User-Agent"])
        assertEquals("https://example.com/", report.entries.last().headers["Referer"])
    }

    @Test fun `supports name fallback default radio and remote relative URLs`() {
        val report = PersonalM3uParser.parse(playlist("#EXTINF:-1 tvg-name=\"Nome fornito\",\n../live.m3u8"),
            "https://example.com/list/path.m3u", MediaKind.RADIO)
        assertEquals("Nome fornito", report.entries.single().name)
        assertEquals("https://example.com/live.m3u8", report.entries.single().url)
        assertEquals(MediaKind.RADIO, report.entries.single().kind)
    }

    @Test fun `rejects HTML HLS manifests truncation and lists with no supported channels`() {
        listOf("<html>Login</html>", "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\na.ts", "#EXTM3U\n#EXTINF:-1,Incomplete",
            playlist(entry("HTTP", "http://example.com/live")), playlist(entry("Local", "file:///private/video.mp4")),
            playlist(entry("Relative", "live.m3u8"))).forEach { text ->
            try { PersonalM3uParser.parse(text); fail("Invalid playlist accepted") } catch (_: PlaylistFormatException) { }
        }
    }

    @Test fun `counts excluded and exact duplicates while retaining quality variants`() {
        val one = entry("Canale HD", extra = "tvg-id=\"same\"")
        val report = PersonalM3uParser.parse(playlist(one, one, entry("Canale SD", extra = "tvg-id=\"same\""),
            entry("Unsupported", "udp://239.1.1.1:1234")))
        assertEquals(2, report.entries.size); assertEquals(1, report.duplicates); assertEquals(1, report.excluded)
    }

    @Test fun `DRM directives remain private while header injection is excluded`() {
        val report = PersonalM3uParser.parse(playlist(entry("Valid"),
            "#EXTINF:-1,Protected\n#KODIPROP:inputstream.adaptive.license_key=secret\nhttps://example.com/drm.mpd",
            entry("Injected", "https://example.com/stream|Authorization=secret%0D%0AX-Evil%3Ayes")))
        assertEquals(2, report.entries.size); assertEquals(1, report.excluded)
        assertEquals("secret", report.entries.last().properties["inputstream.adaptive.license_key"])
        assertFalse(report.entries.last().toString().contains("secret"))
        assertFalse(report.warnings.joinToString().contains("secret"))
    }

    @Test fun `streaming scanner emits entries without retaining input and scopes properties per entry`() {
        val text = playlist("#EXTINF:-1,Uno\n#EXTHTTP:{\"X-Token\":\"private\"}\n#KODIPROP:inputstream.adaptive.manifest_type=mpd\nhttp://example.com/one",
            entry("Due"))
        val entries = mutableListOf<PlaylistEntry>()
        val result = PersonalM3uParser.scan(text.reader(), allowHttp = { true }, accept = { entries += it; null })
        assertEquals(2, result.imported); assertEquals(1, result.httpEntries)
        assertEquals("application/dash+xml", entries.first().mimeType)
        assertTrue(entries.last().properties.isEmpty())
    }

    @Test fun `unbounded lines are stopped before a whole document is allocated`() {
        var read = 0
        val input = object : java.io.Reader() {
            override fun close() {}
            override fun read(chars: CharArray, offset: Int, length: Int): Int {
                chars.fill('x', offset, offset + length); read += length; return length
            }
        }
        try { PersonalM3uParser.scan(input, accept = { null }); fail("Long line accepted") }
        catch (_: PlaylistFormatException) { assertTrue(read <= PersonalM3uParser.MAX_LINE + 8192) }
    }

    @Test fun `all directive forms share the same per entry property budget`() {
        val directives=(1..31).joinToString("\n") { "#KODIPROP:vendor.option$it=value" }
        val oversized="#EXTINF:-1,Troppi dati\n$directives\n#EXTHTTP:{\"X-Token\":\"value\"}\nhttps://example.com/live|User-Agent=Fixture"
        val report=PersonalM3uParser.parse(playlist(oversized,entry("Valido")))
        assertEquals(1,report.excluded);assertEquals("Valido",report.entries.single().name)
        val tooLarge="#EXTINF:-1,Dati eccessivi\n#KODIPROP:vendor.option="+"x".repeat(32769)+"\nhttps://example.com/large"
        assertEquals(1,PersonalM3uParser.parse(playlist(tooLarge,entry("Valido"))).excluded)
    }

    @Test fun `custom headers decode form encoding and transport overrides are rejected`() {
        val entry = PersonalM3uParser.parse(playlist(entry("Headers", "https://example.com/live|X-Token=a%2Bb&User-Agent=My+Player"))).entries.single()
        assertEquals("a+b", entry.headers["X-Token"]); assertEquals("My Player", entry.headers["User-Agent"])
        listOf("Host", "Content-Length", "Range").forEach { name ->
            try { PlaylistHeaders.merge(mapOf(name to "invalid")); fail("Transport override accepted") } catch (_: PlaylistFormatException) {}
        }
    }

    @Test fun `identity survives renumber rename URL and group changes with a unique provider ID`() {
        val before = PersonalM3uParser.parse(playlist(entry("Prima", extra = "tvg-id=\"id\" tvg-chno=\"1\" group-title=\"Vecchio\""))).entries
        val saved = PlaylistIdentity.reconcile(before, emptyList(), 1)
        val after = PersonalM3uParser.parse(playlist(entry("Dopo", "https://different.example.com/stream?token=new", "tvg-id=\"id\" tvg-chno=\"99\" group-title=\"Nuovo\""))).entries
        val refreshed = PlaylistIdentity.reconcile(after, saved, 2)
        assertEquals(saved.single().id, refreshed.single().id)
        assertTrue(Regex("[a-f0-9]{24}").matches(refreshed.single().id))
    }

    @Test fun `duplicate EPG IDs retain separate identities and ambiguous replacements receive new IDs`() {
        val old = listOf(PlaylistEntry("HD", "https://example.com/hd", "shared"), PlaylistEntry("SD", "https://example.com/sd", "shared"))
        val saved = PlaylistIdentity.reconcile(old, emptyList(), 1)
        val fresh = PlaylistIdentity.reconcile(listOf(old[1], old[0]), saved, 2)
        assertEquals(saved[1].id, fresh[0].id); assertEquals(saved[0].id, fresh[1].id)
        val changed = PlaylistIdentity.reconcile(listOf(PlaylistEntry("Nuovo A", "https://example.com/a", "shared"),
            PlaylistEntry("Nuovo B", "https://example.com/b", "shared")), saved, 2)
        assertTrue(changed.none { n -> saved.any { it.id == n.id } })
    }

    @Test fun `zapping remains in the same source and media kind and empty favorites stay empty`() {
        fun c(id: String, source: String, kind: MediaKind = MediaKind.TV) = Channel(id, id, 1, "", "", "", "", "personal", id, kind = kind, sourceId = source)
        val a = c("a", "user_a"); val b = c("b", "user_a"); val other = c("other", "user_b"); val radio = c("radio", "user_a", MediaKind.RADIO)
        assertEquals(b, ChannelNavigation.next(listOf(a, other, radio, b), a.id, 1))
        assertEquals(a, ChannelNavigation.next(listOf(a, other, radio, b), b.id, 1))
        assertNull(ChannelNavigation.next(listOf(a, other, b), a.id, 1, setOf(other.id)))
        assertEquals(listOf(a, radio, b), CatalogSelection.channels(listOf(a, other, radio, b), "user_a"))
    }
}
