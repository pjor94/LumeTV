package it.lumetv.app.data

import it.lumetv.core.Channel
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResolverTest {
    @Test fun `SMIL namespaced references preserve DRM PID and unescape URL`() {
        val smil = """<smil xmlns="http://www.w3.org/2005/SMIL21/Language"><body><seq><switch>
            <ref src="https://cdn.example/live.mpd?a=1&amp;b=2" security="commonEncryption">
            <param name="trackingData" value="foo|pid=channel-pid|bar"/></ref>
            </switch></seq></body></smil>"""
        val (url, pid) = parseSmil(smil)
        assertEquals("https://cdn.example/live.mpd?a=1&b=2", url)
        assertEquals("channel-pid", pid)
    }

    @Test(expected = SourceException::class) fun `encrypted SMIL without PID fails before playback`() {
        parseSmil("""<smil><ref src="https://example.org/live.mpd" security="commonEncryption"/></smil>""")
    }

    @Test(expected = SourceException::class) fun `SMIL never resolves external entities`() {
        parseSmil("""<!DOCTYPE smil [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><smil>&xxe;</smil>""")
    }

    @Test fun `Rai 4K selects nested 4K service and Radio 2 selects visual feed`() {
        val json = JSONObject("""{"Configuration":{"Editors":{"Editor":[{"Services":[{"delivery":"3","Service":[
            {"name":"Rai 1","url":"https://example/rai1"},{"name":"Rai 4K","url":"https://example/4k"},
            {"name":"Rai Radio 2","url":"https://example/audio"},{"name":"Rai Radio 2 Visual","url":"https://example/visual"}
            ]}]}]}}}""")
        assertEquals("https://example/4k", RaiServices.find(json, channel("rai", "Rai 4K")))
        assertEquals("https://example/visual", RaiServices.find(json, channel("rai", "Rai Radio 2")))
    }

    @Test fun `TGR uses region identifier instead of first Rai 3 service`() {
        val json = JSONObject("""{"Configuration":{"Editors":{"Editor":[{"Services":[{"delivery":"3","Service":[
            {"name":"Rai 3","dvbTriplet":"0.0.300","url":"https://example/general"},
            {"name":"Rai 3 TGR Lombardia","dvbTriplet":"0.0.304","url":"https://example/lombardia"}
            ]}]}]}}}""")
        assertEquals("https://example/lombardia", RaiServices.find(json, channel("rainewsrg", "lombardia")))
        assertNull(RaiServices.find(json, channel("rainewsrg", "missing")))
    }

    @Test fun `Mediaset resolves different channel codes while reusing anonymous session`() = runTest {
        val requestedCodes = mutableListOf<String>()
        var logins = 0
        val transport = object : Transport {
            override suspend fun get(url: String, headers: Map<String, String>): String = if (url == "https://mediasetinfinity.mediaset.it") {
                """<meta name="app-name" content="mediaset-test">"""
            } else """<smil><body><ref src="https://example/live.mpd" security="commonEncryption"><param value="a|pid=PID|z"/></ref></body></smil>"""
            override suspend fun post(url: String, data: JSONObject, headers: Map<String, String>): String {
                return if (url.contains("anonymous/login")) { logins++; """{"response":{"sid":"SID","beToken":"TOKEN"}}""" }
                else { requestedCodes += data.getString("channelCode"); """{"response":{"mediaSelector":{"url":"https://selector.example/smil","format":"SMIL"}}}""" }
            }
        }
        val resolver = MediasetResolver(transport)
        for (code in listOf("C5", "I1", "R4", "LB")) {
            val stream = resolver.resolve(channel("mediaset", "$$code"))
            assertEquals("https://example/live.mpd", stream.url)
            assertTrue(stream.drm!!.licenseUrl.contains("releasePid=PID"))
        }
        assertEquals(listOf("C5", "I1", "R4", "LB"), requestedCodes)
        assertEquals(1, logins)
    }

    private fun channel(broadcaster: String, key: String) = Channel("id", key, 1, "", "", "", "", broadcaster, key)
}
