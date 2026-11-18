package com.lumalink.app

import java.util.Locale

internal data class OpticalProfile(val payload: Int = 192, val fps: Int = 6,
    val holdEvery: Int = 0, val holdMillis: Int = 500) {
    init { require(payload in listOf(192, 384, 768, 1024) && fps in listOf(3, 6, 15, 30, 60) && holdEvery in listOf(0, 1) && holdMillis in 0..2000) }
    val intervalNanos get() = 1_000_000_000L / fps
    fun dwellNanos(sequence: Int, extraHold: Boolean): Long =
        if (extraHold && holdEvery == 1 && sequence >= 0)
            maxOf(intervalNanos, holdMillis * 1_000_000L) else intervalNanos
    fun estimate(size: Int): String {
        val blocks = (size + payload - 1L) / payload
        val slots = if (holdEvery == 0) blocks else ((blocks + 3) / 4) * 16
        val anchors = if (holdEvery == 0) 0 else ((blocks + 3) / 4) * 4
        val dataSeconds = slots * intervalNanos / 1e9 + anchors * (holdMillis / 1000.0 - intervalNanos / 1e9).coerceAtLeast(0.0)
        // Periodic manifests also occupy display slots. No losses/repeats are assumed here.
        val seconds = dataSeconds + ((slots + 15) / 16) * maxOf(intervalNanos / 1e9, .35) + .65
        return String.format(Locale.US, "First-pass display estimate (no loss): %.1f min. Repairs may add time.", seconds / 60)
    }
}

/** Deadlines start at actual presentation, so encoder stalls cannot trigger catch-up bursts. */
internal class OpticalPacer(private val profile: OpticalProfile, private val random: java.util.Random = java.util.Random()) {
    private var nextAt = Long.MIN_VALUE
    private data class Visit(var count: Int, val target: Int)
    private val visits = HashMap<Int, Visit>()
    fun dwellFor(sequence: Int): Long {
        if (profile.holdEvery == 0 || sequence < 0) return profile.intervalNanos
        val visit = visits[sequence] ?: run {
            if (visits.size >= Codec.MAX_PACKETS) return profile.intervalNanos
            Visit(0, 2 + random.nextInt(3)).also { visits[sequence] = it }
        }
        visit.count++
        return profile.dwellNanos(sequence, visit.count == visit.target)
    }
    fun ready(now: Long) = now >= nextAt
    fun presented(now: Long, sequence: Int, manifest: Boolean = false, acquisition: Boolean = false) {
        val dwell = if (manifest) maxOf(profile.intervalNanos, 350_000_000L)
            else dwellFor(sequence)
        nextAt = now + if (acquisition) maxOf(dwell, 1_000_000_000L) else dwell
    }
}

/** Four interleaved appearances ensure a randomly selected 2nd–4th hold can occur. */
internal object RandomHoldSchedule {
    fun sequence(emission: Long): Int {
        require(emission >= 0)
        return ((emission / 16) * 4 + emission % 4).also { require(it <= Int.MAX_VALUE) }.toInt()
    }
}
