package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
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
class PlaylistClassificationPersistenceTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private val directory get()=File(context.filesDir,"personal-playlists")
    private fun repository(fetcher:PlaylistFetcher?=null)=PersonalPlaylistRepository(context,fetcher)
    private fun file(id:String)=File(directory,"$id.sqlite")
    private val text="#EXTM3U\n#EXTINF:-1 tvg-id=\"film\" group-title=\"Film\",Film\nhttps://example.com/item.mp4?token=private\n#EXTINF:-1 type=\"live\" group-title=\"Film\",Cinema live\nhttps://example.com/live/u/p/3.mp4\n"

    @Before fun reset(){directory.deleteRecursively();context.getSharedPreferences("lume",0).edit().clear().commit()}

    private fun legacy(id:String,kind:PlaylistContentKind) {
        SQLiteDatabase.openDatabase(file(id).path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            db.beginTransaction()
            try {
                val source=db.rawQuery("SELECT value FROM metadata WHERE key='source'",null).use {it.moveToFirst();JSONObject(it.getString(0))}
                source.remove("classificationVersion");source.remove("classificationNeedsRefresh")
                db.execSQL("UPDATE metadata SET value=? WHERE key='source'",arrayOf(source.toString()))
                db.rawQuery("SELECT rowid,payload FROM records",null).use {cursor->while(cursor.moveToNext()) {
                    val root=JSONObject(cursor.getString(1));val metadata=root.getJSONObject("metadata")
                    listOf("classificationReason","classificationVersion","legacyVod","vodMarker").forEach(metadata::remove)
                    metadata.put("content",kind.name)
                    db.execSQL("UPDATE records SET content=?,payload=? WHERE rowid=?",arrayOf<Any>(kind.name,root.toString(),cursor.getLong(0)))
                }}
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
        }
    }
    private fun source(id:String)=SQLiteDatabase.openDatabase(file(id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
        db.rawQuery("SELECT value FROM metadata WHERE key='source'",null).use {it.moveToFirst();JSONObject(it.getString(0))}
    }

    @Test fun `detection and group policies round trip with prudent defaults for existing settings`() {
        assertEquals(PlaylistVodDetection.PRUDENT,PlaylistSettings.read(JSONObject()).reading.vodDetection)
        val settings=PlaylistSettings(reading=PlaylistReadOptions(vodDetection=PlaylistVodDetection.EXTENDED,groupContent=mapOf("Film" to PlaylistContentKind.VOD,"Sky Cinema" to PlaylistContentKind.LIVE)))
        assertEquals(settings,PlaylistSettings.read(settings.json()))
        assertFalse(settings.toString().contains("Sky Cinema"))
    }
    @Test fun `changing recognition and group rules reclassifies the database without changing IDs or private playback`()=runTest {
        val repo=repository();val id=repo.save("Mista",repo.prepareFile(text.toByteArray()))
        val before=repo.catalog(PersonalCatalogQuery(source=id,content=null)).rows.associate {it.name to it.id}
        val prefs=context.getSharedPreferences("lume",0);prefs.edit().putStringSet("favorites",before.values.toSet()).commit()
        val watch=PersonalPlaybackStore.get(context);val target=PlaybackTarget(before.getValue("Film"),PlaybackMode.VOD)
        watch.write(id,target,14_000,180_000)
        val contextBefore=repo.requestContext(id)!!
        repo.setSettings(id,PlaylistSettings(reading=PlaylistReadOptions(vodDetection=PlaylistVodDetection.EXTENDED)))
        assertEquals(listOf("Film"),repo.library(id,"",null).rows.map {it.name})
        assertSame(contextBefore,repo.requestContext(id));assertTrue(contextBefore.valid())
        repo.setSettings(id,PlaylistSettings(reading=PlaylistReadOptions(groupContent=mapOf("Film" to PlaylistContentKind.VOD))))
        assertEquals(2,repo.library(id,"",null).total)
        repo.setSettings(id,PlaylistSettings(reading=PlaylistReadOptions(groupContent=mapOf("Film" to PlaylistContentKind.VOD)),contentOverride=PlaylistContentKind.LIVE))
        assertTrue(repo.videoSources.value.isEmpty());assertEquals(2,repo.catalog(PersonalCatalogQuery(source=id)).total)
        repo.setSettings(id,PlaylistSettings())
        assertEquals(before,repo.catalog(PersonalCatalogQuery(source=id,content=null)).rows.associate {it.name to it.id})
        assertEquals(before.values.toSet(),prefs.getStringSet("favorites",emptySet()))
        assertEquals(14_000L,watch.read(id,target).position)
        val entry=repo.playbackEntry(before.getValue("Film"))
        assertEquals("https://example.com/item.mp4?token=private",entry.entry.url)
        assertTrue(repo.library(id,"",null).rows.isEmpty())
    }
    @Test fun `existing catalog migration reads every indexed entry and is idempotent without network`()=runTest {
        val repo=repository();val content=buildString {
            appendLine("#EXTM3U")
            for(i in 1..1230){appendLine("#EXTINF:-1 tvg-id=\"c$i\",Item $i");appendLine(if(i==1230)"https://example.com/movie/u/p/1230.mkv" else "https://example.com/live/u/p/$i")}
        }
        val id=repo.save("Grande",repo.prepareFile(content.toByteArray()));val vodId=repo.library(id,"",null).rows.single().id
        legacy(id,PlaylistContentKind.LIVE)
        val events=mutableListOf<Int>();val store=PersonalPlaylistStore(directory)
        val migrated=store.read(id,progress={_,done,_->events+=done})!!
        assertEquals(PlaylistContentClassifier.VERSION,migrated.classificationVersion)
        assertEquals(listOf(vodId),store.catalog(migrated,PersonalCatalogQuery(content=PlaylistContentKind.VOD)).rows.map {it.id})
        assertEquals(0,events.first());assertEquals(1230,events.last());assertFalse(migrated.classificationNeedsRefresh)
        assertEquals(PlaylistContentClassifier.VERSION,source(id).getInt("classificationVersion"))
        store.read(id,progress={_,_,_->fail("Migration repeated")})
        val reopened=repository(PlaylistFetcher {_,_,_->error("Network must not be needed")});reopened.local()
        assertEquals(setOf(id),reopened.videoSources.value);assertEquals(vodId,reopened.library(id,"",null).rows.single().id)
    }
    @Test fun `an interrupted reclassification rolls back both settings and every catalog row`()=runTest {
        val repo=repository();val id=repo.save("Mista",repo.prepareFile(text.toByteArray()));legacy(id,PlaylistContentKind.LIVE)
        val store=PersonalPlaylistStore(directory);val stored=store.read(id)!!
        val original=source(id).toString();var checks=0
        try {
            store.change(stored.copy(settings=PlaylistSettings(reading=PlaylistReadOptions(groupContent=mapOf("Film" to PlaylistContentKind.VOD)))),true,{if(++checks==3)throw CancellationException("stop")})
            fail("Cancellation ignored")
        }catch(_:CancellationException){}
        assertEquals(original,source(id).toString())
        assertEquals(0,store.catalog(stored,PersonalCatalogQuery(content=PlaylistContentKind.VOD)).total)
    }
    @Test fun `legacy marker evidence is preserved until a full refresh and is not confused with explicit live`()=runTest {
        val repo=repository();val id=repo.save("Vecchia",repo.prepareFile(text.toByteArray()));legacy(id,PlaylistContentKind.VOD)
        val reopened=repository();reopened.local()
        assertEquals(listOf("Film"),reopened.library(id,"",null).rows.map {it.name})
        assertTrue(reopened.classificationNeedsRefresh(id))
        reopened.setSettings(id,PlaylistSettings(reading=PlaylistReadOptions(groupContent=mapOf("Film" to PlaylistContentKind.LIVE))))
        assertTrue(reopened.videoSources.value.isEmpty())
        reopened.setSettings(id,PlaylistSettings())
        assertEquals(listOf("Film"),reopened.library(id,"",null).rows.map {it.name});assertTrue(reopened.classificationNeedsRefresh(id))
        reopened.save("Vecchia",reopened.prepareFile(text.toByteArray()),id)
        assertFalse(reopened.classificationNeedsRefresh(id));assertTrue(reopened.videoSources.value.isEmpty())
    }
    @Test fun `reclassification respects the aggregate disk budget and rolls back when it is exceeded`()=runTest {
        val repo=repository();val id=repo.save("Mista",repo.prepareFile(text.toByteArray()))
        val store=PersonalPlaylistStore(directory);val stored=store.read(id)!!;val original=source(id).toString()
        val occupied=File(directory,"occupied.sqlite")
        try {
            java.io.RandomAccessFile(occupied,"rw").use {it.setLength(PersonalPlaylistStore.MAX_DISK)}
            try {store.change(stored.copy(settings=PlaylistSettings(reading=PlaylistReadOptions(groupContent=mapOf("Film" to PlaylistContentKind.VOD)))),true);fail("Disk budget ignored")}
            catch(error:java.io.IOException){assertTrue(error.message.orEmpty().contains("limite di spazio"))}
            assertEquals(original,source(id).toString());assertEquals(0,store.catalog(stored,PersonalCatalogQuery(content=PlaylistContentKind.VOD)).total)
        } finally {occupied.delete()}
    }
    @Test fun `a deferred startup upgrade keeps the saved catalog readable and retries after space is available`()=runTest {
        val requests=mutableListOf<String?>()
        val content=text.replace("group-title=\"Film\",Film","type=\"vod\" group-title=\"Film\",Film")
        val fetcher=PlaylistFetcher {url,tag,_->requests+=tag;PlaylistDownload(content,url,"tag")}
        val original=repository(fetcher);val id=original.save("URL",original.prepareUrl("https://example.com/list.m3u"))
        val before=original.catalog(PersonalCatalogQuery(source=id,content=null)).rows.map {it.id}
        legacy(id,PlaylistContentKind.LIVE)
        val occupied=File(directory,"occupied.sqlite")
        try {
            java.io.RandomAccessFile(occupied,"rw").use {it.setLength(PersonalPlaylistStore.MAX_DISK)}
            val reopened=repository(fetcher);reopened.local()
            assertEquals(before,reopened.catalog(PersonalCatalogQuery(source=id,content=null)).rows.map {it.id})
            assertTrue(reopened.state.value.single().enabled)
            assertTrue(reopened.state.value.single().error.contains("Riconoscimento VOD non aggiornato"))
            assertEquals(0,source(id).optInt("classificationVersion"))
            reopened.rename(id,"URL rinominata")
            occupied.delete()
            val retried=PersonalPlaylistStore(directory).read(id)!!
            assertEquals(PlaylistContentClassifier.VERSION,retried.classificationVersion)
            assertTrue(retried.source.error.isEmpty())
            assertTrue(reopened.refresh(id));assertNull(requests.last())
            assertEquals(PlaylistContentClassifier.VERSION,source(id).getInt("classificationVersion"))
            assertEquals(listOf("Film"),reopened.library(id,"",null).rows.map {it.name})
            assertTrue(reopened.state.value.single().error.isEmpty())
        }finally {occupied.delete()}
    }
    @Test fun `known entry marker scope survives reclassification and reopening`()=runTest {
        val content="#EXTM3U\n#EXTINF:-1,Film\n#EXT-X-PLAYLIST-TYPE:VOD\nhttps://example.com/one\n#EXTINF:-1,Live\nhttps://example.com/two"
        val repo=repository();val id=repo.save("Kodi",repo.prepareFile(content.toByteArray(),settings=PlaylistSettings(reading=PlaylistReadOptions(groups=PlaylistGroupProfile.KODI))))
        repo.setSettings(id,repo.settings(id).copy(reading=repo.settings(id).reading.copy(vodDetection=PlaylistVodDetection.EXTENDED)))
        val reopened=repository();reopened.local()
        assertEquals(listOf("Film"),reopened.library(id,"",null).rows.map {it.name});assertFalse(reopened.classificationNeedsRefresh(id))
        assertEquals(listOf("Live"),reopened.catalog(PersonalCatalogQuery(source=id)).rows.map {it.name})
    }
    @Test fun `a legacy URL source bypasses ETag so a full refresh can repair old marker scope`()=runTest {
        val requests=mutableListOf<String?>()
        val fetcher=PlaylistFetcher {url,tag,_->requests+=tag;PlaylistDownload(text,url,"tag")}
        val original=repository(fetcher);val id=original.save("URL",original.prepareUrl("https://example.com/list.m3u"))
        legacy(id,PlaylistContentKind.VOD)
        val reopened=repository(fetcher);reopened.local();assertTrue(reopened.classificationNeedsRefresh(id))
        assertTrue(reopened.refresh(id));assertNull(requests.last());assertFalse(reopened.classificationNeedsRefresh(id));assertTrue(reopened.videoSources.value.isEmpty())
    }
}
