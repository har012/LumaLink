package com.lumalink.app

import android.content.Context
import android.graphics.*
import android.view.View
import android.animation.ValueAnimator
import android.os.SystemClock
import kotlin.math.sin

/** Decorative optical-link illustration; deliberately not a scannable QR symbol. */
class OpticalSignalView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var motionEnabled = true
        set(value) { field = value; removeCallbacks(tick); invalidate() }
    private var pulseAt = 0L
    private val tick = Runnable { invalidate() }
    init {
        isClickable = true; isFocusable = true
        contentDescription = "Optical link preview. Tap to send a light pulse."
    }
    override fun performClick(): Boolean {
        super.performClick()
        pulseAt = SystemClock.uptimeMillis(); invalidate()
        return true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val cy = height / 2f
        val moving = motionEnabled && ValueAnimator.areAnimatorsEnabled()
        val now = SystemClock.uptimeMillis()
        val phase = if (moving) (now % 2400) / 2400f else .5f
        val left = width * .18f
        val right = width * .82f
        paint.shader = LinearGradient(left, cy, right, cy, intArrayOf(0xFFB9FF65.toInt(), 0xFF9B8AFF.toInt()), null, Shader.TileMode.CLAMP)
        paint.strokeWidth = 2 * d; paint.style = Paint.Style.STROKE
        canvas.drawLine(left + 18*d, cy, right - 18*d, cy, paint)
        for (i in 1..3) {
            paint.alpha = 95 - i * 20
            canvas.drawCircle(width / 2f, cy, (12 + i * 12 + if (moving) 3 * sin(phase * Math.PI * 2).toFloat() else 0f) * d, paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        for (x in listOf(left, right)) {
            paint.shader = null; paint.color = 0xFF182031.toInt()
            canvas.drawRoundRect(x - 22*d, cy - 28*d, x + 22*d, cy + 28*d, 10*d, 10*d, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*d
            paint.color = if (x == left) 0xFFB9FF65.toInt() else 0xFFB3A6FF.toInt()
            canvas.drawRoundRect(x - 14*d, cy - 20*d, x + 14*d, cy + 20*d, 4*d, 4*d, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, cy, 4*d, paint)
        }
        paint.shader = null; paint.color = 0xFFB9FF65.toInt()
        canvas.drawCircle(left + (right - left) * phase, cy, 5*d, paint)
        if (moving && now - pulseAt < 1100) {
            val progress = (now - pulseAt) / 1100f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2*d; paint.alpha = ((1-progress) * 160).toInt()
            canvas.drawCircle(width / 2f, cy, (8 + progress * 44)*d, paint)
            paint.alpha = 255; paint.style = Paint.Style.FILL
        }
        removeCallbacks(tick)
        if (moving && isShown && windowVisibility == VISIBLE) postDelayed(tick, 50)
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate() else removeCallbacks(tick)
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
