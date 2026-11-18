package com.lumalink.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID

class AdaptiveOpticalTest {
    @Test fun actualV4QrImagesReconstructAtEveryPayloadProfile() {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val decoder = FrameDecoder()
        for (payload in listOf(192, 384, 768, 1024)) {
            val sender = sender(payload, 2101); val receiver = Receiver()
            val frames = listOf(OpticalV4.manifest(sender)) + (0 until sender.count).map { OpticalV4.data(sender, it) }
            for (raw in frames) {
                val matrix = writer.encode(raw, com.google.zxing.BarcodeFormat.QR_CODE, 640, 640,
                    mapOf(com.google.zxing.EncodeHintType.MARGIN to 4))
                val pixels = ByteArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 20 else 230.toByte() }
                val decoded = decoder.decodePixels(pixels, matrix.width, matrix.height)
                assertEquals(raw, decoded); receiver.accept(decoded!!)
            }
            assertArrayEquals("payload=$payload", sender.bytes, receiver.complete)
        }
    }
    private fun sender(block: Int = 384, size: Int = 4097): Sender {
        val bytes = ByteArray(size) { (it * 73).toByte() }
        return Sender("resume.bin", bytes, block, OpticalV4.sessionId("resume.bin", Codec.hash(bytes), block))
    }
    @Test fun manifestCanArriveLateAndRepeatedManifestsNeverResetProgress() {
        val sender = sender(); val receiver = Receiver()
        assertFalse(receiver.accept(OpticalV4.data(sender, 0)))
        assertEquals(Receiver.PacketResult.NEED_MANIFEST, receiver.lastPacketResult)
        assertFalse(receiver.accept(OpticalV4.manifest(sender)))
        assertEquals(Receiver.PacketResult.MANIFEST, receiver.lastPacketResult)
        assertTrue(receiver.accept(OpticalV4.data(sender, 0)))
        receiver.accept(OpticalV4.manifest(sender)); assertEquals(1, receiver.solved)
        for (q in 1 until sender.count) receiver.accept(OpticalV4.data(sender, q))
        assertArrayEquals(sender.bytes, receiver.complete)
    }
    @Test fun allProfilesReconstructAfterLossAndPreserveStableIdentity() {
        for (block in listOf(192, 384, 768, 1024)) {
            val sender = sender(block); val receiver = Receiver()
            assertEquals(sender.id, sender(block).id)
            receiver.accept(OpticalV4.manifest(sender))
            for (emission in sender.count.toLong() until sender.count * 10L) {
                if (emission % 7 == 0L) continue
                receiver.accept(OpticalV4.data(sender, LightSchedule.sequence(sender.count, emission)))
                if (receiver.complete != null) break
            }
            assertArrayEquals("block=$block", sender.bytes, receiver.complete)
        }
    }
    @Test fun pacingNeverCatchesUpAfterARendererStall() {
        val pacer = OpticalPacer(OpticalProfile(384, 6, 0))
        assertTrue(pacer.ready(0))
        pacer.presented(0, 0)
        assertFalse(pacer.ready(166_666_665)); assertTrue(pacer.ready(166_666_666))
        pacer.presented(50_000_000_000, 1) // renderer stalled for a long time
        assertFalse(pacer.ready(50_000_000_001))
        assertTrue(pacer.ready(50_166_666_666))
    }
    @Test fun randomHoldIsNeverFirstAndOccursExactlyOnceOnAppearanceTwoToFour() {
        val pacer = OpticalPacer(OpticalProfile(1024, 15, 1), java.util.Random(42))
        val targets = hashSetOf<Int>()
        for (sequence in 0..30) {
            val extended = (1..8).filter { pacer.dwellFor(sequence) == 500_000_000L }
            assertEquals(1, extended.size); assertTrue(extended.single() in 2..4)
            targets.add(extended.single())
        }
        assertTrue("Targets must vary", targets.size > 1)
    }
    @Test fun offSettingsAndManifestDwell() {
        val off = OpticalPacer(OpticalProfile(1024, 15, 0))
        off.presented(0, 0); assertTrue(off.ready(66_666_666))
        off.presented(100_000_000, -1, manifest = true)
        assertFalse(off.ready(449_999_999)); assertTrue(off.ready(450_000_000))
        off.presented(1_000_000_000, -1, manifest = true, acquisition = true)
        assertFalse(off.ready(1_999_999_999)); assertTrue(off.ready(2_000_000_000))
    }
    @Test fun checkpointRestoresAcrossNewReceiverAndSenderInstances() {
        val directory = Files.createTempDirectory("lumalink-resume").toFile()
        try {
            val sender = sender(); val original = Receiver()
            val checkpoint = OpticalCheckpoint(File(directory, "receive.journal")); checkpoint.reset()
            val manifest = OpticalV4.manifest(sender); checkpoint.append(manifest); original.accept(manifest)
            for (q in 0..2) { val packet = OpticalV4.data(sender, q); original.accept(packet); checkpoint.append(packet) }
            val restored = Receiver(); OpticalCheckpoint(File(directory, "receive.journal")).restore(restored)
            assertEquals(original.solved, restored.solved); assertEquals(original.received, restored.received)
            val reopened = sender()
            for (q in 0 until reopened.count) restored.accept(OpticalV4.data(reopened, q))
            assertArrayEquals(sender.bytes, restored.complete)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    @Test fun checkpointRestoresPendingFountainEquationsAsWellAsSolvedBlocks() {
        val directory = Files.createTempDirectory("lumalink-equations").toFile()
        try {
            val sender = sender()
            val sequence = (sender.count..1000).first { Codec.indices(sender.count, it).size >= 2 }
            val missing = Codec.indices(sender.count, sequence).last()
            val checkpoint = OpticalCheckpoint(File(directory, "receive.journal")); checkpoint.reset()
            checkpoint.append(OpticalV4.manifest(sender)); checkpoint.append(OpticalV4.data(sender, sequence))
            val restored = Receiver(); checkpoint.restore(restored)
            assertEquals(1, restored.received); assertEquals(0, restored.solved)
            for (q in 0 until sender.count) if (q != missing) restored.accept(OpticalV4.data(sender, q))
            assertArrayEquals(sender.bytes, restored.complete)
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    @Test fun incompleteJournalTailIsDiscardedAndOldSaveCannotDeleteNewCheckpoint() {
        val directory = Files.createTempDirectory("lumalink-tail").toFile()
        try {
            val path = File(directory, "receive.journal"); val checkpoint = OpticalCheckpoint(path)
            val sender = sender(); checkpoint.reset(); val oldId = checkpoint.identity()
            checkpoint.append(OpticalV4.manifest(sender)); checkpoint.append(OpticalV4.data(sender, 0))
            checkpoint.append(OpticalV4.data(sender, 1))
            RandomAccessFile(path, "rw").use { it.setLength(it.length() - 3) }
            val restored = Receiver(); checkpoint.restore(restored); assertEquals(1, restored.solved)
            restored.accept(OpticalV4.data(sender, 1)); assertEquals(2, restored.solved)
            checkpoint.reset(); checkpoint.clear(oldId); assertTrue(path.exists())
            val oldGeneration = checkpoint.identity()
            val replacement = OpticalCheckpoint(path); replacement.reset()
            checkpoint.clear(oldGeneration); assertTrue(path.exists())
            try { checkpoint.append(OpticalV4.data(sender, 0)); fail("Old writer must not append to a replaced checkpoint") }
            catch (_: IllegalArgumentException) { }
            replacement.clear(replacement.identity()); assertFalse(path.exists())
            checkpoint.reset()
            checkpoint.clear(checkpoint.identity()); assertFalse(path.exists())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    @Test fun calibrationRanksUniqueAcceptedBytesPerActualDisplaySecond() {
        val run = UUID.randomUUID().toString(); val meter = OpticalCalibration.Meter()
        meter.accept(OpticalCalibration.packet(run, 0, -1)); assertEquals(0, meter.count)
        repeat(20) { q -> repeat(2) { meter.accept(OpticalCalibration.packet(run, 0, q)) } }
        repeat(5) { q -> meter.accept(OpticalCalibration.packet(run, 4, q)) }
        val result = meter.accept(OpticalCalibration.finish(run, LongArray(7) { 4000 }, 1))!!
        assertEquals(1024, result.profile.payload); assertEquals(6, result.profile.fps)
        assertEquals(1280.0, result.bytesPerSecond, .001)
        val decoded = OpticalCalibration.recommendation(result.encode())
        assertEquals(result.profile, decoded.profile); assertEquals(result.bytesPerSecond, decoded.bytesPerSecond, .001)
        assertEquals(25, meter.count)
    }
    @Test fun checkpointSizeLimitRejectsBeforeGrowingPastTheRestorableBound() {
        val directory = Files.createTempDirectory("lumalink-bound").toFile()
        try {
            val path = File(directory, "receive.journal"); val checkpoint = OpticalCheckpoint(path)
            checkpoint.reset()
            RandomAccessFile(path, "rw").use { it.setLength(63_999_990) }
            try { checkpoint.append(OpticalV4.manifest(sender())); fail("Expected bounded journal rejection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(63_999_990L, path.length())
        } finally { directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
    @Test fun calibrationIgnoresOtherRunsAndNeverInventsAnUnseenProfile() {
        val run = UUID.randomUUID().toString(); val meter = OpticalCalibration.Meter()
        meter.accept(OpticalCalibration.packet(run, 1, 0))
        meter.accept(OpticalCalibration.packet(UUID.randomUUID().toString(), 6, 0))
        val result = meter.accept(OpticalCalibration.finish(run, LongArray(7) { 4000 }, 0))!!
        assertEquals(192, result.profile.payload); assertEquals(6, result.profile.fps); assertEquals(1, meter.count)
    }
    @Test fun randomScheduleProvidesEveryLaterHoldOpportunityAndReconstructsFile() {
        val sender = sender(384, 4097); val receiver = Receiver()
        receiver.accept(OpticalV4.manifest(sender))
        val pacer = OpticalPacer(OpticalProfile(384, 6, 1), java.util.Random(17))
        val appearances = hashMapOf<Int, Int>()
        for (emission in 0L until 128) {
            val q = RandomHoldSchedule.sequence(emission)
            appearances[q] = (appearances[q] ?: 0) + 1
            if (pacer.dwellFor(q) == 500_000_000L) receiver.accept(OpticalV4.data(sender, q))
        }
        assertTrue(appearances.values.all { it == 4 })
        assertArrayEquals(sender.bytes, receiver.complete)
    }
    @Test fun removedTenthPolicyAndOldCalibrationCannotBeApplied() {
        try { OpticalProfile(192, 6, 10); fail("Removed hold policy") } catch (_: IllegalArgumentException) { }
        val old = OpticalV4.wrap("LLCR:", "{\"payload\":192,\"fps\":6,\"hold\":1,\"rate\":1000,\"policy\":1}".toByteArray())
        try { OpticalCalibration.recommendation(old); fail("Old calibration policy") } catch (_: IllegalArgumentException) { }
    }
    @Test fun repeatDiagnosticsDistinguishConsecutiveRereadsFromRevisitedIds() {
        val stats = QrRepeatStats()
        stats.record("A", Receiver.PacketResult.ACCEPTED)
        stats.record("A", Receiver.PacketResult.DUPLICATE)
        stats.record("A", Receiver.PacketResult.DUPLICATE)
        stats.record("B", Receiver.PacketResult.ACCEPTED)
        stats.record("A", Receiver.PacketResult.DUPLICATE)
        assertEquals(2L, stats.consecutive); assertEquals(1L, stats.revisited)
    }
}
