package it.lumetv.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TmdbTrailersTest {
    @Test fun `Italian comes before official English and official wins within a language`() {
        val english = TmdbTrailer("AAAAAAAAAAA", "YouTube", "en", official = true)
        val italian = TmdbTrailer("BBBBBBBBBBB", "YouTube", "it")
        val officialItalian = TmdbTrailer("CCCCCCCCCCC", "YouTube", "it", official = true)
        assertEquals(listOf(officialItalian, italian, english), TmdbTrailers.ranked(listOf(english, italian, officialItalian)))
        assertEquals("Trailer in italiano", officialItalian.label)
        assertEquals("Trailer in inglese", english.label)
    }

    @Test fun `English is available when there is no Italian trailer`() {
        val english = TmdbTrailer("AAAAAAAAAAA", "YouTube", "en")
        assertEquals(listOf(english), TmdbTrailers.ranked(listOf(english, english.copy(key="BBBBBBBBBBB", language="fr"))))
    }

    @Test fun `clips teasers unsupported languages and malformed video IDs are ignored`() {
        val rows = JSONArray()
        for ((key, site, language, type) in listOf(
            listOf("AAAAAAAAAAA", "YouTube", "it", "Trailer"),
            listOf("BBBBBBBBBBB", "YouTube", "it", "Teaser"),
            listOf("CCCCCCCCCCC", "YouTube", "en", "Clip"),
            listOf("DDDDDDDDDDD", "YouTube", "es", "Trailer"),
            listOf("https://other.example/video", "YouTube", "it", "Trailer"),
            listOf("../path", "Vimeo", "it", "Trailer"),
            listOf("EEEEEEEEEEE", "https://other.example", "it", "Trailer")
        )) rows.put(JSONObject().put("key", key).put("site", site).put("iso_639_1", language).put("type", type))
        assertEquals("AAAAAAAAAAA", TmdbTrailers.parse(rows).single().key)
    }

    @Test fun `a duplicated video retains its preferred language and official metadata`() {
        val video=TmdbTrailer("AAAAAAAAAAA", "YouTube", "en", official=true)
        val italian=video.copy(language="it")
        assertEquals(listOf(italian), TmdbTrailers.ranked(listOf(video,italian.copy(official=false),italian)))
    }

    @Test fun `an Italian candidate after many English entries is retained`() {
        val english=(0..99).map { TmdbTrailer(it.toString().padStart(11,'0'), "YouTube", "en", official=true) }
        val italian=TmdbTrailer("AAAAAAAAAAA", "YouTube", "it")
        val ranked=TmdbTrailers.ranked(english+italian)
        assertEquals(italian,ranked.first())
        assertTrue(ranked.size<=24)
    }

    @Test fun `many Italian candidates cannot discard the English fallback`() {
        val italian=(0..39).map {TmdbTrailer(it.toString().padStart(11,'0'),"YouTube","it")}
        val english=TmdbTrailer("AAAAAAAAAAA","YouTube","en")
        val ranked=TmdbTrailers.ranked(italian+english)
        assertTrue(english in ranked)
        assertEquals("it",ranked.first().language)
        assertTrue(ranked.size<=24)
    }

    @Test fun `metadata survives persistent encoding without turning an empty list into unknown`() {
        val values=listOf(TmdbTrailer("123456789", "Vimeo", "it", "Trailer", true, "2026-01-02T03:04:05Z"),
            TmdbTrailer("AAAAAAAAAAA", "YouTube", "en"))
        assertEquals(values,TmdbTrailers.parse(TmdbTrailers.encode(values)))
        assertTrue(TmdbTrailers.parse(JSONArray()).isEmpty())
        assertTrue(TmdbTrailers.parse(null).isEmpty())
    }
}
