package com.lumalink.app

import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.zip.CRC32

/** Smaller alphanumeric QR symbols: binary fields, raw payload, Base45 and CRC32. */
internal object CompactOpticalPacket {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"
    fun base45(bytes: ByteArray): String = buildString {
        var i = 0
        while (i < bytes.size) {
            var value = bytes[i++].toInt() and 255
            val pair = i < bytes.size
            if (pair) value = value * 256 + (bytes[i++].toInt() and 255)
            append(ALPHABET[value % 45]); value /= 45
            append(ALPHABET[value % 45])
            if (pair) append(ALPHABET[value / 45])
        }
    }
    fun un45(text: String): ByteArray {
        require(text.length <= 3072 && text.length % 3 != 1)
        val out = ByteArrayOutputStream()
        var i = 0
        fun digit(): Int = ALPHABET.indexOf(text[i++]).also { require(it >= 0) }
        while (i < text.length) {
            val pair = text.length - i >= 3
            var value = digit() + 45 * digit()
            if (pair) { value += 2025 * digit(); require(value <= 65535); out.write(value / 256) }
            else require(value <= 255)
            out.write(value % 256)
        }
        return out.toByteArray()
    }
    fun encode(packet: JSONObject): String {
        val name = packet.getString("n").toByteArray(Charsets.UTF_8)
        val uuid = UUID.fromString(packet.getString("id"))
        val block = packet.getInt("b")
        require(name.size <= 400 && block in 128..1024)
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeByte(3); writeLong(uuid.mostSignificantBits); writeLong(uuid.leastSignificantBits)
            writeInt(packet.getInt("s")); writeShort(block); writeInt(packet.getInt("q"))
            val hash = packet.getString("h")
            require(hash.matches(Regex("[0-9a-f]{64}")))
            write(ByteArray(32) { hash.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
            writeShort(name.size); write(name)
            val payload = Codec.un64(packet.getString("d")); require(payload.size == block); write(payload)
            flush()
        }
        val bytes = body.toByteArray()
        DataOutputStream(body).writeInt(CRC32().apply { update(bytes) }.value.toInt())
        return "LL3:" + base45(body.toByteArray())
    }
    fun decode(raw: String): String {
        require(raw.startsWith("LL3:"))
        val bytes = un45(raw.substring(4)); require(bytes.size >= 65)
        val body = bytes.copyOf(bytes.size - 4)
        val crc = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 4, 4)).readInt()
        require(CRC32().apply { update(body) }.value.toInt() == crc) { "Optical packet checksum mismatch" }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readUnsignedByte() == 3)
        val id = UUID(input.readLong(), input.readLong()).toString()
        val size = input.readInt(); val block = input.readUnsignedShort(); val seq = input.readInt()
        require(size in 1..Codec.MAX_BYTES && block in 128..1024 && seq >= 0)
        val hash = ByteArray(32).also(input::readFully).joinToString("") { "%02x".format(it) }
        val nameSize = input.readUnsignedShort(); require(nameSize <= 400 && input.available() == nameSize + block)
        val name = ByteArray(nameSize).also(input::readFully).toString(Charsets.UTF_8)
        require(name.length <= 100)
        val payload = ByteArray(block).also(input::readFully)
        val packet = JSONObject().put("v", 3).put("id", id).put("s", size).put("b", block)
            .put("q", seq).put("h", hash).put("n", name).put("k", (size + block - 1) / block).put("d", Codec.b64(payload))
        return packet.put("c", Codec.packetHash(packet)).toString()
    }
}
