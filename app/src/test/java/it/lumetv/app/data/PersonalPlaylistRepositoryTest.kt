package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.MediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
@Config(sdk = [35])
class PersonalPlaylistRepositoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var time = 1_000_000L
    private val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" tvg-chno=\"5\",Uno\nhttps://example.com/live.m3u8?token=private\n"
    private var answer: suspend (String, String?, String?) -> PlaylistDownload = { url, _, _ -> PlaylistDownload(text, url, "v1") }
    private fun repository() = PersonalPlaylistRepository(context, PlaylistFetcher { url, tag, modified -> answer(url, tag, modified) }, { time })

    @Before fun reset() {
        File(context.filesDir, "personal-playlists").deleteRecursively()
        context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `private playback details are absent from catalog and IDs survive a fresh repository`() = runTest {
        val repo = repository()
        val draft = repo.prepareUrl("https://example.com/list?password=secret")
        val id = repo.save("Casa", draft)
        val channel = repo.local().single()
        assertEquals(id, channel.sourceId); assertTrue(channel.isPersonal)
        assertEquals("", channel.source); assertEquals(PersonalGuideKeys.channel(id,channel.id), channel.epgId); assertTrue(channel.headers.isEmpty())
        assertFalse(channel.toString().contains("private")); assertFalse(repo.state.value.toString().contains("secret"))
        val restored = repository()
        assertEquals(channel, restored.local().single())
        assertEquals("https://example.com/list?password=secret", restored.address(id))
    }

    @Test fun `Video availability reads past the preview and follows enabled sources and content overrides`() = runTest {
        val repo=repository()
        val mixed=buildString {
            appendLine("#EXTM3U")
            for(i in 1..230){appendLine("#EXTINF:${if(i==230)120 else -1},Canale $i");appendLine("https://example.com/item/$i")}
        }
        assertTrue(repo.videoSources.value.isEmpty())
        val id=repo.save("Mista",repo.prepareFile(mixed.toByteArray()))
        assertEquals(200,repo.cached().size);assertTrue(repo.cached().none {it.isVod})
        assertEquals(setOf(id),repo.videoSources.value)
        repo.setEnabled(id,false);assertTrue(repo.videoSources.value.isEmpty())
        repo.rename(id,"Rinominata");assertTrue(repo.videoSources.value.isEmpty())
        repo.setEnabled(id,true);assertEquals(setOf(id),repo.videoSources.value)
        repo.setSettings(id,PlaylistSettings(contentOverride=it.lumetv.core.PlaylistContentKind.LIVE));assertTrue(repo.videoSources.value.isEmpty())
        repo.setSettings(id,PlaylistSettings(contentOverride=it.lumetv.core.PlaylistContentKind.VOD));assertEquals(setOf(id),repo.videoSources.value)
        val restored=repository();restored.local();assertEquals(setOf(id),restored.videoSources.value)
        restored.delete(id);assertTrue(restored.videoSources.value.isEmpty())
    }

    @Test fun `the player picker locates a distant channel within its media kind and favorite filter`() = runTest {
        val repo=repository()
        val content=buildString {
            appendLine("#EXTM3U")
            for(i in 1..450){appendLine("#EXTINF:-1 radio=\"${if(i%2==0)"true" else "false"}\",Canale $i");appendLine("https://example.com/live/$i")}
        }
        val id=repo.save("Mista",repo.prepareFile(content.toByteArray()))
        val distant=repo.catalog(PersonalCatalogQuery(source=id,kind=MediaKind.TV,offset=210,limit=1)).rows.single()
        assertEquals("Canale 421",distant.name)
        assertEquals(210,repo.catalogPosition(distant))
        val first=repo.catalog(PersonalCatalogQuery(source=id,kind=MediaKind.TV,limit=1)).rows.single()
        assertEquals(1,repo.catalogPosition(distant,setOf(first.id,distant.id)))
        assertEquals(0,repo.catalogPosition(distant,emptySet()))
    }

    @Test fun `save progress describes actual preparation and commit phases`() = runTest {
        val repo=repository();val phases=mutableListOf<PlaylistSavePhase>()
        repo.prepareFile(text.toByteArray()).use {draft->repo.save("Casa",draft,progress=phases::add)}
        assertEquals(listOf(PlaylistSavePhase.PREPARING,PlaylistSavePhase.SAVING),phases)
        assertEquals("Uno",repo.local().single().name);assertTrue(repo.videoSources.value.isEmpty())
    }

    @Test fun `a new URL and title preserve identity and disabled sources restore on enabling`() = runTest {
        val repo = repository()
        val id = repo.save("Prima", repo.prepareFile(text.toByteArray()))
        val original = repo.local().single().id
        val changed = text.replace("Uno", "Nuovo nome").replace("token=private", "token=renewed")
        repo.save("Dopo", repo.prepareFile(changed.toByteArray()), id)
        assertEquals(original, repo.local().single().id)
        repo.setEnabled(id, false); assertTrue(repo.local().isEmpty())
        val restored = repository(); assertTrue(restored.local().isEmpty())
        restored.setEnabled(id, true); assertEquals(original, restored.local().single().id)
    }

    @Test fun `a late response cannot resurrect a deleted source or its files`() = runTest {
        val repo = repository()
        val id = repo.save("Casa", repo.prepareUrl("https://example.com/list"))
        time += 31_000
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<PlaylistDownload>()
        answer = { _, _, _ -> started.complete(Unit); response.await() }
        val updating = async { repo.refresh(id) }
        started.await(); repo.delete(id)
        response.complete(PlaylistDownload(text.replace("Uno", "Risposta tardiva"), "https://example.com/list"))
        assertFalse(updating.await()); assertTrue(repo.local().isEmpty())
        assertFalse(File(context.filesDir, "personal-playlists/$id.json").exists())
        assertTrue(repository().local().isEmpty())
    }

    @Test fun `a configuration change invalidates a refresh that is already downloading`() = runTest {
        val repo = repository()
        val id = repo.save("Casa", repo.prepareUrl("https://example.com/list"))
        time += 31_000
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<PlaylistDownload>()
        answer = { _, _, _ -> started.complete(Unit); response.await() }
        val updating = async { repo.refresh(id) }
        started.await(); repo.setEnabled(id, false)
        response.complete(PlaylistDownload(text.replace("Uno", "Tardivo"), "https://example.com/list"))
        assertFalse(updating.await()); assertFalse(repo.state.value.single().enabled)
        repo.setEnabled(id, true); assertEquals("Uno", repo.local().single().name)
    }

    @Test fun `a 304 preserves channels and content timestamp while updating the check time`() = runTest {
        val repo = repository()
        val id = repo.save("Casa", repo.prepareUrl("https://example.com/list"))
        val original = repo.local(); val updated = repo.state.value.single().updated
        time += 31_000
        answer = { url, tag, _ -> assertEquals("v1", tag); PlaylistDownload(null, url, "v2") }
        assertTrue(repo.refresh(id))
        assertEquals(original, repo.local()); assertEquals(updated, repo.state.value.single().updated)
        assertEquals(time, repo.state.value.single().checked)
        time+=31_000
        answer={ url,tag,_ -> assertEquals("v2",tag);PlaylistDownload(null,url) }
        assertTrue(repo.refresh(id))
    }

    @Test fun `cancelling an import closes its stream and removes the unfinished SQLite snapshot`() = runTest {
        val started=CompletableDeferred<Unit>();val closed=java.util.concurrent.atomic.AtomicBoolean()
        val latch=java.util.concurrent.CountDownLatch(1)
        val input=object:java.io.InputStream() {
            override fun read():Int { started.complete(Unit);latch.await();return -1 }
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int { started.complete(Unit);latch.await();return -1 }
            override fun close() { closed.set(true);latch.countDown() }
        }
        answer={url,_,_->PlaylistDownload(null,url,input=input)}
        val repo=repository();val pending=async { repo.prepareUrl("https://example.com/slow") }
        started.await();pending.cancelAndJoin()
        assertTrue(closed.get());assertTrue(repo.local().isEmpty())
        assertTrue(File(context.filesDir,"personal-playlists").listFiles().orEmpty().none { it.name.startsWith(".draft-") })
    }

    @Test fun `SQL identity matching retains separate qualities and restores bounded archived records`() = runTest {
        fun list(hd:String,sd:String)=listOf("#EXTM3U","#EXTINF:-1 tvg-id=\"shared\",HD",hd,"#EXTINF:-1 tvg-id=\"shared\",SD",sd).joinToString("\n")
        val repo=repository();val id=repo.save("Qualita",repo.prepareFile(list("https://example.com/hd.m3u8","https://example.com/sd.m3u8").toByteArray()))
        val old=repo.local().associate { it.name to it.id }
        val onlySd="#EXTM3U\n#EXTINF:-1 tvg-id=\"shared\",SD\nhttps://example.com/sd.m3u8"
        repo.save("Qualita",repo.prepareFile(onlySd.toByteArray()),id)
        assertEquals(old["SD"],repo.local().single().id)
        repo.save("Qualita",repo.prepareFile(list("https://example.com/renewed-hd.m3u8","https://example.com/renewed-sd.m3u8").toByteArray()),id)
        assertEquals(old,repo.local().associate { it.name to it.id })
    }

    @Test fun `truncated refresh retains the previous catalog and reports a bounded safe error`() = runTest {
        val repo = repository()
        val id = repo.save("Casa", repo.prepareUrl("https://example.com/list"))
        val old = repo.local()
        time += 31_000
        answer = { url, _, _ -> PlaylistDownload("#EXTM3U\n#EXTINF:-1,Incomplete", url) }
        try { repo.refresh(id); fail("Truncated list accepted") } catch (_: it.lumetv.core.PlaylistFormatException) { }
        assertEquals(old, repo.local()); assertTrue(repo.state.value.single().error.isNotEmpty())
        assertFalse(repo.state.value.single().refreshing)
        assertEquals(old, repository().local())
    }

    @Test fun `sources are independent and deletion clears only their favorites and recents`() = runTest {
        val repo = repository()
        val a = repo.save("A", repo.prepareFile(text.toByteArray()))
        val b = repo.save("B", repo.prepareFile(text.toByteArray(), MediaKind.RADIO))
        val ca = repo.local().single { it.sourceId == a }; val cb = repo.local().single { it.sourceId == b }
        assertNotEquals(ca.id, cb.id)
        val prefs = context.getSharedPreferences("lume", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorites", setOf(ca.id, cb.id, "official")).putString("recent_channels", "${ca.id},${cb.id},official")
            .putString("last_channel", ca.id).putString("last_tv_channel", "official").commit()
        repo.delete(a)
        assertEquals(setOf(cb.id, "official"), prefs.getStringSet("favorites", emptySet()))
        assertEquals("${cb.id},official", prefs.getString("recent_channels", "")); assertFalse(prefs.contains("last_channel"))
        assertEquals("official", prefs.getString("last_tv_channel", null))
    }

    @Test fun `an unreadable source is isolated and an unreadable index is never overwritten`() = runTest {
        val repo = repository()
        val a = repo.save("A", repo.prepareFile(text.toByteArray()))
        val b = repo.save("B", repo.prepareFile(text.toByteArray()))
        File(context.filesDir, "personal-playlists/$a.sqlite").writeText("broken")
        val restored = repository()
        assertEquals(b, restored.local().single().sourceId)
        assertEquals(2, restored.state.value.size)
        val index = File(context.filesDir, "personal-playlists/index.json")
        index.writeText("broken index")
        val blocked = repository(); assertTrue(blocked.local().isEmpty())
        assertNotNull(blocked.storageError)
        try { blocked.save("C", blocked.prepareFile(text.toByteArray())); fail("Corrupt index overwritten") } catch (_: java.io.IOException) { }
        assertEquals("broken index", index.readText())
    }

    @Test fun `HTTP is explicit per source and revocation survives restart`() = runTest {
        val repo=repository()
        var requested=false
        answer={ url,_,_ -> requested=true;PlaylistDownload(text.replace("https://example.com/live","http://example.com/live"),url) }
        try { repo.prepareUrl("http://example.com/list");fail("HTTP fetched without consent") } catch(_:java.io.IOException) {}
        assertFalse(requested)
        val id=repo.save("HTTP",repo.prepareUrl("http://example.com/list",allowHttp=true))
        val channel=repo.local().single()
        assertTrue(repo.resolve(channel.id,okhttp3.OkHttpClient()).personalAllowHttp)
        repo.setHttpAllowed(id,false)
        try { repo.resolve(channel.id,okhttp3.OkHttpClient());fail("HTTP playback authorized after revocation") } catch(_:it.lumetv.core.PlaylistFormatException) {}
        val restored=repository();assertEquals(channel.id,restored.local().single().id);assertFalse(restored.state.value.single().allowHttp)
    }

    @Test fun `HTTP consent changes clear local failures but retain server rate limits across restart`() = runTest {
        val repo=repository();val id=repo.save("HTTP",repo.prepareUrl("https://example.com/list"))
        time+=31_000
        answer={_,_,_->throw java.io.IOException("local policy failure")}
        try { repo.refresh(id);fail("Failure ignored") } catch(_:java.io.IOException) {}
        repo.setHttpAllowed(id,true)
        answer={url,_,_->PlaylistDownload(text,url)}
        assertTrue(repo.refresh(id))
        time+=31_000
        answer={_,_,_->throw PlaylistFetchException(429,time+120_000,"Il servizio richiede una pausa.")}
        try { repo.refresh(id);fail("Rate limit ignored") } catch(_:PlaylistFetchException) {}
        repo.setHttpAllowed(id,false);repo.setHttpAllowed(id,true)
        answer={url,_,_->PlaylistDownload(text,url)}
        val restored=repository();assertFalse(restored.refresh(id))
        time+=120_001;assertTrue(restored.refresh(id))
    }

    @Test fun `invalid catalog rows isolate their source while preserving the rest of the catalog`() = runTest {
        val repo=repository();val bad=repo.save("Danneggiata",repo.prepareFile(text.toByteArray()))
        val good=repo.save("Valida",repo.prepareFile(text.toByteArray()))
        android.database.sqlite.SQLiteDatabase.openDatabase(File(context.filesDir,"personal-playlists/$bad.sqlite").absolutePath,
            null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE records SET groups_json='invalid JSON'")
        }
        val restored=repository();assertEquals(good,restored.local().single().sourceId)
        assertEquals("Lista non leggibile",restored.state.value.first { it.id==bad }.name)
        assertTrue(File(context.filesDir,"personal-playlists/$bad.sqlite").isFile)
    }

    @Test fun `version one JSON migrates to SQLite without changing IDs favorites or private playback`() = runTest {
        val id="user_"+"1".repeat(32);val channel="a".repeat(24)
        val dir=File(context.filesDir,"personal-playlists").apply { mkdirs() }
        File(dir,"index.json").writeText(JSONObject().put("version",1).put("ids",org.json.JSONArray(listOf(id))).toString())
        val record=JSONObject().put("id",channel).put("name","Migrato").put("url","https://example.com/one.m3u8?secret=private")
            .put("tvgId","one").put("logo","").put("number",5).put("groups",org.json.JSONArray(listOf("Test"))).put("kind","TV")
            .put("headers",JSONObject().put("Authorization","private-token")).put("position",0).put("active",true).put("seen",time)
        File(dir,"$id.json").writeText(JSONObject().put("version",1).put("id",id).put("name","Da 0.8.0").put("location","URL")
            .put("address","https://example.com/list").put("enabled",true).put("kind","TV").put("updated",time).put("checked",time)
            .put("revision",4).put("records",org.json.JSONArray().put(record)).toString())
        val prefs=context.getSharedPreferences("lume",Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorites",setOf(channel)).putString("last_channel",channel).commit()
        val repo=repository();assertEquals(channel,repo.local().single().id)
        assertTrue(File(dir,"$id.sqlite").isFile);assertFalse(File(dir,"$id.json").exists())
        assertEquals(setOf(channel),prefs.getStringSet("favorites",emptySet()))
        assertEquals(channel,prefs.getString("last_channel",null))
        assertEquals("private-token",repo.resolve(channel,okhttp3.OkHttpClient()).headers["Authorization"])
        assertEquals(channel,repository().local().single().id)
        assertFalse(repo.local().toString().contains("private"))
        assertEquals(listOf("Test"),repo.groups(PersonalCatalogQuery(source=id)).first)
    }

    @Test fun `large files stream into disk snapshots and indexed reconciliation retains identities`() = runTest(timeout=kotlin.time.Duration.parse("120s")) {
        val input=File(context.cacheDir,"large.m3u")
        fun generate(suffix:String) { input.bufferedWriter().use { out ->
            out.appendLine("#EXTM3U")
            for(i in 1..14000) { out.appendLine("#EXTINF:-1 tvg-id=\"id$i\" group-title=\"Test\",Canale $i")
                out.appendLine("https://example.com/$i.m3u8?token="+"x".repeat(650)+suffix) }
        } }
        generate("before");assertTrue(input.length()>8*1024*1024)
        val repo=repository();var bytes=0L
        val draft=repo.prepareFile(input,progress={ bytes=it.bytes })
        assertEquals(14000,draft.report.count);assertEquals(5,draft.report.sample.size);assertEquals(input.length(),bytes)
        val id=repo.save("Grande",draft);val old=repo.local().map { it.id }
        assertTrue(old.size<=PersonalPlaylistRepository.CATALOG_WINDOW)
        val lastId=repo.catalog(PersonalCatalogQuery(source=id,offset=13999,limit=1)).rows.single().id
        generate("after");repo.save("Grande",repo.prepareFile(input),id)
        assertEquals(old,repo.local().map { it.id });assertEquals(old,repository().local().map { it.id })
        assertEquals(lastId,repo.catalog(PersonalCatalogQuery(source=id,offset=13999,limit=1)).rows.single().id)
        assertTrue(repo.resolve(lastId,okhttp3.OkHttpClient()).url.endsWith("after"))
        assertTrue(File(context.filesDir,"personal-playlists").listFiles().orEmpty().none { it.name.startsWith(".draft-") })
        input.delete()
    }
}
