package com.lumalink.app

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.min

/** An integer number of physical display pixels per QR module, with no filtering. */
class QrDisplayView(context: Context) : View(context) {
    private var symbol: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val target = Rect()
    fun setSymbol(bitmap: Bitmap) { symbol = bitmap; invalidate() }
    init { contentDescription = "Animated QR transfer. Point the receiving camera here." }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val bitmap = symbol ?: return
        val scale = (min(width, height) / bitmap.width).coerceAtLeast(1)
        val size = bitmap.width * scale
        val left = (width - size) / 2; val top = (height - size) / 2
        target.set(left, top, left + size, top + size)
        canvas.drawBitmap(bitmap, null, target, paint)
    }
}
