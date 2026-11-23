package it.lumetv.app.ui

import android.view.View
import it.lumetv.app.data.VideoItem
import it.lumetv.core.VodKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class VideoSeasonSelectorTest {
    private val episode=VideoItem("episode","source","Serie S02E01",VodKind.EPISODE,season=2,episode=1)
    @Test fun `opening a series starts at its first numbered season rather than its representative or extras`() {
        assertEquals(1,VideoSeasonSelector.initialSeason(listOf(0,1,2),episode))
        assertEquals(3,VideoSeasonSelector.initialSeason(listOf(3,7),episode))
        assertEquals(0,VideoSeasonSelector.initialSeason(listOf(0),episode))
        assertNull(VideoSeasonSelector.initialSeason(emptyList(),episode))
    }
    @Test fun `an episode in progress selects its season including unknown numbers and later pages`() {
        assertEquals(2,VideoSeasonSelector.initialSeason(listOf(0,1,2),episode.copy(position=21_000)))
        assertEquals(99,VideoSeasonSelector.initialSeason((1..50).toList(),episode.copy(season=99,position=21_000)))
        assertEquals(0,VideoSeasonSelector.initialSeason(listOf(0,1,2),episode.copy(season=null,position=21_000)))
        assertEquals(1,VideoSeasonSelector.initialSeason(listOf(1,2),episode.copy(position=180_000,completed=true)))
    }
    @Test fun `unknown episodes and all seasons have distinct accessible choices on phone and TV`() {
        for(mobile in listOf(false,true)) {
            val choices=mutableListOf<Int?>()
            val selector=VideoSeasonSelector(RuntimeEnvironment.getApplication(),mobile,{choices+=it}){}
            selector.submit(listOf(0,1,2) to 3,0)
            val unknown=selector.findViewWithTag<View>("video_season_0")
            assertTrue(unknown.isSelected);assertEquals("Altri episodi",(unknown as android.widget.TextView).text.toString())
            selector.findViewWithTag<View>("video_season_2").performClick()
            selector.findViewWithTag<View>("video_season_all").performClick()
            assertEquals(listOf(2,null),choices)
            selector.submit(listOf(0,1,2) to 3,null)
            assertTrue(selector.findViewWithTag<View>("video_season_all").isSelected)
            assertFalse(selector.findViewWithTag<View>("video_season_0").isSelected)
        }
    }
    @Test fun `large series keep a bounded season bar while exposing the resumed season and remaining seasons`() {
        var selected:Int?=null;var more=0
        val selector=VideoSeasonSelector(RuntimeEnvironment.getApplication(),false,{selected=it}){more++}
        selector.submit((0..49).toList() to 10_000,9999)
        val resumed=selector.findViewWithTag<View>("video_season_9999")
        assertTrue(resumed.isSelected);resumed.performClick();assertEquals(9999,selected)
        selector.findViewWithTag<View>("video_seasons_more").performClick();assertEquals(1,more)
        val row=selector.getChildAt(0) as android.view.ViewGroup
        assertTrue(row.childCount<=12)
        selector.submit(listOf(1,2) to 2,2)
        assertNull(selector.findViewWithTag<View>("video_seasons_more"))
        assertNull(selector.findViewWithTag<View>("video_season_9999"))
        assertTrue(selector.findViewWithTag<View>("video_season_2").isSelected)
    }
}
