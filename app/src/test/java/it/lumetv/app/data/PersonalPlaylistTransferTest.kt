package it.lumetv.app.data

import android.content.Context
import kotlinx.coroutines.runBlocking
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
class PersonalPlaylistTransferTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        File(app.filesDir,"personal-playlists").deleteRecursively()
        app.getSharedPreferences("lume",0).edit().clear().commit()
    }
    private fun input(count:Int=3)=buildString {
        appendLine("#EXTM3U x-tvg-url=\"https://example.org/guide.xml?secret=epg\"")
        repeat(count){index->
            appendLine("#EXTINF:-1 type=\"movie\" group-title=\"Cinema\" plot=\"Trama $index\",Film $index")
            appendLine("#EXTVLCOPT:http-user-agent=FixtureAgent")
            appendLine("https://example.org/$index.mp4?token=private-transfer-fixture")
        }
    }.toByteArray()
    @Test fun `file transfer preserves full contents and access settings with fresh identities and explicit save`()=runBlocking {
        val repo=PersonalPlaylistRepository(app)
        val config=PlaylistSettings(access=mapOf(PlaylistResource.MEDIA to PlaylistAccessRule(mapOf("Authorization" to "private-access-fixture"))),
            guide=PersonalGuideSettings(enabled=false,language="en",xmlZone="Europe/Rome"))
        val original=repo.prepareFile(input(650),allowHttp=true,settings=config).use {repo.save("Cinema",it)}
        val originalRows=repo.catalog(PersonalCatalogQuery(source=original,content=null,limit=50)).rows
        app.getSharedPreferences("lume",0).edit().putStringSet("favorites",setOf(originalRows.first().id)).commit()
        val exported=repo.exportPlaylist(original)
        assertEquals(650,exported.count)
        assertFalse(exported.toString().contains("private"))
        val plain=PersonalPlaylistTransfer.input(exported.file).bufferedReader().use {it.readText()}
        assertFalse(plain.contains(originalRows.first().id));assertFalse(plain.contains("favorites"))
        repo.prepareTransferredPlaylist(exported.file).use {draft->
            assertEquals(650,draft.report.count);assertEquals(5,draft.report.sample.size)
            assertEquals(config,draft.settings);assertTrue(draft.allowHttp)
            assertEquals(1,repo.state.value.size)
            val copy=repo.save("Cinema sulla TV",draft)
            assertNotEquals(original,copy)
            assertEquals(650,repo.state.value.first {it.id==copy}.count)
            val rows=repo.catalog(PersonalCatalogQuery(source=copy,content=null,limit=50)).rows
            assertEquals(originalRows.map {it.name},rows.map {it.name})
            assertTrue(rows.none {row->originalRows.any {it.id==row.id}})
            assertEquals(config,repo.settings(copy))
            assertEquals(setOf(originalRows.first().id),app.getSharedPreferences("lume",0).getStringSet("favorites",emptySet()))
            assertEquals("Trama 0",repo.videos(VideoQuery(source=copy,collapseSeries=false)).rows.first().description)
        }
        exported.close();assertFalse(exported.file.exists())
    }
    @Test fun `URL transfer keeps the update address without contacting the provider during verification`()=runBlocking {
        var fetches=0
        val repo=PersonalPlaylistRepository(app,PlaylistFetcher {url,_,_->fetches++;PlaylistDownload(input().toString(Charsets.UTF_8),url)})
        val url="https://example.org/list.m3u?password=private-list-fixture"
        val original=repo.prepareUrl(url).use {repo.save("Da URL",it)}
        val rows=repo.catalog(PersonalCatalogQuery(source=original,content=null)).rows
        repo.setEnabled(original,false)
        repo.exportPlaylist(original).use {exported->repo.prepareTransferredPlaylist(exported.file).use {draft->
            assertEquals(PlaylistLocation.URL,draft.location);assertEquals(url,draft.address);assertEquals(1,fetches)
            val copy=repo.save("TV",draft)
            assertEquals(url,repo.address(copy));assertTrue(repo.available(copy))
            val before=repo.catalog(PersonalCatalogQuery(source=copy,content=null)).rows
            assertTrue(before.none {row->rows.any {it.id==row.id}})
            repo.refresh(copy,true)
            assertEquals(2,fetches)
            assertEquals(before.map {it.id},repo.catalog(PersonalCatalogQuery(source=copy,content=null)).rows.map {it.id})
        }}
    }
    @Test fun `invalid and truncated transfers leave saved lists unchanged and remove staged databases`()=runBlocking {
        val repo=PersonalPlaylistRepository(app)
        val original=repo.prepareFile(input()).use {repo.save("Originale",it)}
        repo.exportPlaylist(original).use {exported->
            val lines=PersonalPlaylistTransfer.input(exported.file).bufferedReader().use {it.readLines()}
            val broken=File(app.cacheDir,"broken-playlist.gz")
            try {
                val header=JSONObject(lines.first()).put("version",999)
                PersonalPlaylistTransfer.output(broken).use {it.write((header.toString()+"\n"+lines.drop(1).joinToString("\n")).toByteArray())}
                assertTrue(runCatching {repo.prepareTransferredPlaylist(broken)}.isFailure)
                PersonalPlaylistTransfer.output(broken).use {it.write(lines.dropLast(1).joinToString("\n",postfix="\n").toByteArray())}
                assertTrue(runCatching {repo.prepareTransferredPlaylist(broken)}.isFailure)
                assertEquals(listOf(original),repo.state.value.map {it.id})
                assertTrue(File(app.filesDir,"personal-playlists").listFiles().orEmpty().none {it.name.startsWith(".draft-")})
            }finally {broken.delete()}
        }
    }
}
