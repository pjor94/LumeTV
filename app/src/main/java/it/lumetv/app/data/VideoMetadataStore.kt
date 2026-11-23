package it.lumetv.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import it.lumetv.core.VodMediaType
import it.lumetv.core.VodTitleCandidate
import org.json.JSONArray
import org.json.JSONObject

internal data class VideoLookup(val source:String,val key:String,val epoch:Long,val query:it.lumetv.core.VodLookupQuery,
    val explicitPoster:Boolean=false,val explicitBackdrop:Boolean=false) {
    override fun toString()="VideoLookup(type=${query.type})"
}
internal data class VideoStoredMatch(val id:Int?,val candidates:List<VodTitleCandidate>,val manual:Boolean,
    val excluded:Boolean,val expires:Long)
internal data class VideoStoredMetadata(val value:TmdbMetadata,val expires:Long)

/** Independent of playlist databases, which are atomically replaced by imports. */
internal class VideoMetadataStore(context:Context):SQLiteOpenHelper(context,"video-metadata.sqlite",null,1) {
    override fun onConfigure(db:SQLiteDatabase){db.setMaximumSize(64L*1024*1024)}
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE matches(source TEXT NOT NULL,local_key TEXT NOT NULL,fingerprint TEXT NOT NULL,kind TEXT NOT NULL,tmdb_id INTEGER,manual INTEGER NOT NULL,excluded INTEGER NOT NULL,candidates TEXT NOT NULL,expires INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(source,local_key))")
        db.execSQL("CREATE TABLE details(kind TEXT NOT NULL,tmdb_id INTEGER NOT NULL,language TEXT NOT NULL,payload TEXT NOT NULL,expires INTEGER NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(kind,tmdb_id,language))")
        db.execSQL("CREATE INDEX matches_updated ON matches(manual,updated)")
        db.execSQL("CREATE INDEX details_updated ON details(updated)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit

    @Synchronized fun match(input:VideoLookup):VideoStoredMatch?=readableDatabase.rawQuery(
        "SELECT tmdb_id,candidates,manual,excluded,expires FROM matches WHERE source=? AND local_key=? AND fingerprint=? AND kind=?",
        arrayOf(input.source,input.key,input.query.fingerprint,input.query.type.name)).use {c->
        if(!c.moveToFirst())null else VideoStoredMatch(if(c.isNull(0))null else c.getInt(0),
            TmdbClient.candidates(JSONArray(c.getString(1)),input.query.type),c.getInt(2)!=0,c.getInt(3)!=0,c.getLong(4))
    }
    @Synchronized fun details(type:VodMediaType,id:Int):VideoStoredMetadata?=readableDatabase.rawQuery(
        "SELECT payload,expires FROM details WHERE kind=? AND tmdb_id=? AND language='it-IT'",arrayOf(type.name,id.toString())).use {c->
        if(!c.moveToFirst())null else VideoStoredMetadata(decode(JSONObject(c.getString(0))),c.getLong(1))
    }
    @Synchronized fun save(input:VideoLookup,match:VideoStoredMatch,metadata:TmdbMetadata?,now:Long,valid:()->Boolean) {
        if(!valid())return
        val db=writableDatabase;db.beginTransaction()
        try {
            if(match.manual) {
                val count=db.rawQuery("SELECT COUNT(*) FROM matches WHERE manual=1 AND NOT(source=? AND local_key=?)",arrayOf(input.source,input.key)).use {it.moveToFirst();it.getInt(0)}
                require(count<5000){"Troppe associazioni salvate"}
            }
            db.execSQL("INSERT OR REPLACE INTO matches VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(input.source,input.key,input.query.fingerprint,
                input.query.type.name,match.id,if(match.manual)1 else 0,if(match.excluded)1 else 0,candidatesJson(match.candidates).toString(),match.expires,now))
            if(metadata!=null) {
                val ttl=if(metadata.overview.isBlank())DAY else if(metadata.inProduction)7L*DAY else 30L*DAY
                db.execSQL("INSERT OR REPLACE INTO details VALUES(?,?,?,?,?,?)",arrayOf<Any?>(metadata.type.name,metadata.id,"it-IT",encode(metadata).toString(),now+ttl,now))
            }
            db.execSQL("DELETE FROM matches WHERE manual=0 AND rowid IN (SELECT rowid FROM matches WHERE manual=0 ORDER BY updated DESC LIMIT -1 OFFSET 5000)")
            db.execSQL("DELETE FROM details WHERE rowid IN (SELECT rowid FROM details ORDER BY updated DESC LIMIT -1 OFFSET 5000)")
            if(valid())db.setTransactionSuccessful()
        }finally {db.endTransaction()}
    }
    @Synchronized fun removeSource(source:String) {
        writableDatabase.execSQL("DELETE FROM matches WHERE source=?",arrayOf(source));prune()
    }
    private fun prune(){writableDatabase.execSQL("DELETE FROM details WHERE NOT EXISTS(SELECT 1 FROM matches m WHERE m.kind=details.kind AND m.tmdb_id=details.tmdb_id)")}
    @Synchronized fun clear(){writableDatabase.execSQL("DELETE FROM matches WHERE manual=0");writableDatabase.execSQL("DELETE FROM details")}

    @Synchronized fun manualChoices():JSONArray=JSONArray().also {out->
        readableDatabase.rawQuery("SELECT source,local_key,fingerprint,kind,tmdb_id,excluded FROM matches WHERE manual=1 LIMIT 5000",null).use {c->while(c.moveToNext()) {
            out.put(JSONObject().put("source",c.getString(0)).put("key",c.getString(1)).put("fingerprint",c.getString(2))
                .put("type",c.getString(3)).put("id",if(c.isNull(4))JSONObject.NULL else c.getInt(4)).put("excluded",c.getInt(5)!=0))
        }}
    }
    fun restoreChoices(values:JSONArray,sources:Set<String>)=restoreWithChoices(values,sources){Unit}
    @Synchronized fun <T> restoreWithChoices(values:JSONArray,sources:Set<String>,commit:()->T):T {
        validateChoices(values,sources)
        val db=writableDatabase;db.beginTransaction()
        try {for(i in 0 until values.length()) {
            val row=values.getJSONObject(i)
            db.execSQL("INSERT OR IGNORE INTO matches VALUES(?,?,?,?,?,1,?,'[]',?,?)",arrayOf<Any?>(row.getString("source"),row.getString("key"),row.getString("fingerprint"),row.getString("type"),
                if(row.isNull("id"))null else row.getInt("id"),if(row.getBoolean("excluded"))1 else 0,Long.MAX_VALUE,System.currentTimeMillis()))
        }
            require(db.rawQuery("SELECT COUNT(*) FROM matches WHERE manual=1",null).use {it.moveToFirst();it.getInt(0)}<=5000)
            val result=commit();db.setTransactionSuccessful();return result
        }finally {db.endTransaction()}
    }
    companion object {
        const val DAY=86_400_000L
        internal fun candidatesJson(values:List<VodTitleCandidate>)=JSONArray().apply {values.take(20).forEach {candidate->put(JSONObject()
            .put("id",candidate.id).put("title",candidate.title).put("name",candidate.title)
            .put("original_title",candidate.originalTitle).put("original_name",candidate.originalTitle)
            .put("release_date",candidate.year?.toString().orEmpty()).put("first_air_date",candidate.year?.toString().orEmpty()))}}
        internal fun validateChoices(values:JSONArray,sources:Set<String>) {
            require(values.length()<=5000);val seen=hashSetOf<String>()
            for(i in 0 until values.length()) {
                val row=values.getJSONObject(i);val source=row.getString("source");val key=row.getString("key")
                require(source in sources && source.length<=100 && key.matches(Regex("[is]:[A-Za-z0-9_-]{1,160}")))
                require(row.getString("fingerprint").matches(Regex("[a-f0-9]{64}")))
                VodMediaType.valueOf(row.getString("type"))
                require(row.get("excluded") is Boolean && (row.getBoolean("excluded") && row.isNull("id") || !row.getBoolean("excluded") && row.getInt("id")>0))
                require(seen.add("$source|$key"))
            }
        }
        private fun encode(value:TmdbMetadata)=JSONObject().put("id",value.id).put("type",value.type.name).put("title",value.title)
            .put("year",value.year ?: JSONObject.NULL).put("overview",value.overview).put("overviewLanguage",value.overviewLanguage)
            .put("genres",JSONArray(value.genres)).put("cast",JSONArray(value.cast)).put("directors",JSONArray(value.directors))
            .put("runtime",value.runtimeMinutes).put("poster",value.posterPath).put("backdrop",value.backdropPath).put("inProduction",value.inProduction)
            .put("trailers",TmdbTrailers.encode(value.trailers)).put("trailersLoaded",value.trailersLoaded)
        private fun decode(value:JSONObject):TmdbMetadata {
            fun strings(key:String)=value.getJSONArray(key).let {a->(0 until minOf(a.length(),20)).map {a.getString(it).take(120)}}
            return TmdbMetadata(value.getInt("id"),VodMediaType.valueOf(value.getString("type")),value.getString("title").take(300),
                if(value.isNull("year"))null else value.getInt("year"),value.getString("overview").take(4000),value.getString("overviewLanguage"),
                strings("genres"),strings("cast"),strings("directors"),value.getInt("runtime"),value.getString("poster"),value.getString("backdrop"),value.getBoolean("inProduction"),
                TmdbTrailers.parse(value.optJSONArray("trailers")),value.optBoolean("trailersLoaded",false))
        }
    }
}
