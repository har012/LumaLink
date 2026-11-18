package com.lumalink.app

import java.util.Locale

internal data class OpticalProfile(val payload: Int = 192, val fps: Int = 6,
    val holdEvery: Int = 10, val holdMillis: Int = 500) {
    init { require(payload in listOf(192, 384, 768, 1024) && fps in listOf(3, 6, 15, 30, 60) && holdEvery in listOf(0, 1, 10) && holdMillis in 0..2000) }
    val intervalNanos get() = 1_000_000_000L / fps
    fun dwellNanos(sequence: Int, firstExtraHold: Boolean): Long =
        if (firstExtraHold && holdEvery > 0 && sequence >= 0 && sequence % holdEvery == 0)
            maxOf(intervalNanos, holdMillis * 1_000_000L) else intervalNanos
    fun estimate(size: Int): String {
        val blocks = (size + payload - 1L) / payload
        val anchors = if (holdEvery == 0) 0 else (blocks + holdEvery - 1) / holdEvery
        val dataSeconds = blocks * intervalNanos / 1e9 + anchors * (holdMillis / 1000.0 - intervalNanos / 1e9).coerceAtLeast(0.0)
        // Periodic manifests also occupy display slots. No losses/repeats are assumed here.
        val seconds = dataSeconds + ((blocks + 15) / 16) * maxOf(intervalNanos / 1e9, .35) + .65
        return String.format(Locale.US, "First-pass display estimate (no loss): %.1f min. Repairs may add time.", seconds / 60)
    }
}

/** Deadlines start at actual presentation, so encoder stalls cannot trigger catch-up bursts. */
internal class OpticalPacer(private val profile: OpticalProfile) {
    private var nextAt = Long.MIN_VALUE
    private val extraHeld = HashSet<Int>()
    fun ready(now: Long) = now >= nextAt
    fun presented(now: Long, sequence: Int, manifest: Boolean = false, acquisition: Boolean = false) {
        val dwell = if (manifest) maxOf(profile.intervalNanos, 350_000_000L)
            else profile.dwellNanos(sequence, profile.holdEvery > 0 && sequence % profile.holdEvery == 0 && extraHeld.size < Codec.MAX_PACKETS && extraHeld.add(sequence))
        nextAt = now + if (acquisition) maxOf(dwell, 1_000_000_000L) else dwell
    }
}
