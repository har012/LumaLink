package com.lumalink.app

import java.nio.ByteBuffer

/** Copy a cropped, strided plane and rotate clockwise into reusable storage. */
internal object LuminancePlane {
    fun copy(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, left: Int, top: Int,
             width: Int, height: Int, rotation: Int, output: ByteArray) {
        require(rotation in listOf(0, 90, 180, 270))
        require(output.size == width * height)
        val base = buffer.position()
        if (rotation == 0 && pixelStride == 1) {
            val source = buffer.duplicate()
            for (y in 0 until height) {
                source.position(base + (top + y) * rowStride + left)
                source.get(output, y * width, width)
            }
            return
        }
        for (y in 0 until height) for (x in 0 until width) {
            val destination = when (rotation) {
                90 -> x * height + height - 1 - y
                180 -> (height - 1 - y) * width + width - 1 - x
                270 -> (width - 1 - x) * height + y
                else -> y * width + x
            }
            output[destination] = buffer.get(base + (top + y) * rowStride + (left + x) * pixelStride)
        }
    }
}
