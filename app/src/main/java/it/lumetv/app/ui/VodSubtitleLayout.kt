package it.lumetv.app.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.accessibility.CaptioningManager
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import it.lumetv.app.ui.TvStyle.dp
import kotlin.math.roundToInt

/** Reserve the measured overlay area, including captions with an explicit line position. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun keepSubtitlesAbove(player:PlayerView,controls:View?) {
    val subtitles=player.subtitleView ?: return
    if(subtitles.height<=0)return
    val captionLocation=IntArray(2);subtitles.getLocationOnScreen(captionLocation)
    val overlayLocation=IntArray(2)
    var inset=0
    if(controls?.isShown==true && controls.height>0) {
        controls.getLocationOnScreen(overlayLocation)
        val captionBottom=captionLocation[1]+subtitles.height
        val overlayTop = overlayLocation[1] - controls.translationY.roundToInt()
        if(overlayTop+controls.height>captionLocation[1] && overlayTop<captionBottom)
            inset=(captionBottom-overlayTop+player.context.dp(12)).coerceIn(0,(subtitles.height*.85f).toInt())
    }
    if(subtitles.paddingBottom==inset)return
    if(inset>0) {
        val preferences=player.context.getSystemService(Context.CAPTIONING_SERVICE) as CaptioningManager
        val scale=if(preferences.isEnabled)preferences.fontScale else 1f
        // Shrinking the drawing area must not shrink the viewer's chosen caption text size.
        subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX,subtitles.height*SubtitleView.DEFAULT_TEXT_SIZE_FRACTION*scale)
    } else subtitles.setUserDefaultTextSize()
    subtitles.setPadding(subtitles.paddingLeft,subtitles.paddingTop,subtitles.paddingRight,inset)
}
