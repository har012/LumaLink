package com.lumalink.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class OpticalReadabilityTest {
    private fun data(size: Int) = ByteArray(size).also { Random(42).nextBytes(it) }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) {} }
    @Test fun base45KnownVectorsAndAllBytePairs() {
        assertEquals("BB8", CompactOpticalPacket.base45("AB".toByteArray()))
        assertEquals("%69 VD92EX0", CompactOpticalPacket.base45("Hello!!".toByteArray()))
        for (i in 0..65535) {
            val bytes = byteArrayOf((i / 256).toByte(), i.toByte())
            assertArrayEquals(bytes, CompactOpticalPacket.un45(CompactOpticalPacket.base45(bytes)))
        }
        rejects { CompactOpticalPacket.un45("A") }
        rejects { CompactOpticalPacket.un45(":::") }
        rejects { CompactOpticalPacket.un45("abc") }
    }
    @Test fun compactFramesRecoverExactFileAndExcludePadding() {
        for (size in listOf(1, 191, 192, 193, 4096)) {
            val sender = Sender("光 image.bin", data(size), 192)
            val receiver = Receiver()
            for (q in (0 until sender.count).reversed()) assertTrue(receiver.accept(sender.frame(q)))
            assertArrayEquals(sender.bytes, receiver.complete)
            assertEquals(size, receiver.recoveredBytes)
        }
    }
    @Test fun corruptCompactFrameCannotCaptureSessionOrConsumeSequence() {
        val sender = Sender("a.bin", data(600), 192)
        val frame = sender.frame(0)
        val corrupt = frame.dropLast(1) + if (frame.last() == '0') "1" else "0"
        val receiver = Receiver()
        assertFalse(receiver.accept(corrupt)); assertEquals(0, receiver.received)
        assertTrue(receiver.accept(frame)); assertFalse(receiver.accept(frame))
        assertEquals(Receiver.PacketResult.DUPLICATE, receiver.lastPacketResult)
    }
    @Test fun repeatedSystematicPassRecoversPacketsMissedInitially() {
        val sender = Sender("file.bin", data(4096), 192); val receiver = Receiver()
        for (emission in sender.count.toLong() until sender.count * 8L) {
            receiver.accept(sender.frame(LightSchedule.sequence(sender.count, emission)))
            if (receiver.complete != null) break
        }
        assertArrayEquals(sender.bytes, receiver.complete)
    }
    @Test fun compactQrUsesFewerModulesAndDecodesInEveryOrientation() {
        val writer = QRCodeWriter()
        val hints = mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.CHARACTER_SET to "UTF-8")
        val compact = Sender("image.bin", data(1024), 192).frame(0)
        val legacy = Sender("image.bin", data(1024)).frame(0)
        val small = writer.encode(compact, BarcodeFormat.QR_CODE, 0, 0, hints)
        val large = writer.encode(legacy, BarcodeFormat.QR_CODE, 0, 0, hints)
        assertTrue("compact=${small.width}, legacy=${large.width}", small.width < large.width)
        val grid = writer.encode(compact, BarcodeFormat.QR_CODE, 480, 480, hints)
        val pixels = ByteArray(grid.width * grid.height) { i -> if (grid[i % grid.width, i / grid.width]) 20 else 230.toByte() }
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = ByteArray(pixels.size)
            LuminancePlane.copy(java.nio.ByteBuffer.wrap(pixels), grid.width, 1, 0, 0, grid.width, grid.height, rotation, rotated)
            assertEquals("rotation=$rotation", compact, FrameDecoder().decodePixels(rotated, grid.width, grid.height))
        }
    }
    @Test fun bulkLuminanceCopyRespectsPaddingCropAndBufferPosition() {
        val bytes = java.nio.ByteBuffer.wrap(byteArrayOf(99, 9, 1, 2, 3, 9, 9, 4, 5, 6, 9))
        bytes.position(1)
        val output = ByteArray(6)
        LuminancePlane.copy(bytes, 5, 1, 1, 0, 3, 2, 0, output)
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6), output)
        assertEquals(1, bytes.position())
    }
}
