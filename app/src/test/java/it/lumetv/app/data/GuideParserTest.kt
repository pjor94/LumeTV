package it.lumetv.app.data

import org.junit.Assert.*
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.time.Instant

class GuideParserTest {
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
