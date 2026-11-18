package com.lumalink.app

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class DirectProtocolTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun encrypted(data: ByteArray, index: Int = 0): ByteArray = ByteArrayOutputStream().also {
        DirectProtocol.write(DataOutputStream(it), key, "session-one", index, data)
    }.toByteArray()
    private fun decode(data: ByteArray, index: Int = 0, secret: ByteArray = key) =
        DirectProtocol.read(DataInputStream(ByteArrayInputStream(data)), secret, "session-one", index)
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (expected: Exception) { } }
    @Test fun recordsRoundTripIncludingFullChunkAndAcknowledgement() {
        for (size in listOf(0, 1, 420, DirectProtocol.CHUNK)) {
            val bytes = ByteArray(size) { (it * 73).toByte() }
            assertArrayEquals(bytes, decode(encrypted(bytes)))
        }
        assertArrayEquals("verified".toByteArray(), decode(encrypted("verified".toByteArray(), -1), -1))
    }
    @Test fun rejectsTamperWrongKeyAndReorderedRecords() {
        val wire = encrypted(byteArrayOf(1, 2, 3))
        val bad = wire.copyOf(); bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte()
        rejects { decode(bad) }; rejects { decode(wire, 1) }; rejects { decode(wire, secret = ByteArray(32)) }
    }
    @Test fun rejectsOversizedTruncatedAndNegativeLengths() {
        for (length in listOf(-1, 0, Int.MAX_VALUE, DirectProtocol.CHUNK + 17)) {
            val wire = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(length) }.toByteArray()
            rejects { decode(wire) }
        }
        rejects { decode(encrypted(ByteArray(20)).dropLast(1).toByteArray()) }
        rejects { encrypted(ByteArray(DirectProtocol.CHUNK + 1)) }
    }
    @Test fun challengeProofBindsKeyNonceAndSession() {
        val nonce = ByteArray(32) { (it + 1).toByte() }
        val proof = DirectProtocol.proof(key, nonce, "one")
        DirectProtocol.checkProof(proof, DirectProtocol.proof(key, nonce, "one"))
        rejects { DirectProtocol.checkProof(proof, DirectProtocol.proof(key, nonce, "two")) }
        rejects { DirectProtocol.checkProof(proof, DirectProtocol.proof(key, ByteArray(32), "one")) }
    }
    @Test fun inviteRoundTripAndBounds() {
        val invite = DirectInvitation("session-one", key, 9999, "Phone", "file.bin", 420, "0".repeat(64))
        val decoded = DirectInvitation.parse(invite.encode())
        assertEquals(invite.name, decoded.name); assertArrayEquals(key, decoded.key)
        assertEquals(5_000_000_000L, DirectInvitation.parse(invite.copy(size = 5_000_000_000L).encode()).size)
        rejects { DirectInvitation.parse(invite.copy(size = 0).encode()) }
        rejects { DirectInvitation.parse(invite.copy(size = -1).encode()) }
        rejects { DirectInvitation.parse(invite.encode().replace("\"size\":420", "\"size\":1.5")) }
        rejects { DirectInvitation.parse(invite.copy(port = 0).encode()) }
        rejects { DirectInvitation.parse(invite.copy(key = ByteArray(1)).encode()) }
        rejects { DirectInvitation.parse("x".repeat(2049)) }
    }
    @Test fun recordIndicesSupportMoreThanIntRange() {
        val output = ByteArrayOutputStream()
        val index = Int.MAX_VALUE.toLong() + 100
        DirectProtocol.write(DataOutputStream(output), key, "large-file", index, byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), DirectProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())), key, "large-file", index))
        rejects { DirectProtocol.read(DataInputStream(ByteArrayInputStream(output.toByteArray())), key, "large-file", index - 1) }
    }
}
