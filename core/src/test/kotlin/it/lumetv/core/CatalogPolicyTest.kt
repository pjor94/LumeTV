package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class CatalogPolicyTest {
    @Test fun `upstream sample loses exactly the four RSI entries and preserves other channels`() {
        val original = javaClass.getResource("/channels-2026-10-25.m3u")!!.readText()
        val channels = M3uParser.parse(original)
        val filtered = CatalogPolicy.filterPlaylist(original)
        assertEquals(4, channels.count(CatalogPolicy::isExcluded))
        assertEquals(channels.filterNot(CatalogPolicy::isExcluded), M3uParser.parse(filtered))
        assertEquals(155, M3uParser.parse(filtered).size)
        assertEquals(filtered, CatalogPolicy.filterPlaylist(filtered))
    }

    @Test fun `RSI variants remain excluded after title id or endpoint changes`() {
        val entries = listOf(
            "#EXTINF:-1 tvg-id=\"renamed\",News\nplugin://plugin.video.wltvhelper/?play/rsi/LA%201\n",
            "#EXTINF:-1 tvg-id=\"RSILa2.VPN\",News\nhttps://example.org/changed.mpd\n",
            "#EXTINF:-1 tvg-id=\"renamed\",rSi La 1 HD\nhttps://example.org/changed.mpd\n",
            "#EXTINF:-1 tvg-id=\"renamed\",News\nhttps://new.example.org/live/RSILA2HD/manifest.mpd\n",
        )
        entries.forEach { entry ->
            assertTrue(entry, M3uParser.parse(CatalogPolicy.filterPlaylist("#EXTM3U\n$entry")).isEmpty())
        }
    }

    @Test fun `unrelated channels and their stream headers are preserved verbatim`() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"radio\",RSI Radio Rete Uno\n" +
            "#EXTVLCOPT:http-referrer=https://example.org/\nhttps://example.org/radio.m3u8\n" +
            "#EXTINF:-1 tvg-id=\"other\",RSI La10\nhttps://example.org/rsila10/manifest.mpd\n"
        assertEquals(text, CatalogPolicy.filterPlaylist(text))
    }
}
