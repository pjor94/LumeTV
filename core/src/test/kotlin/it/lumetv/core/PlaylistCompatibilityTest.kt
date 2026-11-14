package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class PlaylistCompatibilityTest {
    private fun entry(id:String)="#EXTINF:-1 tvg-id=\"$id\",$id\nhttps://example.test/$id.m3u8"
    private fun scan(text:String,accept:(PlaylistEntry)->String?={null}):Pair<PlaylistScanReport,List<PlaylistEntry>> {
        val entries=mutableListOf<PlaylistEntry>()
        return PersonalM3uParser.scan(text.reader(),"https://example.test/lists/main.m3u",accept={entries+=it;accept(it)}) to entries
    }
    @Test fun `personal lists retain RSI while the bundled catalog policy remains unchanged`() {
        val text="#EXTM3U\n#EXTINF:-1 tvg-id=\"rsila1\",RSI LA1 HD\nhttps://example.test/la1.m3u8\n#EXTINF:-1,RSI LA2 HD\nhttps://example.test/la2.mpd"
        val (report,entries)=scan(text);assertEquals(2,entries.size);assertEquals(0,report.excluded)
        assertFalse(CatalogPolicy.filterPlaylist(text).contains("#EXTINF"))
    }
    @Test fun `format aliases work before or after metadata and do not bleed to the next channel`() {
        val (_,entries)=scan("#EXTM3U\n#KODIPROP:inputstream.adaptive.file_type=MPD\n${entry("one")}\n#EXTINF:-1,two\n#KODIPROP:inputstream.adaptive.file_type=m3u8\nhttps://example.test/opaque\n${entry("three")}")
        assertEquals(listOf("application/dash+xml","application/x-mpegURL",null),entries.map {it.mimeType})
    }
    @Test fun `explicit MIME and manifest type take precedence over format aliases`() {
        val (_,entries)=scan("#EXTM3U\n#EXTINF:-1,one\n#KODIPROP:inputstream.adaptive.file_type=mpd\n#KODIPROP:inputstream.adaptive.manifest_type=hls\nhttps://example.test/one\n#EXTINF:-1,two\n#KODIPROP:mimetype=video/mp2t\n#KODIPROP:inputstream.adaptive.manifest_type=mpd\nhttps://example.test/two")
        assertEquals(listOf("application/x-mpegURL","video/mp2t"),entries.map {it.mimeType})
    }
    @Test fun `all supported localplay routes have typed canonical identities rather than invented URLs`() {
        val names=listOf("Nove","RealTime","DMAX","Giallo","MotorTrend","HGTV","FoodNetwork","K2","Frisbee","Discovery")
        val (report,entries)=scan("#EXTM3U\n"+names.joinToString("\n"){"#EXTINF:-1,$it\nlocalplay=$it"})
        assertEquals(10,report.imported);assertTrue(entries.all {it.localplay!=null && it.url==it.localplay!!.address && !it.url.startsWith("http")})
        assertEquals(PlaylistLocalplay.MOTOR_TREND,entries[4].localplay);assertEquals("Turbo",entries[4].localplay!!.resolverKey)
        assertEquals(0,report.httpEntries)
    }
    @Test fun `localplay aliases are explicit and case insensitive`() {
        assertEquals(PlaylistLocalplay.MOTOR_TREND,PlaylistLocalplay.read("LOCALPLAY = motor trend"))
        assertEquals(PlaylistLocalplay.MOTOR_TREND,PlaylistLocalplay.read("localplay=Turbo"))
        assertEquals(PlaylistLocalplay.FOOD_NETWORK,PlaylistLocalplay.read("localplay=Food Network"))
        assertNull(PlaylistLocalplay.read("localplay=Nove|Authorization=private"));assertNull(PlaylistLocalplay.read("localplay=https://example.test/command"))
    }
    @Test fun `unknown localplay consumes only its own entry and never invokes a resolver or becomes an HTTP path`() {
        val (report,entries)=scan("#EXTM3U\n#EXTINF:-1,Unknown\nlocalplay=run-private-command\n${entry("good")}")
        assertEquals(listOf("good"),entries.map {it.name});assertEquals(1,report.excluded)
        assertEquals(PlaylistDiagnosticCode.UNKNOWN_LOCALPLAY,report.diagnostics.first().code)
        assertFalse(report.warnings.joinToString().contains("run-private-command"))
    }
    @Test fun `localplay also works in a selected local file without a base URL`() {
        val entries=PersonalM3uParser.parse("#EXTM3U\n#EXTINF:-1,Nove\nlocalplay=Nove").entries
        assertEquals(PlaylistLocalplay.NOVE,entries.single().localplay)
    }
    @Test fun `a trailer with properties and template markers is not counted as an extra missing channel`() {
        val (report,entries)=scan("#EXTM3U\n${entry("good")}\n[template_start]\nEXTINF:-1,Template\n#KODIPROP:inputstreamaddon=inputstream.adaptive\n#KODIPROP:inputstream.adaptive.license_type=clearkey\n[template_end]")
        assertEquals(1,report.scanned);assertEquals(1,entries.size);assertEquals(0,report.excluded)
        assertEquals(1,report.diagnosticCounts[PlaylistDiagnosticCode.TRAILING_PROPERTIES]);assertNull(report.diagnosticCounts[PlaylistDiagnosticCode.MISSING_STREAM])
    }
    @Test fun `actual metadata missing its URL remains excluded even when a later trailer is present`() {
        val (report,_)=scan("#EXTM3U\n${entry("good")}\n#EXTINF:-1,Incomplete\n#KODIPROP:inputstream.adaptive.manifest_type=mpd\n[template_end]")
        assertEquals(1,report.excluded);assertEquals(4,report.diagnostics.first {it.excluded}.line)
    }
    @Test fun `later noise cannot evict rejection reasons channel names or line numbers`() {
        val text="#EXTM3U\n"+(1..3).joinToString("\n"){entry("bad$it")}+"\n${entry("good")}\n"+(1..100).joinToString("\n"){"Noise $it"}
        val (report,_)=scan(text){if(it.name.startsWith("bad"))"Configurazione di prova non supportata." else null}
        assertEquals(3,report.excluded);assertEquals(100,report.diagnosticCounts[PlaylistDiagnosticCode.IGNORED_TEXT])
        assertEquals(3,report.diagnostics.take(3).count {it.excluded});assertTrue(report.diagnostics.take(3).all {it.channel.startsWith("bad") && it.reason!=null && it.line>1})
        assertTrue(report.warnings.take(3).all {"Configurazione di prova" in it});assertTrue(report.diagnostics.size<=20)
    }
    @Test fun `rejections from included documents outrank noise already collected in the parent`() {
        val text="#EXTM3U\n${entry("good")}\n"+(1..30).joinToString("\n"){"Noise $it"}+"\n#EXTINF:-1 type=\"playlist\",Child\nchild.m3u"
        val report=PersonalM3uParser.scan(text.reader(),"https://example.test/root.m3u",include={PlaylistDocument("#EXTM3U\n#EXTINF:-1,Missing".reader(),it){}},accept={null})
        assertEquals(PlaylistDiagnosticCode.MISSING_STREAM,report.diagnostics.first().code);assertNotEquals("root",report.diagnostics.first().document)
    }
    @Test fun `group logos are retained and aggregated as artwork information`() {
        val text="#EXTM3U\n"+(1..16).joinToString("\n"){"#EXTINF:-1 group-logo=\"https://example.test/group.png\",Channel $it\nhttps://example.test/$it.m3u8"}
        val (report,entries)=scan(text);assertEquals(16,report.imported);assertEquals(16,report.diagnosticCounts[PlaylistDiagnosticCode.GROUP_ARTWORK])
        assertNull(report.diagnosticCounts[PlaylistDiagnosticCode.UNKNOWN_ATTRIBUTE]);assertEquals(1,report.diagnostics.size)
        assertEquals("https://example.test/group.png",entries.first().metadata["group-logo"])
    }
    @Test fun `legacy fabricated localplay URLs reconcile with typed routes without losing channel IDs`() {
        val old=PlaylistEntry("Nove","https://example.test/lists/localplay=Nove")
        val previous=PlaylistIdentity.reconcile(listOf(old),emptyList(),1)
        val fresh=PersonalM3uParser.parse("#EXTM3U\n#EXTINF:-1,Nove\nlocalplay=Nove").entries
        assertEquals(previous.single().id,PlaylistIdentity.reconcile(fresh,previous,2).single().id)
    }
}
