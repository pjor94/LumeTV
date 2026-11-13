package it.lumetv.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PersonalCatalogPanelsTest {
    @Test fun `restoring closed Video cannot overwrite another section's newer state`()=runTest {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val panels=PersonalCatalogPanels(activity.get(),this)
            panels.restoreState(Bundle().apply {putString("video_query","film");putInt("video_position",650);putString("mobile_query","old")})
            val result=Bundle().apply {putString("mobile_query","new")}
            panels.saveState(result)
            assertEquals("new",result.getString("mobile_query"));assertEquals("film",result.getString("video_query"))
            assertEquals(650,result.getInt("video_position"));assertFalse(result.getBoolean("video_open"))
        } finally {activity.pause().stop().destroy()}
    }
}
