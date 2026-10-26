package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class CatalogTest {
    private val catalog get() = M3uParser.parse(javaClass.getResource("/channels-2026-10-25.m3u")!!.readText())

    @Test fun `current bundled catalogue includes supported Rai and Mediaset channels`() {
        val text = javaClass.getResource("/channels.m3u")!!.readText()
        assertEquals(text, CatalogPolicy.filterPlaylist(text))
        val current = M3uParser.parse(text).filter { it.supported }
        assertTrue(current.size >= 20)
        assertTrue(current.count { it.family == "Rai" } >= 3)
        assertTrue(current.count { it.family == "Mediaset" } >= 3)
    }

    @Test fun `bundled WLTV catalog preserves every Rai Mediaset and TGR entry`() {
        val channels = catalog
        assertEquals(159, channels.size)
        assertEquals(21, channels.count { it.broadcaster == "rai" })
        assertEquals(23, channels.count { it.broadcaster == "mediaset" })
        assertEquals(22, channels.count { it.broadcaster == "rainewsrg" })
        assertEquals(66, channels.count { it.family in setOf("Rai", "Mediaset") })
        assertTrue(channels.any { it.key == "\$C5" })
        assertTrue(channels.any { it.name == "Rai 4K" })
    }

    @Test fun `parser keeps quoted commas and encoded channel keys`() {
        val input = """#EXTM3U
            |#EXTINF:-1 tvg-id="example" tvg-name="Cinema, Italia" tvg-chno="25" group-title="Film, fiction",Cinema, Italia
            |plugin://plugin.video.wltvhelper/?play/rai/Rai%20Movie
        """.trimMargin()
        val channel = M3uParser.parse(input).single()
        assertEquals("Cinema, Italia", channel.name)
        assertEquals("Film, fiction", channel.group)
        assertEquals("Rai Movie", channel.key)
        assertEquals(25, channel.number)
    }

    @Test fun `transport headers do not become part of stream URL`() {
        val input = """#EXTM3U
            |#EXTINF:-1 tvg-id="demo",Demo
            |#EXTVLCOPT:http-user-agent=My TV
            |https://example.org/live.m3u8|Referer=https%3A%2F%2Fexample.org%2F
        """.trimMargin()
        val channel = M3uParser.parse(input).single()
        assertEquals("https://example.org/live.m3u8", channel.source)
        assertEquals("My TV", channel.headers["User-Agent"])
        assertEquals("https://example.org/", channel.headers["Referer"])
    }

    @Test fun `stable IDs survive channel renumbering and title changes`() {
        val before = "#EXTINF:-1 tvg-chno=\"5\",Canale 5\nplugin://plugin.video.wltvhelper/play/mediaset/\$C5"
        val after = "#EXTINF:-1 tvg-chno=\"105\",Canale Cinque\nplugin://plugin.video.wltvhelper/play/mediaset/\$C5"
        assertEquals(M3uParser.parse(before).single().id, M3uParser.parse(after).single().id)
    }

    @Test fun `zapping wraps and handles stale last channel`() {
        val channels = catalog.take(3)
        assertEquals(channels.last(), ChannelNavigation.next(channels, channels.first().id, -1))
        assertEquals(channels.first(), ChannelNavigation.next(channels, channels.last().id, 1))
        assertEquals(channels.first(), ChannelNavigation.next(channels, "removed-channel", 1))
        assertNull(ChannelNavigation.next(emptyList(), null, 1))
    }

    @Test fun `favorites zapping skips other channels and wraps in both directions`() {
        val channels = catalog.take(5)
        val favorites = setOf(channels[1].id, channels[3].id, "removed-channel")
        assertEquals(channels[3], ChannelNavigation.next(channels, channels[1].id, 1, favorites))
        assertEquals(channels[1], ChannelNavigation.next(channels, channels[3].id, 1, favorites))
        assertEquals(channels[3], ChannelNavigation.next(channels, channels[1].id, -1, favorites))
        assertEquals(channels[1], ChannelNavigation.next(channels, channels[0].id, 1, favorites))
        assertEquals(channels[3], ChannelNavigation.next(channels, channels[0].id, -1, favorites))
    }

    @Test fun `empty and stale favorites do not silently zap through all channels`() {
        assertNull(ChannelNavigation.next(catalog, null, 1, emptySet()))
        assertNull(ChannelNavigation.next(catalog, null, -1, setOf("removed-channel")))
        assertEquals(catalog[1], ChannelNavigation.next(catalog, catalog[0].id, 1))
    }

    @Test fun `one favorite remains selected and numerical selection still uses the full catalog`() {
        val channels = catalog.take(5)
        val favorite = channels[3]
        assertEquals(favorite, ChannelNavigation.next(channels, favorite.id, 1, setOf(favorite.id)))
        assertEquals(favorite, ChannelNavigation.next(channels, favorite.id, -1, setOf(favorite.id)))
        assertEquals(channels[0], ChannelNavigation.byNumber(channels, channels[0].number))
    }
}
