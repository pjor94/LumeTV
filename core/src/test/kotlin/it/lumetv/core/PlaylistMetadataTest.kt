package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class PlaylistMetadataTest {
    @Test fun `source defaults and private metadata survive normalization with channel overrides`() {
        val text="""#EXTM3U url-tvg="https://guide.example/epg.xml.gz" url-logo="../logos/" tvg-shift="2" catchup="append" catchup-days="7" refresh="PT6H"
            |#EXTINF:120 tvg-name="Nome guida" ch-number="9999" tvg-shift="-1.5" tvg-language="it;en" tvg-country="IT" adult="1" audio-track="2" vendor-extra="private",Nome schermo
            |https://media.example/movie.mp4?token=a%2Bb+unchanged|seekable=0&User-Agent=Fixture
        """.trimMargin()
        val report=PersonalM3uParser.parse(text,"https://provider.example/lists/main.m3u")
        val e=report.entries.single()
        assertEquals("Nome schermo",e.name);assertEquals("Nome guida",e.metadata.tvgName)
        assertEquals(9999,e.number);assertEquals(-1.5,e.metadata.epgShiftHours)
        assertEquals(7.0,e.metadata.catchup.days);assertEquals(listOf("it","en"),e.metadata.languages)
        assertTrue(e.metadata.restricted);assertEquals(120.0,e.metadata.duration);assertEquals(PlaylistContentKind.VOD,e.metadata.contentKind)
        assertEquals(false,e.metadata.seekable);assertFalse(e.headers.keys.any {it.equals("seekable",true)})
        assertFalse(e.properties.getValue("lumetv.pipe_headers").contains("seekable"))
        assertEquals("https://media.example/movie.mp4?token=a%2Bb+unchanged",e.url)
        assertEquals(6*3_600_000L,report.descriptor.refreshMillis)
        assertEquals(listOf("https://guide.example/epg.xml.gz"),report.descriptor.epgUrls)
        assertFalse(report.toString().contains("vendor-extra=private"));assertFalse(e.metadata.toString().contains("private"))
    }

    @Test fun `relative artwork resolves without rewriting signed query strings`() {
        val report=PersonalM3uParser.parse("#EXTM3U url-logo=\"../logos/\"\n#EXTINF:-1 tvg-logo=\"one.png?token=a%2Bb+x\",Uno\n../live.m3u8",
            "https://provider.example/lists/main.m3u")
        assertEquals("https://provider.example/logos/one.png?token=a%2Bb+x",report.entries.single().logo)
        assertEquals("https://provider.example/live.m3u8",report.entries.single().url)
        assertEquals("https://provider.example/logo.png?signature=a%2Bb+x",PlaylistUris.resolve(java.net.URI("https://provider.example/root.m3u"),java.net.URI("../logo.png?signature=a%2Bb+x")).toString())
        assertEquals("https://provider.example/root.m3u?signature=a%2Bb+x",PlaylistUris.resolve(java.net.URI("https://provider.example/root.m3u?old=1"),java.net.URI("?signature=a%2Bb+x")).toString())
    }

    @Test fun `group conventions are explicit and legacy behavior remains the default`() {
        val text="#EXTM3U\n#EXTGRP:Primo\n#EXTINF:-1,Uno\nhttps://example.com/1\n#EXTINF:-1 group-title=\"Altro\",Due\nhttps://example.com/2\n#EXTINF:-1,Tre\nhttps://example.com/3"
        fun groups(profile:PlaylistGroupProfile)=PersonalM3uParser.parse(text,options=PlaylistReadOptions(groups=profile)).entries.map {it.groups}
        assertEquals(listOf(listOf("Primo"),listOf("Altro"),listOf("Primo")),groups(PlaylistGroupProfile.CURRENT))
        assertEquals(listOf(listOf("Primo"),listOf("Altro"),emptyList<String>()),groups(PlaylistGroupProfile.KODI))
        assertEquals(listOf(listOf("Primo"),listOf("Altro"),emptyList<String>()),groups(PlaylistGroupProfile.OTT))
    }

    @Test fun `plain mode is explicit and still refuses HTML and segment manifests`() {
        val plain="https://example.com/one.mp3?secret=hidden\nhttps://example.com/two.mp4"
        val report=PersonalM3uParser.parse(plain,options=PlaylistReadOptions(format=PlaylistFormat.PLAIN))
        assertEquals(listOf("one.mp3","two.mp4"),report.entries.map {it.name})
        listOf(plain,"<html>Login</html>","#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nhttps://example.com/segment.ts").forEach { text ->
            try {PersonalM3uParser.parse(text);fail("Non-playlist accepted")}catch(_:PlaylistFormatException){}
        }
        try {PersonalM3uParser.parse("<html>Login</html>",options=PlaylistReadOptions(format=PlaylistFormat.PLAIN));fail("HTML accepted")}catch(_:PlaylistFormatException){}
    }

    @Test fun `Kodi VOD marker is distinct from an HLS media playlist`() {
        val marker="#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:-1,Film\nhttps://example.com/movie.mp4"
        val opts=PlaylistReadOptions(groups=PlaylistGroupProfile.KODI)
        assertEquals(PlaylistContentKind.VOD,PersonalM3uParser.parse(marker,options=opts).entries.single().metadata.contentKind)
        try {PersonalM3uParser.parse(marker.replace("#EXTINF:-1","#EXT-X-TARGETDURATION:10\n#EXTINF:10"),options=opts);fail("HLS segments accepted")}catch(_:PlaylistFormatException){}
    }

    @Test fun `includes merge inline with shared budgets defaults and resource cleanup`() {
        val root="#EXTM3U catchup-days=\"7\"\n#EXTINF:-1,Prima\nhttps://example.com/1\n#EXTINF:-1 type=\"playlist\",Figlia\nchild/list.m3u\n#EXTINF:-1,Ultima\nhttps://example.com/3"
        val entries=mutableListOf<PlaylistEntry>();var closed=false
        val report=PersonalM3uParser.scan(root.reader(),"https://example.com/root.m3u",include={url ->
            assertEquals("https://example.com/child/list.m3u",url)
            PlaylistDocument("#EXTM3U tvg-shift=\"1\"\n#EXTINF:-1,Seconda\n../2".reader(),url) {closed=true}
        },accept={entries+=it;null})
        assertTrue(closed);assertEquals(listOf("Prima","Seconda","Ultima"),entries.map {it.name})
        assertEquals(listOf(0,2,3),entries.map {it.position});assertEquals(4,report.scanned);assertEquals(2,report.descriptor.documents.size)
        assertEquals("https://example.com/2",entries[1].url);assertEquals(7.0,entries[1].metadata.catchup.days)
    }

    @Test fun `include cycles depth and document budgets are rejected and documents close`() {
        val root="#EXTM3U\n#EXTINF:-1 type=\"playlist\",Include\nhttps://example.com/root.m3u"
        var closed=false
        try {PersonalM3uParser.scan(root.reader(),"https://example.com/root.m3u",include={url->PlaylistDocument(root.reader(),url){closed=true}},accept={null});fail("Cycle accepted")}
        catch(_:PlaylistFormatException){assertTrue(closed)}
        val budget=PlaylistScanBudget().apply {entries=PersonalM3uParser.MAX_ENTRIES-1}
        try {PersonalM3uParser.scan(("#EXTM3U\n#EXTINF:-1,Uno\nhttps://example.com/1\n#EXTINF:-1,Due\nhttps://example.com/2").reader(),budget=budget,accept={null});fail("Aggregate count ignored")}
        catch(_:PlaylistFormatException){assertEquals(PersonalM3uParser.MAX_ENTRIES+1,budget.entries)}
    }

    @Test fun `inherited artwork base keeps its original origin after an include redirects to a CDN`() {
        val text="#EXTM3U url-logo=\"../logos/\" url-tvg=\"../guide.xml?token=a%2Bb+x\"\n#EXTINF:-1 type=\"playlist\",Include\nchild.m3u"
        var entry:PlaylistEntry?=null
        val result=PersonalM3uParser.scan(text.reader(),"https://provider.example/lists/root.m3u",include={
            PlaylistDocument("#EXTM3U\n#EXTINF:-1 tvg-logo=\"one.png\",Uno\nvideo.m3u8".reader(),"https://cdn.example/streams/child.m3u"){}
        },accept={entry=it;null})
        assertEquals("https://provider.example/logos/one.png",entry!!.logo)
        assertEquals("https://cdn.example/streams/child.m3u",entry!!.metadata.baseUrl)
        assertEquals(listOf("https://provider.example/guide.xml?token=a%2Bb+x"),result.descriptor.epgUrls)
        assertEquals("https://provider.example/lists/root.m3u",result.descriptor.bases["root"])
    }

    @Test fun `diagnostics retain exclusions even after many optional metadata warnings`() {
        val text="#EXTM3U\n"+(1..25).joinToString("\n") {"#EXTINF:-1 catchup=\"shift\",Canale $it\nhttps://example.com/$it"}+"\n#EXTINF:-1,Bloccato\nhttp://blocked.example/live"
        val result=PersonalM3uParser.parse(text)
        assertEquals(1,result.excluded);assertTrue(result.warnings.first().contains("HTTP"));assertEquals(20,result.diagnostics.size)
        assertFalse(result.warnings.joinToString().contains("blocked.example"))
        assertNull(PlaylistDescriptor(mapOf("root" to mapOf("refresh" to "12"))).refreshMillis)
    }

    @Test fun `real 9999 channel is distinct from a missing channel number`() {
        val real=Channel("one","Uno",9999,"","","","","direct","",numberKnown=true)
        val missing=real.copy(id="two",numberKnown=false)
        assertEquals(9999,real.displayNumber);assertNull(missing.displayNumber)
        assertEquals(real,ChannelNavigation.byNumber(listOf(missing,real),9999))
    }
}
