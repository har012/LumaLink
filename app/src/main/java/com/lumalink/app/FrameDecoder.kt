package com.lumalink.app

import androidx.camera.core.ImageProxy
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Decode luminance directly. QR finder patterns handle rotation without a pixel rotation pass. */
class FrameDecoder {
    private var packed = ByteArray(0)
    private var full = ByteArray(0)
    private var attempts = 0
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    var resolution = ""; private set
    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val crop = image.cropRect
        val width = crop.width(); val height = crop.height()
        resolution = "${image.width}x${image.height}"
        val side = (minOf(width, height) * .9f).toInt()
        if (packed.size != side * side) packed = ByteArray(side * side)
        LuminancePlane.copy(plane.buffer, plane.rowStride, plane.pixelStride,
            crop.left + (width - side) / 2, crop.top + (height - side) / 2, side, side, 0, packed)
        decodePixels(packed, side, side)?.let { return it }
        // Periodic full-frame search handles QR symbols outside the centered scan region.
        if (++attempts % 3 != 0) return null
        if (full.size != width * height) full = ByteArray(width * height)
        LuminancePlane.copy(plane.buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top, width, height, 0, full)
        return decodePixels(full, width, height)
    }
    internal fun decodePixels(bytes: ByteArray, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) { null } finally { reader.reset() }
}
