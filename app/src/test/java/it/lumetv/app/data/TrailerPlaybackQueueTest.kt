package it.lumetv.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TrailerPlaybackQueueTest {
    private val italian=TmdbTrailer("AAAAAAAAAAA","YouTube","it")
    private val english=TmdbTrailer("BBBBBBBBBBB","YouTube","en",official=true)
    private fun stream(trailer:TmdbTrailer)=TrailerStream(trailer,"https://media.example.org/trailer.mp4","video/mp4")

    @Test fun `unplayable Italian videos fall back to another Italian before English`()=runBlocking {
        val second=italian.copy(key="CCCCCCCCCCC",official=true)
        val tried=mutableListOf<TmdbTrailer>()
        val queue=TrailerPlaybackQueue(listOf(english,italian,second))
        val result=queue.next(TrailerResolver {candidate->
            tried+=candidate
            if(candidate==second)throw TrailerUnavailableException()
            stream(candidate)
        }){}
        assertEquals(listOf(second,italian),tried)
        assertEquals(italian,result!!.trailer)
    }

    @Test fun `English is selected when Italian resolution fails and the label follows playback`()=runBlocking {
        val loading=mutableListOf<String>()
        val queue=TrailerPlaybackQueue(listOf(english,italian))
        val result=queue.next(TrailerResolver {candidate->
            if(candidate.language=="it")throw TrailerUnavailableException()
            stream(candidate)
        }){loading+=it.label}
        assertEquals(listOf("Trailer in italiano","Trailer in inglese"),loading)
        assertEquals("en",result!!.trailer.language)
    }

    @Test fun `playback failure advances to the next candidate without resolving the same video again`()=runBlocking {
        val queue=TrailerPlaybackQueue(listOf(english,italian))
        val tried=mutableListOf<String>()
        val resolver=TrailerResolver {tried+=it.key;stream(it)}
        assertEquals(italian,queue.next(resolver){}!!.trailer)
        assertEquals(english,queue.next(resolver){}!!.trailer)
        assertNull(queue.next(resolver){})
        assertEquals(listOf(italian.key,english.key),tried)
    }

    @Test fun `closing during resolution cancels instead of starting the English fallback`()=runBlocking {
        val started=CompletableDeferred<Unit>()
        val tried=mutableListOf<String>()
        val task=launch {
            TrailerPlaybackQueue(listOf(english,italian)).next(TrailerResolver {
                tried+=it.key;started.complete(Unit);awaitCancellation()
            }){}
        }
        started.await();task.cancelAndJoin()
        assertEquals(listOf(italian.key),tried)
    }

    @Test fun `unsupported hosts do not create a playable trailer button`() {
        assertTrue(TrailerPlaybackQueue.candidates(listOf(italian.copy(site="Vimeo",key="123456789"))).isEmpty())
    }

    @Test fun `resolved media URLs reject lookalike hosts credentials and cleartext`() {
        assertTrue(YoutubeTrailerResolver.safeMediaUrl("https://rr1---sn-a.googlevideo.com/videoplayback?sig=temporary"))
        for(url in listOf("http://rr1.googlevideo.com/video","https://googlevideo.com.example.org/video",
            "https://googlevideo.com@other.example/video","https://user:pass@rr1.googlevideo.com/video",
            "https://www.youtube.com/watch?v=AAAAAAAAAAA"))assertFalse(url,YoutubeTrailerResolver.safeMediaUrl(url))
        assertFalse(stream(italian).toString().contains("https://"))
    }
}
