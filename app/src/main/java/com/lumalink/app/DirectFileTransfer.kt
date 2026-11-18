package com.lumalink.app

import org.json.JSONObject
import java.io.*
import java.net.Socket
import java.security.MessageDigest

/** v2 mutual authentication, directional session keys, reusable ordered stream. */
internal class DirectFileTransfer private constructor(private val input: DataInputStream,
    private val output: DataOutputStream, private val sendKey: ByteArray, private val receiveKey: ByteArray,
    private val id: String, val peer: DirectIdentity, private val socket: Socket) {
    private var sent = 1L
    private var received = 1L
    fun write(bytes: ByteArray) { DirectProtocol.write(output, sendKey, id, sent++, bytes) }
    fun read(): ByteArray = DirectProtocol.read(input, receiveKey, id, received++)
    fun control(o: JSONObject) = write(o.toString().toByteArray())
    fun control(): JSONObject = JSONObject(String(read(), Charsets.UTF_8))

    fun send(source: DirectSource, progress: (Long) -> Unit) {
        control(source.offer.json())
        socket.soTimeout = 180_000 // Allow time to approve the incoming file.
        val answer = try { control() } finally { socket.soTimeout = 30_000 }
        require(answer.getString("id") == source.offer.id) { "Wrong file acknowledgement" }
        if (answer.getString("type") == "saved") return
        if (answer.getString("type") != "accept") throw DirectDeclined(answer.optString("reason", "Receiver declined the file"))
        var count = 0L
        progress(0)
        source.file.inputStream().buffered().use { file ->
            val buffer = ByteArray(DirectProtocol.CHUNK)
            while (count < source.offer.size) {
                val wanted = minOf(buffer.size.toLong(), source.offer.size - count).toInt()
                file.readNBytesExact(buffer, wanted)
                write(if (wanted == buffer.size) buffer else buffer.copyOf(wanted))
                count += wanted; progress(count)
            }
        }
        var receipt = control()
        while (receipt.optString("type") == "saving" && receipt.optString("id") == source.offer.id) receipt = control()
        require(receipt.getString("type") == "saved" && receipt.getString("id") == source.offer.id) { "Receiver did not save the verified file" }
    }

    /** Only verified files reach save. Retain a receipt before ACK to prevent retry duplicates. */
    fun receive(offer: DirectOffer, directory: File, receipts: MutableMap<String, DirectOffer>,
        accept: (DirectOffer) -> Boolean, save: (File, String) -> String, progress: (Long) -> Unit): String? {
        val previous = receipts[offer.id]
        if (previous != null) {
            require(previous == offer) { "Transfer ID reused for another file" }
            control(JSONObject().put("type", "saved").put("id", offer.id)); return null
        }
        if (!accept(offer)) {
            control(JSONObject().put("type", "decline").put("id", offer.id).put("reason", "Receiver declined the file")); return null
        }
        require(directory.usableSpace > offer.size && directory.usableSpace - offer.size > 1_048_576) { "Not enough temporary storage" }
        val temporary = File.createTempFile("direct-in-", ".part", directory)
        try {
            control(JSONObject().put("type", "accept").put("id", offer.id))
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            progress(0)
            temporary.outputStream().buffered().use { file ->
                while (count < offer.size) {
                    val bytes = read()
                    require(bytes.isNotEmpty() && bytes.size <= offer.size - count) { "Invalid file length" }
                    file.write(bytes); digest.update(bytes); count += bytes.size; progress(count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == offer.hash) { "File hash verification failed" }
            val path = save(temporary, offer.name)
            receipts[offer.id] = offer
            while (receipts.size > 128) receipts.remove(receipts.keys.first())
            control(JSONObject().put("type", "saved").put("id", offer.id))
            return path
        } finally { temporary.delete() }
    }

    companion object {
        private fun InputStream.readNBytesExact(buffer: ByteArray, count: Int) {
            var offset = 0
            while (offset < count) {
                val n = read(buffer, offset, count - offset)
                if (n < 0) throw EOFException("Source file changed")
                offset += n
            }
        }
        fun open(socket: Socket, invitation: DirectInvitation, identity: DirectIdentity,
            server: Boolean, expectedPeer: String? = null): DirectFileTransfer {
            socket.soTimeout = 30_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val local = DirectProtocol.random(32)
            val remote = ByteArray(32)
            val challenge: ByteArray
            if (server) {
                output.writeInt(0x4c4c4432); output.write(local); output.flush()
                input.readFully(remote); challenge = local + remote
                val actual = ByteArray(32).also(input::readFully)
                DirectProtocol.checkProof(DirectProtocol.proof(invitation.key, challenge, invitation.id + "/client"), actual)
                output.write(DirectProtocol.proof(invitation.key, challenge, invitation.id + "/server")); output.flush()
            } else {
                require(input.readInt() == 0x4c4c4432) { "Update LumaLink on both phones" }
                input.readFully(remote); challenge = remote + local
                output.write(local); output.write(DirectProtocol.proof(invitation.key, challenge, invitation.id + "/client")); output.flush()
                val actual = ByteArray(32).also(input::readFully)
                DirectProtocol.checkProof(DirectProtocol.proof(invitation.key, challenge, invitation.id + "/server"), actual)
            }
            val serverKey = DirectProtocol.proof(invitation.key, challenge, invitation.id + "/server-records")
            val clientKey = DirectProtocol.proof(invitation.key, challenge, invitation.id + "/client-records")
            val sendKey = if (server) serverKey else clientKey
            val receiveKey = if (server) clientKey else serverKey
            DirectProtocol.write(output, sendKey, invitation.id, 0, identity.json().toString().toByteArray())
            val peer = DirectIdentity.parse(JSONObject(String(DirectProtocol.read(input, receiveKey, invitation.id, 0))))
            require(expectedPeer == null || peer.id == expectedPeer) { "This is a different saved device" }
            return DirectFileTransfer(input, output, sendKey, receiveKey, invitation.id, peer, socket)
        }
    }
}

internal class DirectDeclined(message: String) : IOException(message)
