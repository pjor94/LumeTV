package it.lumetv.app.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import it.lumetv.app.data.HttpClient
import it.lumetv.app.data.PersonalPlaylistNetwork
import it.lumetv.app.data.PersonalRequestContext
import it.lumetv.app.data.PlaylistResource
import it.lumetv.app.data.SourceException
import it.lumetv.core.DrmScheme
import it.lumetv.core.ResolvedStream
import okhttp3.OkHttpClient

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PersonalMediaSources {
    fun http(base:OkHttpClient,headers:Map<String,String>):OkHttpDataSource.Factory = OkHttpDataSource.Factory(base)
        .setUserAgent(if(headers.keys.any { it.equals("User-Agent",true) })null else HttpClient.USER_AGENT)
        .setDefaultRequestProperties(headers)

    fun create(context:Context,stream:ResolvedStream,item:MediaItem,mediaClient:OkHttpClient,licenseClient:OkHttpClient,
               requestsContext:PersonalRequestContext?=null,
               httpAllowed:()->Boolean):MediaSource {
        if(stream.drm?.scheme==DrmScheme.CLEARKEY && stream.mimeType!="application/dash+xml")
            throw SourceException("ClearKey personale richiede uno stream DASH con cifratura CENC.")
        fun requests(headers:Map<String,String>,role:PlaylistResource):DataSource.Factory {
            val client=requestsContext?.client(mediaClient,stream.url,role,headers.keys) ?: PersonalPlaylistNetwork.client(mediaClient,stream.url,headers.keys) {
                PersonalPlaylistNetwork.allowed(it,stream.personalAllowHttp && httpAllowed())
            }
            return DefaultDataSource.Factory(context,http(client,headers))
        }
        val manifest=requests(stream.manifestHeaders ?: stream.headers,PlaylistResource.MANIFEST)
        val segments=requests(stream.headers,PlaylistResource.MEDIA)
        val keyBase=requests(stream.hlsKey?.headers ?: stream.headers,PlaylistResource.KEY)
        val keys=ResolvingDataSource.Factory(keyBase) { spec ->
            val parameters=stream.hlsKey?.parameters.orEmpty()
            if(parameters.isBlank())spec else {
                val raw=spec.uri.toString()
                spec.withUri(android.net.Uri.parse(raw+(if('?' in raw)"&" else "?")+parameters))
            }
        }
        val config=stream.drm
        val localResponse=config?.clearKeyResponse
        val drm:DrmSessionManagerProvider = if(localResponse!=null) {
            val manager=DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID,FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(config.multiSession).build(LocalMediaDrmCallback(localResponse.toByteArray(Charsets.UTF_8)))
            DrmSessionManagerProvider { manager }
        } else if(config!=null) {
                val client=requestsContext?.client(licenseClient,config.licenseUrl,PlaylistResource.LICENSE,config.headers.keys)
                    ?: PersonalPlaylistNetwork.client(licenseClient,config.licenseUrl,config.headers.keys) {
                    PersonalPlaylistNetwork.allowed(it,stream.personalAllowHttp && httpAllowed())
                }
                val manager=DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(if(config.scheme==DrmScheme.CLEARKEY)C.CLEARKEY_UUID else C.WIDEVINE_UUID,FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(config.multiSession).build(PersonalLicenseCallback(config,client))
                DrmSessionManagerProvider {manager}
        } else DefaultDrmSessionManagerProvider()
        return when(stream.mimeType) {
            "application/x-mpegURL", "application/vnd.apple.mpegurl" -> HlsMediaSource.Factory(HlsDataSourceFactory { type ->
                (when(type) {C.DATA_TYPE_MANIFEST -> manifest;C.DATA_TYPE_DRM -> keys;else -> segments}).createDataSource()
            }).setDrmSessionManagerProvider(drm).createMediaSource(item)
            "application/dash+xml" -> DashMediaSource.Factory(DefaultDashChunkSource.Factory(segments),manifest)
                .setDrmSessionManagerProvider(drm).createMediaSource(item)
            else -> ProgressiveMediaSource.Factory(segments).setDrmSessionManagerProvider(drm).createMediaSource(item)
        }
    }
}
