package it.lumetv.core

import org.junit.Assert.*
import org.junit.Test

class VodLookupTest {
    private fun movie(title:String,attributes:String="")=PersonalM3uParser.parse("#EXTM3U\n#EXTINF:-1 type=\"movie\" $attributes,$title\nhttps://example.org/1.mp4").entries.single()
    @Test fun `technical labels are removed while title numbers and It survive`() {
        assertEquals("Dune",VodTitleMatcher.from(movie("IT | Dune (2021) [4K]"))!!.title)
        assertEquals(2021,VodTitleMatcher.from(movie("IT | Dune (2021) [4K]"))!!.year)
        assertEquals("It",VodTitleMatcher.from(movie("It (2017)"))!!.title)
        assertEquals("It: Capitolo due",VodTitleMatcher.from(movie("It: Capitolo due (2019)"))!!.title)
        assertEquals("1917",VodTitleMatcher.from(movie("1917"))!!.title)
        assertNull(VodTitleMatcher.from(movie("1917"))!!.year)
        assertEquals("Blade Runner 2049",VodTitleMatcher.from(movie("Blade Runner 2049"))!!.title)
        assertNull(VodTitleMatcher.from(movie("Blade Runner 2049"))!!.year)
    }
    @Test fun `series lookup uses show title and never episode release year or provider series id`() {
        val entry=PersonalM3uParser.parse("#EXTM3U\n#EXTINF:-1 type=\"episode\" year=\"2024\" series-id=\"123\",Orbita S03E02 - Il ritorno\nhttps://example.org/2.mkv").entries.single()
        val query=VodTitleMatcher.from(entry)!!
        assertEquals(VodMediaType.TV,query.type);assertEquals("Orbita",query.title);assertNull(query.year);assertNull(query.tmdbId)
    }
    @Test fun `ambiguous exact names require a year or a manual choice`() {
        val query=VodLookupQuery("Dune",VodMediaType.MOVIE)
        val old=VodTitleCandidate(1,VodMediaType.MOVIE,"Dune",year=1984)
        val new=VodTitleCandidate(2,VodMediaType.MOVIE,"Dune",year=2021)
        assertNull(VodTitleMatcher.automatic(query,listOf(old,new)))
        assertEquals(new,VodTitleMatcher.automatic(query.copy(year=2021),listOf(old,new)))
        assertNull(VodTitleMatcher.automatic(query.copy(year=2000),listOf(old,new)))
    }
    @Test fun `translations accents and original names are compared without accepting fuzzy matches`() {
        val query=VodLookupQuery("Citta perduta",VodMediaType.MOVIE,2020)
        val candidate=VodTitleCandidate(2,VodMediaType.MOVIE,"Città perduta","Lost City",2020)
        assertEquals(candidate,VodTitleMatcher.automatic(query,listOf(candidate)))
        assertNull(VodTitleMatcher.automatic(query,listOf(candidate.copy(title="Città perduta 2"))))
        assertNull(VodTitleMatcher.automatic(query,listOf(candidate.copy(type=VodMediaType.TV))))
    }
    @Test fun `explicit movie identifiers are retained independently from playback data`() {
        val query=VodTitleMatcher.from(movie("Film", "tmdb-id=\"42\" imdb-id=\"tt0068646\""))!!
        assertEquals(42,query.tmdbId);assertEquals("tt0068646",query.imdbId)
        assertFalse(query.toString().contains("Film"));assertEquals(64,query.fingerprint.length)
    }
}
