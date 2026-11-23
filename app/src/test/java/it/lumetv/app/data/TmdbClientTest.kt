package it.lumetv.app.data

import it.lumetv.core.*
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TmdbClientTest {
    private fun client(server:MockWebServer,http:OkHttpClient=OkHttpClient())=TmdbClient("fixture-token",http,server.url("/3/"))
    private val movie="""{"id":42,"title":"Orizzonte","overview":"Una storia italiana","release_date":"2023-04-01","runtime":122,"poster_path":"/poster.jpg","credits":{"cast":[{"name":"Anna Rossi"},{"name":"Luca Verdi"}],"crew":[{"name":"Ada Bianchi","job":"Director"},{"name":"Altri","job":"Producer"}]},"genres":[{"name":"Avventura"}]}"""
    @Test fun `search sends only presentation fields and application authentication`()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("""{"results":[{"id":42,"title":"Orizzonte","original_title":"Horizon","release_date":"2023-01-01"}]}"""))
            val rows=client(server).search(VodLookupQuery("Orizzonte",VodMediaType.MOVIE,2023))
            assertEquals(42,rows.single().id)
            val request=server.takeRequest(1,TimeUnit.SECONDS)!!
            assertEquals("Bearer fixture-token",request.getHeader("Authorization"))
            assertEquals("it-IT",request.requestUrl!!.queryParameter("language"))
            assertEquals("2023",request.requestUrl!!.queryParameter("year"))
            assertEquals("false",request.requestUrl!!.queryParameter("include_adult"))
            assertFalse(request.path!!.contains("fixture-token"));assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun `movie details parse Italian overview actors director and editorial runtime`()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(movie))
            val details=client(server).details(VodMediaType.MOVIE,42)
            assertEquals("Una storia italiana",details.overview);assertEquals("it",details.overviewLanguage)
            assertEquals(listOf("Anna Rossi","Luca Verdi"),details.cast);assertEquals(listOf("Ada Bianchi"),details.directors)
            assertEquals(122,details.runtimeMinutes)
            val url=server.takeRequest().requestUrl!!
            assertEquals("credits,videos",url.queryParameter("append_to_response"))
            assertEquals("it,en",url.queryParameter("include_video_language"))
        }
    }
    @Test fun `series aggregate cast is distinct from creators and has no invented runtime`()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("""{"id":7,"name":"Orbita","overview":"Una serie","first_air_date":"2020-02-01","in_production":true,"created_by":[{"name":"Ada Bianchi"}],"aggregate_credits":{"cast":[{"name":"Anna Rossi"},{"name":"Anna Rossi"}]}}"""))
            val details=client(server).details(VodMediaType.TV,7)
            assertEquals(listOf("Anna Rossi"),details.cast);assertEquals(listOf("Ada Bianchi"),details.directors)
            assertEquals(0,details.runtimeMinutes);assertTrue(details.inProduction)
            assertEquals("aggregate_credits,videos",server.takeRequest().requestUrl!!.queryParameter("append_to_response"))
        }
    }
    @Test fun `missing Italian overview falls back to English while preserving Italian genres`()=runBlocking {
        MockWebServer().use {server->
            server.enqueue(MockResponse().setBody(movie.replace("Una storia italiana","")))
            server.enqueue(MockResponse().setBody("""{"id":42,"overview":"An English story"}"""))
            val details=client(server).details(VodMediaType.MOVIE,42)
            assertEquals("en",details.overviewLanguage);assertEquals("An English story",details.overview)
            assertEquals(listOf("Avventura"),details.genres)
            server.takeRequest();assertEquals("en-US",server.takeRequest().requestUrl!!.queryParameter("language"))
        }
    }
    @Test fun `trailers are requested with details and survive the English overview fallback`()=runBlocking {
        MockWebServer().use {server->
            val body=JSONObject(movie).put("overview","").put("videos",JSONObject().put("results",JSONArray()
                .put(JSONObject().put("key","AAAAAAAAAAA").put("site","YouTube").put("iso_639_1","en").put("type","Trailer").put("official",true))
                .put(JSONObject().put("key","BBBBBBBBBBB").put("site","YouTube").put("iso_639_1","it").put("type","Trailer"))))
            server.enqueue(MockResponse().setBody(body.toString()))
            server.enqueue(MockResponse().setBody("""{"id":42,"overview":"An English story"}"""))
            val details=client(server).details(VodMediaType.MOVIE,42)
            assertEquals(listOf("it","en"),details.trailers.map {it.language})
            assertTrue(details.trailersLoaded)
            assertEquals("en",details.overviewLanguage)
            assertEquals(2,server.requestCount)
        }
    }
    @Test fun `429 pauses subsequent requests and 401 does not retry on every title`()=runBlocking {
        for(status in listOf(429,401))MockWebServer().use {server->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After","60"))
            val api=client(server)
            repeat(2) {assertEquals(status,(runCatching {api.details(VodMediaType.MOVIE,42)}.exceptionOrNull() as TmdbException).status)}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun `redirects are refused before sending the project token to a different destination`()=runBlocking {
        MockWebServer().use {server->MockWebServer().use {other->
            other.start();server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/steal")))
            assertEquals(302,(runCatching {client(server).details(VodMediaType.MOVIE,42)}.exceptionOrNull() as TmdbException).status)
            assertEquals(0,other.requestCount)
        }}
    }
    @Test fun `leaving during a slow response body cancels the call`()=runBlocking {
        MockWebServer().use {server->
            val call=AtomicReference<Call>()
            val http=OkHttpClient.Builder().eventListener(object:EventListener(){override fun callStart(value:Call){call.set(value)}}).build()
            server.enqueue(MockResponse().setBody(movie).setBodyDelay(2,TimeUnit.SECONDS))
            val task=launch {client(server,http).details(VodMediaType.MOVIE,42)}
            withContext(Dispatchers.IO){assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))}
            task.cancelAndJoin();assertTrue(call.get().isCanceled())
        }
    }
    @Test fun `image paths cannot introduce hosts queries or traversal`() {
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg",TmdbClient.imageUrl("/poster.jpg"))
        for(path in listOf("https://other.org/a.jpg","//other.org/a.jpg","/../a.jpg","/a.jpg?key=secret",""))assertNull(TmdbClient.imageUrl(path))
    }
}
