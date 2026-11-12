package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.PlaylistEncoding
import it.lumetv.core.PlaylistReadOptions
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
class PlaylistPersistenceTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    private var time=1_000_000L
    private val root="https://provider.example/root.m3u"
    private val text="#EXTM3U url-tvg=\"https://guide.example/epg.xml\" tvg-shift=\"2\" catchup-days=\"7\" refresh=\"PT6H\"\n#EXTINF:-1 tvg-id=\"one\" tvg-name=\"Nome guida\" ch-number=\"9999\" tvg-logo=\"../logo.png\",Uno\nhttps://media.example/one.mpd"
    private var answer:suspend(String,String?,String?)->PlaylistDownload={url,_,_->PlaylistDownload(text,url,"etag")}
    private fun repo()=PersonalPlaylistRepository(context,PlaylistFetcher {url,tag,modified->answer(url,tag,modified)},{time})
    @Before fun reset(){File(context.filesDir,"personal-playlists").deleteRecursively()}

    @Test fun `new metadata diagnostics settings and real number persist privately across restart`()=runTest {
        val repo=repo()
        val settings=PlaylistSettings(access=mapOf(PlaylistResource.PLAYLIST to PlaylistAccessRule(mapOf("Authorization" to "synthetic-private"))),artworkHttp=true)
        val id=repo.save("Casa",repo.prepareUrl(root,settings=settings))
        val channel=repo.local().single();assertEquals(9999,channel.displayNumber);assertEquals("",channel.logo)
        val restored=repo();assertEquals(channel,restored.local().single());assertEquals(settings,restored.settings(id))
        assertEquals("2",restored.descriptor(id).attributes["tvg-shift"])
        assertTrue(restored.importReport(id)!!.diagnosticCounts.isNotEmpty())
        assertFalse(restored.state.value.toString().contains("synthetic-private"))
        val store=PersonalPlaylistStore(File(context.filesDir,"personal-playlists"))
        val entry=store.entry(id,channel.id)!!
        assertEquals("Nome guida",entry.metadata.tvgName);assertEquals(7.0,entry.metadata.catchup.days)
        assertEquals("https://provider.example/logo.png",entry.logo)
        SQLiteDatabase.openDatabase(store.file(id).path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            db.rawQuery("SELECT payload FROM records",null).use {c->c.moveToFirst();assertFalse(c.getString(0).contains("catchup-days"))}
        }
    }

    @Test fun `version two migration preserves IDs favorites HTTP and asks refresh to recover discarded fields`()=runTest {
        val original=repo();val id=original.save("Vecchia",original.prepareUrl(root,allowHttp=true));val channel=original.local().single()
        context.getSharedPreferences("lume",Context.MODE_PRIVATE).edit().putStringSet("favorites",setOf(channel.id)).commit()
        val file=File(context.filesDir,"personal-playlists/$id.sqlite")
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            val metadata=db.rawQuery("SELECT value FROM metadata WHERE key='source'",null).use {c->c.moveToFirst();JSONObject(c.getString(0))}
            metadata.put("version",2);listOf("settings","complete","documents","report").forEach(metadata::remove)
            db.execSQL("UPDATE metadata SET value=? WHERE key='source'",arrayOf(metadata.toString()))
            val payload=db.rawQuery("SELECT payload FROM records",null).use {c->c.moveToFirst();JSONObject(c.getString(0))};payload.remove("metadata")
            db.execSQL("UPDATE records SET payload=?",arrayOf(payload.toString()))
        }
        val restored=repo();assertEquals(channel.id,restored.local().single().id)
        assertTrue(restored.state.value.single().allowHttp);assertFalse(restored.state.value.single().metadataComplete)
        assertFalse(restored.settings(id).artworkHttp);assertNull(restored.importReport(id))
        assertEquals(setOf(channel.id),context.getSharedPreferences("lume",Context.MODE_PRIVATE).getStringSet("favorites",emptySet()))
        time+=31_000;var unconditional=false
        answer={url,tag,modified->unconditional=tag==null && modified==null;PlaylistDownload(text,url)}
        assertTrue(restored.refresh(id));assertTrue(unconditional);assertTrue(restored.state.value.single().metadataComplete)
        assertEquals(channel.id,restored.local().single().id);assertTrue(restored.descriptor(id).epgUrls.isNotEmpty())
    }

    @Test fun `nested sources are atomic and failure retains original metadata channels and IDs`()=runTest {
        answer={url,_,_->when(url) {
            root->PlaylistDownload("#EXTM3U tvg-shift=\"2\"\n#EXTINF:-1 type=\"playlist\",Include\nchild.m3u",url)
            else->PlaylistDownload(text,url)
        }}
        val repo=repo();val id=repo.save("Incluse",repo.prepareUrl(root));val before=repo.local()
        assertEquals(2,repo.descriptor(id).documents.size)
        time+=31_000
        answer={url,_,_->if(url==root)PlaylistDownload("#EXTM3U\n#EXTINF:-1 type=\"playlist\",Include\nchild.m3u",url) else PlaylistDownload("<html>Login</html>",url)}
        try {repo.refresh(id);fail("Broken child replaced catalog")}catch(_:it.lumetv.core.PlaylistFormatException){}
        assertEquals(before,repo.local());assertEquals(2,repo.descriptor(id).documents.size)
        assertEquals(before,repo().local())
        assertTrue(File(context.filesDir,"personal-playlists").listFiles().orEmpty().none {it.name.startsWith(".draft-")})
    }

    @Test fun `chosen legacy encoding works and new access replaces an active context`()=runTest {
        val bytes="#EXTM3U\n#EXTINF:-1,Caffè più\nhttps://example.com/live".toByteArray(java.nio.charset.Charset.forName("windows-1252"))
        val repo=repo()
        try {repo.prepareFile(bytes);fail("Malformed UTF8 accepted")}catch(_:java.io.IOException){}
        val settings=PlaylistSettings(reading=PlaylistReadOptions(encoding=PlaylistEncoding.WINDOWS_1252))
        val id=repo.save("Codifica",repo.prepareFile(bytes,settings=settings));assertEquals("Caffè più",repo.local().single().name)
        val old=repo.requestContext(id)!!
        repo.setSettings(id,settings.copy(sessionCookies=true));assertFalse(old.valid());assertNotEquals(old.token,repo.requestContext(id)!!.token)
        val current=repo.requestContext(id)!!;repo.rename(id,"Rinominata");assertEquals(current.token,repo.requestContext(id)!!.token)
        repo.setEnabled(id,false);assertFalse(current.valid());assertNull(repo.requestContext(id))
    }

    @Test fun `manual interval overrides a future provider refresh date while server backoff remains separate`()=runTest {
        val tomorrow=java.time.Instant.ofEpochMilli(time+86_400_000).toString()
        answer={url,_,_->PlaylistDownload(text.replace("refresh=\"PT6H\"","refresh_at=\"$tomorrow\""),url)}
        val repo=repo();val id=repo.save("Intervallo",repo.prepareUrl(root,settings=PlaylistSettings(refreshMinutes=15)))
        time+=15*60_000+1
        assertTrue(repo.refresh(id,force=false))
        repo.setSettings(id,PlaylistSettings());time+=3_600_000
        assertFalse(repo.refresh(id,force=false))
    }
}
