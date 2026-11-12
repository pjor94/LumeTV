package it.lumetv.app.playlists

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.Debug
import android.graphics.Color
import it.lumetv.app.data.ChannelLogoRepository
import it.lumetv.app.data.HttpClient
import it.lumetv.app.data.PersonalPlaylistRepository
import it.lumetv.app.data.PlaylistAccessRule
import it.lumetv.app.data.PlaylistResource
import it.lumetv.app.data.PlaylistSettings
import it.lumetv.core.PlaylistEncoding
import it.lumetv.core.PlaylistReadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI

/** Called only by the test APK's hardware-guarded instrumentation runner. */
object PlaylistInspection {
    fun run(context:Context,args:Bundle):JSONObject = runBlocking(Dispatchers.IO) {
        val root=requireNotNull(args.getString("fixtureRoot"))
        check(URI(root).host=="10.0.2.2" && URI(root).scheme=="http") {"Local emulator fixtures required"}
        when(args.getString("operation")) {
            "playlist-compatibility" -> compatibility(context,root,args.getString("cdnOrigin").orEmpty())
            "playlist-capacity" -> capacity(context,root)
            else -> error("Unsupported playlist inspection")
        }
    }
    private suspend fun compatibility(context:Context,root:String,cdn:String):JSONObject {
        var now=System.currentTimeMillis()-60_000
        val repo=PersonalPlaylistRepository(context,now={now})
        repo.local();repo.state.value.forEach {repo.delete(it.id)}
        val access=PlaylistSettings(access=mapOf(PlaylistResource.PLAYLIST to PlaylistAccessRule(mapOf("X-List" to "fixture-list"),setOf(cdn)))).validated()
        val id=repo.save("Compatibilità avanzata",repo.prepareUrl("$root/parent.m3u",allowHttp=true,settings=access))
        val channels=repo.local();check(channels.size==2)
        check(channels.any {it.displayNumber==9999});check(channels.all {it.source.isEmpty() && it.headers.isEmpty() && it.logo.isEmpty()})
        check(repo.descriptor(id).documents.size==2)
        check(repo.importReport(id)?.excluded==1)
        check(repo.importReport(id)?.drmEntries==1)
        val sourceDb=File(context.filesDir,"personal-playlists/$id.sqlite")
        SQLiteDatabase.openDatabase(sourceDb.path,null,SQLiteDatabase.OPEN_READONLY).use {db->
            db.rawQuery("SELECT payload FROM records WHERE active=1",null).use {cursor->
                while(cursor.moveToNext()) {
                    val entry=JSONObject(cursor.getString(0));val m=entry.getJSONObject("metadata")
                    check(m.getJSONObject("attributes").has("tvg-name"))
                    check(!m.has("defaults"))
                }
            }
        }
        val restored=PersonalPlaylistRepository(context,now={now});check(restored.local().map {it.id}==channels.map {it.id})
        check(restored.settings(id)==access);check(restored.descriptor(id).attributes["tvg-shift"]=="2")
        val token=restored.requestContext(id)!!
        restored.setSettings(id,access.copy(sessionCookies=true));check(!token.valid())
        val live=restored.requestContext(id)!!;restored.setEnabled(id,false);check(!live.valid());restored.setEnabled(id,true)
        val cp="#EXTM3U\n#EXTINF:-1,Caffè più\n$root/plain.mp4".toByteArray(java.nio.charset.Charset.forName("windows-1252"))
        val file=restored.save("Codifica",restored.prepareFile(cp,allowHttp=true,settings=PlaylistSettings(reading=PlaylistReadOptions(encoding=PlaylistEncoding.WINDOWS_1252))))
        check(restored.local().any {it.name=="Caffè più"})
        restored.delete(file)
        // Convert the fixture to the previous persisted shape and exercise the real native migration.
        SQLiteDatabase.openDatabase(sourceDb.path,null,SQLiteDatabase.OPEN_READWRITE).use {db->
            val value=db.rawQuery("SELECT value FROM metadata WHERE key='source'",null).use {cursor->cursor.moveToFirst();JSONObject(cursor.getString(0))}
            value.put("version",2);listOf("complete","documents","settings","report").forEach(value::remove)
            db.execSQL("UPDATE metadata SET value=? WHERE key='source'",arrayOf(value.toString()))
        }
        val old=PersonalPlaylistRepository(context,now={now});check(old.local().map {it.id}==channels.map {it.id})
        check(!old.state.value.single().metadataComplete);check(!old.settings(id).artworkHttp)
        // The old source now has no custom headers, so restore the access before recovering metadata.
        old.setSettings(id,access);now+=31_000
        check(old.refresh(id));check(old.state.value.single().metadataComplete)
        check(old.local().map {it.id}==channels.map {it.id})
        old.setSettings(id,access.copy(refreshMinutes=15));now+=15*60_000+1
        check(old.refresh(id,force=false)) {"Manual interval was blocked by provider refresh date"}
        check(File(context.filesDir,"personal-playlists").listFiles().orEmpty().none {it.name.startsWith(".draft-")}) {"Import sidecars remain after commit"}
        val aes=old.save("AES",old.prepareUrl("$root/aes.m3u",allowHttp=true))
        val aesChannel=old.local().single {it.sourceId==aes}.id
        artwork(context,old,root)
        return JSONObject().put("passed",true).put("source",id).put("channels",JSONArray(channels.map {it.id}))
            .put("aesChannel",aesChannel)
            .put("checks",JSONArray(listOf("native nested downloads with scoped authentication","per-document metadata survives restart","real channel 9999",
                "unsupported certificate is reported","source context invalidation","Windows-1252 import","native version 2 migration and ID preservation","metadata recovery via refresh",
                "native HTTP artwork has separate consent","same artwork URL stays isolated by source headers","artwork access change invalidates cached bitmap",
                "manual interval overrides provider refresh date")))
    }
    private suspend fun artwork(context:Context,repo:PersonalPlaylistRepository,root:String) {
        val url="http://artwork.fixture.example:${URI(root).port}/fixture-logo.png"
        fun settings(color:String)=PlaylistSettings(artworkHttp=true,access=mapOf(PlaylistResource.LOGO to PlaylistAccessRule(mapOf("X-Logo" to color))))
        val text="#EXTM3U\n#EXTINF:-1 tvg-logo=\"$url\",Logo\nhttps://media.example/video.mp4"
        val a=repo.save("Loghi A",repo.prepareFile(text.toByteArray(),settings=settings("red")))
        val b=repo.save("Loghi B",repo.prepareFile(text.toByteArray(),settings=settings("blue")))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        // Only this test instance maps the public fixture name to the emulator's local server.
        val http=HttpClient(OkHttpClient.Builder().dns(object:Dns {
            override fun lookup(hostname:String)=if(hostname=="artwork.fixture.example")listOf(java.net.InetAddress.getByName("10.0.2.2")) else Dns.SYSTEM.lookup(hostname)
        }).build())
        val logos=ChannelLogoRepository(context,http,scope,artworkAddressAllowed={it.hostAddress=="10.0.2.2"})
        try {
            val source=PersonalPlaylistRepository.get(context);source.local()
            suspend fun pixel(id:String):Int {
                val result=CompletableDeferred<Int>()
                logos.request(source.local().single {it.sourceId==id}) {image -> if(!result.isCompleted)result.complete(image?.bitmap?.getPixel(0,0) ?: 0)}
                return withTimeout(20_000){result.await()}
            }
            check(pixel(a)==Color.RED) {"HTTP artwork failed"};check(pixel(b)==Color.BLUE) {"Artwork crossed source boundary"}
            source.setSettings(a,settings("green"));check(pixel(a)==Color.GREEN) {"Artwork access change reused an old bitmap"}
            check(pixel(b)==Color.BLUE) {"Changing one source affected another artwork cache"}
        } finally {scope.cancel()}
    }
    private suspend fun capacity(context:Context,root:String):JSONObject {
        var now=System.currentTimeMillis()-60_000
        val repo=PersonalPlaylistRepository(context,now={now});repo.local();repo.state.value.forEach {repo.delete(it.id)}
        val metrics=JSONArray();var largeId="";var firstId=""
        val started=System.nanoTime()
        val sizes=listOf(50_000,42_000)+List(8){1000}
        sizes.forEachIndexed {index,size->
            val before=System.nanoTime()
            val draft=repo.prepareUrl("$root/large.m3u?count=$size&set=$index&v=0",allowHttp=true)
            check(draft.report.count==size)
            val id=try {repo.save("Carico ${index+1}",draft)}finally {draft.close()}
            if(index==0) {largeId=id;firstId=repo.local().first {it.sourceId==id}.id}
            metrics.put(JSONObject().put("entries",size).put("milliseconds",(System.nanoTime()-before)/1_000_000).put("pssKiB",Debug.getPss()))
        }
        check(repo.local().size==100_000);check(repo.state.value.size==10)
        val channel=repo.local().first {it.sourceId==largeId}
        context.getSharedPreferences("lume",Context.MODE_PRIVATE).edit().putStringSet("favorites",setOf(firstId)).commit()
        repo.prepareUrl("$root/large.m3u?count=1&set=extra&v=0",allowHttp=true).use {draft->
            try {repo.save("Undicesima",draft);error("Source limit ignored")}catch(expected:java.io.IOException){check(repo.state.value.size==10)}
        }
        now+=31_000
        check(repo.refresh(largeId));check(repo.local().first {it.sourceId==largeId}.id==channel.id)
        check(context.getSharedPreferences("lume",Context.MODE_PRIVATE).getStringSet("favorites",emptySet())==setOf(firstId))
        val small=repo.state.value.last().id;repo.delete(small)
        repo.prepareUrl("$root/large.m3u?count=1001&set=extra&v=0",allowHttp=true).use {draft->
            try {repo.save("Oltre limite",draft);error("Total limit ignored")}catch(expected:java.io.IOException){check(repo.local().size==99_000)}
        }
        repo.prepareUrl("$root/large.m3u?count=1000&set=extra&v=0",allowHttp=true).use {repo.save("Carico 10",it)}
        val restored=PersonalPlaylistRepository(context,now={now});check(restored.local().size==100_000);check(restored.state.value.size==10)
        check(restored.local().first {it.sourceId==largeId}.id==firstId)
        check(File(context.filesDir,"personal-playlists").listFiles().orEmpty().none {it.name.startsWith(".draft-")})
        return JSONObject().put("passed",true).put("entries",100_000).put("sources",10).put("largestSource",50_000)
            .put("milliseconds",(System.nanoTime()-started)/1_000_000).put("pssKiB",Debug.getPss()).put("maxHeapBytes",Runtime.getRuntime().maxMemory())
            .put("imports",metrics).put("firstChannel",firstId).put("lastChannel",restored.local().last().id)
            .put("checks",JSONArray(listOf("100000 native catalog rows","10 independent sources","50000 rows in one source",
                "source and total limits keep prior data","50000 row refresh preserves IDs and favorite","100000 rows survive fresh repository","drafts cleaned")))
    }
}
