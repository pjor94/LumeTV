package it.lumetv.app.data

data class ProgrammeWindow(
    val current: Programme?,
    val next: Programme?,
    val progress: Int,
    val remainingMinutes: Long,
) {
    companion object {
        fun at(programmes: List<Programme>, now: Long): ProgrammeWindow {
            val valid = programmes.filter { it.title.isNotBlank() && it.start > 0 && it.end > it.start }
            val current = valid.filter { it.start <= now && it.end > now }.maxByOrNull { it.start }
            val next = valid.filter { it.start > now && it.start >= (current?.end ?: now) }.minByOrNull { it.start }
            val progress = current?.let { ((now - it.start).toDouble() / (it.end - it.start) * 1000).toInt().coerceIn(0, 1000) } ?: 0
            val remaining = current?.let { (it.end - now + 59_999) / 60_000 } ?: 0
            return ProgrammeWindow(current, next, progress, remaining)
        }
    }
}
