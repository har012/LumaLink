package com.lumalink.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

/** Generated QR luminance -> production decoder -> production receiver -> verified file. */
class QrReceivingTest {
    private val writer = QRCodeWriter()
    private val hints = mapOf(EncodeHintType.MARGIN to 4,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "UTF-8")
    private fun file(size: Int) = ByteArray(size).also { Random(2026).nextBytes(it) }

    private fun scan(text: String, decoder: FrameDecoder, rotation: Int = 0, noiseSeed: Int = 0): String? {
        val grid = writer.encode(text, BarcodeFormat.QR_CODE, 360, 360, hints)
        val random = Random(noiseSeed.toLong())
        // Synthetic grayscale capture with mild illumination gradient and pixel noise.
        val pixels = ByteArray(grid.width * grid.height) { i ->
            val base = if (grid[i % grid.width, i / grid.width]) 24 else 215
            (base + 15 * (i / grid.width) / grid.height + random.nextInt(9) - 4).toByte()
        }
        val turned = ByteArray(pixels.size)
        LuminancePlane.copy(ByteBuffer.wrap(pixels), grid.width, 1, 0, 0,
            grid.width, grid.height, rotation, turned)
        return decoder.decodePixels(turned, grid.width, grid.height)
    }

    @Test(timeout = 120_000) fun reconstructsTenKilobytesFromQrImagesWithLossLateJoinRotationAndDuplicates() {
        for (seed in 0..2) {
            val random = Random(seed.toLong())
            val original = file(10_000)
            val sender = Sender("qr-receive-test.bin", original, 192)
            val receiver = Receiver()
            val decoder = FrameDecoder()
            var scanned = 0; var decoded = 0; var duplicates = 0; var missed = 0
            val batch = mutableListOf<Pair<Int, String>>()
            // Join halfway through the initial systematic pass; deliberately lose more frames.
            val start = sender.count / 2
            for (emission in start.toLong() until sender.count * 20L) {
                if (emission % 23 in 4L..6L || random.nextDouble() < .25) { missed++; continue }
                val sequence = LightSchedule.sequence(sender.count, emission)
                batch.add(sequence to sender.frame(sequence))
                if (batch.size < 4) continue
                java.util.Collections.shuffle(batch, random)
                for ((q, raw) in batch) {
                    scanned++
                    val text = scan(raw, decoder, (scanned % 4) * 90, seed + scanned)
                    if (text != null) {
                        decoded++
                        assertEquals("QR text differs at seed=$seed sequence=$q", raw, text)
                        receiver.accept(text)
                        if (receiver.complete == null) {
                            val before = receiver.received
                            assertFalse("Duplicate must not be accepted twice", receiver.accept(text))
                            assertEquals(before, receiver.received)
                            assertEquals(Receiver.PacketResult.DUPLICATE, receiver.lastPacketResult)
                            duplicates++
                        }
                    }
                    if (receiver.complete != null) break
                }
                batch.clear()
                if (receiver.complete != null) break
            }
            assertTrue("No QR images decoded for seed=$seed", decoded > 0)
            assertTrue(missed > 0); assertTrue(duplicates > 0)
            assertArrayEquals("seed=$seed scanned=$scanned decoded=$decoded accepted=${receiver.received}", original, receiver.complete)
            assertEquals(Receiver.State.COMPLETE, receiver.state)
            assertEquals(original.size, receiver.recoveredBytes)
            assertEquals(Codec.hash(original), Codec.hash(receiver.complete!!))
            println("seed=$seed file=${original.size}B sourceBlocks=${sender.count} QRimages=$scanned decoded=$decoded dropped=$missed duplicates=$duplicates accepted=${receiver.received} SHA256=PASS")
        }
    }

    @Test fun readableQrWithBadPacketChecksumCannotPoisonReceiver() {
        val sender = Sender("small.bin", file(500), 192)
        val decoder = FrameDecoder(); val receiver = Receiver()
        val valid = sender.frame(0)
        val corrupt = valid.dropLast(1) + if (valid.last() == '0') "1" else "0"
        val text = scan(corrupt, decoder)
        assertEquals(corrupt, text)
        assertFalse(receiver.accept(text!!))
        assertEquals(Receiver.State.WAITING, receiver.state)
        assertEquals(0, receiver.received)
        for (q in 0 until sender.count) {
            val decoded = scan(sender.frame(q), decoder, q % 4 * 90)
            assertNotNull("Valid QR was not decoded: $q", decoded)
            assertTrue(receiver.accept(decoded!!))
        }
        assertArrayEquals(sender.bytes, receiver.complete)
    }

    @Test fun blankFramesDoNotPreventDecodingTheNextValidQr() {
        val decoder = FrameDecoder()
        repeat(5) { assertNull(decoder.decodePixels(ByteArray(360 * 360) { 230.toByte() }, 360, 360)) }
        val sender = Sender("one.bin", file(1), 192)
        val text = scan(sender.frame(0), decoder, 270)
        assertNotNull(text)
        val receiver = Receiver()
        assertTrue(receiver.accept(text!!))
        assertArrayEquals(sender.bytes, receiver.complete)
    }
}
