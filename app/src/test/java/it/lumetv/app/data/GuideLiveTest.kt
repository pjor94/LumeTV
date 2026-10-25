package it.lumetv.app.data

import it.lumetv.core.M3uParser
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.tukaani.xz.XZInputStream
import java.io.File

/** Explicit local replay of a downloaded guide; never downloads from the unit test. */
class GuideLiveTest {
    @Test fun `downloaded guide provides current programmes for the main channels`() {
        val path = System.getProperty("lume.guideFile").orEmpty()
        assumeTrue(path.isNotBlank())
        val channels = M3uParser.parse(javaClass.getResource("/channels.m3u")!!.readText()).filter { it.supported }
        val now = System.currentTimeMillis()
        val shows = XZInputStream(File(path).inputStream(), 64 * 1024).use {
            XmlTvGuideParser.parse(it, channels.map { c -> c.epgId }.toSet(), now, KXmlParser())
        }
        for (id in listOf("rai1", "rai2", "rai3", "rete4", "canale5", "italia1", "la7")) {
            assertTrue("Missing current programme: $id", shows[id].orEmpty().any { it.start <= now && it.end > now })
            assertTrue("Missing next programme: $id", shows[id].orEmpty().any { it.start > now })
        }
        println("Live XMLTV replay: ${shows.size} channel IDs; ${shows.values.sumOf { it.size }} programmes")
    }
}
