package it.lumetv.app.home

import android.content.Context
import android.app.AlertDialog
import android.net.Uri
import androidx.activity.ComponentActivity
import it.lumetv.app.integration.IntegrationStore
import it.lumetv.app.integration.SharedCatalog
import it.lumetv.app.integration.SharedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomePrivacyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val id = "1".repeat(24)
    private val name = "$id-${"2".repeat(16)}.png"
    private val prefs get() = context.getSharedPreferences("home_channels", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("lume", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `home consent and revocation do not change the Aura grant`() {
        IntegrationStore.setAllowed(context, true)
        HomeChannels.configure(context, true, HomeSelection.MANUAL, setOf(id))
        assertTrue(IntegrationStore.allowed(context)); assertTrue(HomeChannels.configuration(context).enabled)
        HomeChannels.configure(context, false)
        assertTrue(IntegrationStore.allowed(context)); assertFalse(HomeChannels.configuration(context).enabled)
        IntegrationStore.setAllowed(context, false)
        HomeChannels.configure(context, true)
        assertFalse(IntegrationStore.allowed(context)); assertTrue(HomeChannels.configuration(context).enabled)
    }

    @Test fun `manual selection drops invalid IDs and survives a new preference read`() {
        HomeChannels.configure(context, true, HomeSelection.MANUAL, (1..30).map { "%024x".format(it) }.toSet() + "../private")
        val config = HomeChannels.configuration(context)
        assertEquals(24, config.manualIds.size); assertFalse(config.manualIds.contains("../private"))
        assertEquals(HomeSelection.MANUAL, config.selection); assertTrue(config.createRequested)
    }

    @Test fun `ordinary Home visibility requests preserve dismissed favorites`() {
        prefs.edit().putBoolean("enabled", true).putString("selection", HomeSelection.FAVORITES.name)
            .putStringSet("suppressed_ids", setOf(id)).commit()
        HomeChannels.configure(context, true, requestRow = true)
        assertEquals(setOf(id), HomeChannels.configuration(context).suppressedIds)
        HomeChannels.configure(context, true, restoreRemoved = true)
        assertTrue(HomeChannels.configuration(context).suppressedIds.isEmpty())
    }

    @Test fun `showing a manual row preserves dismissals while explicitly choosing a card restores it`() {
        prefs.edit().putBoolean("enabled", true).putString("selection", HomeSelection.MANUAL.name)
            .putStringSet("manual_ids", setOf(id)).putStringSet("suppressed_ids", setOf(id)).putStringSet("published_ids", setOf(id)).commit()
        HomeChannels.configure(context, true, requestRow = true)
        assertEquals(setOf(id), HomeChannels.configuration(context).suppressedIds)
        HomeChannels.configure(context, true, restoreSelection = true)
        assertTrue(HomeChannels.configuration(context).suppressedIds.isEmpty())
        assertTrue(HomeChannels.configuration(context).publishedIds.isEmpty())
    }

    @Test @Config(sdk = [25]) fun `older Android versions keep normal navigation without invoking Home APIs`() {
        assertFalse(HomeChannels.supported(context))
        HomeChannels.schedule(context)
        assertNull(HomeChannels.publish(context))
    }

    @Test fun `deselect all stays cleared when the native checkbox adapter binds its rows again`() {
        val channels = (1..3).map { SharedChannel("%024x".format(it), "Canale $it", it, null, null, false, false, null, null) }
        HomeChannels.configure(context, true, HomeSelection.MANUAL, channels.map { it.id }.toSet())
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var dialog: AlertDialog? = null
        val settings = HomeChannelSettings(activity, scope, { dialog = it }, { true }, {}, {})
        controller.start().resume().visible()
        try {
            val picker = HomeChannelSettings::class.java.getDeclaredMethod("chooseChannels", SharedCatalog::class.java)
            picker.isAccessible = true
            picker.invoke(settings, SharedCatalog(channels, null, null, null, null))
            val window = requireNotNull(dialog)
            window.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            channels.indices.forEach { position ->
                window.listView.adapter.getView(position, null, window.listView)
                assertFalse("Rebound checkbox $position must stay cleared", window.listView.isItemChecked(position))
            }
            assertFalse(window.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
            window.dismiss()
        } finally { scope.cancel(); controller.pause().stop().destroy() }
    }

    @Test fun `artwork reads are bounded by consent exact manifest and URI grammar`() {
        val controller = Robolectric.buildContentProvider(HomeArtworkProvider::class.java).create()
        val provider = controller.get()
        val file = HomeArtwork.file(context, name); file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
        val uri = Uri.parse("content://${HomeArtwork.AUTHORITY}/poster/$name")
        fun denied(value: Uri = uri, mode: String = "r") {
            try { provider.openFile(value, mode); fail("Artwork unexpectedly accessible: $value") }
            catch (_: FileNotFoundException) { }
        }
        denied()
        HomeChannels.configure(context, true)
        denied()
        prefs.edit().putStringSet("artwork_names", setOf(name)).commit()
        provider.openFile(uri, "r").use { assertTrue(it.statSize > 0) }
        denied(uri, "rw"); denied(Uri.parse("$uri?token=secret")); denied(Uri.parse("$uri#fragment"))
        denied(Uri.parse("content://${HomeArtwork.AUTHORITY}/poster/%2e%2e%2fprivate.xml"))
        denied(Uri.parse("content://other/ poster/$name"))
        HomeChannels.configure(context, false); denied()
        controller.shutdown()
    }
}
