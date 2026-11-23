package it.lumetv.app.ui

/** A pending seek never changes playback until the user confirms it. */
internal class VodSeekState {
    var pending:Long?=null
        private set
    fun preview(position:Long,duration:Long):Long = position.coerceIn(0,(duration-1).coerceAtLeast(0)).also {pending=it}
    fun move(position:Long,duration:Long,direction:Int,repeats:Int):Long {
        val step=when {repeats>=18->60_000L;repeats>=7->30_000L;else->10_000L}
        return preview((pending ?: position)+step*direction,duration)
    }
    fun confirm():Long?=pending.also {pending=null}
    fun cancel(){pending=null}
}
