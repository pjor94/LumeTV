package it.lumetv.app.playlists

/** Test-APK-only document capability. Instrumentation monitors supply owned result files. */
class BackupDocumentActivity:android.app.Activity() {
    override fun onCreate(state:android.os.Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        finish()
    }
}
