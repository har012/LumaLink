package com.lumalink.app

import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID

internal data class DirectIdentity(val id: String, val name: String) {
    fun json() = JSONObject().put("id", id).put("name", name)
    companion object {
        fun parse(o: JSONObject): DirectIdentity {
            val id = o.getString("id"); val name = o.getString("name")
            require(id.matches(Regex("[a-zA-Z0-9-]{8,64}")) && name.length in 1..100)
            return DirectIdentity(id, name)
        }
    }
}

internal data class DirectOffer(val id: String, val name: String, val size: Long, val hash: String) {
    fun json() = JSONObject().put("type", "offer").put("id", id).put("name", name).put("size", size).put("hash", hash)
    companion object {
        fun positiveLong(o: JSONObject, field: String): Long {
            val value = o.get(field)
            require(value is Int || value is Long) { "Invalid integer size" }
            return (value as Number).toLong().also { require(it > 0) }
        }
        fun parse(o: JSONObject): DirectOffer {
            require(o.getString("type") == "offer")
            val offer = DirectOffer(o.getString("id"), o.getString("name"), positiveLong(o, "size"), o.getString("hash"))
            require(offer.id.matches(Regex("[a-zA-Z0-9-]{8,64}")) && offer.name.length in 1..100 && offer.hash.matches(Regex("[0-9a-f]{64}")))
            return offer
        }
    }
}

/** Stable disk snapshot for retries. No whole-file arrays and no optical size cap. */
internal data class DirectSource(val file: File, val offer: DirectOffer) : AutoCloseable {
    override fun close() { file.delete() }
    companion object {
        fun prepare(input: InputStream, name: String, directory: File): DirectSource {
            val file = File.createTempFile("direct-out-", ".part", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                file.outputStream().buffered().use { out ->
                    val buffer = ByteArray(DirectProtocol.CHUNK)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Preparation cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size = Math.addExact(size, count.toLong())
                        digest.update(buffer, 0, count); out.write(buffer, 0, count)
                    }
                }
                require(size > 0) { "Empty files are not supported" }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                return DirectSource(file, DirectOffer(UUID.randomUUID().toString(), name.take(100).ifBlank { "file.bin" }, size, hash))
            } catch (e: Exception) { file.delete(); throw e }
        }
    }
}

internal class DirectRetryBudget(private val automaticRetries: Int = 2) {
    private var failures = 0
    fun failed(): Boolean = failures++ < automaticRetries
    fun reset() { failures = 0 }
}
