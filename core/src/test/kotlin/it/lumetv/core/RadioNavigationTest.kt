package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class RadioNavigationTest {
    private fun channel(id: String, radio: Boolean = false) = Channel(id, id, if (radio) 9999 else 1,
        "", "", "", "https://example.com/$id", "direct", "", kind = if (radio) MediaKind.RADIO else MediaKind.TV)
    private val channels = listOf(channel("tv1"), channel("tv2"), channel("radio1", true), channel("radio2", true))

    @Test fun `station next previous and wrap stay in the current media kind`() {
        assertEquals("radio2", ChannelNavigation.next(channels, "radio1", 1)?.id)
        assertEquals("radio2", ChannelNavigation.next(channels, "radio1", -1)?.id)
        assertEquals("tv1", ChannelNavigation.next(channels, "tv2", 1)?.id)
    }
    @Test fun `radio favorites do not become TV zapping candidates`() {
        assertNull(ChannelNavigation.next(channels, "tv1", 1, setOf("radio1")))
        assertEquals("radio2", ChannelNavigation.next(channels, "radio1", 1, setOf("radio2", "tv1"))?.id)
    }
    @Test fun `radios are excluded from numeric TV channel selection`() {
        assertNull(ChannelNavigation.byNumber(channels, 9999))
    }
}
