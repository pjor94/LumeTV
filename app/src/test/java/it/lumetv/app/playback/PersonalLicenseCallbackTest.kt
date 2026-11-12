package it.lumetv.app.playback

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.exoplayer.drm.ExoMediaDrm
import it.lumetv.app.data.PersonalPlaybackConfig
import it.lumetv.app.data.PersonalRequestContext
import it.lumetv.app.data.PlaylistResource
import it.lumetv.app.data.PlaylistSettings
import it.lumetv.core.PlaylistEntry
import it.lumetv.core.PlaylistFormatException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PersonalLicenseCallbackTest {
    private val prefix="inputstream.adaptive."
    private fun entry(properties:Map<String,String>)=PlaylistEntry("Test","https://media.example/manifest.mpd",mimeType="application/dash+xml",properties=properties)
    @Test fun `legacy four field licenses preserve raw challenge response and GET semantics`() {
        for(get in listOf(false,true))MockWebServer().use {server->
            server.enqueue(MockResponse().setBody("synthetic-license"))
            val url=server.url("/license").toString()
            val config=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_type" to "widevine",prefix+"license_key" to "$url|X-License=fixture|${if(get)"" else "R{SSM}"}|R")),true).drm!!
            val context=PersonalRequestContext(PlaylistSettings(),true,url)
            val callback=PersonalLicenseCallback(config,context.client(OkHttpClient(),url,PlaylistResource.LICENSE,config.headers.keys))
            val response=callback.executeKeyRequest(C.WIDEVINE_UUID,ExoMediaDrm.KeyRequest("synthetic-challenge".toByteArray(),url))
            assertEquals("synthetic-license",response.data.toString(Charsets.UTF_8))
            val request=server.takeRequest();assertEquals(if(get)"GET" else "POST",request.method)
            assertEquals(if(get)"" else "synthetic-challenge",request.body.readUtf8());assertEquals("fixture",request.getHeader("X-License"))
        }
    }
    @Test fun `legacy JSON Base64 response is unwrapped through the real HTTP callback`() {
        MockWebServer().use {server->
            val encoded=Base64.encodeToString("synthetic-license".toByteArray(),Base64.NO_WRAP)
            server.enqueue(MockResponse().setBody(JSONObject().put("license",encoded).toString()))
            val url=server.url("/license").toString()
            val config=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_type" to "widevine",prefix+"license_key" to "$url||b{SSM}|JBlicense")),true).drm!!
            val client=PersonalRequestContext(PlaylistSettings(),true,url).client(OkHttpClient(),url,PlaylistResource.LICENSE,emptySet())
            val result=PersonalLicenseCallback(config,client).executeKeyRequest(C.WIDEVINE_UUID,ExoMediaDrm.KeyRequest("challenge".toByteArray(),url))
            assertEquals("synthetic-license",result.data.toString(Charsets.UTF_8))
            assertEquals(Base64.encodeToString("challenge".toByteArray(),Base64.NO_WRAP),server.takeRequest().body.readUtf8())
        }
    }
    @Test fun `modern JSON request and ordered response wrappers are explicit and bounded`() {
        val data=Base64.encodeToString("{\"challenge\":\"{CHA-B64}\"}".toByteArray(),Base64.NO_WRAP)
        val json=JSONObject().put("com.widevine.alpha",JSONObject().put("license",JSONObject().put("server_url","https://license.example")
            .put("req_data",data).put("wrapper","base64").put("unwrapper","base64,json,base64")
            .put("unwrapper_params",JSONObject().put("path_data","response/data"))))
        val config=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"drm" to json.toString())),false).drm!!
        val body=LicenseTransforms.request("challenge".toByteArray(),config.request)
        assertEquals("Y2hhbGxlbmdl",JSONObject(Base64.decode(body,Base64.DEFAULT).toString(Charsets.UTF_8)).getString("challenge"))
        val inner=JSONObject().put("response",JSONObject().put("data",Base64.encodeToString("license".toByteArray(),Base64.NO_WRAP)))
        assertEquals("license",LicenseTransforms.response(Base64.encode(inner.toString().toByteArray(),Base64.NO_WRAP),config.request).toString(Charsets.UTF_8))
        try {LicenseTransforms.response(ByteArray(LicenseTransforms.MAX_BYTES+1),config.request);fail("Oversized response accepted")}catch(_:java.io.IOException){}
    }
    @Test fun `known unimplemented DRM properties are rejected instead of silently ignored`() {
        for(name in listOf("server_certificate","pre_init_data","license_data","license_flags")) {
            try {PersonalPlaybackConfig.parse(entry(mapOf(prefix+name to "private-fixture")),false);fail("Unsupported property ignored")}
            catch(error:PlaylistFormatException){assertFalse(error.message.orEmpty().contains("private-fixture"))}
        }
    }
    @Test fun `HLS key syntax has a separate header scope and unseparated hex stays explicit`() {
        val hls=PlaylistEntry("AES","https://media.example/master.m3u8",mimeType="application/x-mpegURL",
            properties=mapOf(prefix+"license_key" to "?token=a%2Bb|X-Key=fixture||"))
        val stream=PersonalPlaybackConfig.parse(hls,false)
        assertNull(stream.drm);assertEquals("token=a%2Bb",stream.hlsKey!!.parameters);assertEquals("fixture",stream.hlsKey!!.headers["X-Key"])
        try {PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_type" to "clearkey",prefix+"license_key" to "11".repeat(32))),false);fail("Ambiguous concatenated hex accepted")}
        catch(error:PlaylistFormatException){assertTrue(error.message.orEmpty().contains("identificativo:chiave"))}
    }
}
