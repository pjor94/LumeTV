package it.lumetv.app.update

/** Per-launch acknowledgement, carried across recreation and interface changes, never persisted to preferences. */
internal class UpdatePromptSession(launchId: String, shown: IntArray = intArrayOf()) {
    var launchId = launchId
        private set
    private val shownVersions = shown.toMutableSet()
    fun shouldOffer(versionCode: Int) = versionCode !in shownVersions
    fun acknowledge(versionCode: Int) { shownVersions += versionCode }
    fun snapshot(): IntArray = shownVersions.toIntArray()
    fun enter(launchId: String, shown: IntArray = intArrayOf()) {
        if (this.launchId != launchId) { this.launchId = launchId; shownVersions.clear() }
        shownVersions.addAll(shown.toList())
    }
}
