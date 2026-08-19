package com.example.myapp.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophoneAvailabilityTest {
    @Test
    fun globallyMutedMicrophoneIsBlockedBeforeRecordingStarts() {
        assertEquals(
            "\u7cfb\u7edf\u9ea6\u514b\u98ce\u5df2\u5173\u95ed\uff0c\u8bf7\u5148\u6253\u5f00\u9ea6\u514b\u98ce\u603b\u5f00\u5173",
            systemMicrophoneBlockMessage(isSystemMicrophoneMuted = true)
        )
    }

    @Test
    fun availableSystemMicrophoneDoesNotBlockRecording() {
        assertNull(systemMicrophoneBlockMessage(isSystemMicrophoneMuted = false))
    }
}
