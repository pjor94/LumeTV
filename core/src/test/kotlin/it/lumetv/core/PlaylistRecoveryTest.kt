package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.Reader

class PlaylistRecoveryTest {
    private fun entry(id:String)="#EXTINF:-1 tvg-id=\"$id\",Canale $id\nhttps://example.test/$id.m3u8"
    private fun scan(text:String,options:PlaylistReadOptions=PlaylistReadOptions(),base:String?="https://example.test/list/main.m3u"):Pair<PlaylistScanReport,List<PlaylistEntry>> {
        val rows=mutableListOf<PlaylistEntry>()
        val report=PersonalM3uParser.scan(text.reader(),base,options=options,accept={rows+=it;null})
        return report to rows
    }

    @Test fun `a stray line at 1204 preserves the previous 248 channels and all following entries`() {
        val lines=mutableListOf("#EXTM3U")
        repeat(248){lines+=entry(it.toString()).split('\n')}
        while(lines.size<1203)lines+="# comment"
        lines+="Rigaestranea";lines+=entry("last").split('\n')
        val (report,rows)=scan(lines.joinToString("\n"))
        assertEquals(249,rows.size);assertEquals("last",rows.last().tvgId)
        assertEquals(0,report.excluded);assertEquals(1,report.diagnosticCounts[PlaylistDiagnosticCode.IGNORED_TEXT])
        assertEquals(1204,report.diagnostics.single().line)
    }

    @Test fun `noise between metadata and URL does not consume the channel or its headers`() {
        val (report,rows)=scan("#EXTM3U\n#EXTINF:-1,Uno\n#EXTVLCOPT:http-user-agent=Fixture\n---- SPORT ----\nTesto con spazi\nhttps://example.test/one")
        assertEquals("Uno",rows.single().name);assertEquals("Fixture",rows.single().headers["User-Agent"])
        assertEquals(2,report.diagnosticCounts[PlaylistDiagnosticCode.IGNORED_TEXT])
    }

    @Test fun `absolute orphan URLs recover without inheriting MIME DRM VOD or header state`() {
        val text="#EXTM3U\n#EXTINF:120 type=\"vod\",Primo\n#KODIPROP:inputstream.adaptive.manifest_type=mpd\n#KODIPROP:inputstream.adaptive.license_key=private\n#EXTVLCOPT:http-user-agent=PrivateAgent\nhttps://example.test/one.mpd\nhttps://example.test/two.m3u8?token=hidden"
        val (report,rows)=scan(text)
        val recovered=rows.last();assertEquals("Canale 2",recovered.name)
        assertNull(recovered.mimeType);assertNull(recovered.metadata.duration);assertTrue(recovered.headers.isEmpty());assertTrue(recovered.properties.isEmpty())
        assertEquals(PlaylistContentKind.LIVE,recovered.metadata.contentKind)
        assertEquals(1,report.diagnosticCounts[PlaylistDiagnosticCode.RECOVERED_ENTRY])
        assertFalse(report.warnings.joinToString().contains("hidden"));assertFalse(report.warnings.joinToString().contains("private"))
    }

    @Test fun `relative orphan text is ignored while relative URLs in a channel remain supported`() {
        val (report,rows)=scan("#EXTM3U\nSport\nrelative.ts\n#EXTINF:-1,Uno\n../one.m3u8")
        assertEquals("https://example.test/one.m3u8",rows.single().url)
        assertEquals(2,report.diagnosticCounts[PlaylistDiagnosticCode.IGNORED_TEXT])
    }

    @Test fun `prefix and suffix directives share one entry and keep explicit header precedence`() {
        val text="""#EXTM3U http-user-agent="Global"
            |#KODIPROP:inputstream.adaptive.manifest_type=mpd
            |#KODIPROP:inputstream.adaptive.license_type=clearkey
            |#KODIPROP:inputstream.adaptive.license_key=private
            |#EXTHTTP:{"X-Token":"private"}
            |#EXTVLCOPT:http-user-agent=Prefix
            |#EXTINF:-1 http-user-agent="Attribute",Uno
            |#EXTVLCOPT:http-user-agent=Suffix
            |https://example.test/one.mpd|User-Agent=Pipe
            |${entry("two")}
        """.trimMargin()
        val (_,rows)=scan(text)
        assertEquals("application/dash+xml",rows.first().mimeType)
        assertEquals("clearkey",rows.first().properties["inputstream.adaptive.license_type"])
        assertEquals("private",rows.first().properties["inputstream.adaptive.license_key"])
        assertEquals("Pipe",rows.first().headers["User-Agent"])
        assertEquals("Global",rows.last().headers["User-Agent"])
        assertTrue(rows.last().properties.isEmpty());assertNull(rows.last().mimeType)
    }

    @Test fun `a new metadata block and EOF discard only entries without streams`() {
        val (report,rows)=scan("#EXTM3U\n${entry("one")}\n#EXTINF:-1,Missing\n#EXTINF:-1,After\nhttps://example.test/after\n#EXTINF:-1,Last")
        assertEquals(listOf("Canale one","After"),rows.map {it.name})
        assertEquals(2,report.excluded);assertEquals(2,report.diagnosticCounts[PlaylistDiagnosticCode.MISSING_STREAM])
        assertEquals(4,report.scanned)
    }

    @Test fun `broken metadata quarantines its directives and URL instead of lending them to the next channel`() {
        val text="#EXTM3U\n#KODIPROP:inputstream.adaptive.license_key=private\n#EXTINF:-1 tvg-name=\"unclosed,Invalid\n#EXTVLCOPT:http-user-agent=private\nhttps://example.test/broken\n${entry("good") }"
        val (report,rows)=scan(text)
        assertEquals("good",rows.single().tvgId);assertTrue(rows.single().headers.isEmpty());assertTrue(rows.single().properties.isEmpty())
        assertEquals(1,report.excluded);assertEquals(1,report.diagnosticCounts[PlaylistDiagnosticCode.MALFORMED_METADATA])
        assertFalse(report.warnings.joinToString().contains("private"))
    }

    @Test fun `missing comma with an explicit name and an empty display name recover bounded metadata`() {
        val (report,rows)=scan("#EXTM3U\n#EXTINF:-1 tvg-name=\"Fornito\"\nhttps://example.test/one\n#EXTINF:-1,\nhttps://example.test/two")
        assertEquals(listOf("Fornito","Canale 2"),rows.map {it.name})
        assertEquals(2,report.diagnosticCounts[PlaylistDiagnosticCode.RECOVERED_ENTRY])
    }

    @Test fun `prefix options retain the same bounded budget and injection rules as suffix options`() {
        val prefix=(1..33).joinToString("\n"){"#KODIPROP:vendor.$it=private"}
        val (report,rows)=scan("#EXTM3U\n$prefix\n${entry("oversized")}\n#EXTVLCOPT:http-user-agent=bad\u0001value\n${entry("injected")}\n${entry("good")}")
        assertEquals(listOf("good"),rows.map {it.tvgId});assertEquals(2,report.excluded)
        assertFalse(report.warnings.joinToString().contains("private"))
    }

    @Test fun `BOMs and comments do not break channel boundaries or header detection`() {
        val (_,rows)=scan("\uFEFF# comment\r\n#EXTM3U\r\n\uFEFF#KODIPROP:mimetype=video/mp2t\r\n${entry("one")}")
        assertEquals("video/mp2t",rows.single().mimeType)
    }

    @Test fun `a dangling prefix at EOF is reported without losing earlier valid entries`() {
        val (report,rows)=scan("#EXTM3U\n${entry("good")}\n#KODIPROP:inputstream.adaptive.license_type=clearkey")
        assertEquals("good",rows.single().tvgId);assertEquals(0,report.excluded)
        assertEquals(4,report.diagnostics.single {it.code==PlaylistDiagnosticCode.TRAILING_PROPERTIES}.line)
    }

    @Test fun `unsupported orphan protocols are counted as excluded rather than fabricated channels`() {
        val (report,rows)=scan("#EXTM3U\nudp://239.0.0.1:1234\nfile:///private/media\n${entry("good")}")
        assertEquals("good",rows.single().tvgId);assertEquals(2,report.excluded)
    }

    @Test fun `include diagnostics retain the child document and local line number`() {
        val rows=mutableListOf<PlaylistEntry>()
        val report=PersonalM3uParser.scan("#EXTM3U\n#EXTINF:-1 type=\"playlist\",Child\nchild.m3u".reader(),"https://example.test/root.m3u",
            include={PlaylistDocument(("#EXTM3U\nRigaestranea\n"+entry("one")).reader(),it){}},accept={rows+=it;null})
        val d=report.diagnostics.single {it.code==PlaylistDiagnosticCode.IGNORED_TEXT}
        assertEquals(2,d.line);assertNotEquals("root",d.document);assertTrue(d.message.startsWith("Lista inclusa"))
        assertFalse(d.message.contains("https://"));assertEquals(1,report.imported)
    }

    @Test fun `recoveries and diagnostics remain bounded and preserve identity matching`() {
        val text="#EXTM3U\n"+(1..200).joinToString("\n"){"Noise $it\n"+entry(it.toString())}
        val (report,rows)=scan(text);assertEquals(200,report.diagnosticCounts[PlaylistDiagnosticCode.IGNORED_TEXT]);assertTrue(report.diagnostics.size<=20);assertTrue(report.warnings.size<=20)
        val saved=PlaylistIdentity.reconcile(rows,emptyList(),1)
        assertEquals(saved.map {it.id},PlaylistIdentity.reconcile(scan(text.replace("Noise","Separator")).second,saved,2).map {it.id})
    }

    @Test fun `HTML HLS empty imports limits and transport interruption remain fatal`() {
        for(text in listOf("<html>Login</html>","#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://example.test/master.m3u8","#EXTM3U\nNoise","#EXTM3U\n#EXTINF:-1,Missing")) {
            try {scan(text);fail("Invalid document accepted")}catch(_:PlaylistFormatException){}
        }
        val prefix=("#EXTM3U\n"+entry("one")+"\n").toCharArray();var offset=0
        val reader=object:Reader(){override fun close(){};override fun read(b:CharArray,o:Int,n:Int):Int {
            if(offset==prefix.size)throw IOException("transport interrupted")
            val size=minOf(n,prefix.size-offset);prefix.copyInto(b,o,offset,offset+size);offset+=size;return size
        }}
        try {PersonalM3uParser.scan(reader,accept={null});fail("Transport error swallowed")}catch(_:IOException){}
        val budget=PlaylistScanBudget().apply {entries=PersonalM3uParser.MAX_ENTRIES}
        try {PersonalM3uParser.scan("#EXTM3U\nhttps://example.test/one".reader(),budget=budget,accept={null});fail("Limit bypassed")}catch(_:PlaylistFormatException){}
    }
}
