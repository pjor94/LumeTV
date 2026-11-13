package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class VodMetadataTest {
    private fun item(name:String,attrs:String="",url:String="https://example.org/video.mp4")=PersonalM3uParser.parse("#EXTM3U\n#EXTINF:-1 $attrs,$name\n$url").entries.single()
    @Test fun `explicit presentation metadata preserves playback identity and original names`() {
        val entry=item("Un film (2023)","type=\"movie\" plot=\"Una storia\" genre=\"Dramma,Avventura\" year=\"2023\" poster=\"https://example.org/poster.jpg\"")
        val value=VodMetadata.from(entry)
        assertEquals(VodKind.MOVIE,value.kind);assertEquals(entry.name,value.title);assertEquals(2023,value.year)
        assertEquals(listOf("Dramma","Avventura"),value.genres);assertEquals("Una storia",value.description)
        assertEquals("https://example.org/video.mp4",entry.url);assertFalse(value.toString().contains("poster.jpg"))
    }
    @Test fun `season and episode names group only named series`() {
        val one=VodMetadata.from(item("Orbita S01E02 - Il ritorno","type=\"series\""))
        val two=VodMetadata.from(item("Orbita 2x03 La partenza","type=\"series\""))
        assertEquals("Orbita",one.seriesTitle);assertEquals(one.seriesKey,two.seriesKey)
        assertEquals(1,one.season);assertEquals(2,one.episode);assertEquals(2,two.season);assertEquals(3,two.episode)
    }
    @Test fun `explicit series attributes work without a filename pattern`() {
        val value=VodMetadata.from(item("Il ritorno","type=\"episode\" series-name=\"Orbita\" season=\"2\" episode=\"4\""))
        assertEquals(VodKind.EPISODE,value.kind);assertEquals("Orbita",value.seriesTitle);assertEquals(2,value.season);assertEquals(4,value.episode)
    }
    @Test fun `unnamed provider episodes remain individual videos without invented series`() {
        val value=VodMetadata.from(item("Episodio sconosciuto",url="https://example.org/series/u/p/123.mkv"))
        assertEquals(VodKind.EPISODE,value.kind);assertTrue(value.seriesKey.isEmpty());assertNull(value.season)
    }
    @Test fun `movie routes and generic vod remain distinct`() {
        assertEquals(VodKind.MOVIE,VodMetadata.from(item("Film",url="https://example.org/movie/u/p/1.mp4")).kind)
        assertEquals(VodKind.VIDEO,VodMetadata.from(item("Concerto","type=\"vod\"")).kind)
        assertEquals("",VodMetadata.from(item("Film S1 senza episodio","type=\"vod\"")).seriesKey)
    }
    @Test fun `artwork resolves against the original document and non web schemes are ignored`() {
        val entry=PersonalM3uParser.parse("#EXTM3U\n#EXTINF:-1 type=\"movie\" poster=\"covers/one.jpg\" backdrop=\"javascript:bad\",Film\nvideo.mp4",baseUrl="https://example.org/list/catalog.m3u").entries.single()
        val value=VodMetadata.from(entry);assertEquals("https://example.org/list/covers/one.jpg",value.poster);assertTrue(value.backdrop.isEmpty())
    }
    @Test fun `display metadata is bounded and no duration is invented`() {
        val value=VodMetadata.from(item("Titolo","type=\"vod\" year=\"9999\" description=\"<b>Una storia</b>\""))
        assertNull(value.year);assertEquals(0L,value.duration);assertEquals("Una storia",value.description)
    }
    @Test fun `group presentation hints do not change live versus vod classification`() {
        val entry=item("Un film","group-title=\"IT | Film UHD\"")
        assertEquals(PlaylistContentKind.LIVE,entry.metadata.contentKind)
        assertEquals(VodKind.MOVIE,VodMetadata.from(entry).kind)
        assertEquals(VodKind.VIDEO,VodMetadata.from(item("Concerto","type=\"vod\" group-title=\"Musica\"")).kind)
    }
    @Test fun `explicit movie type wins over ambiguous series names and groups`() {
        val value=VodMetadata.from(item("Una storia S01E01","type=\"movie\" group-title=\"Serie\""))
        assertEquals(VodKind.MOVIE,value.kind);assertTrue(value.seriesKey.isEmpty())
    }
    @Test fun `explicit series ids survive a presentation title change`() {
        val one=VodMetadata.from(item("Episodio","type=\"episode\" series-name=\"Orbita\" series-id=\"123\""))
        val two=VodMetadata.from(item("Puntata","type=\"episode\" series-name=\"Orbita - versione italiana\" series-id=\"123\""))
        assertEquals(one.seriesKey,two.seriesKey)
    }
}
