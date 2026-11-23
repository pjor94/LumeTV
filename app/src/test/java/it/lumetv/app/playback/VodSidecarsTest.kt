package it.lumetv.app.playback

import org.junit.Assert.*
import org.junit.Test

class VodSidecarsTest {
    @Test fun `relative thumbnail sprites retain their real cue times and crop`() {
        val cues=VodSidecars.previews("WEBVTT\n\n00:00:10.000 --> 00:00:20.000\nimages/one.jpg#xywh=160,90,160,90\n", "https://media.test/story/index.vtt")
        assertEquals(1,cues.size);assertEquals(10_000L,cues[0].start);assertEquals(20_000L,cues[0].end)
        assertEquals("https://media.test/story/images/one.jpg",cues[0].url);assertEquals(listOf(160,90,160,90),cues[0].crop)
    }
    @Test fun `invalid times paths schemes and rectangles are rejected`() {
        for(payload in listOf("file:///secret","https://user:pass@media.test/a.jpg","a.jpg#xywh=-1,0,100,100"))
            assertTrue(VodSidecars.previews("WEBVTT\n\n00:00.000 --> 00:10.000\n$payload", "https://media.test/p.vtt").isEmpty())
        assertTrue(VodSidecars.chapters("WEBVTT\n\n00:70.000 --> 00:80.000\nInvalid").isEmpty())
    }
    @Test fun `chapters are sorted and titles have bounded plain text`() {
        val result=VodSidecars.chapters("WEBVTT\n\n00:30.000 --> 01:00.000\n<b>Secondo</b>\n\n00:00.000 --> 00:30.000\nPrimo\n")
        assertEquals(listOf(VodChapter(0,"Primo"),VodChapter(30_000,"Secondo")),result)
    }
    @Test fun `sidecars cannot allocate an unbounded index`() {
        assertTrue(VodSidecars.chapters("WEBVTT\n"+"x".repeat(512*1024)).isEmpty())
    }
}
