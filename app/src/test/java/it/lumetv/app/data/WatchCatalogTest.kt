package it.lumetv.app.data

import android.content.Context
import kotlinx.coroutines.runBlocking
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
class WatchCatalogTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    @Before fun reset(){File(app.filesDir,"personal-playlists").deleteRecursively();app.getSharedPreferences("lume",0).edit().clear().commit()}
    private fun input(path:String="film",count:Int=1)=buildString {
        appendLine("#EXTM3U")
        repeat(count){appendLine("#EXTINF:-1 type=\"movie\",Stesso titolo $it");appendLine("https://example.org/$path/$it.mp4?token=private-watch-fixture")}
    }.toByteArray()
    private suspend fun copy(repo:PersonalPlaylistRepository,source:String,name:String)=repo.exportPlaylist(source).use {file->
        repo.prepareTransferredPlaylist(file.file).use {repo.save(name,it)}
    }
    @Test fun `transferred files match after IDs change and reference contains no playback credentials`()=runBlocking {
        val repo=PersonalPlaylistRepository(app)
        val settings=PlaylistSettings(access=mapOf(PlaylistResource.MEDIA to PlaylistAccessRule(mapOf("Authorization" to "private-header"))))
        val phone=repo.prepareFile(input(count=700),settings=settings).use {repo.save("Telefono",it)}
        val original=repo.catalog(PersonalCatalogQuery(source=phone,content=null,offset=650,limit=1)).rows.single()
        val ref=repo.watchReference(original)
        assertFalse(ref.json().toString().contains("https"));assertFalse(ref.json().toString().contains("private"));assertEquals(ref,WatchReference.read(ref.json()))
        val tv=copy(repo,phone,"TV");repo.setEnabled(phone,false)
        val matched=repo.resolveWatch(ref)
        assertEquals(tv,matched.sourceId);assertEquals(original.name,matched.name);assertNotEquals(original.id,matched.id)
        repo.setSettings(tv,settings.copy(guide=PersonalGuideSettings(language="en",enabled=false)))
        assertEquals(matched.id,repo.resolveWatch(ref).id)
        repo.setSettings(tv,settings.copy(access=mapOf(PlaylistResource.MEDIA to PlaylistAccessRule(mapOf("Authorization" to "different-account")))))
        assertEquals("watch_missing",runCatching {repo.resolveWatch(ref)}.exceptionOrNull().let {it as WatchMatchException}.code)
    }
    @Test fun `same title is never a fallback and duplicate enabled copies require a decision`()=runBlocking {
        val repo=PersonalPlaylistRepository(app)
        val phone=repo.prepareFile(input()).use {repo.save("Telefono",it)}
        val original=repo.catalog(PersonalCatalogQuery(source=phone,content=null)).rows.single()
        val ref=repo.watchReference(original)
        val a=copy(repo,phone,"TV A");val b=copy(repo,phone,"TV B");repo.setEnabled(phone,false)
        assertEquals("watch_ambiguous",runCatching {repo.resolveWatch(ref)}.exceptionOrNull().let {it as WatchMatchException}.code)
        repo.setEnabled(b,false);assertEquals(a,repo.resolveWatch(ref).sourceId)
        repo.setEnabled(a,false)
        repo.prepareFile(input("different-content")).use {repo.save("Omonimo",it)}
        assertEquals("watch_missing",runCatching {repo.resolveWatch(ref)}.exceptionOrNull().let {it as WatchMatchException}.code)
    }
    @Test fun `URL lists match through refreshes while a missing entry cannot be guessed`()=runBlocking {
        var text=input().toString(Charsets.UTF_8);var time=1_000_000L
        val repo=PersonalPlaylistRepository(app,PlaylistFetcher {url,_,_->PlaylistDownload(text,url)},{time})
        val phone=repo.prepareUrl("https://example.org/list?password=private").use {repo.save("Telefono",it)}
        val original=repo.catalog(PersonalCatalogQuery(source=phone,content=null)).rows.single();val ref=repo.watchReference(original)
        val tv=copy(repo,phone,"TV");repo.setEnabled(phone,false)
        text=input(count=5).toString(Charsets.UTF_8);time+=3_600_000;assertTrue(repo.refresh(tv,true))
        assertEquals(tv,repo.resolveWatch(ref).sourceId)
        text=input("replaced").toString(Charsets.UTF_8);time+=3_600_000;assertTrue(repo.refresh(tv,true))
        assertEquals("watch_missing",runCatching {repo.resolveWatch(ref)}.exceptionOrNull().let {it as WatchMatchException}.code)
    }
}
