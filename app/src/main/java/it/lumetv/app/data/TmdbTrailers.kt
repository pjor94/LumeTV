package it.lumetv.app.data

import org.json.JSONArray
import org.json.JSONObject

/** A video reference, never a cached or playlist-authenticated playback URL. */
internal data class TmdbTrailer(
    val key: String,
    val site: String,
    val language: String,
    val name: String = "",
    val official: Boolean = false,
    val publishedAt: String = ""
) {
    val label get() = if (language == "it") "Trailer in italiano" else "Trailer in inglese"
}

internal object TmdbTrailers {
    private const val LIMIT = 24
    private val youtubeId = Regex("[A-Za-z0-9_-]{11}")
    private val vimeoId = Regex("[0-9]{1,12}")

    fun ranked(values: List<TmdbTrailer>): List<TmdbTrailer> = values
        .filter { it.language in setOf("it", "en") && when (it.site) {
            "YouTube" -> youtubeId.matches(it.key)
            "Vimeo" -> vimeoId.matches(it.key)
            else -> false
        } }
        .sortedWith(compareBy<TmdbTrailer> { if (it.language == "it") 0 else 1 }
            .thenByDescending { it.official }
            .thenByDescending { it.publishedAt }
            .thenBy { it.key })
        .distinctBy { it.site to it.key }
        .let { rows ->
            val english=rows.filter {it.language=="en"}.take(LIMIT/2)
            rows.filter {it.language=="it"}.take(LIMIT-english.size)+english
        }

    fun parse(rows: JSONArray?): List<TmdbTrailer> = ranked(
        (0 until minOf(rows?.length() ?: 0, 2000)).mapNotNull { index ->
            val row = rows?.optJSONObject(index) ?: return@mapNotNull null
            if (row.optString("type") != "Trailer") return@mapNotNull null
            TmdbTrailer(row.optString("key"), row.optString("site"), row.optString("iso_639_1"),
                row.optString("name").takeUnless { it == "null" }.orEmpty()
                    .replace(Regex("<[^>]{0,200}>"), "").trim().take(200),
                row.optBoolean("official"), row.optString("published_at").take(40))
        })

    fun encode(values: List<TmdbTrailer>): JSONArray = JSONArray().apply {
        ranked(values).forEach { trailer ->
            put(JSONObject().put("key", trailer.key).put("site", trailer.site)
                .put("iso_639_1", trailer.language).put("name", trailer.name)
                .put("official", trailer.official).put("published_at", trailer.publishedAt)
                .put("type", "Trailer"))
        }
    }
}
