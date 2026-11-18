package com.lumalink.app

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.*

class DirectFileTransferTest {
    private val senderIdentity = DirectIdentity("sender-device", "Sender")
    private val receiverIdentity = DirectIdentity("receiver-device", "Receiver")
    private fun invitation(port: Int, size: Long = 1) = DirectInvitation("test-session", DirectProtocol.random(32), port,
        "Sender", "file.bin", size, "0".repeat(64))
    private fun source(directory: File, size: Long): DirectSource = DirectSource.prepare(object : InputStream() {
        var offset = 0L
        override fun read(): Int = if (offset == size) -1 else ((offset++ * 37) and 255).toInt()
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            assertTrue("Preparation must use bounded buffers", length <= DirectProtocol.CHUNK)
            if (offset == size) return -1
            val count = minOf(length.toLong(), size - offset).toInt()
            repeat(count) { buffer[start + it] = ((offset++ * 37) and 255).toByte() }
            return count
        }
    }, "test.bin", directory)

    @Test fun streamsAboveTenMbAndMultipleFilesOnSameConnection() {
        val directory = Files.createTempDirectory("direct-test").toFile()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val sources = listOf(source(directory, 12_000_123), source(directory, 32769), source(directory, 1))
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                val invite = invitation(listener.localPort)
                val sent = executor.submit<Boolean> {
                    listener.accept().use { socket ->
                        val stream = DirectFileTransfer.open(socket, invite, senderIdentity, true, receiverIdentity.id)
                        sources.forEach { source -> stream.send(source) {} }
                        true
                    }
                }
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                    val stream = DirectFileTransfer.open(socket, invite, receiverIdentity, false, senderIdentity.id)
                    val receipts = linkedMapOf<String, DirectOffer>()
                    sources.forEach { expected ->
                        val offer = DirectOffer.parse(stream.control())
                        var count = 0L
                        val path = stream.receive(offer, directory, receipts, { true }, { file, _ ->
                            assertEquals(expected.offer.size, file.length())
                            expected.file.inputStream().use { a -> file.inputStream().use { b ->
                                val left = ByteArray(8192); val right = ByteArray(8192)
                                while (true) {
                                    val n = a.read(left); val m = b.read(right)
                                    assertEquals(n, m); if (n < 0) break
                                    assertArrayEquals(left.copyOf(n), right.copyOf(m))
                                }
                            } }
                            "Download/LumaLink/test.bin"
                        }) { count = it }
                        assertEquals(expected.offer.size, count)
                        assertNotNull(path)
                    }
                }
                assertTrue(sent.get(30, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun interruptedTransferRestartsAndLostSaveAckDoesNotSaveTwice() {
        val directory = Files.createTempDirectory("direct-retry").toFile()
        val executor = Executors.newSingleThreadExecutor()
        try {
            source(directory, 200_000).use { source ->
                val receipts = linkedMapOf<String, DirectOffer>()
                val budget = DirectRetryBudget()
                var saves = 0
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                    val invite = invitation(listener.localPort)
                    val sent = executor.submit<Int> {
                        var attempts = 0
                        while (true) {
                            attempts++
                            try {
                                listener.accept().use { socket ->
                                    DirectFileTransfer.open(socket, invite, senderIdentity, true).send(source) {}
                                }
                                return@submit attempts
                            } catch (e: Exception) { if (!budget.failed()) throw e }
                        }
                        @Suppress("UNREACHABLE_CODE") 0
                    }
                    repeat(3) { attempt ->
                        Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                            val stream = DirectFileTransfer.open(socket, invite, receiverIdentity, false)
                            val offer = DirectOffer.parse(stream.control())
                            val result = runCatching {
                                stream.receive(offer, directory, receipts, { true }, { _, _ ->
                                    saves++
                                    if (attempt == 1) socket.close() // Saved, then final ACK lost.
                                    "saved"
                                }) { if (attempt == 0) socket.close() } // Interrupted mid-file.
                            }
                            if (attempt < 2) assertTrue(result.isFailure) else assertTrue(result.isSuccess)
                        }
                    }
                    assertEquals(3, sent.get(10, TimeUnit.SECONDS))
                    assertEquals(1, saves)
                }
            }
            assertFalse(directory.listFiles()!!.any { it.name.startsWith("direct-in-") })
        } finally { executor.shutdownNow(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun badHashNeverSavesOrAcknowledges() {
        val directory = Files.createTempDirectory("direct-hash").toFile()
        val executor = Executors.newSingleThreadExecutor()
        try {
            source(directory, 400).use { original ->
                val source = original.copy(offer = original.offer.copy(hash = "0".repeat(64)))
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                    val invite = invitation(listener.localPort)
                    val sent = executor.submit<Boolean> { listener.accept().use { socket ->
                        runCatching { DirectFileTransfer.open(socket, invite, senderIdentity, true).send(source) {} }.isFailure
                    } }
                    Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                        val stream = DirectFileTransfer.open(socket, invite, receiverIdentity, false)
                        val offer = DirectOffer.parse(stream.control())
                        val failure = runCatching { stream.receive(offer, directory, linkedMapOf(), { true }, { _, _ -> error("Must not save") }) {} }.exceptionOrNull()
                        assertTrue(failure is IllegalArgumentException)
                        assertTrue(failure!!.message!!.contains("hash"))
                    }
                    assertTrue(sent.get(5, TimeUnit.SECONDS))
                }
            }
        } finally { executor.shutdownNow(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun unauthenticatedClientGetsNoFileRecords() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                val invite = invitation(listener.localPort)
                val sent = executor.submit<Boolean> { listener.accept().use { socket ->
                    runCatching { DirectFileTransfer.open(socket, invite, senderIdentity, true) }.isFailure
                } }
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                    socket.soTimeout = 5000
                    val input = DataInputStream(socket.getInputStream())
                    assertEquals(0x4c4c4432, input.readInt()); input.readFully(ByteArray(32))
                    socket.getOutputStream().write(ByteArray(64)); socket.getOutputStream().flush()
                    assertEquals(-1, input.read())
                }
                assertTrue(sent.get(5, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun retryBudgetAllowsOnlyTwoAutomaticRetriesAndCanBeResetByUser() {
        val budget = DirectRetryBudget()
        assertTrue(budget.failed()); assertTrue(budget.failed()); assertFalse(budget.failed())
        budget.reset(); assertTrue(budget.failed())
    }

    @Test fun savedDeviceIdentityMismatchIsRejectedDespiteValidSecret() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                val invite = invitation(listener.localPort)
                val sent = executor.submit<Boolean> { listener.accept().use { socket ->
                    runCatching { DirectFileTransfer.open(socket, invite, senderIdentity, true, "different-device") }.isFailure
                } }
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                    val stream = DirectFileTransfer.open(socket, invite, receiverIdentity, false)
                    assertTrue(runCatching { stream.control() }.isFailure)
                }
                assertTrue(sent.get(5, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun preparationFailureRemovesPartialSnapshot() {
        val directory = Files.createTempDirectory("direct-prepare-failure").toFile()
        try {
            val result = runCatching { DirectSource.prepare(object : InputStream() {
                override fun read(): Int = throw IOException("Provider lost access")
            }, "file.bin", directory) }
            assertTrue(result.isFailure)
            assertEquals(0, directory.listFiles()!!.size)
        } finally { directory.delete() }
    }

    @Test fun declinedOfferDoesNotBreakTheNextFileOnTheConnection() {
        val directory = Files.createTempDirectory("direct-decline").toFile()
        val executor = Executors.newSingleThreadExecutor()
        try {
            source(directory, 33).use { source ->
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                    val invite = invitation(listener.localPort)
                    val sent = executor.submit<Boolean> { listener.accept().use { socket ->
                        val stream = DirectFileTransfer.open(socket, invite, senderIdentity, true)
                        assertTrue(runCatching { stream.send(source) {} }.exceptionOrNull() is DirectDeclined)
                        stream.send(source) {}; true
                    } }
                    Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                        val stream = DirectFileTransfer.open(socket, invite, receiverIdentity, false)
                        val receipts = linkedMapOf<String, DirectOffer>()
                        assertNull(stream.receive(DirectOffer.parse(stream.control()), directory, receipts, { false }, { _, _ -> error("Must not save") }) {})
                        assertEquals("saved", stream.receive(DirectOffer.parse(stream.control()), directory, receipts, { true }, { _, _ -> "saved" }) {})
                    }
                    assertTrue(sent.get(5, TimeUnit.SECONDS))
                }
            }
        } finally { executor.shutdownNow(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun metadataSupportsLongSizesAndRejectsMalformedOffers() {
        val offer = DirectOffer("file-session", "huge.bin", 5_000_000_000L, "0".repeat(64))
        assertEquals(offer, DirectOffer.parse(offer.json()))
        assertTrue(runCatching { DirectOffer.parse(offer.json().put("size", -1)) }.isFailure)
        assertTrue(runCatching { DirectOffer.parse(offer.json().put("size", "500")) }.isFailure)
        assertTrue(runCatching { DirectOffer.parse(offer.json().put("size", 1.5)) }.isFailure)
        assertTrue(runCatching { DirectOffer.parse(offer.json().put("hash", "wrong")) }.isFailure)
    }
}
