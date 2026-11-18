package com.lumalink.app

/** Observable repeat categories, not an inferred packet-loss percentage. */
internal class QrRepeatStats {
    private var previous: String? = null
    var consecutive = 0L; private set
    var revisited = 0L; private set
    fun record(raw: String, result: Receiver.PacketResult) {
        if (result == Receiver.PacketResult.DUPLICATE) {
            if (raw == previous) consecutive++ else revisited++
        }
        previous = raw
    }
}
