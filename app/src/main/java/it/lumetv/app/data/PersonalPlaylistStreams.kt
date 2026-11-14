package it.lumetv.app.data

import it.lumetv.core.ResolvedStream
import okhttp3.OkHttpClient
import org.json.JSONObject

/** Resolves only typed provider routes; playlist text is never executed or made into an HTTP path. */
internal object PersonalPlaylistStreams {
    suspend fun initial(snapshot:PersonalEntrySnapshot,http:OkHttpClient,transport:Transport?=null):ResolvedStream {
        val context=snapshot.context
        if(!context.valid())throw SourceException("La configurazione della lista è cambiata. Riapri il canale.")
        val provider=snapshot.entry.localplay?.let {route->
            val scoped=transport ?: object:Transport {
                private fun client(url:String,headers:Map<String,String>)=HttpClient(context.client(http,url,PlaylistResource.MANIFEST,headers.keys))
                override suspend fun get(url:String,headers:Map<String,String>)=client(url,headers).get(url,headers)
                override suspend fun post(url:String,data:JSONObject,headers:Map<String,String>)=client(url,headers).post(url,data,headers)
            }
            DiscoveryResolver(scoped).resolve(route.resolverKey)
        }
        if(!context.valid())throw SourceException("La configurazione della lista è cambiata. Riapri il canale.")
        return PersonalPlaybackConfig.parse(snapshot.entry,snapshot.source.allowHttp,snapshot.settings,provider)
    }
}
