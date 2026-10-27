package it.lumetv.app.data

import it.lumetv.core.Channel
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class LogoPolicyTest {
    @Test fun `public artwork hostnames cannot resolve to a local network address`() {
        listOf("127.0.0.1", "192.168.1.10", "10.0.2.2", "169.254.1.1", "100.64.1.2", "::1", "fc00::1").forEach {
            assertFalse(it, LogoPolicy.publicAddress(InetAddress.getByName(it)))
        }
        assertTrue(LogoPolicy.publicAddress(InetAddress.getByName(LogoPolicy.EPG_IMAGE_HOST)))
    }
    @Test fun `HTTP is limited to the observed artwork destinations`() {
        assertNotNull(LogoPolicy.url("http://epg-guide.com/icn/icons/rai1.png"))
        assertNotNull(LogoPolicy.url("http://116.202.210.205/icn/icons/rai1.png"))
        assertNotNull(LogoPolicy.url("http://www.man-ga.it/img/layout/manga-logo.gif"))
        assertNull(LogoPolicy.url("http://example.com/logo.png"))
        assertNull(LogoPolicy.url("http://116.202.210.206/icn/icons/rai1.png"))
        assertNull(LogoPolicy.url("http://epg-guide.com/other/page.html"))
        assertNull(LogoPolicy.url("http://epg-guide.com/icn/icons/../outside.png"))
    }

    @Test fun `private addresses credentials and nonstandard ports cannot become logos`() {
        listOf("https://127.0.0.1/logo.png", "https://192.168.1.2/logo.png", "https://[::1]/logo.png",
            "https://tv.local/logo.png", "https://localhost/logo.png", "https://app.internal/logo.png",
            "https://user:secret@example.com/logo.png", "https://example.com:8080/logo.png",
            "file:///data/local/logo.png", "data:image/png;base64,AAAA").forEach { assertNull(it, LogoPolicy.url(it)) }
        assertNotNull(LogoPolicy.url("https://canaleitalia.it/wp-content/logo.png"))
    }

    @Test fun `redirects cannot silently expand the HTTP exception`() {
        val epg = "https://epg-guide.com/icn/icons/rai1.png".toHttpUrl()
        assertTrue(LogoPolicy.redirect(epg, "http://116.202.210.205/icn/icons/rai1.png".toHttpUrl()))
        assertFalse(LogoPolicy.redirect(epg, "http://116.202.210.206/icn/icons/rai1.png".toHttpUrl()))
        assertFalse(LogoPolicy.redirect(epg, "https://unrelated.example.com/logo.png".toHttpUrl()))
        assertFalse(LogoPolicy.redirect("https://canaleitalia.it/logo.png".toHttpUrl(), "http://canaleitalia.it/logo.png".toHttpUrl()))
    }

    @Test fun `regional artwork shares the Rai 3 brand without changing channel identity`() {
        val channel = Channel("id", "Rai 3 TGR Lazio", 3, "rai3tgrlazio", "", "", "source", "rainewsrg", "key")
        val epg = mapOf("rai3" to "http://epg-guide.com/icn/icons/rai3.png", "rai3tgrlazio" to "http://epg-guide.com/icn/icons/rai3tgr.png")
        assertEquals(listOf(epg.getValue("rai3tgrlazio"), epg.getValue("rai3")), LogoPolicy.candidates(channel, epg))
        assertEquals("rai3tgrlazio", channel.epgId)
        assertEquals("id", channel.id)
    }

    @Test fun `playlist precedes guide artwork and duplicate URLs are removed`() {
        val url = "https://example.com/logo.png"
        val channel = Channel("id", "Name", 1, "epg", "", url, "source", "direct", "key")
        assertEquals(listOf(url), LogoPolicy.candidates(channel, mapOf("epg" to url)))
    }

    @Test fun `cache revalidates after a week or a clock correction`() {
        val saved = 1_000_000L
        assertTrue(LogoPolicy.fresh(saved, saved + LogoPolicy.FRESH_FOR - 1))
        assertFalse(LogoPolicy.fresh(saved, saved + LogoPolicy.FRESH_FOR))
        assertFalse(LogoPolicy.fresh(saved, saved - 1))
        assertFalse(LogoPolicy.fresh(0, saved))
    }
}
