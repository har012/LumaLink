package com.lumalink.app

import java.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import kotlin.math.min

/** Binary fountain code: systematic symbols followed by random XOR combinations.
 * Experimental peeling decoder; recovery efficiency has not been established.
 */
object Codec {
    const val BLOCK = 420
    const val MAX_BYTES = 10_000_000
    const val MAX_PACKETS = 100_000
    const val MAX_EQUATIONS = 4096
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun indices(k: Int, seq: Int): IntArray {
        if (seq < k) return intArrayOf(seq)
        val rng = Random(seq.toLong() xor 0x4c554d414c494e4bL)
        val degree = when (rng.nextInt(100)) { in 0..44 -> 1; in 45..74 -> 2; in 75..89 -> 3; in 90..96 -> 4; else -> min(8, k) }.coerceAtMost(k)
        val out = HashSet<Int>()
        while (out.size < degree) out.add(rng.nextInt(k))
        return out.sorted().toIntArray()
    }
    fun xor(dst: ByteArray, src: ByteArray) { for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte() }
    fun b64(b: ByteArray): String = Base64.getUrlEncoder().encodeToString(b)
    fun un64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    // Length-prefixed fields avoid ambiguous concatenation. Integrity, not authentication.
    fun packetHash(o: JSONObject): String = hash(listOf("v", "id", "n", "s", "k", "h", "q", "d")
        .joinToString("") { key -> val value = o.get(key).toString(); "${value.length}:$value" }.toByteArray(Charsets.UTF_8))
}
class Sender(val name: String, val bytes: ByteArray) {
    init { require(bytes.isNotEmpty() && bytes.size <= Codec.MAX_BYTES) }
    val id: String = UUID.randomUUID().toString()
    val count = (bytes.size + Codec.BLOCK - 1) / Codec.BLOCK
    val sha = Codec.hash(bytes)
    private val blocks = Array(count) { i -> bytes.copyOfRange(i * Codec.BLOCK, min(bytes.size, (i+1)*Codec.BLOCK)).copyOf(Codec.BLOCK) }
    fun frame(seq: Int): String {
        require(seq >= 0)
        val payload = ByteArray(Codec.BLOCK)
        for (i in Codec.indices(count, seq)) Codec.xor(payload, blocks[i])
        val packet = JSONObject().put("v",2).put("id",id).put("n",name.take(100)).put("s",bytes.size).put("k",count)
            .put("h",sha).put("q",seq).put("d",Codec.b64(payload))
        return packet.put("c", Codec.packetHash(packet)).toString()
    }
}
class Receiver {
    enum class State { WAITING, RECEIVING, COMPLETE, FAILED }
    var state = State.WAITING; private set
    var error: String? = null; private set
    private fun fail(message: String): Boolean { error = message; state = State.FAILED; equations.clear(); return false }
    private var id = ""
    private var name = ""
    private var size = 0
    private var count = 0
    private var sha = ""
    private lateinit var blocks: Array<ByteArray?>
    private val equations = mutableListOf<Pair<MutableSet<Int>,ByteArray>>()
    private val seen = HashSet<Int>()
    var received = 0; private set
    var solved = 0; private set
    private var solvedBytes = 0
    var complete: ByteArray? = null; private set
    val filename get() = name
    val recoveredBytes get() = solvedBytes
    val progress get() = if (count == 0) 0 else solved * 100 / count
    @Synchronized fun accept(raw: String): Boolean {
        if (state == State.FAILED || state == State.COMPLETE || raw.length > 4096) return false
        val o = try { JSONObject(raw) } catch (_: Exception) { return false }
        fun integer(key: String): Boolean = o.opt(key) is Int
        if (!listOf("v", "s", "k", "q").all(::integer)) return false
        if (!listOf("id", "n", "h", "d", "c").all { o.opt(it) is String }) return false
        if (o.optInt("v") != 2) return false
        val fid = o.optString("id")
        val k = o.optInt("k")
        val len = o.optInt("s")
        val seq = o.optInt("q", -1)
        val hash = o.optString("h")
        if (!fid.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) ||
            !hash.matches(Regex("[0-9a-f]{64}")) || o.getString("n").length > 100 ||
            len !in 1..Codec.MAX_BYTES || k != (len+Codec.BLOCK-1)/Codec.BLOCK || seq < 0) return false
        val payload = try {
            if (o.getString("d").length != 560 || Codec.packetHash(o) != o.getString("c")) return false
            Codec.un64(o.getString("d"))
        } catch (_: Exception) { return false }
        if (payload.size != Codec.BLOCK) return false
        if (state != State.WAITING && (id != fid || k != count || len != size || sha != hash || name != safeName(o.getString("n")))) return false
        if (seq in seen) return false
        if (seen.size >= Codec.MAX_PACKETS) return fail("Packet limit reached. Restart reception.")
        if (state == State.WAITING) {
            id = fid; name = o.optString("n").substringAfterLast('/').take(100)
            name = safeName(name)
            size = len; count = k; sha = o.optString("h")
            blocks = arrayOfNulls(k)
            state = State.RECEIVING
        }
        seen.add(seq)
        received++
        val unknown = Codec.indices(count, seq).toMutableSet()
        for (i in unknown.toList()) blocks[i]?.let { Codec.xor(payload,it); unknown.remove(i) }
        if (unknown.isEmpty() && payload.any { it != 0.toByte() }) return fail("Contradictory packet. Restart reception.")
        // Evict an old unresolved equation to bound memory; later symbols can replace it.
        if (unknown.isNotEmpty()) {
            if (equations.size >= Codec.MAX_EQUATIONS) equations.removeAt(0)
            equations.add(unknown to payload)
        }
        var changed = true
        while (changed) {
            changed = false
            val iter = equations.iterator()
            while (iter.hasNext()) {
                val (ids, data) = iter.next()
                for (i in ids.toList()) blocks[i]?.let { Codec.xor(data,it); ids.remove(i) }
                if (ids.isEmpty()) {
                    if (data.any { it != 0.toByte() }) return fail("Contradictory equations. Restart reception.")
                    iter.remove()
                }
                else if (ids.size == 1) {
                    val i = ids.first(); blocks[i] = data.copyOf(); solved++
                    solvedBytes += min(Codec.BLOCK, size - i * Codec.BLOCK)
                    iter.remove(); changed = true
                }
            }
        }
        if (solved == count && complete == null) {
            val output = ByteArray(size)
            for (i in 0 until count) System.arraycopy(blocks[i]!!,0,output,i*Codec.BLOCK,min(Codec.BLOCK,size-i*Codec.BLOCK))
            if (Codec.hash(output) == sha) { complete = output; state = State.COMPLETE; equations.clear(); seen.clear() }
            else return fail("File verification failed. Restart reception.")
        }
        return true
    }
    private fun safeName(value: String) = value.substringAfterLast('/').substringAfterLast('\\')
        .filter { it >= ' ' && it != '\u007f' }.take(100).ifBlank { "received.bin" }
}
