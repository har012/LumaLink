package com.lumalink.app

import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.zip.CRC32

/** Manifests repeat periodically; data symbols carry only identity, sequence and payload. */
internal object OpticalV4 {
    fun wrap(prefix: String, bytes: ByteArray): String {
        val output = ByteArrayOutputStream(); output.write(bytes)
        DataOutputStream(output).writeInt(CRC32().apply { update(bytes) }.value.toInt())
        return prefix + CompactOpticalPacket.base45(output.toByteArray())
    }
    fun unwrap(raw: String, prefix: String): ByteArray {
        require(raw.startsWith(prefix) && raw.length <= 4096)
        val bytes = CompactOpticalPacket.un45(raw.substring(prefix.length)); require(bytes.size >= 4)
        val body = bytes.copyOf(bytes.size - 4)
        require(CRC32().apply { update(body) }.value.toInt() == DataInputStream(ByteArrayInputStream(bytes, bytes.size - 4, 4)).readInt())
        return body
    }
    fun sessionId(name: String, hash: String, block: Int) = UUID.nameUUIDFromBytes("LL4/${name.take(100)}/$hash/$block".toByteArray(Charsets.UTF_8)).toString()
    fun manifest(sender: Sender): String = wrap("LL4M:", JSONObject().put("id", sender.id)
        .put("n", sender.name.take(100)).put("s", sender.bytes.size).put("b", sender.blockSize).put("h", sender.sha).toString().toByteArray(Charsets.UTF_8))
    fun data(sender: Sender, sequence: Int): String {
        val output = ByteArrayOutputStream()
        val id = UUID.fromString(sender.id)
        DataOutputStream(output).apply {
            writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits); writeInt(sequence)
            write(Codec.un64(sender.packet(sequence).getString("d")))
        }
        return wrap("LL4D:", output.toByteArray())
    }
    class Decoder {
        private val manifests = linkedMapOf<String, JSONObject>()
        var waitingForManifest = false; private set
        var lastManifest: String? = null; private set
        fun normalize(raw: String): String? {
            if (raw.startsWith("LL4M:")) {
                val o = JSONObject(unwrap(raw, "LL4M:").toString(Charsets.UTF_8))
                require(listOf("s", "b").all { o.opt(it) is Int } && listOf("id", "n", "h").all { o.opt(it) is String })
                val id = o.getString("id"); UUID.fromString(id)
                val size = o.getInt("s"); val block = o.getInt("b")
                require(size in 1..Codec.MAX_BYTES && block in 128..1024 && o.getString("n").length <= 100)
                require(o.getString("h").matches(Regex("[0-9a-f]{64}")))
                require(sessionId(o.getString("n"), o.getString("h"), block) == id)
                if (manifests.size >= 4 && id !in manifests) manifests.remove(manifests.keys.first())
                manifests[id] = o; lastManifest = raw; waitingForManifest = false
                return null
            }
            val input = DataInputStream(ByteArrayInputStream(unwrap(raw, "LL4D:")))
            val id = UUID(input.readLong(), input.readLong()).toString(); val seq = input.readInt()
            require(seq >= 0)
            val manifest = manifests[id] ?: run { waitingForManifest = true; return null }
            val block = manifest.getInt("b"); val size = manifest.getInt("s")
            require(input.available() == block)
            val payload = ByteArray(block).also(input::readFully)
            val o = JSONObject(manifest.toString()).put("v", 3).put("q", seq)
                .put("k", (size + block - 1) / block).put("d", Codec.b64(payload))
            waitingForManifest = false
            return o.put("c", Codec.packetHash(o)).toString()
        }
    }
}
