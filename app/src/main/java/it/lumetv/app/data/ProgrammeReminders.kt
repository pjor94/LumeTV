package it.lumetv.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ProgrammeReminder(val channelId: String, val start: Long, val end: Long, val title: String) {
    val key get() = "$channelId:$start"
}

/** Foreground-only delivery: a reminder is consumed once, never replayed after a long standby. */
class ReminderBook(initial: List<ProgrammeReminder> = emptyList()) {
    private val pending = initial.filter { it.channelId.isNotBlank() && it.start > 0 && it.end > it.start && it.title.isNotBlank() }
        .distinctBy { it.key }.take(MAX_REMINDERS).toMutableList()
    val entries: List<ProgrammeReminder> get() = pending.toList()
    fun contains(item: DiscoveryProgramme) = pending.any { it.key == item.key }
    fun toggle(item: DiscoveryProgramme, now: Long): Boolean {
        if (pending.removeAll { it.key == item.key }) return false
        prune(now)
        if (item.programme.start <= now) return false
        if (pending.size >= MAX_REMINDERS) pending.removeAt(0)
        pending += ProgrammeReminder(item.channel.id, item.programme.start, item.programme.end, item.programme.title)
        return true
    }
    fun takeDue(channelIds: Set<String>, now: Long): ProgrammeReminder? {
        prune(now)
        pending.removeAll { it.channelId !in channelIds }
        val due = pending.filter { it.start - now <= 2 * 60_000 && now < it.start + 2 * 60_000 }
            .minByOrNull { it.start }
        if (due != null) pending.remove(due)
        return due
    }
    private fun prune(now: Long) { pending.removeAll { it.end <= now || now >= it.start + 2 * 60_000 } }
    companion object { const val MAX_REMINDERS = 50 }
}

class ProgrammeReminders(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("discovery", Context.MODE_PRIVATE)
    private val book = ReminderBook(runCatching {
        val text = prefs.getString("reminders", "[]").orEmpty()
        require(text.length <= 64 * 1024)
        val rows = JSONArray(text)
        (0 until minOf(rows.length(), ReminderBook.MAX_REMINDERS)).mapNotNull { index ->
            rows.optJSONObject(index)?.let { ProgrammeReminder(it.optString("channel"), it.optLong("start"),
                it.optLong("end"), it.optString("title").take(300)) }
        }
    }.getOrDefault(emptyList()))
    fun contains(item: DiscoveryProgramme) = book.contains(item)
    fun toggle(item: DiscoveryProgramme, now: Long): Boolean = book.toggle(item, now).also { save() }
    fun takeDue(channelIds: Set<String>, now: Long): ProgrammeReminder? {
        val before = book.entries
        return book.takeDue(channelIds, now).also { if (book.entries != before) save() }
    }
    private fun save() {
        val rows = JSONArray()
        book.entries.forEach { p -> rows.put(JSONObject().put("channel", p.channelId).put("start", p.start)
            .put("end", p.end).put("title", p.title)) }
        prefs.edit().putString("reminders", rows.toString()).apply()
    }
}
