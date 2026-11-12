package it.lumetv.app.data

import android.util.Base64
import it.lumetv.core.DrmScheme
import it.lumetv.core.PersonalM3uParser
import it.lumetv.core.PlaylistEntry
import it.lumetv.core.PlaylistFormatException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PersonalPlaybackConfigTest {
    private val kid="00112233445566778899aabbccddeeff"
    private val key="ffeeddccbbaa99887766554433221100"
    private val prefix="inputstream.adaptive."
    private fun entry(properties:Map<String,String>,url:String="https://media.example/manifest.mpd") =
        PlaylistEntry("Test",url,headers=mapOf("User-Agent" to "Test Player","Authorization" to "stream-secret"),mimeType="application/dash+xml",properties=properties)
    private fun local(value:String) = PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_type" to "clearkey",prefix+"license_key" to value)),false)
    private fun reject(block:()->Unit) {
        try { block();fail("Invalid private configuration accepted") } catch(error:PlaylistFormatException) {
            assertFalse(error.message.orEmpty().contains(key));assertFalse(error.message.orEmpty().contains("stream-secret"))
        }
    }

    @Test fun `static ClearKey is normalized into a private JWK response`() {
        val stream=local("$kid:$key")
        assertEquals(DrmScheme.CLEARKEY,stream.drm!!.scheme);assertEquals("",stream.drm!!.licenseUrl)
        val json=JSONObject(stream.drm!!.clearKeyResponse!!);val item=json.getJSONArray("keys").getJSONObject(0)
        assertEquals(16,Base64.decode(item.getString("kid"),Base64.URL_SAFE).size)
        assertEquals(16,Base64.decode(item.getString("k"),Base64.URL_SAFE).size)
        assertFalse(stream.toString().contains("stream-secret"));assertFalse(stream.toString().contains(key))
    }
    @Test fun `multiple keys raw JSON and JWK forms have the same normalized response`() {
        val second="11".repeat(16);val secondKey="22".repeat(16)
        val pairs=local("$kid:$key,$second:$secondKey").drm!!.clearKeyResponse!!
        val map=local(JSONObject().put(kid,key).put(second,secondKey).toString()).drm!!.clearKeyResponse!!
        fun decoded(value:String)=JSONObject(value).getJSONArray("keys").let { array -> (0 until array.length()).associate {
            val item=array.getJSONObject(it);item.getString("kid") to item.getString("k")
        } }
        assertEquals(decoded(pairs),decoded(map));assertEquals(decoded(pairs),decoded(local(pairs).drm!!.clearKeyResponse!!))
    }
    @Test fun `legacy and new Kodi DRM properties normalize to ClearKey`() {
        val legacy=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"drm_legacy" to "org.w3.clearkey|$kid:$key")),false)
        val modern=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"drm" to JSONObject().put("org.w3.clearkey",JSONObject().put("license",JSONObject().put("keyids",JSONObject().put(kid,key)))).toString())),false)
        assertEquals(legacy.drm!!.clearKeyResponse,modern.drm!!.clearKeyResponse)
    }
    @Test fun `invalid keys unsupported schemes and deeply nested JSON fail without secrets`() {
        listOf("$kid:short","not-hex:$key","$kid:$key,$kid:${"11".repeat(16)}", "{\"keys\":[]}").forEach { reject { local(it) } }
        reject { PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_type" to "playready",prefix+"license_key" to "https://license.example")),false) }
        reject { PersonalPlaybackConfig.parse(entry(mapOf("exthttp" to "{\"x\":".repeat(18)+"0"+"}".repeat(18))),false) }
    }
    @Test fun `manifest and segment headers have separate precedence and do not leak to another license origin`() {
        val p=mapOf(prefix+"common_headers" to "X-Common=common&User-Agent=Common+Player",
            "exthttp" to "{\"X-Common\":\"json\"}",prefix+"stream_headers" to "X-Stream=segment",
            prefix+"manifest_headers" to "X-Manifest=manifest",prefix+"license_type" to "widevine",
            prefix+"license_key" to "https://license.example/acquire|X-License=license%2Btoken")
        val stream=PersonalPlaybackConfig.parse(entry(p),false)
        assertEquals("json",stream.headers["X-Common"]);assertEquals("segment",stream.headers["X-Stream"])
        assertNull(stream.headers["X-Manifest"]);assertNull(stream.manifestHeaders!!["X-Stream"])
        assertEquals("manifest",stream.manifestHeaders!!["X-Manifest"])
        assertNull(stream.drm!!.headers["Authorization"]);assertNull(stream.drm!!.headers["X-Common"])
        assertEquals("license+token",stream.drm!!.headers["X-License"])
        assertEquals("Common Player",stream.drm!!.headers["User-Agent"])
    }
    @Test fun `legacy stream headers apply to manifests unless an explicit manifest section exists`() {
        val old=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"stream_headers" to "X-Test=yes")),false)
        assertEquals("yes",old.manifestHeaders!!["X-Test"])
        val split=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"stream_headers" to "X-Test=yes",prefix+"manifest_headers" to "")),false)
        assertNull(split.manifestHeaders!!["X-Test"]);assertEquals("yes",split.headers["X-Test"])
    }
    @Test fun `inline headers override common declarations and cannot control framing`() {
        val text="#EXTM3U\n#EXTINF:-1,Test\n#EXTHTTP:{\"User-Agent\":\"JSON\"}\nhttps://media.example/live.mpd|User-Agent=Inline+Player\n"
        val e=PersonalM3uParser.parse(text).entries.single()
        assertEquals("Inline Player",PersonalPlaybackConfig.parse(e,false).headers["User-Agent"])
        reject { PersonalPlaybackConfig.parse(entry(mapOf("exthttp" to "{\"Host\":\"different.example\"}")),false) }
        reject { PersonalPlaybackConfig.parse(entry(mapOf("exthttp" to "{\"X-Test\":\"bad\\r\\nsecret\"}")),false) }
    }
    @Test fun `HTTP licenses need source consent and wrapped license transformations are explicit errors`() {
        val e=entry(mapOf(prefix+"license_type" to "clearkey",prefix+"license_key" to "http://license.example/keys"))
        reject { PersonalPlaybackConfig.parse(e,false) }
        assertEquals("http://license.example/keys",PersonalPlaybackConfig.parse(e,true).drm!!.licenseUrl)
        reject { PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_type" to "widevine",prefix+"license_key" to "https://license.example|X-Test=yes|wrapped-body|wrapped-response")),false) }
    }
}
