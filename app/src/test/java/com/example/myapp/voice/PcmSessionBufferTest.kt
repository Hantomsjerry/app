package com.example.myapp.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmSessionBufferTest {
    @Test
    fun chunksUseOneFixedBackingArrayAndTakeDetachesItFromTheSessionBuffer() {
        val buffer = PcmSessionBuffer(capacity = 8)

        assertEquals(3, buffer.append(shortArrayOf(1, 2, 3)))
        assertEquals(2, buffer.append(shortArrayOf(4, 5)))

        val payload = requireNotNull(buffer.take())
        assertEquals(8, payload.samples.size)
        assertEquals(5, payload.sampleCount)
        assertEquals(listOf<Short>(1, 2, 3, 4, 5), payload.samples.take(5))
        assertNull(buffer.take())
        assertEquals(0, buffer.append(shortArrayOf(6)))
    }

    @Test
    fun capacityBoundsWritesAndReleaseDropsTheBackingArray() {
        val buffer = PcmSessionBuffer(capacity = 3)

        assertEquals(3, buffer.append(shortArrayOf(7, 8, 9, 10)))
        buffer.release()

        assertEquals(0, buffer.append(shortArrayOf(11)))
        assertNull(buffer.take())
    }
}
