package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import it.lumetv.core.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PersonalBackupRepositoryTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private val password="Una password 2026".toCharArray()
    private val prefs get()=app.getSharedPreferences("lume",0)
    private val history get()=PersonalPlaybackStore.get(app)
    private fun repo()=PersonalPlaylistRepository(app)
    private fun manager(repo:PersonalPlaylistRepository)=PersonalBackupRepository(app,repo){emptyList()}
    private fun input(prefix:String="Prova",count:Int=3)=buildString {
        val encoded=java.net.URLEncoder.encode(prefix,"UTF-8").replace("+","%20")
        appendLine("#EXTM3U")
        repeat(count) {i->
            appendLine("#EXTINF:-1 type=\"movie\" tvg-id=\"$prefix-$i\",$prefix $i")
            appendLine("https://example.org/$encoded/$i.mp4?token=private-backup-fixture")
        }
    }.toByteArray()
    private suspend fun source(repo:PersonalPlaylistRepository,name:String="Cinema",count:Int=3)=
        repo.prepareFile(input(name,count),settings=PlaylistSettings(guide=PersonalGuideSettings(enabled=false))).use {repo.save(name,it)}
    private suspend fun items(repo:PersonalPlaylistRepository,id:String)=repo.videos(VideoQuery(source=id,collapseSeries=false)).rows
    private fun target(item:VideoItem)=PlaybackTarget(item.id,PlaybackMode.VOD)
    @Before fun reset() {
        File(app.filesDir,"personal-playlists").deleteRecursively();File(app.filesDir,"personal-watch.sqlite").delete()
        File(app.cacheDir,"backup-work").deleteRecursively();prefs.edit().clear().commit()
    }
    @Test fun `full backup preserves stable identities access configuration favorites hidden progress and preferences`()=runTest {
        val repo=repo();val source=source(repo);val rows=items(repo,source)
        repo.setSettings(source,PlaylistSettings(access=mapOf(PlaylistResource.MEDIA to PlaylistAccessRule(mapOf("Authorization" to "fixture-secret"))),guide=PersonalGuideSettings(enabled=false)))
        history.write(source,target(rows[0]),42_000,180_000);history.hide(source,target(rows[0]),true)
        history.write(source,target(rows[1]),110_000,180_000,true)
        prefs.edit().putStringSet("favorites",setOf(rows[0].id)).putStringSet("video_saved_series",setOf("$source:orbita"))
            .putString("audio_language","en").putString("subtitle_mode","OFF").putBoolean("data_saver",true).commit()
        val manager=manager(repo);val file=manager.export(password)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("fixture-secret"))
        manager.prepare(file,password).use {preview->
            assertFalse(preview.toString().contains("token"));assertEquals(1,preview.favorites);assertEquals(2,preview.watches.size)
            repo.delete(source);prefs.edit().clear().commit()
            val result=manager.restore(preview,BackupSelection.ALL)
            assertEquals(1,result.playlists);assertEquals(2,result.watch);assertEquals(1,result.favorites)
            assertEquals(rows.map {it.id},items(repo,source).map {it.id})
            assertEquals("fixture-secret",repo.settings(source).rule(PlaylistResource.MEDIA).headers["Authorization"])
            assertEquals(WatchPosition(42_000,180_000,false,true),history.read(source,target(rows[0])))
            assertTrue(history.read(source,target(rows[1])).completed)
            assertTrue(prefs.getBoolean("data_saver",false));assertEquals("en",prefs.getString("audio_language",null))
            assertEquals(setOf("$source:orbita"),prefs.getStringSet("video_saved_series",emptySet()))
        }
        assertEquals(1,PersonalPlaylistRepository(app).local().count {it.id==rows[0].id})
    }
    @Test fun `existing lists and watch decisions win while favorites merge and repeated restore is idempotent`()=runTest {
        val repo=repo();val source=source(repo);val rows=items(repo,source)
        history.write(source,target(rows[0]),42_000,180_000);history.write(source,target(rows[1]),25_000,180_000)
        prefs.edit().putStringSet("favorites",setOf(rows[0].id)).commit()
        val manager=manager(repo);val file=manager.export(password)
        repo.rename(source,"Nome attuale");history.write(source,target(rows[0]),90_000,180_000,true)
        prefs.edit().putStringSet("favorites",setOf(rows[2].id)).commit()
        manager.prepare(file,password).use {preview->
            val first=manager.restore(preview,BackupSelection.ALL)
            assertEquals(0,first.playlists);assertEquals(1,first.keptPlaylists);assertEquals(0,first.watch)
            assertEquals("Nome attuale",repo.state.value.single().name)
            assertEquals(WatchPosition(90_000,180_000,true),history.read(source,target(rows[0])))
            assertEquals(setOf(rows[0].id,rows[2].id),prefs.getStringSet("favorites",emptySet()))
            assertEquals(0,manager.restore(preview,BackupSelection.ALL).favorites)
            assertEquals(1,repo.state.value.size)
        }
    }
    @Test fun `individual list choices keep unrelated data and skip unavailable references`()=runTest {
        val repo=repo();val a=source(repo,"Uno");val b=source(repo,"Due");val row=items(repo,b).first()
        history.write(b,target(row),42_000,180_000);prefs.edit().putStringSet("favorites",setOf(row.id)).commit()
        val manager=manager(repo);val file=manager.export(password)
        repo.delete(a);repo.delete(b)
        manager.prepare(file,password).use {preview->
            val result=manager.restore(preview,BackupSelection(BackupCategory.entries.toSet(),setOf(a)))
            assertEquals(listOf(a),repo.state.value.map {it.id});assertEquals(0,result.watch);assertEquals(0,result.favorites);assertTrue(result.skipped>=2)
            assertEquals(WatchPosition(),history.read(b,target(row)))
        }
    }
    @Test fun `preferences only never imports playlists favorites or progress or device consents`()=runTest {
        val repo=repo();val source=source(repo);val row=items(repo,source).first()
        prefs.edit().putStringSet("favorites",setOf(row.id)).putString("audio_language","en").putBoolean("aura_allowed",true).commit()
        history.write(source,target(row),42_000,180_000)
        val manager=manager(repo);val file=manager.export(password);repo.delete(source);prefs.edit().clear().commit()
        manager.prepare(file,password).use {preview->
            manager.restore(preview,BackupSelection(setOf(BackupCategory.PREFERENCES)))
            assertTrue(repo.state.value.isEmpty());assertTrue(history.backupRows().isEmpty());assertTrue(prefs.getStringSet("favorites",emptySet()).orEmpty().isEmpty())
            assertEquals("en",prefs.getString("audio_language",null));assertFalse(prefs.contains("aura_allowed"))
        }
    }
    @Test fun `incorrect password and corrupt files cannot change installed data and leave no plaintext staging`()=runTest {
        val repo=repo();val source=source(repo);val manager=manager(repo);val file=manager.export(password)
        assertFails {manager.prepare(file,"Password diversa".toCharArray())}
        val broken=File(app.cacheDir,"broken.lumebackup").apply {writeBytes(file.readBytes().dropLast(12).toByteArray())}
        assertFails {manager.prepare(broken,password)}
        assertEquals(listOf(source),repo.state.value.map {it.id})
        assertTrue(File(app.cacheDir,"backup-work").listFiles().orEmpty().none {it.isDirectory})
    }
    @Test fun `future formats archive paths duplicate identities and oversized rows are rejected before restore`()=runTest {
        val repo=repo();source(repo,"Uno");source(repo,"Due");val manager=manager(repo);val file=manager.export(password)
        val original=archive(file)
        val future=original.toMutableMap().apply {this["manifest.json"]=JSONObject(this.getValue("manifest.json").toString(Charsets.UTF_8)).put("version",99).toString().toByteArray()}
        val path=original.toMutableMap().apply {this["../outside"]=byteArrayOf(1)}
        val lists=original.keys.filter {it.startsWith("lists/")}
        val same=original.toMutableMap()
        val first=original.getValue(lists[0]).toString(Charsets.UTF_8).lines()[1].let(::JSONObject).getString("id")
        val secondLines=original.getValue(lists[1]).toString(Charsets.UTF_8).lines().toMutableList()
        secondLines[1]=JSONObject(secondLines[1]).put("id",first).toString();same[lists[1]]=secondLines.joinToString("\n").toByteArray()
        val huge=original.toMutableMap().apply {this[lists[0]]=original.getValue(lists[0])+"x".repeat(BackupIO.MAX_ROW+1).toByteArray()}
        for(entries in listOf(future,path,same,huge))assertFails {manager.prepare(pack(entries),password)}
        assertEquals(2,repo.state.value.size)
        assertFalse(File(app.cacheDir,"outside").exists())
    }
    @Test fun `failed commit rolls back preferences added history and newly copied playlists`()=runTest {
        val repo=repo();val keep=source(repo,"Attuale");val restore=source(repo,"Da ripristinare")
        val row=items(repo,restore).first()
        history.write(restore,target(row),42_000,180_000);prefs.edit().putString("audio_language","en").putStringSet("favorites",setOf(row.id)).commit()
        val manager=manager(repo);val file=manager.export(password)
        repo.delete(restore);prefs.edit().putString("audio_language","it").putStringSet("favorites",emptySet()).commit()
        manager.prepare(file,password).use {preview->
            val index=File(app.filesDir,"personal-playlists/index.json");val saved=index.readBytes()
            val blocked=File(index.path+".new").apply {mkdir();resolve("block").writeText("fail index creation")}
            try {
                assertFails {manager.restore(preview,BackupSelection.ALL)}
                assertEquals("it",prefs.getString("audio_language",null));assertTrue(prefs.getStringSet("favorites",emptySet()).orEmpty().isEmpty())
                assertEquals(WatchPosition(),history.read(restore,target(row)))
                assertEquals(listOf(keep),repo.state.value.map {it.id})
                assertFalse(File(app.filesDir,"personal-playlists/$restore.sqlite").exists())
            }finally {blocked.deleteRecursively();index.writeBytes(saved)}
        }
    }
    @Test fun `name collisions add a distinct source without renaming the current one`()=runTest {
        val repo=repo();val original=source(repo,"Cinema");val manager=manager(repo);val file=manager.export(password);repo.delete(original)
        val current=source(repo,"Cinema")
        manager.prepare(file,password).use {manager.restore(it,BackupSelection.ALL)}
        assertEquals("Cinema",repo.state.value.first {it.id==current}.name)
        assertEquals("Cinema (backup)",repo.state.value.first {it.id==original}.name)
    }
    @Test fun `abandoned plaintext previews are cleaned without deleting a live preview`()=runTest {
        val repo=repo();source(repo);val manager=manager(repo);val file=manager.export(password)
        val abandoned=File(app.cacheDir,"backup-work/preview-abandoned").apply {mkdirs();resolve("data").writeText("private")}
        manager.prepare(file,password).use {live->
            assertFalse(abandoned.exists())
            manager(repo).export(password)
            assertTrue(live.directory.isDirectory)
        }
        assertTrue(File(app.cacheDir,"backup-work").listFiles().orEmpty().none {it.isDirectory})
    }
    @Test fun `live and radio layout merges retain the current order and restore hidden channels`()=runTest {
        val repo=repo()
        val input=("#EXTM3U\n#EXTINF:-1 type=\"live\",Prima\nhttps://example.org/a.m3u8\n"+
            "#EXTINF:-1 type=\"live\",Seconda\nhttps://example.org/b.m3u8\n"+
            "#EXTINF:-1 radio=\"true\",Radio\nhttps://example.org/audio.mp3\n").toByteArray()
        val source=repo.prepareFile(input).use {repo.save("Dirette",it)}
        val rows=repo.catalog(PersonalCatalogQuery(source=source,content=null)).rows
        val layout=ChannelPersonalization.get(app);val order=rows.reversed().map {it.id}
        prefs.edit().putStringSet("favorites",rows.map {it.id}.toSet()).commit()
        assertTrue(layout.reorder(order,rows.map {it.id},layout.revision.value,rows.associate {it.id to source}))
        layout.hide(rows[0])
        val manager=manager(repo);val file=manager.export(password)
        prefs.edit().remove("channel_layout").putStringSet("favorites",setOf(rows[1].id)).commit()
        assertTrue(layout.reorder(listOf(rows[1].id),listOf(rows[1].id),layout.revision.value,mapOf(rows[1].id to source)))
        manager.prepare(file,password).use {manager.restore(it,BackupSelection(setOf(BackupCategory.PERSONAL)))}
        assertEquals(listOf(rows[1].id)+order.filter {it!=rows[1].id},layout.order)
        assertEquals(setOf(rows[0].id),layout.hiddenIds)
        assertEquals(rows.map {it.id}.toSet(),prefs.getStringSet("favorites",emptySet()))
    }
    @Test fun `built in TV and radio keep their distinct owners favorites and hidden states`()=runTest {
        val repo=repo()
        val tv=Channel("a".repeat(24),"TV integrata",1,"","","","https://example.org/tv.m3u8","direct","tv")
        val radio=tv.copy(id="b".repeat(24),name="Radio integrata",kind=MediaKind.RADIO,sourceId=BUILTIN_RADIO_SOURCE)
        val manager=PersonalBackupRepository(app,repo){listOf(tv,radio)}
        val layout=ChannelPersonalization.get(app)
        prefs.edit().putStringSet("favorites",setOf(tv.id,radio.id)).commit()
        layout.hide(radio)
        assertTrue(layout.reorder(listOf(radio.id,tv.id),listOf(tv.id,radio.id),layout.revision.value,mapOf(tv.id to tv.sourceId,radio.id to radio.sourceId)))
        val file=manager.export(password);prefs.edit().clear().commit()
        manager.prepare(file,password).use {manager.restore(it,BackupSelection(setOf(BackupCategory.PERSONAL)))}
        assertEquals(setOf(tv.id,radio.id),prefs.getStringSet("favorites",emptySet()))
        assertEquals(listOf(radio.id,tv.id),layout.order)
        assertEquals(BUILTIN_RADIO_SOURCE,layout.hidden.single().source)
        assertEquals(setOf(radio.id),layout.sourceIds(BUILTIN_RADIO_SOURCE))
    }
    @Test fun `source limit is checked before any preference or list is changed`()=runTest {
        val repo=repo();val backupSource=source(repo,"Backup");val manager=manager(repo);val file=manager.export(password);repo.delete(backupSource)
        repeat(10){source(repo,"Attuale"+it,1)}
        prefs.edit().putString("audio_language","it").commit()
        manager.prepare(file,password).use {assertFails {manager.restore(it,BackupSelection.ALL)}}
        assertEquals(10,repo.state.value.size);assertEquals("it",prefs.getString("audio_language",null))
        assertFalse(File(app.filesDir,"personal-playlists/$backupSource.sqlite").exists())
    }
    @Test fun `streamed lists extend beyond the display window and restore keeps playback identities`()=runTest {
        val repo=repo();val source=source(repo,"Lunga",1200);val page=repo.catalog(PersonalCatalogQuery(source=source,content=null,offset=1000,limit=50))
        val manager=manager(repo);val file=manager.export(password);repo.delete(source)
        manager.prepare(file,password).use {manager.restore(it,BackupSelection.ALL)}
        val restored=repo.catalog(PersonalCatalogQuery(source=source,content=null,offset=1000,limit=50))
        assertEquals(1200,restored.total);assertEquals(page.rows.map {it.id},restored.rows.map {it.id})
        assertTrue(repo.playbackEntry(restored.rows.last().id).entry.url.contains("private-backup-fixture"))
    }
    @Test fun `history capacity import never evicts existing records and imports invalidate pending writes`() {
        val source="user_"+"a".repeat(32);val file=history.database()
        SQLiteDatabase.openOrCreateDatabase(file,null).use {db->db.beginTransaction();try {
            db.compileStatement("INSERT INTO watch(source,channel,mode,event,position,duration,completed,hidden,updated) VALUES(?,?,'VOD','',6000,90000,0,0,1)").use {insert->
                repeat(5000){insert.bindString(1,source);insert.bindString(2,it.toString().padStart(24,'0'));insert.executeInsert()}
            };db.setTransactionSuccessful()
        }finally {db.endTransaction()}}
        val next=BackupWatch(source,PlaybackTarget("f".repeat(24),PlaybackMode.VOD),WatchPosition(42_000,90_000),2)
        assertEquals(0,history.restoreMissing(listOf(next)){imported,_->imported})
        assertEquals(5000,history.backupRows().size)
        history.removeSource(source)
        val ticket=history.session(source,next.target)
        assertEquals(1,history.restoreMissing(listOf(next)){imported,_->imported})
        assertFalse(history.record(ticket,60_000,90_000,false))
        assertEquals(42_000L,history.read(source,next.target).resume)
    }
    private suspend fun assertFails(block:suspend ()->Any?) {
        try {block();fail("An invalid backup must be rejected")}catch(_:IOException){}
    }
    private fun archive(file:File):Map<String,ByteArray> {
        val bytes=ByteArrayOutputStream();file.inputStream().use {BackupCipher.decrypt(it,bytes,password)}
        return ZipInputStream(bytes.toByteArray().inputStream()).use {zip->buildMap {while(true){val entry=zip.nextEntry ?: break;put(entry.name,zip.readBytes())}}}
    }
    private fun pack(entries:Map<String,ByteArray>):File {
        val file=File.createTempFile("backup-test-",".lumebackup",app.cacheDir)
        file.outputStream().use {output->BackupCipher.encrypt(output,password){encrypted->
            ZipOutputStream(encrypted).use {zip->entries.forEach {(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}}
        }}
        return file
    }
}
