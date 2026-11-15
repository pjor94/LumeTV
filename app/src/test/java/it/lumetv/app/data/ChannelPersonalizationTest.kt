package it.lumetv.app.data

import android.content.Context
import android.os.Looper
import it.lumetv.core.Channel
import it.lumetv.core.MediaKind
import it.lumetv.core.PlaylistContentKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ChannelPersonalizationTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val prefs get()=context.getSharedPreferences("lume",0)
    private val store get()=ChannelPersonalization.get(context)
    private fun c(id:String,kind:MediaKind=MediaKind.TV,source:String="builtin_tv")=Channel(id,id,1,id,"","","https://example.org/$id","direct",id,kind=kind,sourceId=source)
    private fun favorites(vararg ids:String){prefs.edit().putStringSet("favorites",ids.toSet()).commit();shadowOf(Looper.getMainLooper()).idle()}
    private fun reorder(ids:List<String>,baseline:List<String> = ids):Boolean {
        shadowOf(Looper.getMainLooper()).idle();return store.reorder(ids,baseline,store.revision.value)
    }
    private fun list(count:Int=450,prefix:String="A")=buildString {
        appendLine("#EXTM3U")
        for(i in 1..count){appendLine("#EXTINF:-1 tvg-id=\"$prefix$i\" tvg-chno=\"$i\" group-title=\"Gruppo ${i%2}\",$prefix canale $i");appendLine("https://example.org/$prefix/$i")}
    }.toByteArray()
    @Before fun reset(){File(context.filesDir,"personal-playlists").deleteRecursively();File(context.filesDir,"personal-watch.sqlite").delete();prefs.edit().clear().commit();shadowOf(Looper.getMainLooper()).idle()}

    @Test fun `legacy TV radio and Video favorites survive layout edits without changing saved series or playback`() {
        favorites("tv","radio","movie")
        prefs.edit().putStringSet("video_saved_series",setOf("source:series")).putString("recent_channels","tv,radio").commit()
        val movie=c("movie").copy(contentKind=PlaylistContentKind.VOD)
        val progress=PersonalPlaybackStore.get(context);val source="user_"+"0".repeat(32)
        val target=it.lumetv.core.PlaybackTarget(movie.id,it.lumetv.core.PlaybackMode.VOD)
        progress.write(source,target,42_000,180_000)
        assertTrue(store.order.isEmpty());assertTrue(store.hidden.isEmpty())
        assertTrue(reorder(listOf("radio","tv")))
        store.hide(c("tv"));store.restore("tv")
        assertEquals(setOf("tv","radio","movie"),prefs.getStringSet("favorites",emptySet()))
        assertEquals(setOf("source:series"),prefs.getStringSet("video_saved_series",emptySet()))
        assertEquals(WatchPosition(42_000,180_000),progress.read(source,target));assertEquals("tv,radio",prefs.getString("recent_channels",null))
        assertFalse(store.isHidden(movie));assertEquals(listOf("radio","tv"),store.order)
    }
    @Test fun `subset reorder retains hidden disabled and radio slots and rejects stale or duplicate moves`() {
        val original=listOf("one","hidden","radio","disabled","two")
        favorites(*original.toTypedArray());assertTrue(reorder(original))
        store.hide(c("hidden"));shadowOf(Looper.getMainLooper()).idle()
        val revision=store.revision.value
        assertTrue(store.reorder(listOf("two","one"),listOf("one","hidden","two"),revision))
        assertEquals(listOf("two","hidden","radio","disabled","one"),store.order)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(store.reorder(listOf("one","two"),original,revision))
        assertFalse(reorder(listOf("one","one"),original))
        store.restore("hidden")
        assertEquals(listOf("two","hidden","radio","disabled","one"),store.ordered(original.map(::c)).map {it.id})
    }
    @Test fun `favorite additions append after custom order while Video does not alter live order`() {
        favorites("one","two");assertTrue(reorder(listOf("two","one")))
        store.favoriteChanged(c("three"),true);assertEquals(listOf("two","one","three"),store.order)
        store.favoriteChanged(c("movie").copy(contentKind=PlaylistContentKind.VOD),true);assertEquals(3,store.order.size)
        store.favoriteChanged(c("one"),false);assertEquals(listOf("two","three"),store.order)
    }
    @Test fun `unsupported or corrupt layout stays intact and cannot be silently replaced`() {
        favorites("tv","movie");val raw="{\"version\":99,\"order\":[\"tv\"],\"hidden\":[]}"
        prefs.edit().putString("channel_layout",raw).commit()
        assertTrue(store.order.isEmpty());assertNotNull(store.storageError)
        assertThrows(java.io.IOException::class.java){store.hide(c("tv"))}
        assertEquals(raw,prefs.getString("channel_layout",null));assertEquals(setOf("tv","movie"),prefs.getStringSet("favorites",emptySet()))
        prefs.edit().putString("channel_layout","broken").commit();assertTrue(store.hidden.isEmpty());assertNotNull(store.storageError)
    }
    @Test fun `merged favorite pages preserve private and builtin identity and are bounded beyond preview`()=runTest {
        val repo=PersonalPlaylistRepository(context)
        val a=repo.save("Prima",repo.prepareFile(list()));val b=repo.save("Seconda",repo.prepareFile(list(3,"B")))
        val first=repo.catalog(PersonalCatalogQuery(source=a,limit=1)).rows.single()
        val last=repo.catalog(PersonalCatalogQuery(source=a,offset=449)).rows.single()
        val other=repo.catalog(PersonalCatalogQuery(source=b,limit=1)).rows.single()
        val builtin=c("builtin");val radio=c("radio",MediaKind.RADIO,"builtin_radio")
        favorites(first.id,last.id,other.id,builtin.id,radio.id,"movie")
        assertTrue(reorder(listOf(other.id,builtin.id,last.id,radio.id,first.id)))
        val q=PersonalCatalogQuery(kind=MediaKind.TV,ids=prefs.getStringSet("favorites",emptySet()),favoriteOrder=true,includeBuiltin=true,limit=2)
        val p1=repo.catalog(q,listOf(builtin,radio));val p2=repo.catalog(q.copy(offset=2),listOf(builtin,radio))
        assertEquals(4,p1.total);assertEquals(listOf(other.id,builtin.id,last.id,first.id),(p1.rows+p2.rows).map {it.id})
        assertEquals(builtin,p1.rows.last());assertEquals("",p2.rows.first().source)
        assertEquals(listOf(last.id),repo.catalog(q.copy(text="450"),listOf(builtin,radio)).rows.map {it.id})
        assertEquals(listOf(last.id),repo.catalog(q.copy(group="Gruppo 0",source=a),emptyList()).rows.map {it.id})
        assertTrue(repo.cached().size<=400)
    }
    @Test fun `hidden rows disappear from catalog search number groups and both zapping modes but restore keeps favorites`()=runTest {
        val repo=PersonalPlaylistRepository(context);val source=repo.save("Casa",repo.prepareFile(list(3)))
        val rows=repo.catalog(PersonalCatalogQuery(source=source)).rows
        favorites(*rows.map {it.id}.toTypedArray());assertTrue(reorder(rows.reversed().map {it.id}))
        store.hide(rows[1])
        assertEquals(2,repo.catalog(PersonalCatalogQuery(source=source)).total)
        assertEquals(0,repo.catalog(PersonalCatalogQuery(source=source,number=2)).total)
        assertEquals(0,repo.catalog(PersonalCatalogQuery(source=source,text="canale 2")).total)
        assertEquals(listOf("Gruppo 1"),repo.groups(PersonalCatalogQuery(source=source)).first)
        assertEquals(rows[2],repo.next(rows[0],1));assertEquals(rows[2],repo.next(rows[0],1,rows.map {it.id}.toSet()))
        assertEquals(3,repo.catalog(PersonalCatalogQuery(source=source,includeHidden=true)).total)
        assertEquals(3,prefs.getStringSet("favorites",emptySet())!!.size)
        store.restore(rows[1].id);assertEquals(rows[1],repo.next(rows[0],1));assertEquals(3,repo.catalog(PersonalCatalogQuery(source=source)).total)
        assertEquals(rows[2],repo.next(rows[0],1,rows.map {it.id}.toSet()))
    }
    @Test fun `refresh and disabling preserve customization while deleting its source cleans references`()=runTest {
        val repo=PersonalPlaylistRepository(context);val source=repo.save("Casa",repo.prepareFile(list(3)))
        val rows=repo.catalog(PersonalCatalogQuery(source=source)).rows
        favorites(*rows.map {it.id}.toTypedArray());assertTrue(reorder(rows.reversed().map {it.id}));store.hide(rows[1])
        repo.setEnabled(source,false);assertEquals(0,repo.catalog(PersonalCatalogQuery(ids=prefs.getStringSet("favorites",emptySet()),favoriteOrder=true)).total)
        assertEquals(3,store.order.size);assertTrue(store.isHidden(rows[1]))
        repo.setEnabled(source,true)
        repo.save("Rinominata",repo.prepareFile(String(list(3)).replace("A canale","Nome nuovo").toByteArray()),source)
        val restored=PersonalPlaylistRepository(context);restored.local()
        assertEquals(listOf(rows[2].id,rows[0].id),restored.catalog(PersonalCatalogQuery(ids=prefs.getStringSet("favorites",emptySet()),favoriteOrder=true)).rows.map {it.id})
        assertTrue(store.isHidden(rows[1]));assertTrue(restored.channel(rows[0].id)!!.name.startsWith("Nome nuovo"))
        restored.delete(source);assertTrue(store.order.isEmpty());assertTrue(store.hidden.isEmpty());assertTrue(prefs.getStringSet("favorites",emptySet())!!.isEmpty())
    }
    @Test fun `reclassifying a hidden live channel as Video does not hide the Video library`()=runTest {
        val repo=PersonalPlaylistRepository(context);val source=repo.save("Casa",repo.prepareFile(list(1)))
        val live=repo.catalog(PersonalCatalogQuery(source=source)).rows.single();store.hide(live)
        repo.setSettings(source,PlaylistSettings(contentOverride=PlaylistContentKind.VOD))
        val video=repo.catalog(PersonalCatalogQuery(source=source,content=PlaylistContentKind.VOD)).rows.single()
        assertTrue(video.isVod);assertFalse(store.isHidden(video));assertEquals(1,repo.videos().total)
    }
    @Test fun `deleting a source also cleans ordered favorites whose provider archive has expired`()=runTest {
        val repo=PersonalPlaylistRepository(context);val source=repo.save("Casa",repo.prepareFile(list(3)))
        val rows=repo.catalog(PersonalCatalogQuery(source=source)).rows
        favorites(*rows.map {it.id}.toTypedArray(),"saved-video")
        val query=PersonalCatalogQuery(source=source,ids=rows.map {it.id}.toSet(),favoriteOrder=true,includeHidden=true)
        val owners=repo.favoriteOwners(query)
        assertEquals(rows.map {it.id}.toSet(),owners.keys);assertTrue(owners.values.all {it==source})
        assertTrue(store.reorder(rows.reversed().map {it.id},owners.keys.toList(),store.revision.value,owners))
        // Simulate the provider removing a channel beyond the snapshot's retention period.
        android.database.sqlite.SQLiteDatabase.openDatabase(File(context.filesDir,"personal-playlists/$source.sqlite").path,null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("DELETE FROM records WHERE id=?",arrayOf(rows[1].id))}
        assertNull(repo.channel(rows[1].id));assertTrue(rows[1].id in store.sourceIds(source))
        repo.delete(source)
        assertTrue(store.order.isEmpty());assertTrue(store.sourceIds(source).isEmpty())
        assertEquals(setOf("saved-video"),prefs.getStringSet("favorites",emptySet()))
    }
    @Test fun `guide favorites follow custom order without changing original numbers`() {
        val rows=listOf(c("one"),c("two"),c("three"));val nav=GuideNavigator()
        nav.favoriteOrder=listOf("three","one","two")
        nav.submit(rows,emptyMap(),rows.map {it.id}.toSet(),"one",1_000L)
        nav.filterFavorites(true)
        assertEquals(listOf("three","one","two"),nav.channels.map {it.id});assertEquals("one",nav.channel?.id)
        assertEquals(rows.map {it.number},nav.channels.map {it.number})
    }
    @Test fun `shared metadata excludes hidden channels and keeps private sources out after reordering`() {
        val one=c("000000000000000000000001");val two=c("000000000000000000000002")
        val private=c("000000000000000000000003",source="user_private")
        favorites(one.id,two.id,private.id);assertTrue(reorder(listOf(two.id,one.id,private.id)))
        it.lumetv.app.integration.IntegrationStore.catalogChanged(context,listOf(one,two,private),1000L)
        store.hide(one)
        assertEquals(listOf(two.id),it.lumetv.app.integration.IntegrationStore.content(context).channels.map {it.id})
        store.restore(one.id)
        assertEquals(listOf(two.id,one.id),it.lumetv.app.integration.IntegrationStore.content(context).channels.map {it.id})
    }
}
