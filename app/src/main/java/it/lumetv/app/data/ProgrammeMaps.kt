package it.lumetv.app.data

/** Combine visible history with current/next without replacing the selected channel's past events. */
internal fun mergeProgrammeWindows(first:Map<String,List<Programme>>,second:Map<String,List<Programme>>):Map<String,List<Programme>> =
    (first.keys+second.keys).associateWith {key->(first[key].orEmpty()+second[key].orEmpty()).distinctBy {it.eventId.ifBlank {it.start.toString()}}.sortedBy {it.start}}
