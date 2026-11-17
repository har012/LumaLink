package com.lumalink.app

import org.junit.Assert.*
import org.junit.Test

class RecoveredBytesTest {
    @Test fun partialFinalBlockNeverCountsZeroPaddingOrDuplicates() {
        val sender = Sender("partial.bin", ByteArray(421))
        val receiver = Receiver()
        assertEquals(0, receiver.recoveredBytes)
        receiver.accept(sender.frame(1))
        assertEquals(1, receiver.recoveredBytes)
        receiver.accept(sender.frame(1))
        assertEquals(1, receiver.recoveredBytes)
        receiver.accept(sender.frame(0))
        assertEquals(421, receiver.recoveredBytes)
    }
}
