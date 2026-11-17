package com.lumalink.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.widget.FrameLayout
import kotlin.math.sin

/** Home-only ambient light; no animation runs during optical sending or receiving. */
class AmbientLayout(context: Context) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var violet: Shader? = null
    private var teal: Shader? = null
    var motionEnabled = true
        set(value) { field = value; removeCallbacks(tick); invalidate() }
    private val tick = Runnable { invalidate() }
    init { setWillNotDraw(false) }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        violet = RadialGradient(0f, 0f, w.coerceAtLeast(1) * .95f, 0x303B3269, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        teal = RadialGradient(0f, 0f, w.coerceAtLeast(1) * .8f, 0x25346D69, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0A0E18.toInt())
        val moving = motionEnabled && ValueAnimator.areAnimatorsEnabled()
        val phase = if (moving) SystemClock.uptimeMillis() / 7000.0 else 0.0
        canvas.save(); canvas.translate(width * .9f, height * (.15f + .06f * sin(phase).toFloat()))
        paint.shader = violet; canvas.drawCircle(0f, 0f, width.toFloat(), paint); canvas.restore()
        canvas.save(); canvas.translate(width * .05f, height * (.65f + .08f * sin(phase + 2).toFloat()))
        paint.shader = teal; canvas.drawCircle(0f, 0f, width.toFloat(), paint); canvas.restore()
        paint.shader = null
        removeCallbacks(tick)
        if (moving && isShown && windowVisibility == VISIBLE) postDelayed(tick, 50)
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate() else removeCallbacks(tick)
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
}
