package com.lumalink.app

import android.content.Context
import android.graphics.*
import android.view.View

/** Decorative optical-link illustration; deliberately not a scannable QR symbol. */
class OpticalSignalView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val cy = height / 2f
        val left = width * .18f
        val right = width * .82f
        paint.shader = LinearGradient(left, cy, right, cy, intArrayOf(0xFFB9FF65.toInt(), 0xFF9B8AFF.toInt()), null, Shader.TileMode.CLAMP)
        paint.strokeWidth = 2 * d; paint.style = Paint.Style.STROKE
        canvas.drawLine(left + 18*d, cy, right - 18*d, cy, paint)
        for (i in 1..3) {
            paint.alpha = 95 - i * 20
            canvas.drawCircle(width / 2f, cy, (12 + i * 12) * d, paint)
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
        canvas.drawCircle(width / 2f, cy, 5*d, paint)
    }
}
