package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.MediaKind
import it.lumetv.core.PlaylistContentKind
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
class LargePlaylistTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val directory get()=File(context.filesDir,"personal-playlists")
    private fun text(count:Int,prefix:String="A",vod:Boolean=false)="#EXTM3U\n"+(0 until count).joinToString("\n") {
        "#EXTINF:${if(vod)60 else -1} tvg-id=\"$prefix$it\" group-title=\"Gruppo ${it%3}\" tvg-chno=\"${it+1}\",$prefix voce $it\nhttps://example.com/$prefix/$it.mp4"
    }
    private suspend fun source(repo:PersonalPlaylistRepository,count:Int,prefix:String="A",vod:Boolean=false)=
        repo.save(prefix,repo.prepareFile(text(count,prefix,vod).toByteArray()))
    @Before fun clean(){directory.deleteRecursively();context.getSharedPreferences("lume",0).edit().clear().commit()}

    @Test fun `catalog is bounded while later pages and full source counts survive restart`()=runTest {
        val repo=PersonalPlaylistRepository(context);val source=source(repo,450)
        assertEquals(450,repo.state.value.single().count);assertEquals(200,repo.local().size)
        val page=repo.catalog(PersonalCatalogQuery(source=source,offset=400))
        assertEquals(450,page.total);assertEquals(50,page.rows.size);assertEquals("A voce 449",page.rows.last().name)
        val restored=PersonalPlaylistRepository(context)
        assertEquals(200,restored.local().size);assertEquals(page,restored.catalog(PersonalCatalogQuery(source=source,offset=400)))
        assertEquals(page.rows.last(),restored.channel(page.rows.last().id))
        assertEquals(page.rows.last().name,restored.playbackEntry(page.rows.last().id).entry.name)
    }
    @Test fun `group filters and counts use the whole database rather than the preview`()=runTest {
        val repo=PersonalPlaylistRepository(context);val id=source(repo,450)
        val last=repo.catalog(PersonalCatalogQuery(source=id,text="voce 449",group="Gruppo 2"))
        assertEquals(1,last.total);assertEquals("A voce 449",last.rows.single().name)
        val groups=repo.groups(PersonalCatalogQuery(source=id,limit=2))
        assertEquals(3,groups.second);assertEquals(2,groups.first.size)
        assertEquals(listOf("Gruppo 2"),repo.groups(PersonalCatalogQuery(source=id,offset=2)).first)
    }
    @Test fun `video pages across sources retain their order without loading complete catalogs`()=runTest {
        val repo=PersonalPlaylistRepository(context);source(repo,230,"A",true);source(repo,240,"B",true)
        val page=repo.library(null,"",null,220)
        assertEquals(470,page.total);assertEquals(50,page.rows.size)
        assertEquals("A voce 220",page.rows.first().name);assertEquals("B voce 39",page.rows.last().name)
        assertTrue(repo.local().size<=400)
        assertEquals(3,repo.groups(PersonalCatalogQuery(content=PlaylistContentKind.VOD)).second)
    }
    @Test fun `favorites and zapping work outside the first window and deletion clears their references`()=runTest {
        val repo=PersonalPlaylistRepository(context);val id=source(repo,450)
        val last=repo.catalog(PersonalCatalogQuery(source=id,offset=449)).rows.single()
        val first=repo.catalog(PersonalCatalogQuery(source=id,limit=1)).rows.single()
        val prefs=context.getSharedPreferences("lume",0)
        prefs.edit().putStringSet("favorites",setOf(last.id)).putString("last_channel",last.id).commit()
        assertEquals(last,repo.catalog(PersonalCatalogQuery(ids=setOf(last.id))).rows.single())
        assertEquals(first,repo.next(last,1));assertEquals(last,repo.next(first,-1))
        assertEquals(last,repo.next(first,1,setOf(last.id)));assertNull(repo.next(first,1,emptySet()))
        repo.delete(id);assertTrue(prefs.getStringSet("favorites",emptySet())!!.isEmpty());assertFalse(prefs.contains("last_channel"))
    }
    @Test fun `SQLite duplicate tracking shares a budget across included documents`()=runTest {
        val one=text(2)
        val repo=PersonalPlaylistRepository(context,PlaylistFetcher {url,_,_->PlaylistDownload(if(url.endsWith("root"))
            "#EXTM3U\n#EXTINF:-1 type=\"playlist\",Uno\na.m3u\n#EXTINF:-1 type=\"playlist\",Due\nb.m3u" else one,url)})
        val draft=repo.prepareUrl("https://example.com/root")
        assertEquals(2,draft.report.count);assertEquals(2,draft.report.duplicates)
        draft.close()
    }
    @Test fun `old snapshots gain a derived group index without changing IDs or metadata`()=runTest {
        val repo=PersonalPlaylistRepository(context);val id=source(repo,3);val before=repo.local()
        SQLiteDatabase.openDatabase(File(directory,"$id.sqlite").path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("DROP TABLE record_groups")}
        val restored=PersonalPlaylistRepository(context);assertEquals(before,restored.local())
        assertEquals(3,restored.groups(PersonalCatalogQuery(source=id)).second)
    }
    @Test fun `oversized guide input is rejected before allocating its candidates and leaves the catalog intact`()=runTest {
        val repo=PersonalPlaylistRepository(context);val id=source(repo,3)
        val store=PersonalPlaylistStore(directory)
        val stored=store.read(id)!!.let {it.copy(settings=it.settings.copy(guide=it.settings.guide.copy(enabled=true,urls=listOf("https://example.com/guide.xml"))))}
        try {store.guideCandidates(stored,maxCandidates=2);fail("Guide allocation budget ignored")}
        catch(error:SourceException){assertTrue(error.message.orEmpty().contains("100.000"))}
        assertEquals(3,repo.catalog(PersonalCatalogQuery(source=id)).total)
    }
}
