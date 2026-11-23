package it.lumetv.app.data

import it.lumetv.app.BuildConfig
import it.lumetv.core.VodLookupQuery
import it.lumetv.core.VodMediaType
import it.lumetv.core.VodTitleMatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Explicit opt-in. The report contains public metadata and never includes credentials. */
class TmdbLiveTest {
    @Test fun `real movie and series searches return Italian metadata cast and downloadable artwork`()=runBlocking {
        assumeTrue(System.getProperty("lume.live")=="true")
        val api=TmdbClient(BuildConfig.TMDB_READ_ACCESS_TOKEN)
        assertTrue("Configure the project's TMDB read access token before running the live check",api.configured)
        val rows=JSONArray()
        val artwork=LogoFetcher(OkHttpClient(),allowed={it.isHttps && it.host=="image.tmdb.org"},
            redirectAllowed={_,to->to.isHttps && to.host=="image.tmdb.org"})
        for(query in listOf(VodLookupQuery("Il padrino",VodMediaType.MOVIE,1972),VodLookupQuery("Breaking Bad",VodMediaType.TV,2008))) {
            val candidates=api.search(query)
            val match=VodTitleMatcher.automatic(query,candidates)
            assertNotNull("No unambiguous match for the public test title",match)
            val details=api.details(query.type,match!!.id)
            assertEquals(query.year,details.year)
            assertEquals("it",details.overviewLanguage)
            assertTrue(details.overview.isNotBlank())
            assertTrue(details.cast.isNotEmpty())
            assertTrue(details.directors.isNotEmpty())
            val image=TmdbClient.imageUrl(details.posterPath)
            assertNotNull("Missing poster for the public test title",image)
            val bytes=artwork.fetch(image!!.toHttpUrl()).bytes
            assertTrue("The public poster download is empty",bytes!=null && bytes.isNotEmpty())
            rows.put(JSONObject().put("type",query.type.apiName).put("id",details.id).put("title",details.title)
                .put("year",details.year).put("overview_language",details.overviewLanguage).put("overview_present",true)
                .put("cast_count",details.cast.size).put("director_or_creator_count",details.directors.size)
                .put("poster_bytes",bytes!!.size))
        }
        File(requireNotNull(System.getProperty("lume.liveReport"))).parentFile.resolve("tmdb-live.json").apply {
            parentFile?.mkdirs()
            writeText(JSONObject().put("checked_at_utc",Instant.now().toString()).put("passed",true).put("titles",rows).toString(2)+"\n")
        }
        Unit
    }
}
