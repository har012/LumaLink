package com.lumalink.app

import java.util.ArrayDeque
import java.util.Locale

/** Rolling recovered-byte rate. Duplicated or redundant symbols add no file bytes. */
internal class ReceiveSpeed {
    private data class Sample(val at: Long, val bytes: Int)
    private val samples = ArrayDeque<Sample>()
    private var firstAt: Long? = null
    fun start(now: Long) {
        if (firstAt == null) { firstAt = now; samples.add(Sample(now, 0)) }
    }
    fun update(now: Long, recoveredBytes: Int, accepted: Int): String {
        if (accepted == 0) return "Receiving: waiting for data"
        start(now)
        samples.add(Sample(now, recoveredBytes))
        while (samples.size > 2 && now - samples.first.at > 2000) samples.removeFirst()
        val first = samples.first
        val elapsed = now - first.at
        if (elapsed < 250) return "Receiving: measuring speed…"
        val rate = (recoveredBytes - first.bytes).coerceAtLeast(0) / elapsed.toDouble()
        // bytes/ms numerically equals decimal KB/s.
        return String.format(Locale.US, "Receiving: %.1f KB/s recovered", rate)
    }
    fun verified(now: Long, size: Int): String {
        val elapsed = firstAt?.let { now - it } ?: return "Verified; transfer too brief to measure"
        if (elapsed < 250) return "Verified; transfer too brief to measure"
        return String.format(Locale.US, "Verified transfer: %.1f KB/s · %.1f s", size / elapsed.toDouble(), elapsed / 1000.0)
    }
}
