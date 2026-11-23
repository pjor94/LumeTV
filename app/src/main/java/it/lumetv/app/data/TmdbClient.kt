package it.lumetv.app.data

import it.lumetv.core.VodLookupQuery
import it.lumetv.core.VodMediaType
import it.lumetv.core.VodTitleCandidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal data class TmdbMetadata(val id:Int,val type:VodMediaType,val title:String,val year:Int?,
    val overview:String,val overviewLanguage:String="it",val genres:List<String> = emptyList(),
    val cast:List<String> = emptyList(),val directors:List<String> = emptyList(),val runtimeMinutes:Int=0,
    val posterPath:String="",val backdropPath:String="",val inProduction:Boolean=false,
    val trailers:List<TmdbTrailer> = emptyList(),val trailersLoaded:Boolean=true) {
    override fun toString()="TmdbMetadata(type=$type,id=$id)"
}

internal class TmdbException(val status:Int=0,val retryAt:Long=0):IOException("TMDB request unavailable")

internal interface TmdbService {
    val configured:Boolean
    suspend fun search(query:VodLookupQuery):List<VodTitleCandidate>
    suspend fun details(type:VodMediaType,id:Int):TmdbMetadata
}

/** Dedicated transport: playlist headers and authentication never enter this client. */
internal class TmdbClient(private val token:String,
    client:OkHttpClient=OkHttpClient(),private val base:HttpUrl="https://api.themoviedb.org/3/".toHttpUrl(),
    private val now:()->Long=System::currentTimeMillis):TmdbService {
    override val configured get()=token.isNotBlank()
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(4,TimeUnit.SECONDS).readTimeout(7,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build()
    private val slots=Semaphore(2)
    private val pace=Mutex()
    private var nextRequest=0L
    @Volatile private var retryAt=0L
    @Volatile private var rejected=false

    override suspend fun search(query:VodLookupQuery):List<VodTitleCandidate> {
        if(query.imdbId.isNotBlank()) {
            val result=get("find/${query.imdbId}",mapOf("external_source" to "imdb_id"))
            return candidates(result.optJSONArray(if(query.type==VodMediaType.MOVIE)"movie_results" else "tv_results"),query.type)
        }
        val params=mutableMapOf("query" to query.title,"include_adult" to "false")
        query.year?.let {params[if(query.type==VodMediaType.MOVIE)"year" else "first_air_date_year"]=it.toString()}
        return candidates(get("search/${query.type.apiName}",params).optJSONArray("results"),query.type)
    }

    override suspend fun details(type:VodMediaType,id:Int):TmdbMetadata {
        require(id>0)
        val value=get("${type.apiName}/$id",mapOf(
            "append_to_response" to if(type==VodMediaType.MOVIE)"credits,videos" else "aggregate_credits,videos",
            "include_video_language" to "it,en"))
        val metadata=parse(value,type,id)
        if(metadata.overview.isNotBlank())return metadata
        return try {
            val english=get("${type.apiName}/$id",language="en-US")
            metadata.copy(overview=text(english,"overview",4000),overviewLanguage="en")
        }catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){metadata}
    }

    private suspend fun get(route:String,params:Map<String,String> = emptyMap(),language:String="it-IT"):JSONObject=slots.withPermit {
        if(!configured || rejected)throw TmdbException(401)
        if(now()<retryAt)throw TmdbException(429,retryAt)
        pace.withLock {delay((nextRequest-now()).coerceIn(0,250));nextRequest=now()+250}
        if(now()<retryAt)throw TmdbException(429,retryAt)
        val url=requireNotNull(base.resolve(route)).newBuilder().addQueryParameter("language",language)
            .apply {params.forEach {(key,value)->addQueryParameter(key,value)}}.build()
        val request=Request.Builder().url(url).header("Authorization","Bearer $token")
            .header("Accept","application/json").header("User-Agent","LumeTV metadata").build()
        response(request) {response->
            if(response.code==401 || response.code==403)rejected=true
            if(response.code==429) {
                val header=response.header("Retry-After").orEmpty()
                val duration=header.toLongOrNull()?.times(1000) ?: runCatching {
                    ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now()
                }.getOrDefault(60_000)
                retryAt=now()+duration.coerceIn(1000,15*60_000)
            }
            if(!response.isSuccessful)throw TmdbException(response.code,retryAt)
            val body=response.body ?: throw TmdbException()
            if(body.contentLength()>MAX_RESPONSE)throw TmdbException()
            val bytes=body.byteStream().use {input->
                val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break
                    if(output.size()+count>MAX_RESPONSE)throw TmdbException()
                    output.write(buffer,0,count)}
                output.toByteArray()
            }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }

    private suspend fun <T> response(request:Request,consume:(Response)->T):T=suspendCancellableCoroutine {continuation->
        val call=client.newCall(request);continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response){response.use {
                if(!continuation.isActive)return
                try {val value=consume(response);if(continuation.isActive)continuation.resumeWith(Result.success(value))}
                catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
            }}
        })
    }

    companion object {
        private const val MAX_RESPONSE=4*1024*1024
        private fun text(value:JSONObject,key:String,max:Int=300)=value.optString(key).takeUnless {it=="null"}.orEmpty()
            .replace(Regex("<[^>]{0,200}>"),"").trim().take(max)
        private fun year(value:String)=value.take(4).toIntOrNull()?.takeIf {it in 1888..2100}
        private fun names(values:JSONArray?,key:String="name",limit:Int=12):List<String> =
            (0 until minOf(values?.length() ?: 0,2000)).mapNotNull {values?.optJSONObject(it)?.let {row->text(row,key,120).takeIf(String::isNotBlank)}}.distinct().take(limit)
        internal fun candidates(values:JSONArray?,type:VodMediaType):List<VodTitleCandidate> =
            (0 until minOf(values?.length() ?: 0,20)).mapNotNull {i->values?.optJSONObject(i)?.let {value->
                val id=value.optInt("id");val title=text(value,if(type==VodMediaType.MOVIE)"title" else "name")
                if(id<=0 || title.isBlank())null else VodTitleCandidate(id,type,title,
                    text(value,if(type==VodMediaType.MOVIE)"original_title" else "original_name"),
                    year(text(value,if(type==VodMediaType.MOVIE)"release_date" else "first_air_date")))
            }}
        internal fun parse(value:JSONObject,type:VodMediaType,id:Int):TmdbMetadata {
            require(value.getInt("id")==id)
            val movie=type==VodMediaType.MOVIE
            val credits=value.optJSONObject(if(movie)"credits" else "aggregate_credits")
            val directors=if(movie) {
                val crew=credits?.optJSONArray("crew")
                (0 until minOf(crew?.length() ?: 0,2000)).mapNotNull {crew?.optJSONObject(it)?.takeIf {row->row.optString("job")=="Director"}?.let {row->text(row,"name",120)}}.distinct().take(6)
            }else names(value.optJSONArray("created_by"),limit=6)
            return TmdbMetadata(id,type,text(value,if(movie)"title" else "name"),
                year(text(value,if(movie)"release_date" else "first_air_date")),text(value,"overview",4000),
                genres=names(value.optJSONArray("genres"),limit=20),cast=names(credits?.optJSONArray("cast")),directors=directors,
                runtimeMinutes=if(movie)value.optInt("runtime").coerceIn(0,1440) else 0,
                posterPath=text(value,"poster_path"),backdropPath=text(value,"backdrop_path"),inProduction=value.optBoolean("in_production"),
                trailers=TmdbTrailers.parse(value.optJSONObject("videos")?.optJSONArray("results")))
        }
        internal fun imageUrl(path:String,backdrop:Boolean=false):String? = path.takeIf {
            it.matches(Regex("/[A-Za-z0-9_-]+\\.(?:jpg|png|webp)"))
        }?.let {"https://image.tmdb.org/t/p/${if(backdrop)"w1280" else "w500"}$it"}
    }
}
