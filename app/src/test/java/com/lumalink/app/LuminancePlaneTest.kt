package com.lumalink.app

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LuminancePlaneTest {
    @Test fun cropStrideBufferOffsetAndAllRotations() {
        val buffer = ByteBuffer.allocate(50)
        buffer.position(3)
        // Crop x=1,y=1, 3x2 inside a plane with row padding and pixel stride 2.
        for (y in 0..1) for (x in 0..2) buffer.put(3 + (y+1)*12 + (x+1)*2, (y*3+x+1).toByte())
        val fixtures = mapOf(
            0 to byteArrayOf(1,2,3,4,5,6),
            90 to byteArrayOf(4,1,5,2,6,3),
            180 to byteArrayOf(6,5,4,3,2,1),
            270 to byteArrayOf(3,6,2,5,1,4))
        val output = ByteArray(6)
        for ((rotation, expected) in fixtures) {
            LuminancePlane.copy(buffer, 12, 2, 1, 1, 3, 2, rotation, output)
            assertArrayEquals(expected, output)
            assertEquals(3, buffer.position())
        }
    }
}
