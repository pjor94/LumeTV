package it.lumetv.app.data

import android.content.Context
import it.lumetv.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.time.ZoneId

internal class PersonalPlaybackResult(val stream:ResolvedStream,val target:PlaybackTarget,val title:String,
    val duration:Long,val startPosition:Long,val baseOffset:Long,val regenerate:Boolean) {
    override fun toString()="PersonalPlaybackResult(mode=${target.mode})"
}
internal class PersonalPlaybackResolver(context:Context,private val personal:PersonalPlaylistRepository,private val http:OkHttpClient) {
    private val guide=PersonalGuideRepository.get(context)
    private val history=PersonalPlaybackStore.get(context)
    suspend fun resolve(channel:Channel,target:PlaybackTarget,position:Long?):PersonalPlaybackResult=withContext(Dispatchers.IO) {
        val entry=personal.playbackEntry(channel.id)
        var resolved=PersonalPlaybackConfig.parse(entry.entry,entry.source.allowHttp,entry.settings)
        val mode=if(target.mode==PlaybackMode.ARCHIVE)PlaybackMode.ARCHIVE else if(entry.content==PlaylistContentKind.VOD)PlaybackMode.VOD else PlaybackMode.LIVE
        val actual=target.copy(mode=mode)
        val point=position ?: history.read(entry.source.id,actual).resume
        var title=channel.name;var duration=if(mode==PlaybackMode.VOD)((entry.entry.metadata.duration ?: 0.0)*1000).toLong() else 0L
        var start=point;var base=0L;var regenerate=false
        if(mode==PlaybackMode.ARCHIVE) {
            val event=guide.event(channel,target.eventId) ?: throw SourceException("Il programma non è più presente nella guida. Torna alla diretta.")
            val settings=entry.settings.guide
            val spec=CatchupUrls.spec(entry.entry.metadata,settings.archiveDays) ?: throw SourceException("Questo canale non dichiara un archivio.")
            val archive=CatchupUrls.build(entry.entry.url,spec,ArchiveEvent(event.programme.start,event.programme.end,event.catchupId),System.currentTimeMillis(),point,
                settings.archiveZone.takeIf(String::isNotBlank)?.let(ZoneId::of) ?: ZoneId.systemDefault(),settings.ongoing,settings.startBufferSeconds,settings.endBufferSeconds)
            if(URI(archive.url).scheme.equals("http",true) && !entry.source.allowHttp)
                throw SourceException("L’archivio richiede HTTP. Consenti HTTP per questa lista prima di rivedere il programma.")
            title=event.programme.title;duration=archive.duration;regenerate=archive.canRegenerate
            base=if(regenerate)archive.offset else 0L;start=if(regenerate)0 else point
            val oldOrigin=origin(entry.entry.url);val newOrigin=origin(archive.url)
            fun scoped(role:PlaylistResource,headers:Map<String,String>):Map<String,String> = if(oldOrigin==newOrigin || newOrigin in entry.settings.rule(role).origins)headers
                else headers.filterKeys {it.equals("User-Agent",true)}
            val path=URI(archive.url).path.orEmpty().lowercase()
            val mime=when {path.endsWith(".m3u8")->"application/x-mpegURL";path.endsWith(".mpd")->"application/dash+xml";path.endsWith(".ts")->"video/mp2t";path.endsWith(".mp4")->"video/mp4";else->null}
            if(resolved.drm?.scheme==DrmScheme.CLEARKEY && mime?.contains("mpegURL",true)==true)throw SourceException("L’archivio HLS di questo canale non è compatibile con ClearKey CENC.")
            resolved=resolved.copy(url=archive.url,mimeType=mime,headers=scoped(PlaylistResource.MEDIA,resolved.headers),
                manifestHeaders=scoped(PlaylistResource.MANIFEST,resolved.manifestHeaders ?: resolved.headers),
                hlsKey=resolved.hlsKey?.let {it.copy(headers=scoped(PlaylistResource.KEY,it.headers))})
        }
        val mime=resolved.mimeType ?: PersonalPlaylistNetwork.detectMime(resolved.url,resolved.manifestHeaders ?: resolved.headers,http,entry.source.allowHttp,entry.context)
        if(!entry.context.valid())throw SourceException("La lista non è più disponibile.")
        PersonalPlaybackResult(resolved.copy(mimeType=mime,personalNetworkToken=entry.context.token),actual,title,duration,start,base,regenerate)
    }
    private fun origin(raw:String):String=PlaylistSettings.origin(raw.toHttpUrl())
}
