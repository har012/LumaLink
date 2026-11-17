package com.lumalink.app

import java.io.*
import java.net.Socket

/** Transport-independent encrypted TCP stream, shared by production and loopback tests. */
internal object DirectFileTransfer {
    fun send(client: Socket, invite: DirectInvitation, data: ByteArray, progress: (Int) -> Unit): Boolean {
        client.soTimeout = 15_000
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
        val nonce = DirectProtocol.random(32); output.write(nonce); output.flush()
        val proof = ByteArray(32)
        val authenticated = runCatching {
            input.readFully(proof)
            DirectProtocol.checkProof(DirectProtocol.proof(invite.key, nonce, invite.id), proof)
        }.isSuccess
        if (!authenticated) return false
        client.soTimeout = 30_000
        var offset = 0; var index = 0
        while (offset < data.size) {
            val end = minOf(offset + DirectProtocol.CHUNK, data.size)
            DirectProtocol.write(output, invite.key, invite.id, index++, data.copyOfRange(offset, end))
            offset = end; progress(offset)
        }
        require(DirectProtocol.read(input, invite.key, invite.id, -1).contentEquals("verified".toByteArray())) {
            "Receiver did not verify the file"
        }
        // Let the receiver observe confirmation before either UI removes the P2P group.
        DirectProtocol.write(output, invite.key, invite.id, -2, "complete".toByteArray())
        runCatching { client.shutdownOutput(); input.read() }
        return true
    }
    fun receive(client: Socket, invite: DirectInvitation, progress: (Int) -> Unit): ByteArray {
        client.soTimeout = 30_000
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
        val nonce = ByteArray(32).also(input::readFully)
        output.write(DirectProtocol.proof(invite.key, nonce, invite.id)); output.flush()
        val bytes = ByteArray(invite.size)
        var offset = 0; var index = 0
        while (offset < bytes.size) {
            val chunk = DirectProtocol.read(input, invite.key, invite.id, index++)
            require(chunk.isNotEmpty() && chunk.size <= bytes.size - offset) { "Invalid file length" }
            chunk.copyInto(bytes, offset); offset += chunk.size; progress(offset)
        }
        require(Codec.hash(bytes) == invite.hash) { "File hash verification failed" }
        DirectProtocol.write(output, invite.key, invite.id, -1, "verified".toByteArray())
        require(DirectProtocol.read(input, invite.key, invite.id, -2).contentEquals("complete".toByteArray())) {
            "Sender did not confirm completion"
        }
        return bytes
    }
}
