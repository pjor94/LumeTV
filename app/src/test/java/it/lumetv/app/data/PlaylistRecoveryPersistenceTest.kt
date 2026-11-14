package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.*
import kotlinx.coroutines.test.runTest
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
class PlaylistRecoveryPersistenceTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private var time=1_000_000L
    private fun entry(id:String)="#EXTINF:-1 tvg-id=\"$id\",Canale $id\nhttps://example.test/$id.m3u8"
    private var body="#EXTM3U\n"+entry("one")+"\n"+entry("two")
    private fun repo()=PersonalPlaylistRepository(context,PlaylistFetcher {url,_,_->PlaylistDownload(body,url)},{time})
    @Before fun reset(){File(context.filesDir,"personal-playlists").deleteRecursively();context.getSharedPreferences("lume",0).edit().clear().commit()}

    @Test fun `preview and saved report retain recovery counts and line diagnostics`()=runTest {
        val repository=repo()
        body="#EXTM3U\nRigaestranea\n"+entry("one")+"\nhttps://example.test/orphan.mp4\n#EXTINF:-1,Incomplete"
        val draft=repository.prepareUrl("https://example.test/list.m3u")
        assertEquals(2,draft.report.count);assertEquals(1,draft.report.recovered);assertEquals(1,draft.report.ignoredLines);assertEquals(1,draft.report.structuralIssues)
        val id=repository.save("Recupero",draft)
        val restored=repo();restored.local();val report=restored.importReport(id)!!
        assertEquals(1,report.recovered);assertEquals(1,report.ignoredLines);assertEquals(1,report.structuralIssues)
        assertEquals(2,report.diagnostics.single {it.code==PlaylistDiagnosticCode.IGNORED_TEXT}.line)
        assertTrue(report.diagnostics.all {it.document=="root"});assertFalse(report.warnings.joinToString().contains("https://"))
    }

    @Test fun `an incomplete refresh preserves IDs the valid snapshot and previous report`()=runTest {
        val repository=repo();val id=repository.save("Casa",repository.prepareUrl("https://example.test/list.m3u"));val old=repository.local()
        time+=31_000;body="#EXTM3U\n"+entry("one")+"\n#EXTINF:-1,Missing\n#EXTINF:-1,After\nhttps://example.test/after.m3u8"
        try {repository.refresh(id);fail("Partial refresh committed")}catch(_:PlaylistFormatException){}
        assertEquals(old,repository.local());assertTrue(repository.state.value.single().error.contains("conservata"));assertFalse(repository.state.value.single().refreshing)
        val restored=repo();assertEquals(old,restored.local());assertEquals(0,restored.importReport(id)!!.structuralIssues)
        assertTrue(File(context.filesDir,"personal-playlists").listFiles().orEmpty().none {it.name.startsWith(".draft-")})
    }

    @Test fun `unknown text combined with a reduced catalog requires a reviewed replacement`()=runTest {
        val repository=repo();val id=repository.save("Casa",repository.prepareUrl("https://example.test/list.m3u"));val old=repository.local()
        time+=31_000;body="#EXTM3U\nRigaestranea\n"+entry("one")
        try {repository.refresh(id);fail("Lossy refresh committed")}catch(_:PlaylistFormatException){}
        assertEquals(old,repository.local())
        val draft=repository.prepareUrl("https://example.test/list.m3u");repository.save("Casa",draft,id)
        assertEquals(old.first {it.name=="Canale one"}.id,repository.local().single().id)
    }

    @Test fun `a noisy complete refresh updates normally and retains the existing identities`()=runTest {
        val repository=repo();val id=repository.save("Casa",repository.prepareUrl("https://example.test/list.m3u"));val old=repository.local()
        time+=31_000;body="#EXTM3U\n---- SPORT ----\n"+entry("one")+"\nRigaestranea\n"+entry("two")
        assertTrue(repository.refresh(id));assertEquals(old.map {it.id},repository.local().map {it.id})
        assertEquals(2,repository.importReport(id)!!.ignoredLines)
    }

    @Test fun `prefix ClearKey and HTTP properties reach playback and never contaminate the following channel`()=runTest {
        val repository=repo();val kid="00112233445566778899aabbccddeeff";val key="ffeeddccbbaa99887766554433221100"
        body="#EXTM3U\n#KODIPROP:inputstream.adaptive.manifest_type=mpd\n#KODIPROP:inputstream.adaptive.license_type=clearkey\n#KODIPROP:inputstream.adaptive.license_key=$kid:$key\n#EXTHTTP:{\"X-Private\":\"hidden\"}\n#EXTVLCOPT:http-user-agent=Prefix\n#EXTINF:-1,Protected\nhttps://example.test/protected.mpd\n"+entry("normal")
        repository.save("Casa",repository.prepareUrl("https://example.test/list.m3u"));val channels=repository.local()
        val protected=repository.resolve(channels.first().id,okhttp3.OkHttpClient())
        assertEquals(DrmScheme.CLEARKEY,protected.drm?.scheme);assertEquals("Prefix",protected.headers["User-Agent"]);assertEquals("hidden",protected.headers["X-Private"])
        val normal=repository.resolve(channels.last().id,okhttp3.OkHttpClient())
        assertNull(normal.drm);assertFalse(normal.headers.containsKey("X-Private"));assertFalse(normal.headers.containsValue("Prefix"))
        val report=repository.importReport(channels.first().sourceId)!!
        assertFalse(report.warnings.joinToString().contains(key));assertFalse(report.warnings.joinToString().contains("hidden"))
    }
}
