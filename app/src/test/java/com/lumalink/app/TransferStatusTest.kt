package com.lumalink.app

import org.junit.Assert.*
import org.junit.Test

class TransferStatusTest {
    @Test fun progressPercentNeverBecomesAFormatConversion() {
        for (progress in listOf(0, 1, 50, 100)) {
            val result = TransferStatus.receiving(progress, 12, 14, 60, 20, 2.0)
            assertTrue(result.startsWith("$progress% complete"))
            assertTrue(result.contains("30.0 camera/s"))
        }
    }
    @Test fun initialStatusHandlesZeroElapsedTime() {
        assertFalse(TransferStatus.receiving(0, 0, 0, 0, 0, 0.0).contains("NaN"))
    }
}
