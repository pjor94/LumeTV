package it.lumetv.app.data

import org.junit.Assert.*
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.time.Instant

class GuideParserTest {
    @Test fun `future schedule retains descriptions, prefers Italian and removes duplicate entries`() {
        val entry = """<programme channel="rai1" start="20261027200000 +0000" stop="20261027210000 +0000">
            <title lang="it">Film</title><desc lang="it">Una serata speciale &amp; una storia.</desc><desc lang="en">A story.</desc></programme>"""
        val shows = XmlTvGuideParser.parse(("<tv>" + entry + entry + "</tv>").byteInputStream(), setOf("rai1"),
            Instant.parse("2026-10-27T18:00:00Z").toEpochMilli(), KXmlParser()).getValue("rai1")
        assertEquals(1, shows.size)
        assertEquals("Una serata speciale & una storia.", shows.single().description)
    }

    @Test fun `channel artwork imports even when no programmes are current`() {
        val xml = """<tv>
            <channel id="Rai1.it"><display-name>Rai 1</display-name><icon src="http://epg-guide.com/icn/icons/rai1.png"/></channel>
            <channel id="rai3"><icon src="http://epg-guide.com/icn/icons/rai3.png"/></channel>
            <channel id="other"><icon src="https://example.com/other.png"/></channel>
            <programme channel="rai1" start="20251025130000 +0000" stop="20251025140000 +0000"><icon src="https://example.com/poster.png"/><title>Old</title></programme>
            </tv>"""
        var logos = emptyMap<String, String>()
        val shows = XmlTvGuideParser.parse(xml.byteInputStream(), setOf("rai1", "rai3tgrlazio"),
            Instant.parse("2026-10-27T13:30:00Z").toEpochMilli(), KXmlParser()) { logos = it }
        assertTrue(shows.isEmpty())
        assertEquals(setOf("rai1", "rai3"), logos.keys)
        assertEquals("http://epg-guide.com/icn/icons/rai1.png", logos["rai1"])
    }

    @Test fun `unsafe artwork and programme posters cannot enter the logo index`() {
        val xml = """<tv><channel id="rai1"><icon src="http://127.0.0.1/logo.png"/></channel>
            <channel id="canale5"><icon src="http://untrusted.example.com/logo.png"/></channel>
            <programme channel="rai1" start="20261027130000 +0000" stop="20261027140000 +0000"><icon src="https://example.com/poster.png"/><title>Now</title></programme></tv>"""
        var logos = emptyMap<String, String>()
        XmlTvGuideParser.parse(xml.byteInputStream(), setOf("rai1", "canale5"),
            Instant.parse("2026-10-27T13:30:00Z").toEpochMilli(), KXmlParser()) { logos = it }
        assertTrue(logos.isEmpty())
    }

    @Test fun `guide parses timezone offsets and prefers Italian titles`() {
        val xml = """<tv>
            <programme channel="rai1" start="20261025130000 +0100" stop="20261025140000 +0100">
              <title lang="it">Notizie &amp; attualità</title><title lang="en">News</title>
            </programme>
            <programme channel="rai1" start="20261025140000 +0100" stop="20261025150000 +0100"><title>Film</title></programme>
            <programme channel="other" start="20261025130000 +0100" stop="20261025140000 +0100"><title>Altro</title></programme>
            </tv>"""
        val now = Instant.parse("2026-10-25T12:30:00Z").toEpochMilli()
        val shows = XmlTvGuideParser.parse(xml.byteInputStream(), setOf("rai1"), now, KXmlParser())
        assertEquals(setOf("rai1"), shows.keys)
        assertEquals(2, shows.getValue("rai1").size)
        assertEquals("Notizie & attualità", shows.getValue("rai1").first().title)
        assertEquals(Instant.parse("2026-10-25T12:00:00Z").toEpochMilli(), shows.getValue("rai1").first().start)
    }

    @Test fun `guide ignores old or invalid records and bounds retained programmes`() {
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(java.time.ZoneOffset.UTC)
        val base = Instant.parse("2026-10-25T13:00:00Z")
        val programmes = (0 until 120).joinToString("") { index ->
            val start = base.plusSeconds(index * 60L)
            """<programme channel="rai1" start="${formatter.format(start)}" stop="${formatter.format(start.plusSeconds(3600))}"><title>Programme $index</title></programme>"""
        }
        val xml = """<tv><programme channel="rai1" start="invalid" stop="20261025140000 +0000"><title>Invalid</title></programme>
            <programme channel="rai1" start="20251025130000 +0000" stop="20251025140000 +0000"><title>Old</title></programme>$programmes</tv>"""
        val shows = XmlTvGuideParser.parse(xml.byteInputStream(), setOf("rai1"), Instant.parse("2026-10-25T12:30:00Z").toEpochMilli(), KXmlParser()).getValue("rai1")
        assertEquals(96, shows.size)
        assertEquals("Programme 0", shows.first().title)
        assertFalse(shows.any { it.title in setOf("Old", "Invalid") })
    }

    @Test fun `known feed aliases map only to the matching requested channels`() {
        val xml = """<tv>
            <programme channel="Rai1.it" start="20261025130000 +0000" stop="20261025140000 +0000"><title>Rai Uno</title></programme>
            <programme channel="rairadio2visualradio" start="20261025130000 +0000" stop="20261025140000 +0000"><title>Radio 2</title></programme>
            <programme channel="Rai3TGR.it" start="20261025130000 +0000" stop="20261025140000 +0000"><title>TGR Veneto</title></programme>
            </tv>"""
        val shows = XmlTvGuideParser.parse(xml.byteInputStream(), setOf("rai1", "rairadio2", "rai3tgrveneto", "rai3tgrlazio"), Instant.parse("2026-10-25T13:30:00Z").toEpochMilli(), KXmlParser())
        assertEquals(setOf("rai1", "rairadio2", "rai3tgrveneto"), shows.keys)
        assertFalse(shows.containsKey("rai3tgrlazio"))
    }
}
