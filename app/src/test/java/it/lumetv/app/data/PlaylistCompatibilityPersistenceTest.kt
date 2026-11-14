package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class PlaylistCompatibilityPersistenceTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val prefix="inputstream.adaptive."
    private val kid="00112233445566778899aabbccddeeff"
    private val key="ffeeddccbbaa99887766554433221100"
    private val directory get()=File(context.filesDir,"personal-playlists")
    private fun entry(p:Map<String,String>,url:String="https://example.test/live.mpd")=PlaylistEntry("Fixture",url,properties=p)
    private fun rejects(block:()->Unit) {try {block();fail("Invalid configuration accepted")}catch(e:PlaylistFormatException){assertFalse(e.message.orEmpty().contains(key))}}
    @Before fun reset(){directory.deleteRecursively();context.getSharedPreferences("lume",0).edit().clear().commit()}

    @Test fun `six strictly validated ClearKey pairs without a type reach the existing DRM callback`() {
        val pairs=(0..5).joinToString(","){i->"${i.toString(16).padStart(32,'0')}:${(i+10).toString(16).padStart(32,'0')}"}
        val stream=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_key" to pairs)),false)
        assertEquals(DrmScheme.CLEARKEY,stream.drm!!.scheme);assertEquals(6,JSONObject(stream.drm!!.clearKeyResponse!!).getJSONArray("keys").length())
        assertEquals("",stream.drm!!.licenseUrl)
    }
    @Test fun `self describing JSON and JWK keys can also infer ClearKey`() {
        val raw=JSONObject().put(kid,key).toString()
        val first=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_key" to raw)),false)
        val second=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_key" to first.drm!!.clearKeyResponse!!)),false)
        assertEquals(first.drm!!.clearKeyResponse,second.drm!!.clearKeyResponse)
    }
    @Test fun `invalid ambiguous and conflicting key payloads remain rejected and explicit DRM wins`() {
        for(value in listOf("https://license.example/test","private-invalid","$kid:$key,$kid:${"11".repeat(16)}","{\"keys\":[]}"))
            rejects {PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_key" to value)),false)}
        rejects {PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_type" to "playready",prefix+"license_key" to "$kid:$key")),false)}
        val explicit=PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_type" to "widevine",prefix+"license_key" to "https://license.example/test")),false)
        assertEquals(DrmScheme.WIDEVINE,explicit.drm!!.scheme)
        rejects {PersonalPlaybackConfig.parse(entry(mapOf(prefix+"license_key" to "$kid:$key"),"https://example.test/live.m3u8"),false)}
    }
    @Test fun `bare HbbTV user agent is scoped normally and manual role headers retain priority`() {
        val e=entry(mapOf(prefix+"stream_headers" to "HbbTV/1.6.1"))
        val stream=PersonalPlaybackConfig.parse(e,false)
        assertEquals("HbbTV/1.6.1",stream.headers["User-Agent"]);assertEquals("HbbTV/1.6.1",stream.manifestHeaders!!["User-Agent"])
        val settings=PlaylistSettings(access=mapOf(PlaylistResource.MEDIA to PlaylistAccessRule(headers=mapOf("User-Agent" to "Manual"))))
        assertEquals("Manual",PersonalPlaybackConfig.parse(e,false,settings).headers["User-Agent"])
        rejects {PersonalPlaybackConfig.parse(entry(mapOf(prefix+"stream_headers" to "private-arbitrary-value")),false)}
        rejects {PersonalPlaybackConfig.parse(entry(mapOf(prefix+"stream_headers" to "HbbTV/1.6.1&broken")),false)}
        rejects {PersonalPlaybackConfig.parse(entry(mapOf(prefix+"stream_headers" to "Host=private")),false)}
    }
    @Test fun `format hints work for persisted entries but explicit MIME and manifests win`() {
        assertEquals("application/dash+xml",PersonalPlaybackConfig.parse(entry(mapOf(prefix+"file_type" to "mpd")),false).mimeType)
        assertEquals("application/x-mpegURL",PersonalPlaybackConfig.parse(entry(mapOf(prefix+"file_type" to "m3u8",prefix+"manifest_type" to "hls")),false).mimeType)
        assertEquals("video/mp2t",PersonalPlaybackConfig.parse(entry(mapOf(prefix+"file_type" to "mpd","mimetype" to "video/mp2t")),false).mimeType)
    }
    @Test fun `provider specific manifest headers survive and explicit list headers take precedence`() {
        val route=PlaylistLocalplay.NOVE
        val base=ResolvedStream("https://stream.example/live.m3u8",headers=mapOf("User-Agent" to "Provider common"),manifestHeaders=mapOf("User-Agent" to "Provider manifest"))
        val e=PlaylistEntry("Nove",route.address,localplay=route)
        assertEquals("Provider manifest",PersonalPlaybackConfig.parse(e,false,resolved=base).manifestHeaders!!["User-Agent"])
        val explicit=PlaylistEntry("Nove",route.address,headers=mapOf("User-Agent" to "Listed"),localplay=route)
        assertEquals("Listed",PersonalPlaybackConfig.parse(explicit,false,resolved=base).manifestHeaders!!["User-Agent"])
        rejects {PersonalPlaybackConfig.parse(e,false,resolved=base.copy(url="https://user:private@stream.example/live.m3u8"))}
        rejects {PersonalPlaybackConfig.parse(e,false,resolved=base.copy(url="http://stream.example/live.m3u8"))}
    }
    @Test fun `typed localplay survives persistence reclassification and replacement with stable identities`()=runTest {
        val repo=PersonalPlaylistRepository(context)
        val id=repo.save("First",repo.prepareFile("#EXTM3U\n#EXTINF:-1,Nove\nhttps://example.test/lists/localplay=Nove".toByteArray()))
        val old=repo.local().single().id
        repo.save("First",repo.prepareFile("#EXTM3U\n#EXTINF:-1,Nove\nlocalplay=Nove".toByteArray()),id)
        assertEquals(old,repo.local().single().id)
        val restored=PersonalPlaylistRepository(context);restored.local()
        assertEquals(PlaylistLocalplay.NOVE,restored.playbackEntry(old).entry.localplay)
        assertEquals("localplay=Nove",restored.playbackEntry(old).entry.url)
        restored.setSettings(id,PlaylistSettings(contentOverride=PlaylistContentKind.LIVE))
        assertEquals(PlaylistLocalplay.NOVE,restored.playbackEntry(old).entry.localplay)
        val entry=restored.playbackEntry(old).entry
        rejects {PersonalPlaybackConfig.parse(entry,false)}
    }
    @Test fun `all screenshot configuration cases import with RSI and an uncounted trailer`()=runTest {
        val pairs=(0..5).joinToString(","){"${it.toString().padStart(32,'0')}:$key"}
        val text="#EXTM3U\n#KODIPROP:${prefix}license_key=$pairs\n#EXTINF:-1,K2\nhttps://example.test/k2.mpd\n#KODIPROP:${prefix}stream_headers=HbbTV/1.6.1\n#EXTINF:-1,Rai 4K\nhttps://example.test/rai4k.m3u8\n#EXTINF:-1,RSI LA1 HD\nhttps://example.test/la1.m3u8\n#EXTINF:-1,RSI LA2 HD\nhttps://example.test/la2.m3u8\n#KODIPROP:${prefix}license_type=clearkey\n[template_end]"
        val repo=PersonalPlaylistRepository(context);val draft=repo.prepareFile(text.toByteArray())
        assertEquals(4,draft.report.count);assertEquals(0,draft.report.excluded);assertEquals(0,draft.report.structuralIssues)
        assertEquals(1,draft.report.diagnosticCounts[PlaylistDiagnosticCode.TRAILING_PROPERTIES])
        repo.save("Recovered",draft);assertEquals(4,repo.local().size)
    }
    @Test fun `saved diagnostics retain precise failure details ahead of later noise`()=runTest {
        val repo=PersonalPlaylistRepository(context)
        val text="#EXTM3U\n#EXTINF:-1,Bad DRM\n#KODIPROP:${prefix}license_type=playready\n#KODIPROP:${prefix}license_key=private\nhttps://example.test/bad.mpd\n#EXTINF:-1,Good\nhttps://example.test/good.m3u8\n"+(1..50).joinToString("\n"){"Noise $it"}
        val id=repo.save("Report",repo.prepareFile(text.toByteArray()));val restored=PersonalPlaylistRepository(context);restored.local();val report=restored.importReport(id)!!
        val first=report.diagnostics.first();assertTrue(first.excluded);assertEquals("Bad DRM",first.channel);assertEquals(2,first.line);assertTrue(first.reason!!.contains("DRM"))
        assertEquals(50,report.ignoredLines);assertFalse(report.warnings.joinToString().contains("private"));assertFalse(report.warnings.joinToString().contains("https://"))
    }
    @Test fun `older imports keep their snapshot and require a full body despite an existing ETag`()=runTest {
        var now=1_000_000L;val etags=mutableListOf<String?>();val body="#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",One\nhttps://example.test/one.m3u8"
        val repo=PersonalPlaylistRepository(context,PlaylistFetcher {u,e,_->etags+=e;PlaylistDownload(body,u,"old-etag")},{now})
        val id=repo.save("Older",repo.prepareUrl("https://example.test/list.m3u"));val old=repo.local()
        SQLiteDatabase.openDatabase(File(directory,"$id.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            val json=db.rawQuery("SELECT value FROM metadata WHERE key='source'",null).use {it.moveToFirst();JSONObject(it.getString(0))};json.remove("importVersion")
            db.execSQL("UPDATE metadata SET value=? WHERE key='source'",arrayOf(json.toString()))
        }
        val restored=PersonalPlaylistRepository(context,PlaylistFetcher {u,e,_->etags+=e;PlaylistDownload(body,u,"new-etag")},{now})
        assertEquals(old,restored.local());assertFalse(restored.state.value.single().metadataComplete)
        now+=31_000;assertTrue(restored.refresh(id));assertNull(etags.last());assertTrue(restored.state.value.single().metadataComplete)
        assertEquals(old.map {it.id},restored.local().map {it.id})
    }

    private class Provider:Transport {
        val calls=mutableListOf<String>();val headerCalls=mutableListOf<Map<String,String>>()
        override suspend fun get(url:String,headers:Map<String,String>):String {
            calls+=url;headerCalls+=headers
            return if("/token" in url)"{\"data\":{\"attributes\":{\"token\":\"owned-token\"}}}"
            else "{\"settings\":{\"site\":{\"simulcast\":{\"sonicEndpoint\":\"https://provider.example\",\"sonicRealm\":\"it\",\"channelId\":\"3\"}}}}"
        }
        override suspend fun post(url:String,data:JSONObject,headers:Map<String,String>):String {
            calls+=url;headerCalls+=headers
            return "{\"data\":{\"attributes\":{\"streaming\":[{\"type\":\"hls\",\"url\":\"https://stream.example/live.m3u8\",\"protection\":{\"drmEnabled\":true,\"schemes\":{\"widevine\":{\"licenseUrl\":\"https://license.example/live\"}}}}]}}}"
        }
    }
    @Test fun `personal localplay reuses the provider resolver and keeps headers DRM and source isolation`()=runTest {
        val repo=PersonalPlaylistRepository(context);val id=repo.save("Source",repo.prepareFile("#EXTM3U\n#EXTINF:-1,Nove\n#EXTHTTP:{\"X-Private\":\"source-only\"}\nlocalplay=Nove".toByteArray()))
        val channel=repo.local().single();val snapshot=repo.playbackEntry(channel.id);val provider=Provider()
        val stream=PersonalPlaylistStreams.initial(snapshot,OkHttpClient(),provider)
        assertEquals("https://stream.example/live.m3u8",stream.url);assertTrue(stream.personal);assertEquals("source-only",stream.headers["X-Private"])
        assertEquals(DrmScheme.WIDEVINE,stream.drm!!.scheme);assertEquals("https://license.example/live",stream.drm!!.licenseUrl)
        assertTrue(provider.headerCalls.all {"X-Private" !in it});assertEquals(3,provider.calls.size)
        repo.setEnabled(id,false)
        try {PersonalPlaylistStreams.initial(snapshot,OkHttpClient(),provider);fail("Invalid source resolved")}catch(_:SourceException){}
        assertEquals(3,provider.calls.size)
    }
    @Test fun `the real personal playback path resolves localplay through scoped HTTP instead of a fake URL`()=runTest {
        val provider=Provider();val client=OkHttpClient.Builder().addInterceptor {chain->
            val request=chain.request();val text=kotlinx.coroutines.runBlocking {if(request.method=="POST")provider.post(request.url.toString(),JSONObject(),emptyMap()) else provider.get(request.url.toString(),emptyMap())}
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody()).build()
        }.build()
        val repo=PersonalPlaylistRepository(context);repo.save("Source",repo.prepareFile("#EXTM3U\n#EXTINF:-1,Motor Trend\nlocalplay=MotorTrend".toByteArray()));val channel=repo.local().single()
        val result=PersonalPlaybackResolver(context,repo,client).resolve(channel,PlaybackTarget(channel.id),null)
        assertEquals("https://stream.example/live.m3u8",result.stream.url);assertTrue(result.stream.personal);assertTrue(result.stream.personalNetworkToken.isNotBlank())
        assertEquals(PlaybackMode.LIVE,result.target.mode);assertTrue(provider.calls.first().contains("motortrend"));assertTrue(provider.calls.none {"localplay=" in it})
    }
    @Test fun `the built in catalog uses the same extracted provider implementation`()=runTest {
        val provider=Provider();val channel=Channel("fixture","Turbo",1,"","","","plugin://fixture","discovery","Turbo")
        val stream=StreamResolver(provider).resolve(channel)
        assertEquals("https://stream.example/live.m3u8",stream.url);assertFalse(stream.personal);assertEquals(DrmScheme.WIDEVINE,stream.drm!!.scheme)
        assertEquals(3,provider.calls.size)
    }
}
