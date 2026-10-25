package it.lumetv.core

/** Keep RSI LA1/LA2 out of bundled, cached and refreshed catalogues. */
object CatalogPolicy {
    private val rsiName = Regex("^rsi\\s*la\\s*[12](?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val rsiId = Regex("^rsila[12](?:vpn|instabile|hd|it|ch)?$", RegexOption.IGNORE_CASE)
    private val rsiSource = Regex("(?:^|[/=?&])rsila[12](?:hd)?(?=$|[/.?&#])", RegexOption.IGNORE_CASE)

    fun isExcluded(channel: Channel): Boolean =
        channel.broadcaster.equals("rsi", ignoreCase = true) ||
            rsiId.matches(channel.epgId.replace(Regex("[^a-zA-Z0-9]"), "")) ||
            rsiName.containsMatchIn(channel.name.trim()) ||
            rsiSource.containsMatchIn(channel.source)

    /** Preserve complete M3U entry blocks, including their per-stream headers. */
    fun filterPlaylist(text: String): String {
        val kept = mutableListOf<String>()
        val entry = mutableListOf<String>()
        fun flush() {
            if (M3uParser.parse(entry.joinToString("\n")).none(::isExcluded)) kept.addAll(entry)
            entry.clear()
        }
        text.split('\n').forEach { line ->
            if (line.trim().startsWith("#EXTINF:")) {
                flush()
                entry.add(line)
            } else if (entry.isNotEmpty()) {
                entry.add(line)
            } else {
                kept.add(line)
            }
        }
        flush()
        val filtered = kept.joinToString("\n")
        return if (text.endsWith('\n') && !filtered.endsWith('\n')) "$filtered\n" else filtered
    }
}
