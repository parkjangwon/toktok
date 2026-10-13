package org.parkjw.apps.toktok

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Dark overlay with a circular hole showing the crop area.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dimPaint = Paint().apply { color = 0xCC000000.toInt() }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2
        val cy = h / 2
        val r = min(w, h) / 2 * 0.92f

        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        canvas.drawCircle(cx, cy, r, clearPaint)
        canvas.restoreToCount(layer)
        canvas.drawCircle(cx, cy, r, ringPaint)
    }

    /** Crop circle in this view's coordinates. */
    fun cropCircle(): FloatArray {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) / 2 * 0.92f
        return floatArrayOf(w / 2, h / 2, r)
    }
}
