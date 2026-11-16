package com.lumalink.app

import java.util.Locale

internal object TransferStatus {
    fun receiving(progress: Int, solved: Int, accepted: Int, frames: Long, decoded: Long, seconds: Double): String {
        val elapsed = seconds.coerceAtLeast(0.001)
        // Progress is an argument, never part of the formatter's template.
        return String.format(Locale.US,
            "%d%% complete · %d blocks · %d accepted\n%.1f camera/s · %.1f QR/s · %.1f accepted/s",
            progress, solved, accepted, frames / elapsed, decoded / elapsed, accepted / elapsed)
    }
}
