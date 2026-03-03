package com.selfcode.xmusic.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

class AudioVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barCount = 32
    private val barHeights = FloatArray(barCount) { Random.nextFloat() * 0.3f }
    private val targetHeights = FloatArray(barCount) { Random.nextFloat() * 0.3f }
    private var colorStart = 0xFF7C3AFF.toInt()
    private var colorEnd = 0xFFC850C0.toInt()
    private var isAnimating = false
    private var phase = 0f
    private val rect = RectF()
    private val cornerRadius = 4f

    fun setColors(start: Int, end: Int) {
        colorStart = start
        colorEnd = end
        invalidate()
    }

    fun startAnimation() {
        isAnimating = true
        invalidate()
    }

    fun stopAnimation() {
        isAnimating = false
        for (i in targetHeights.indices) targetHeights[i] = 0.05f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val gap = w * 0.015f
        val barWidth = (w - gap * (barCount + 1)) / barCount

        paint.shader = LinearGradient(0f, h, 0f, 0f, colorStart, colorEnd, Shader.TileMode.CLAMP)

        if (isAnimating) {
            phase += 0.08f
            for (i in 0 until barCount) {
                targetHeights[i] = (0.2f + 0.8f * ((sin(phase + i * 0.4f) + 1f) / 2f) * (0.5f + Random.nextFloat() * 0.5f)).coerceIn(0.05f, 1f)
            }
        }

        for (i in 0 until barCount) {
            barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.15f
            val barH = barHeights[i] * h
            val left = gap + i * (barWidth + gap)
            rect.set(left, h - barH, left + barWidth, h)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }

        if (isAnimating) {
            postInvalidateOnAnimation()
        }
    }
}
