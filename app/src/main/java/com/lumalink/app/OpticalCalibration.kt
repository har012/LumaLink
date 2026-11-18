package com.lumalink.app

import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.Random

internal object OpticalCalibration {
    val candidates = listOf(192 to 3, 192 to 6, 384 to 6, 768 to 6, 1024 to 6, 768 to 15, 1024 to 15)
    fun packet(run: String, candidate: Int, sequence: Int): String {
        val (payload, _) = candidates[candidate]
        val out = ByteArrayOutputStream(); val id = UUID.fromString(run)
        DataOutputStream(out).apply {
            writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits)
            writeByte(candidate); writeInt(sequence)
            write(ByteArray(payload).also { Random(sequence.toLong() + candidate * 65537L).nextBytes(it) })
        }
        return OpticalV4.wrap("LLCT:", out.toByteArray())
    }
    fun finish(run: String, durations: LongArray, holdEvery: Int): String = OpticalV4.wrap("LLCE:",
        JSONObject().put("run", run).put("durations", org.json.JSONArray(durations.toList()))
            .put("hold", holdEvery).put("policy", 2).toString().toByteArray(Charsets.UTF_8))
    data class Recommendation(val profile: OpticalProfile, val bytesPerSecond: Double) {
        fun encode() = OpticalV4.wrap("LLCR:", JSONObject().put("payload", profile.payload).put("fps", profile.fps)
            .put("hold", profile.holdEvery).put("policy", 2).put("rate", bytesPerSecond).toString().toByteArray(Charsets.UTF_8))
    }
    fun recommendation(raw: String): Recommendation {
        val o = JSONObject(OpticalV4.unwrap(raw, "LLCR:").toString(Charsets.UTF_8))
        require(o.getInt("policy") == 2)
        val rate = o.getDouble("rate"); require(rate.isFinite() && rate > 0 && rate <= 1_000_000)
        return Recommendation(OpticalProfile(o.getInt("payload"), o.getInt("fps"), o.getInt("hold")), rate)
    }
    class Meter {
        var run: String? = null; private set
        private val seen = Array(candidates.size) { hashSetOf<Int>() }
        var count = 0; private set
        fun accept(raw: String): Recommendation? {
            if (raw.startsWith("LLCT:")) {
                val input = DataInputStream(ByteArrayInputStream(OpticalV4.unwrap(raw, "LLCT:")))
                val id = UUID(input.readLong(), input.readLong()).toString()
                val candidate = input.readUnsignedByte(); val sequence = input.readInt()
                require(candidate in candidates.indices && sequence in -1..10_000 && input.available() == candidates[candidate].first)
                if (run == null) run = id
                if (run != id || sequence < 0) return null
                if (seen[candidate].add(sequence)) count++
                return null
            }
            val o = JSONObject(OpticalV4.unwrap(raw, "LLCE:").toString(Charsets.UTF_8))
            require(o.getInt("policy") == 2)
            require(o.getString("run") == run) { "No matching calibration samples" }
            val durations = o.getJSONArray("durations"); require(durations.length() == candidates.size)
            val hold = o.getInt("hold"); require(hold in listOf(0, 1))
            return candidates.indices.mapNotNull { i ->
                val milliseconds = durations.getLong(i); require(milliseconds in 1..180_000)
                if (seen[i].isEmpty()) null else Recommendation(OpticalProfile(candidates[i].first, candidates[i].second, hold), seen[i].size * candidates[i].first * 1000.0 / milliseconds)
            }.maxByOrNull { it.bytesPerSecond } ?: throw IllegalArgumentException("No moving QR packets were decoded")
        }
    }
}
