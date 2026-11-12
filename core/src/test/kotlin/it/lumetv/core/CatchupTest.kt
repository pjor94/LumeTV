package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CatchupTest {
    private val now=Instant.parse("2026-10-25T04:00:00Z").toEpochMilli()
    private val event=ArchiveEvent(now-3_600_000,now-1_800_000,"id/a+b")
    private val live="https://provider.example/live/user/pass/42.ts?sig=a%2Bb+c&x=1"
    private fun spec(mode:String,template:String="",extra:Map<String,String> = emptyMap())=CatchupUrls.spec(PlaylistMetadata(mapOf("catchup" to mode,"catchup-days" to "7","catchup-source" to template)+extra))!!
    @Test fun `disabled unknown unsupported modes and missing declarations are explicit`() {
        assertNull(CatchupUrls.spec(PlaylistMetadata()));assertNull(CatchupUrls.spec(PlaylistMetadata(mapOf("catchup" to "disabled"))))
        try{spec("unknown");fail("unknown mode accepted")}catch(_:PlaylistFormatException){}
        assertEquals(CatchupMode.SHIFT,CatchupUrls.spec(PlaylistMetadata(mapOf("timeshift" to "7")))!!.mode)
        assertEquals(7.0,CatchupUrls.spec(PlaylistMetadata(mapOf("catchup" to "shift","catchup-time" to "604800")))!!.days,0.0)
    }
    @Test fun `availability is bounded and ongoing programmes need explicit consent`() {
        val s=spec("shift")
        assertTrue(CatchupUrls.available(s,event,now));assertFalse(CatchupUrls.available(s,ArchiveEvent(now-8*86_400_000L,now-7*86_400_000L),now))
        val ongoing=ArchiveEvent(now-1000,now+60_000)
        assertFalse(CatchupUrls.available(s,ongoing,now));assertTrue(CatchupUrls.available(s,ongoing,now,true))
        assertFalse(CatchupUrls.available(s,ArchiveEvent(now+1000,now+2000),now,true))
        assertFalse(CatchupUrls.available(spec("vod"),ArchiveEvent(event.start,event.end),now))
    }
    @Test fun `append and shift preserve signed query bytes and encode only event values`() {
        val url=CatchupUrls.build(live,spec("append","&start={utc}&end={utcend}&d={duration:60}&id={catchup-id}"),event,now).url
        assertTrue(url.startsWith(live+"&start="));assertTrue(url.endsWith("&d=30&id=id%2Fa%2Bb"))
        assertEquals(live+"&utc=${event.start/1000}&lutc=${now/1000}",CatchupUrls.build(live,spec("shift"),event,now).url)
    }
    @Test fun `default templates offsets buffers and correction are applied once`() {
        val s=spec("default","../replay.mp4?start={start}&end={end}&offset={offset:60}",mapOf("catchup-correction" to "-1"))
        val result=CatchupUrls.build(live,s,event,now,60_000,startBufferSeconds=30,endBufferSeconds=45)
        assertEquals(1_875_000L,result.duration);assertEquals(60_000L,result.offset);assertTrue(result.canRegenerate)
        assertTrue(result.url.contains("start=${(event.start-30_000+60_000-3_600_000)/1000}"))
        assertTrue(result.url.contains("end=${(event.end+45_000-3_600_000)/1000}"))
    }
    @Test fun `flussonic transports retain authentication and construct bounded archive addresses`() {
        val hls=CatchupUrls.build(live,spec("flussonic"),event,now)
        assertEquals("https://provider.example/live/user/pass/timeshift_rel-3600.m3u8?sig=a%2Bb+c&x=1",hls.url)
        assertTrue(CatchupUrls.build(live,spec("flussonic-ts"),event,now).url.contains("timeshift_abs-${event.start/1000}.ts?sig=a%2Bb+c"))
        assertTrue(CatchupUrls.build(live,spec("flussonic-dash"),event,now).url.contains("archive-${event.start/1000}-1800.mpd"))
    }
    @Test fun `Xtream uses provider zone including DST and rounds minute duration`() {
        val s=spec("xc");val zone=ZoneId.of("Europe/Rome")
        val first=ArchiveEvent(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),Instant.parse("2026-10-25T01:00:01Z").toEpochMilli())
        val a=CatchupUrls.build(live,s,first,now,zone=zone)
        assertTrue(a.url.contains("/timeshift/user/pass/31/2026-10-25:02-30/42.ts?sig=a%2Bb+c&x=1"))
        val second=ArchiveEvent(first.start+3_600_000,first.end+3_600_000)
        assertTrue(CatchupUrls.build(live,s,second,now,zone=zone).url.contains("/2026-10-25:02-30/"))
    }
    @Test fun `formatted placeholders and event URLs never evaluate expressions`() {
        val s=spec("default","https://archive.example/{Y}/{m}/{d}/{H}-{M}-{S}.mp4?start=\${start:Y-m-dTH:M:S}")
        assertEquals("https://archive.example/2026/10/25/03-00-00.mp4?start=2026-10-25T03:00:00",CatchupUrls.build(live,s,event,now,zone=ZoneId.of("UTC")).url)
        val url="https://archive.example/movie.mp4?sig=a%2Bb+c"
        assertEquals(url,CatchupUrls.build(live,spec("vod"),ArchiveEvent(event.start,event.end,url),now).url)
        for(t in listOf("https://example.com/{unknown}","file:///tmp/private","https://u:p@example.com/movie","javascript:foo")) {
            try{CatchupUrls.build(live,spec("default",t),event,now);fail("unsafe template accepted: $t")}catch(_:PlaylistFormatException){}
        }
    }
    @Test fun `relative XMLTV defaults keep their declaring document base across includes`() {
        val entries=mutableListOf<PlaylistEntry>()
        PersonalM3uParser.scan(java.io.StringReader("#EXTM3U url-tvg=\"guide.xml.gz?sig=a%2Bb+c\"\n#EXTINF:-1 type=\"playlist\",Child\nhttps://cdn.example/child.m3u"),"https://provider.example/lists/root.m3u",
            include={PlaylistDocument(java.io.StringReader("#EXTM3U\n#EXTINF:-1,One\nhttps://example.com/live"),it,{})},accept={entries+=it;null})
        assertEquals("https://provider.example/lists/guide.xml.gz?sig=a%2Bb+c",entries.single().metadata["url-tvg"])
    }
}
