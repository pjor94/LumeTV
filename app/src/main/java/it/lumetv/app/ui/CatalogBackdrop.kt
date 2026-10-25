package it.lumetv.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** Static light, drawn without blur passes, bitmaps or a running animation. */
class CatalogBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        paint.shader = RadialGradient(w * 0.78f, -h * 0.24f, w * 0.9f,
            intArrayOf(0xFF202B3D.toInt(), 0xFF11151D.toInt(), TvStyle.background),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.BLACK); canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
}
