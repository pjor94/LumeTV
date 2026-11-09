package it.lumetv.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

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
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }
}
