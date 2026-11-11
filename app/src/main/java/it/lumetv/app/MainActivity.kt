package it.lumetv.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import it.lumetv.app.update.UpdateController
import java.util.UUID

/** Stable launcher/deep-link entry point. Each interface owns its navigation and window. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        route(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    private fun route(source: Intent) {
        val target = if (InterfacePreferences.resolve(this) == InterfaceMode.SMARTPHONE)
            MobileActivity::class.java else TvActivity::class.java
        startActivity(Intent(this, target).apply {
            action = source.action
            data = source.data
            source.extras?.let { putExtras(it) }
            putExtra(UpdateController.LAUNCH_KEY, source.getStringExtra(UpdateController.LAUNCH_KEY) ?: UUID.randomUUID().toString())
            // Routing stays inside the app; it must not trigger leave-to-PiP behavior.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        })
        finish()
    }
}
