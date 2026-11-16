package com.lumalink.app

import androidx.camera.core.ImageProxy
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

/** Confined to the camera executor. Reuse buffers after synchronous decoding. */
class FrameDecoder {
    private var packed = ByteArray(0)
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val crop = image.cropRect
        val width = crop.width(); val height = crop.height()
        if (packed.size != width * height) packed = ByteArray(width * height)
        val rotation = image.imageInfo.rotationDegrees
        val swap = rotation == 90 || rotation == 270
        val outWidth = if (swap) height else width
        val outHeight = if (swap) width else height
        LuminancePlane.copy(buffer, plane.rowStride, plane.pixelStride, crop.left, crop.top, width, height, rotation, packed)
        return try {
            val source = PlanarYUVLuminanceSource(packed, outWidth, outHeight, 0, 0, outWidth, outHeight, false)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) { null } finally { reader.reset() }
    }
}
