package it.lumetv.app.data

import it.lumetv.app.BuildConfig
import it.lumetv.core.VodMediaType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Explicit network opt-in; reports contain public titles and statuses, never signed URLs. */
class TrailerLiveTest {
    @Test fun `real movie and series trailers resolve to readable native media`()=runBlocking {
        assumeTrue(System.getProperty("lume.live")=="true")
        val api=TmdbClient(BuildConfig.TMDB_READ_ACCESS_TOKEN)
        assertTrue("Configure the project's TMDB token before running the live check",api.configured)
        val http=OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()
        fun readable(url:String):Int {
            val request=Request.Builder().url(url)
                .header("User-Agent",if(YoutubeParsingHelper.isVisionOsStreamingUrl(url))YoutubeParsingHelper.getVisionOsUserAgent(null) else YoutubeTrailerResolver.USER_AGENT)
                .header("Range","bytes=0-1023").header("TE","trailers")
                .post(byteArrayOf(0x78,0).toRequestBody())
            if(YoutubeParsingHelper.isWebStreamingUrl(url))request.header("Origin","https://www.youtube.com").header("Referer","https://www.youtube.com")
            return http.newCall(request.build()).execute().use {
            if(!it.isSuccessful)return@use it.code
            if(it.body?.byteStream()?.read(ByteArray(1024))?.let {count->count>0}==true)200 else 0
            }
        }
        val rows=JSONArray()
        for((kind,id) in listOf(VodMediaType.MOVIE to 157336,VodMediaType.TV to 66732)) {
            val details=api.details(kind,id)
            val queue=TrailerPlaybackQueue(details.trailers)
            var tries=0
            val selected=withTimeout(90_000) {
                var found:TrailerStream?=null
                while(found==null) {
                    val candidate=queue.next(YoutubeTrailerResolver){} ?: break
                    tries++
                    val videoStatus=readable(candidate.videoUrl)
                    val audioStatus=candidate.audioUrl?.let(::readable) ?: 200
                    if(videoStatus==200 && audioStatus==200)found=candidate
                }
                found
            }
            assertNotNull("No readable native trailer for public test title ${details.title}",selected)
            rows.put(JSONObject().put("type",kind.apiName).put("title",details.title)
                .put("language",selected!!.trailer.language).put("separate_audio",selected.audioUrl!=null)
                .put("video_id",selected.trailer.key).put("dash_segments",selected.videoManifest!=null && selected.audioManifest!=null)
                .put("playable_candidates_checked",tries))
        }
        File(requireNotNull(System.getProperty("lume.liveReport"))).parentFile.resolve("trailer-live.json").apply {
            parentFile?.mkdirs()
            writeText(JSONObject().put("checked_at_utc",Instant.now().toString()).put("passed",true).put("titles",rows).toString(2)+"\n")
        }
        Unit
    }
}
