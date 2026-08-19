package com.example.myapp.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRecordingPolicyTest {
    @Test
    fun exactBoundsMatchConfirmedDurations() {
        assertEquals(16_000, PcmRecordingPolicy.SAMPLE_RATE)
        assertEquals(4_800, PcmRecordingPolicy.MIN_SAMPLES)
        assertEquals(320_000, PcmRecordingPolicy.MAX_SAMPLES)
        assertEquals(20_000L, PcmRecordingPolicy.MAX_DURATION_MILLIS)
    }

    @Test
    fun shortRecordingIsRejected() {
        assertFalse(PcmRecordingPolicy.isLongEnough(4_799))
        assertTrue(PcmRecordingPolicy.isLongEnough(4_800))
    }
}
