package com.example.myapp

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.voice.VoiceTranscript
import com.example.myapp.voice.VoiceTranscriptLines
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTranscriptLinesInstrumentedTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun onlyCorrectedTextIsVisible() {
        rule.setContent {
            VoiceTranscriptLines(
                VoiceTranscript("将绿二强度调到六十", "将Lv2强度调到60")
            )
        }

        rule.onNodeWithTag("voice_raw_transcript").assertDoesNotExist()
        rule.onNodeWithText("将绿二强度调到六十").assertDoesNotExist()
        rule.onNodeWithTag("voice_corrected_transcript")
            .assertTextEquals("将Lv2强度调到60")
    }

    @Test
    fun identicalRawAndCorrectedTextRendersOnce() {
        rule.setContent {
            VoiceTranscriptLines(VoiceTranscript("将Lv2强度调到60", "将Lv2强度调到60"))
        }

        rule.onNodeWithTag("voice_raw_transcript").assertDoesNotExist()
        rule.onNodeWithTag("voice_corrected_transcript")
            .assertExists()
            .assertTextEquals("将Lv2强度调到60")
    }
}
