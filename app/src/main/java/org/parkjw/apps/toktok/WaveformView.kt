package org.parkjw.apps.toktok

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.random.Random

/**
 * Simple animated waveform bars driven by [amplitude] (0..1).
 * When idle, shows gentle random animation.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var amplitude: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val bars = 24
    private val levels = FloatArray(bars)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val step = w / bars
        for (i in 0 until bars) {
            // Ease toward target: amplitude-driven + slight randomness for liveliness
            val target = (amplitude * (0.6f + 0.4f * Random.nextFloat()) +
                0.08f * Random.nextFloat()).coerceIn(0.05f, 1f)
            levels[i] += (target - levels[i]) * 0.4f
            val bh = min(levels[i], 1f) * h
            val x = step * i + step / 2
            canvas.drawLine(x, (h - bh) / 2, x, (h + bh) / 2, paint)
        }
        // Keep animating while visible
        if (isShown) postInvalidateDelayed(80)
    }
}
