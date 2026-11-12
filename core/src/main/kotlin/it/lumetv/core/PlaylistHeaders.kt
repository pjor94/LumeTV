package it.lumetv.core

import java.util.Locale

/** Only request metadata; transport framing and proxy credentials belong to the HTTP client. */
object PlaylistHeaders {
    private val token = Regex("[A-Za-z0-9!#$%&'*+.^_~-]+")
    private val reserved = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization",
        "proxy-connection", "upgrade", "te", "trailer", "range", "accept-encoding")
    fun name(value: String): String {
        val key = value.removePrefix("!").lowercase(Locale.ROOT)
        if (!token.matches(key) || key in reserved || key.length > 100) invalid()
        return when (key) {
            "referrer" -> "Referer"; "cookies" -> "Cookie"
            else -> key.split('-').joinToString("-") { it.replaceFirstChar(Char::uppercaseChar) }
        }
    }
    fun merge(vararg sources: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        sources.forEach { source -> source.forEach { (key, value) ->
            if (value.length > 4096 || value.any { it.code !in 32..126 }) invalid()
            result[name(key)] = value
        } }
        if (result.size > 32 || result.entries.sumOf { it.key.length + it.value.length } > 32 * 1024) invalid()
        return result
    }
    private fun invalid(): Nothing = throw PlaylistFormatException("La voce contiene header HTTP non validi o non supportati.")
}
