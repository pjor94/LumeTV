package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class LocalplayPlaybackConfigTest {
    private val prefix="inputstream.adaptive."
    private val kid="00112233445566778899aabbccddeeff"
    private val key="ffeeddccbbaa99887766554433221100"
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val staticClearKey=mapOf(prefix+"license_type" to "clearkey",prefix+"license_key" to "$kid:$key")
    private fun route(properties:Map<String,String> = staticClearKey,localplay:PlaylistLocalplay=PlaylistLocalplay.FOOD_NETWORK,
                      mimeType:String?="application/dash+xml",headers:Map<String,String> = emptyMap())=
        PlaylistEntry("Fixture",localplay.address,headers=headers,mimeType=mimeType,properties=properties,localplay=localplay)
    private val hls=ResolvedStream("https://stream.example/live.m3u8","application/x-mpegURL")

    @Before fun reset() {
        File(context.filesDir,"personal-playlists").deleteRecursively()
        context.getSharedPreferences("lume",0).edit().clear().commit()
    }

    @Test fun `all typed routes keep clear HLS despite static ClearKey and DASH hints`() {
        for(localplay in PlaylistLocalplay.entries) {
            val entry=route(staticClearKey+mapOf("mimetype" to "application/dash+xml",prefix+"manifest_type" to "mpd",prefix+"file_type" to "mpd"),localplay)
            assertNull(PersonalPlaybackConfig.validate(entry,false))
            val stream=PersonalPlaybackConfig.parse(entry,false,resolved=hls)
            assertEquals(hls.url,stream.url);assertEquals(hls.mimeType,stream.mimeType)
            assertNull(stream.drm);assertTrue(stream.personal);assertFalse(stream.personalAllowHttp)
        }
    }

    @Test fun `provider Widevine and license headers survive conflicting static ClearKey`() {
        val protection=DrmSpec("https://license.example/provider",mapOf("X-Provider" to "provider-only"),DrmScheme.WIDEVINE)
        val settings=PlaylistSettings(access=mapOf(PlaylistResource.LICENSE to PlaylistAccessRule(headers=mapOf("X-Manual" to "manual"))))
        val entry=route(headers=mapOf("Authorization" to "list-only"))
        val stream=PersonalPlaybackConfig.parse(entry,false,settings,hls.copy(drm=protection))
        assertEquals(DrmScheme.WIDEVINE,stream.drm!!.scheme);assertEquals(protection.licenseUrl,stream.drm!!.licenseUrl)
        assertEquals(mapOf("X-Provider" to "provider-only","X-Manual" to "manual"),stream.drm!!.headers)
        assertNull(stream.drm!!.clearKeyResponse);assertEquals("list-only",stream.headers["Authorization"])
    }

    @Test fun `provider DASH and ClearKey survive conflicting static Widevine and HLS hints`() {
        val direct=PlaylistEntry("Fixture","https://stream.example/live.mpd",properties=staticClearKey)
        val protection=PersonalPlaybackConfig.parse(direct,false).drm!!
        val provider=ResolvedStream(direct.url,"application/dash+xml",drm=protection)
        val entry=route(mapOf(prefix+"license_type" to "widevine",prefix+"license_key" to "https://license.example/old",prefix+"manifest_type" to "hls"),mimeType="application/x-mpegURL")
        val stream=PersonalPlaybackConfig.parse(entry,false,resolved=provider)
        assertEquals("application/dash+xml",stream.mimeType);assertEquals(protection,stream.drm)
    }

    @Test fun `static HLS key templates do not alter provider keys`() {
        val properties=mapOf(prefix+"license_key" to "old=value|X-Old=old||")
        val entry=route(properties)
        val clear=PersonalPlaybackConfig.parse(entry,false,resolved=hls)
        assertTrue(clear.hlsKey!!.parameters.isEmpty());assertNull(clear.hlsKey!!.headers["X-Old"])
        val settings=PlaylistSettings(access=mapOf(PlaylistResource.KEY to PlaylistAccessRule(headers=mapOf("X-Manual" to "manual"))))
        val providerKey=HlsKeySpec("fresh=value",mapOf("X-Provider" to "provider"))
        val encrypted=PersonalPlaybackConfig.parse(entry,false,settings,hls.copy(hlsKey=providerKey))
        assertEquals("fresh=value",encrypted.hlsKey!!.parameters)
        assertEquals(mapOf("X-Provider" to "provider","X-Manual" to "manual"),encrypted.hlsKey!!.headers)
    }

    @Test fun `unused malformed legacy modern and remote DRM declarations do not configure a typed route`() {
        for(properties in listOf(
            staticClearKey,
            mapOf(prefix+"license_key" to "not-a-valid-key"),
            mapOf(prefix+"drm_legacy" to "invalid|unused"),
            mapOf(prefix+"drm" to "invalid-json"),
            mapOf(prefix+"license_type" to "widevine",prefix+"license_key" to "http://license.example/old"),
            mapOf(prefix+"manifest_type" to "unsupported")
        )) {
            val entry=route(properties)
            assertNull(PersonalPlaybackConfig.validate(entry,false))
            assertNull(PersonalPlaybackConfig.parse(entry,false,resolved=hls).drm)
        }
    }

    @Test fun `unresolved format is detected from the provider stream instead of stale list metadata`() {
        val stream=PersonalPlaybackConfig.parse(route(),false,resolved=hls.copy(mimeType=null))
        assertNull(stream.mimeType);assertNull(stream.drm)
    }

    @Test fun `direct URL ClearKey validation and HLS rejection stay active`() {
        val direct=PlaylistEntry("Fixture","https://stream.example/live.mpd",properties=staticClearKey)
        assertEquals(DrmScheme.CLEARKEY,PersonalPlaybackConfig.parse(direct,false).drm!!.scheme)
        for(entry in listOf(PlaylistEntry("Fixture",hls.url,properties=staticClearKey),PlaylistEntry("Fixture",direct.url,properties=mapOf(prefix+"license_key" to "not-a-valid-key")))) {
            try {PersonalPlaybackConfig.parse(entry,false);fail("Invalid direct entry accepted")}
            catch(error:PlaylistFormatException) {assertFalse(error.message.orEmpty().contains(key))}
        }
    }

    @Test fun `an incompatible ClearKey returned by the provider is still rejected`() {
        val direct=PlaylistEntry("Fixture","https://stream.example/live.mpd",properties=staticClearKey)
        val protection=PersonalPlaybackConfig.parse(direct,false).drm
        try {PersonalPlaybackConfig.parse(route(),false,resolved=hls.copy(drm=protection));fail("Provider ClearKey accepted on HLS")}
        catch(error:PlaylistFormatException) {assertTrue(error.message.orEmpty().contains("DASH"))}
    }

    @Test fun `resolved routes still require HTTP consent and validate private request headers`() {
        try {PersonalPlaybackConfig.parse(route(),false,resolved=hls.copy(url="http://stream.example/live.m3u8"));fail("HTTP accepted without consent")}
        catch(_:PlaylistFormatException) {}
        assertTrue(PersonalPlaybackConfig.parse(route(),true,resolved=hls.copy(url="http://stream.example/live.m3u8")).personalAllowHttp)
        try {PersonalPlaybackConfig.parse(route(staticClearKey+mapOf(prefix+"stream_headers" to "Host=private")),false,resolved=hls);fail("Unsafe header accepted")}
        catch(_:PlaylistFormatException) {}
        try {PersonalPlaybackConfig.parse(route(),false);fail("Unresolved command accepted")}
        catch(_:PlaylistFormatException) {}
    }

    @Test fun `a saved Food Network route opens through the shared resolver without rereading the source`()=runTest {
        val repo=PersonalPlaylistRepository(context)
        repo.save("Fixture",repo.prepareFile("#EXTM3U\n#EXTINF:-1,Food Network\n#KODIPROP:${prefix}license_type=clearkey\n#KODIPROP:${prefix}license_key=$kid:$key\nlocalplay=FoodNetwork".toByteArray()))
        val restored=PersonalPlaylistRepository(context);val channel=restored.local().single();val snapshot=restored.playbackEntry(channel.id)
        assertEquals(staticClearKey,snapshot.entry.properties)
        var calls=0
        val provider=object:Transport {
            override suspend fun get(url:String,headers:Map<String,String>):String {
                calls++
                return if("/token" in url)"{\"data\":{\"attributes\":{\"token\":\"fixture-token\"}}}"
                else "{\"settings\":{\"site\":{\"simulcast\":{\"sonicEndpoint\":\"https://provider.example\",\"sonicRealm\":\"it\",\"channelId\":\"6\"}}}}"
            }
            override suspend fun post(url:String,data:JSONObject,headers:Map<String,String>):String {
                calls++
                return "{\"data\":{\"attributes\":{\"streaming\":[{\"type\":\"hls\",\"url\":\"https://stream.example/live.m3u8\",\"protection\":{\"drmEnabled\":false}}]}}}"
            }
        }
        val stream=PersonalPlaylistStreams.initial(snapshot,OkHttpClient(),provider)
        assertEquals(3,calls);assertEquals("application/x-mpegURL",stream.mimeType);assertNull(stream.drm)
        assertEquals(channel.id,restored.local().single().id);assertEquals(staticClearKey,restored.playbackEntry(channel.id).entry.properties)
    }
}
