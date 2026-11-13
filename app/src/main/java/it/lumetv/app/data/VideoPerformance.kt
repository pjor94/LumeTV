package it.lumetv.app.data

import android.os.Trace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Counts and timings contain phase names only, never playlist text, URLs or credentials. */
internal object VideoPerformance {
    private val counts=ConcurrentHashMap<String,AtomicLong>()
    private val nanos=ConcurrentHashMap<String,AtomicLong>()
    fun count(name:String){counts.getOrPut(name){AtomicLong()}.incrementAndGet()}
    fun <T> measure(name:String,action:()->T):T {
        Trace.beginSection("Video:$name");val start=System.nanoTime()
        try {return action()}finally {Trace.endSection();count(name);nanos.getOrPut(name){AtomicLong()}.addAndGet(System.nanoTime()-start)}
    }
    fun snapshot():Map<String,Long> = counts.mapValues {it.value.get()}+nanos.mapKeys {it.key+"_ns"}.mapValues {it.value.get()}
}
