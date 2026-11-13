package it.lumetv.app.ui

import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EmbeddedVideoHostTest {
    @Test fun `global selectors cannot discover the embedded Video page as their host`() {
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup();val activity=controller.get()
        val root=FrameLayout(activity);activity.setContentView(root);val region=FrameLayout(activity);root.addView(region)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
        try {
            PersonalCatalogPanels(activity,scope).attachVideo(region){}
            val embedded=region.getChildAt(0) as CatalogPanelHost
            val global=CatalogPanelHost.get(activity)
            assertNotSame(embedded,global);assertSame(root,global.parent);assertSame(global,CatalogPanelHost.get(activity))
            val page=FrameLayout(activity);embedded.push(page);embedded.park()
            assertTrue(embedded.parked);assertFalse(global.back());assertSame(page,embedded.currentView)
            assertTrue(embedded.resume());assertSame(page,embedded.currentView)
        }finally {scope.cancel();controller.pause().stop().destroy()}
    }
}
