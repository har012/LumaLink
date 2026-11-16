package com.lumalink.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class CodecTest {
    private fun fixture(size: Int) = ByteArray(size).also { Random(42).nextBytes(it) }
    private fun signed(raw: String, edit: (JSONObject) -> Unit): String {
        val o = JSONObject(raw); edit(o); o.put("c", Codec.packetHash(o)); return o.toString()
    }
    @Test fun systematicRoundTripsAtBlockBoundaries() {
        for (size in listOf(1, 419, 420, 421, 4096)) {
            val data = fixture(size); val sender = Sender("test.bin", data); val receiver = Receiver()
            for (q in 0 until sender.count) assertTrue(receiver.accept(sender.frame(q)))
            assertArrayEquals(data, receiver.complete)
            assertEquals(Receiver.State.COMPLETE, receiver.state)
        }
    }
    @Test fun malformedFirstPacketDoesNotCaptureSessionOrSequence() {
        val sender = Sender("a", fixture(1000)); val receiver = Receiver()
        assertFalse(receiver.accept(signed(sender.frame(0)) { it.put("d", "!") }))
        assertEquals(Receiver.State.WAITING, receiver.state)
        assertEquals(0, receiver.received)
        assertTrue(receiver.accept(sender.frame(0)))
    }
    @Test fun emptyIdAndBadIntegrityAreRejectedWithoutMutation() {
        val sender = Sender("a", fixture(1000)); val receiver = Receiver()
        assertFalse(receiver.accept(signed(sender.frame(0)) { it.put("id", "") }))
        assertFalse(receiver.accept(JSONObject(sender.frame(0)).put("q", 1).toString()))
        assertEquals(Receiver.State.WAITING, receiver.state)
        assertTrue(receiver.accept(sender.frame(0)))
        assertFalse(receiver.accept(sender.frame(0)))
        assertEquals(1, receiver.received)
    }
    @Test fun wrongHashFailsExplicitly() {
        val sender = Sender("a", fixture(420)); val receiver = Receiver()
        assertFalse(receiver.accept(signed(sender.frame(0)) { it.put("h", "0".repeat(64)) }))
        assertEquals(Receiver.State.FAILED, receiver.state)
        assertNull(receiver.complete)
        assertNotNull(receiver.error)
        assertFalse(receiver.accept(sender.frame(1)))
    }
    @Test fun foreignSessionCannotChangeActiveTransfer() {
        val sender = Sender("a", fixture(1000)); val other = Sender("b", fixture(1000)); val receiver = Receiver()
        assertTrue(receiver.accept(sender.frame(0)))
        assertFalse(receiver.accept(other.frame(1)))
        assertEquals(1, receiver.received)
        for (q in 1 until sender.count) receiver.accept(sender.frame(q))
        assertArrayEquals(sender.bytes, receiver.complete)
    }
    @Test fun invalidPacketAfterInitializationDoesNotConsumeSequence() {
        val sender = Sender("a", fixture(1000)); val receiver = Receiver()
        receiver.accept(sender.frame(0))
        assertFalse(receiver.accept(signed(sender.frame(1)) { it.put("d", "!") }))
        assertEquals(1, receiver.received)
        assertTrue(receiver.accept(sender.frame(1)))
        receiver.accept(sender.frame(2))
        assertArrayEquals(sender.bytes, receiver.complete)
    }
    @Test fun contradictoryRedundantPacketFailsVisibly() {
        val sender = Sender("a", fixture(1000)); val receiver = Receiver()
        receiver.accept(sender.frame(0))
        // Find a degree-one repair packet for the already known block.
        val q = (sender.count..1000).first { Codec.indices(sender.count, it).contentEquals(intArrayOf(0)) }
        val bad = signed(sender.frame(q)) {
            val payload = Codec.un64(it.getString("d")); payload[0] = (payload[0].toInt() xor 1).toByte()
            it.put("d", Codec.b64(payload))
        }
        assertFalse(receiver.accept(bad))
        assertEquals(Receiver.State.FAILED, receiver.state)
        assertNotNull(receiver.error)
    }
    @Test fun seededLossBurstsLateJoinReorderingAndDuplicates() {
        for (seed in 0 until 30) {
            val random = Random(seed.toLong())
            val data = fixture(12_345); val sender = Sender("a", data); val receiver = Receiver()
            val batch = mutableListOf<String>()
            // Start after systematic packets to exercise actual fountain recovery.
            for (q in sender.count until sender.count * 40) {
                if (q % 37 in 0..6 || random.nextDouble() < 0.30) continue
                batch.add(sender.frame(q))
                if (batch.size == 9) {
                    java.util.Collections.shuffle(batch, random)
                    for (frame in batch) { receiver.accept(frame); receiver.accept(frame) }
                    batch.clear()
                }
                if (receiver.complete != null) break
            }
            assertArrayEquals("seed=$seed", data, receiver.complete)
        }
    }
    @Test fun oversizeAndIncorrectTypesAreRejected() {
        val sender = Sender("a", fixture(1000)); val receiver = Receiver()
        assertFalse(receiver.accept("x".repeat(4097)))
        assertFalse(receiver.accept(signed(sender.frame(0)) { it.put("s", "1000") }))
        assertFalse(receiver.accept(signed(sender.frame(0)) { it.put("q", -1) }))
        assertEquals(Receiver.State.WAITING, receiver.state)
    }
}
