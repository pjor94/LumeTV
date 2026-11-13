package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class PlaylistContentClassifierTest {
    private fun entry(attributes:String="",duration:String="-1",url:String="https://example.com/stream",options:PlaylistReadOptions=PlaylistReadOptions())=
        PersonalM3uParser.parse("#EXTM3U\n#EXTINF:$duration $attributes,Item\n$url",options=options).entries.single()

    @Test fun `explicit VOD aliases support unknown durations and case normalization`() {
        for(attribute in listOf("type","content-type"))for(value in listOf("vod","movie","movies","film","films","video","series","episode","episodes","tvshow"," VOD "))
            assertEquals("$attribute=$value",PlaylistContentKind.VOD,entry("$attribute=\"$value\"").metadata.contentKind)
    }
    @Test fun `explicit live wins over duration file extension group and provider path`() {
        val row=entry("type=\"LIVE\" group-title=\"Film\"",duration="7200",url="https://example.com/movie/u/p/123.mp4",options=PlaylistReadOptions(vodDetection=PlaylistVodDetection.EXTENDED))
        assertEquals(PlaylistContentKind.LIVE,row.metadata.contentKind);assertEquals(PlaylistClassificationReason.TYPE,row.metadata.classificationReason)
    }
    @Test fun `entry declarations override document defaults and unknown type falls back to a recognized content type`() {
        val row=PersonalM3uParser.parse("#EXTM3U type=\"vod\"\n#EXTINF:-1 content-type=\"live\",Live\nhttps://example.com/one").entries.single()
        assertEquals(PlaylistContentKind.LIVE,row.metadata.contentKind)
        assertEquals(PlaylistContentKind.VOD,entry("type=\"video/mp4\" content-type=\"vod\"").metadata.contentKind)
        assertEquals(PlaylistContentKind.LIVE,entry("content-type=\"video/mp4\"").metadata.contentKind)
    }
    @Test fun `contradictory explicit types keep deterministic precedence and a bounded diagnostic`() {
        val report=PersonalM3uParser.parse("#EXTM3U\n#EXTINF:-1 type=\"vod\" content-type=\"live\" media=\"false\",Item\nhttps://example.com/private?token=hidden")
        assertEquals(PlaylistContentKind.VOD,report.entries.single().metadata.contentKind)
        assertEquals(1,report.diagnostics.count {it.code==PlaylistDiagnosticCode.CONTENT_CONFLICT})
        assertFalse(report.warnings.joinToString().contains("hidden"))
    }
    @Test fun `Kodi media directory and positive byte size identify VOD without a duration`() {
        for(attr in listOf("media=\"true\"","media=\"1\"","media-dir=\"/movies/scifi\"","media-size=\"102400000\""))
            assertEquals(PlaylistContentKind.VOD,entry(attr).metadata.contentKind)
        for(attr in listOf("media-size=\"0\"","media-size=\"-1\"","media-size=\"unknown\"","media-dir=\"\""))
            assertEquals(PlaylistContentKind.LIVE,entry(attr).metadata.contentKind)
    }
    @Test fun `explicit media false prevents inference and seeking or DRM flags do not classify a stream`() {
        assertEquals(PlaylistContentKind.LIVE,entry("media=\"false\"",duration="120",url="https://example.com/movie/u/p/10.mp4").metadata.contentKind)
        assertEquals(PlaylistContentKind.LIVE,entry(url="https://example.com/movie.mp4|seekable=1").metadata.contentKind)
    }
    @Test fun `structured provider paths identify movies and episodes without changing the URL`() {
        for(path in listOf("/movie/User/p%2Bass/123.mp4","/series/u/p/99.mkv","/prefix/movie/u/p/10","/MOVIE/u/p/12.MP4")) {
            val url="https://example.com$path?token=A%2Bb+unchanged"
            val row=entry(url=url)
            assertEquals(PlaylistContentKind.VOD,row.metadata.contentKind);assertEquals(url,row.url)
        }
    }
    @Test fun `host query substrings malformed paths and live routes cannot trigger provider inference`() {
        for(url in listOf("https://movie.example.com/channel","https://example.com/channel?url=/movie/u/p/12.mp4",
            "https://example.com/movies/u/p/12.mp4","https://example.com/movie/12.mp4","https://example.com/movie/u/p/title.mp4",
            "https://example.com/live/movie/u/p/12.mp4","https://example.com/timeshift/u/p/12.mp4"))
            assertEquals(url,PlaylistContentKind.LIVE,entry(url=url).metadata.contentKind)
    }
    @Test fun `extended inference requires both a video group and a file extension`() {
        val extended=PlaylistReadOptions(vodDetection=PlaylistVodDetection.EXTENDED)
        assertEquals(PlaylistContentKind.LIVE,entry("group-title=\"IT | FILM UHD\"",url="https://example.com/film.mp4").metadata.contentKind)
        assertEquals(PlaylistContentKind.VOD,entry("group-title=\"IT | FILM UHD\"",url="https://example.com/film.MP4?token=x",options=extended).metadata.contentKind)
        assertEquals(PlaylistContentKind.VOD,entry("group-title=\"Séries\"",url="https://example.com/item.mkv",options=extended).metadata.contentKind)
        for((group,url) in listOf("Film" to "https://example.com/stream","Sport" to "https://example.com/item.mp4","Film" to "https://example.com/live/u/p/1.mp4",
            "Film Live" to "https://example.com/item.mp4","Film 24h" to "https://example.com/item.mp4","Film" to "https://example.com/live.m3u8",
            "Film" to "https://example.com/stream?logo=poster.mp4"))
            assertEquals(PlaylistContentKind.LIVE,entry("group-title=\"$group\"",url=url,options=extended).metadata.contentKind)
    }
    @Test fun `catchup channels remain live when only weak group and extension evidence is available`() {
        assertEquals(PlaylistContentKind.LIVE,entry("group-title=\"Film\" catchup=\"append\"",url="https://example.com/item.mp4",options=PlaylistReadOptions(vodDetection=PlaylistVodDetection.EXTENDED)).metadata.contentKind)
    }
    @Test fun `manual group rules override file declarations and multiple matching rules favor live`() {
        val options=PlaylistReadOptions(groupContent=mapOf("Cinema" to PlaylistContentKind.VOD,"Dirette" to PlaylistContentKind.LIVE))
        assertEquals(PlaylistContentKind.VOD,entry("type=\"live\" group-title=\"CINEMA\"",options=options).metadata.contentKind)
        val text="#EXTM3U\n#EXTINF:-1 group-title=\"Cinema;Dirette\",Mixed\nhttps://example.com/one"
        val report=PersonalM3uParser.parse(text,options=options)
        assertEquals(PlaylistContentKind.LIVE,report.entries.single().metadata.contentKind)
        assertTrue(report.diagnostics.any {it.code==PlaylistDiagnosticCode.CONTENT_CONFLICT})
    }
    @Test fun `includes remain structural even with a VOD group rule`() {
        val rows=mutableListOf<PlaylistEntry>()
        PersonalM3uParser.scan("#EXTM3U\n#EXTINF:-1 type=\"playlist\" group-title=\"Film\",Include\nhttps://example.com/child.m3u".reader(),
            options=PlaylistReadOptions(groupContent=mapOf("Film" to PlaylistContentKind.VOD)),include={PlaylistDocument("#EXTM3U\n#EXTINF:-1,Live\nhttps://example.com/one".reader(),it){}},accept={rows+=it;null})
        assertEquals(1,rows.size);assertEquals(PlaylistContentKind.LIVE,rows.single().metadata.contentKind)
    }
    @Test fun `Kodi entry marker stops at its URL while a document marker is inherited within that document`() {
        val options=PlaylistReadOptions(groups=PlaylistGroupProfile.KODI)
        val content="#EXTM3U\n#EXTINF:-1,Film\n#EXT-X-PLAYLIST-TYPE:VOD\nhttps://example.com/one\n#EXTINF:-1,Live\nhttps://example.com/two"
        val rows=PersonalM3uParser.parse(content,options=options).entries
        assertEquals(listOf(PlaylistContentKind.VOD,PlaylistContentKind.LIVE),rows.map {it.metadata.contentKind})
        assertEquals(listOf(PlaylistVodMarker.ENTRY,PlaylistVodMarker.NONE),rows.map {it.metadata.vodMarker})
        val global=PersonalM3uParser.parse(content.replace("#EXTM3U\n","#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n"),options=options).entries
        assertTrue(global.all {it.metadata.contentKind==PlaylistContentKind.VOD})
        val between=PersonalM3uParser.parse("#EXTM3U\n#EXTINF:-1,First\nhttps://example.com/first\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:-1,Second\nhttps://example.com/second\n#EXTINF:-1,Third\nhttps://example.com/third",options=options).entries
        assertEquals(listOf(PlaylistContentKind.LIVE,PlaylistContentKind.VOD,PlaylistContentKind.LIVE),between.map {it.metadata.contentKind})
        assertEquals(PlaylistVodMarker.ENTRY,between[1].metadata.vodMarker)
    }
    @Test fun `group rules reject duplicates unsupported kinds and excessive counts`() {
        for(rules in listOf(mapOf("Film" to PlaylistContentKind.PLAYLIST),mapOf("Film" to PlaylistContentKind.VOD,"FILM" to PlaylistContentKind.LIVE),(1..101).associate {"Group $it" to PlaylistContentKind.VOD})) {
            try {PlaylistReadOptions(groupContent=rules).validated();fail("Invalid rules accepted")}catch(_:PlaylistFormatException){}
        }
    }
}
