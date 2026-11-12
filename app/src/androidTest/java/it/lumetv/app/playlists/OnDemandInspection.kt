package it.lumetv.app.playlists

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import it.lumetv.app.MainActivity
import it.lumetv.app.data.*
import it.lumetv.app.playback.PlaybackService
import it.lumetv.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Owned fixtures and native SQLite/ExoPlayer, invoked only by the emulator-guarded test runner. */
object OnDemandInspection {
    suspend fun run(context:Context,root:String,mobile:Boolean):JSONObject {
        require(java.net.URI(root).host=="10.0.2.2" && java.net.URI(root).scheme=="http")
        val checks=mutableListOf<String>()
        val repo=PersonalPlaylistRepository.get(context);repo.local();repo.state.value.forEach {repo.delete(it.id)}
        val settings=PlaylistSettings(guide=PersonalGuideSettings(allowHttp=true,ongoing=true),access=mapOf(PlaylistResource.EPG to PlaylistAccessRule(mapOf("X-Guide" to "guide-fixture"))))
        val a=repo.save("Guida personale A",repo.prepareUrl("$root/catalog.m3u",allowHttp=true,settings=settings))
        val b=repo.save("Guida personale B",repo.prepareUrl("$root/second.m3u",allowHttp=true,settings=settings))
        val c=repo.save("Guida compressa xz",repo.prepareUrl("$root/third.m3u",allowHttp=true,settings=settings))
        val guide=PersonalGuideRepository.get(context);guide.refresh(true)
        check(guide.state.value.size==3 && guide.state.value.all {it.error.isBlank() && it.matched==1}) {guide.state.value.toString()}
        check(listOf(a,b,c).all {guide.storedCounts(it)==(1 to 4)}) {"Guide retention or wanted-channel filter failed"}
        checks+="native XML, gzip and xz with role-scoped EPG headers"
        checks+="only matched channels and seven-day history plus 48-hour future are stored"
        val rows=repo.local();val live=rows.single {it.sourceId==a && !it.isVod};val vod=rows.single {it.sourceId==a && it.name=="Video personale"};val locked=rows.single {it.name=="Video senza seek"}
        val now=System.currentTimeMillis();val window=guide.window(rows,now-7*86_400_000L,now+48*3_600_000L)
        check(window.keys.size==3 && window.values.all {it.size in 2..5000})
        check(window[live.epgId]!!.any {it.title=="Replay personale A" && it.replayAvailable})
        check(window[rows.single {it.sourceId==b}.epgId]!!.none {it.title=="Replay personale A"})
        checks+="same XMLTV IDs stay isolated across three sources"
        val past=window[live.epgId]!!.single {it.title=="Replay personale A"}
        val current=window[live.epgId]!!.single {it.title=="In onda personale A"}
        repo.setSettings(a,settings.copy(guide=settings.guide.copy(urls=listOf("$root/guide.xml?replacement=1"))))
        guide.refresh(true,a)
        val replaced=guide.window(listOf(live),now-7*86_400_000L,now+48*3_600_000L)[live.epgId]!!
        check(guide.storedCounts(a)==(1 to 4) && replaced.single {it.title==past.title}.eventId!=past.eventId)
        repo.setSettings(a,settings);guide.refresh(true,a)
        check(guide.window(listOf(live),now-7*86_400_000L,now+48*3_600_000L)[live.epgId]!!.single {it.title==past.title}.eventId==past.eventId)
        checks+="changing a guide URL replaces its history without changing channel identity"
        val probe=PersonalPlaybackResolver(context,repo,okhttp3.OkHttpClient()).resolve(live,PlaybackTarget(live.id,PlaybackMode.ARCHIVE,past.eventId),0L)
        check(probe.target.mode==PlaybackMode.ARCHIVE && probe.stream.url.contains("sig=a%2Bb+c"))
        check(current.replayAvailable);checks+="ongoing programmes require explicit start-over setting"
        val page=repo.library(a,"Video",null);check(page.rows.size==2 && page.total==2 && page.rows.all {it.isVod})
        checks+="native VOD classification and SQLite library search"
        val prefs=context.getSharedPreferences("lume",Context.MODE_PRIVATE)
        val catalog=ChannelRepository(context,HttpClient())
        if(!catalog.isFavorite(vod))catalog.toggleFavorite(vod)
        check(catalog.isFavorite(vod));checks+="VOD favourites retain the catalog identity"
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val connected=CompletableDeferred<PlaybackService>()
        val connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){connected.complete((binder as PlaybackService.LocalBinder).service)}
            override fun onServiceDisconnected(name:ComponentName){}
        }
        withContext(Dispatchers.Main){context.bindService(Intent(context,PlaybackService::class.java).setAction(PlaybackService.LOCAL_BIND),connection,Context.BIND_AUTO_CREATE)}
        val service=withTimeout(20_000){connected.await()}
        suspend fun waitFor(label:String,predicate:()->Boolean) {
            try {withTimeout(40_000){while(!withContext(Dispatchers.Main){predicate()})delay(100)}}
            catch(_:TimeoutCancellationException){error(label+": "+withContext(Dispatchers.Main){service.snapshot.toString()})}
            checks+=label
        }
        suspend fun main(action:()->Unit)=withContext(Dispatchers.Main){action()}
        try {
            main {service.select(live,mobile)}
            waitFor("one service player opens personal live"){service.snapshot.playing && service.snapshot.mode==PlaybackMode.LIVE}
            val lastLive=prefs.getString("last_channel",null)
            main {service.select(vod,mobile)}
            waitFor("VOD opens with duration and seeking"){service.snapshot.playing && service.snapshot.mode==PlaybackMode.VOD && service.snapshot.seekable && service.snapshot.duration>100_000}
            main {service.seek(35_000)}
            waitFor("VOD seeks without changing the live channel"){service.snapshot.position>=34_000 && service.snapshot.channel?.id==vod.id}
            main {service.zap(1)}
            waitFor("media next seeks forward instead of zapping"){service.snapshot.position>=64_000 && service.snapshot.channel?.id==vod.id}
            main {service.pausePlayback()}
            delay(300)
            check(!withContext(Dispatchers.Main){service.snapshot.wantsToPlay})
            main {service.stopPlayback()};delay(300)
            check(PersonalPlaybackStore.get(context).read(a,PlaybackTarget(vod.id,PlaybackMode.VOD)).resume>=64_000)
            main {service.select(vod,mobile)}
            waitFor("VOD resumes its saved private watch position"){service.snapshot.playing && service.snapshot.position>=64_000}
            check(prefs.getString("last_channel",null)==lastLive);checks+="VOD history does not replace the last live channel"
            val before=repo.requestContext(a)!!
            repo.setSettings(a,settings.copy(guide=settings.guide.copy(language="en",allowHttp=false)))
            delay(300);check(before===repo.requestContext(a));check(withContext(Dispatchers.Main){service.snapshot.playing})
            guide.refresh(true,a);check(guide.state.value.first {it.sourceId==a}.error.isNotBlank())
            check(withContext(Dispatchers.Main){service.snapshot.playing})
            checks+="guide HTTP revocation and refresh failure keep the current video and cached EPG"
            repo.setSettings(a,settings)
            main {service.seek(service.snapshot.duration-600)}
            waitFor("VOD EOF completes without live reconnect"){service.snapshot.ended && !service.snapshot.wantsToPlay}
            delay(1800);check(withContext(Dispatchers.Main){service.snapshot.ended && !service.snapshot.wantsToPlay})
            check(PersonalPlaybackStore.get(context).read(a,PlaybackTarget(vod.id,PlaybackMode.VOD)).completed)
            main {service.restart()}
            waitFor("completed video restarts from the beginning"){service.snapshot.playing && service.snapshot.position<10_000}
            main {service.select(locked,mobile)}
            waitFor("seekable zero remains enforced on native player"){service.snapshot.playing && !service.snapshot.seekable}
            main {service.seek(70_000)};check(withContext(Dispatchers.Main){service.snapshot.position<10_000})
            main {service.selectArchive(live,past.eventId,mobile)}
            waitFor("catch-up opens a past XMLTV event with archive mode"){service.snapshot.playing && service.snapshot.mode==PlaybackMode.ARCHIVE && service.snapshot.title==past.title}
            main {service.seek(60_000)}
            waitFor("archive seek retains programme identity"){service.snapshot.playing && service.snapshot.position>=59_000 && service.snapshot.title==past.title}
            main {service.returnToLive()}
            waitFor("return to live leaves the archive"){service.snapshot.playing && service.snapshot.mode==PlaybackMode.LIVE && service.snapshot.channel?.id==live.id}
            main {service.selectArchive(live,current.eventId,mobile)}
            waitFor("start-over opens the programme currently on air"){service.snapshot.playing && service.snapshot.mode==PlaybackMode.ARCHIVE && service.snapshot.title==current.title}
            main {service.returnToLive();service.pausePlayback()}
            delay(300)
            val builtinFile=File(context.filesDir,"guide.json").takeIf {it.exists()}?.readText().orEmpty()
            check(!builtinFile.contains("Replay personale") && !builtinFile.contains(live.epgId))
            checks+="private guide does not enter the shared guide snapshot"
        }finally {withContext(Dispatchers.Main){context.unbindService(connection)}}
        return JSONObject().put("passed",true).put("checks",JSONArray(checks)).put("live",live.id).put("vod",vod.id).put("source",a)
            .put("sources",JSONArray(listOf(a,b,c))).put("event",past.eventId).put("guideProgrammes",window.values.sumOf {it.size})
    }
}
