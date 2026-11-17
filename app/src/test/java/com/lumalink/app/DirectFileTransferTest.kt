package com.lumalink.app

import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.*

class DirectFileTransferTest {
    @Test fun unauthenticatedClientReceivesNoFileRecords() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                listener.soTimeout = 5000
                val invite = DirectInvitation("test-session", DirectProtocol.random(32), listener.localPort, "Test", "test.bin", 1, Codec.hash(byteArrayOf(1)))
                val sender = executor.submit<Boolean> {
                    listener.accept().use { DirectFileTransfer.send(it, invite, byteArrayOf(1)) {} }
                }
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { client ->
                    client.soTimeout = 5000
                    val input = java.io.DataInputStream(client.getInputStream())
                    input.readFully(ByteArray(32))
                    client.getOutputStream().write(ByteArray(32)); client.getOutputStream().flush()
                    assertEquals(-1, input.read())
                }
                assertFalse(sender.get(5, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun encryptedLoopbackRoundTripsExactBytesAndAcknowledgesVerification() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (size in listOf(1, 32767, 32768, 32769, 2_000_000)) {
                val bytes = ByteArray(size) { (it * 37).toByte() }
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                    listener.soTimeout = 5000
                    val invite = DirectInvitation("test-session", DirectProtocol.random(32), listener.localPort, "Test", "test.bin", size, Codec.hash(bytes))
                    val sender = executor.submit<Boolean> {
                        listener.accept().use { DirectFileTransfer.send(it, invite, bytes) {} }
                    }
                    Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { client ->
                        var progress = 0
                        assertArrayEquals(bytes, DirectFileTransfer.receive(client, invite) { progress = it })
                        assertEquals(size, progress)
                    }
                    assertTrue(sender.get(10, TimeUnit.SECONDS))
                }
            }
        } finally { executor.shutdownNow() }
    }
    @Test fun incorrectFileHashNeverReceivesVerifiedAcknowledgement() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                listener.soTimeout = 5000
                val invite = DirectInvitation("test-session", DirectProtocol.random(32), listener.localPort, "Test", "test.bin", 1, "0".repeat(64))
                val sender = executor.submit<Boolean> {
                    listener.accept().use { runCatching { DirectFileTransfer.send(it, invite, byteArrayOf(1)) {} }.isFailure }
                }
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { client ->
                    try { DirectFileTransfer.receive(client, invite) {}; fail("Expected hash failure") }
                    catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("hash")) }
                }
                assertTrue(sender.get(5, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }
}
