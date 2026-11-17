package com.lumalink.app

import org.junit.Assert.*
import org.junit.Test

class ReceiveSpeedTest {
    @Test fun waitingAndShortTransfersDoNotInventRates() {
        val meter = ReceiveSpeed()
        assertTrue(meter.update(1000, 0, 0).contains("waiting"))
        assertTrue(meter.update(2000, 420, 1).contains("measuring"))
        assertTrue(meter.verified(2000, 420).contains("too brief"))
    }
    @Test fun usefulBytesDetermineRateNotPacketCount() {
        val meter = ReceiveSpeed()
        meter.start(1000)
        assertTrue(meter.update(2000, 2000, 100).contains("2.0 KB/s"))
        assertTrue(meter.verified(3000, 2000).contains("1.0 KB/s"))
    }
    @Test fun stalledRecoveryFallsToZeroDespiteRedundantPackets() {
        val meter = ReceiveSpeed()
        meter.update(1000, 420, 1)
        meter.update(2000, 420, 10)
        assertTrue(meter.update(4000, 420, 100).contains("0.0 KB/s"))
    }
    @Test fun frameUpdatesCannotResetTheTransferStart() {
        val meter = ReceiveSpeed()
        meter.start(1000); meter.start(2000)
        assertTrue(meter.verified(3000, 4000).contains("2.0 KB/s"))
    }
}
