package com.example.myapp

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.voice.VoiceTranscript
import com.example.myapp.voice.VoiceTranscriptLines
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTranscriptLinesInstrumentedTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rawTextIsAboveCorrectedTextWithoutVisibleLabels() {
        rule.setContent {
            VoiceTranscriptLines(
                VoiceTranscript("将绿二强度调到六十", "将Lv2强度调到60")
            )
        }

        val raw = rule.onNodeWithTag("voice_raw_transcript")
        val corrected = rule.onNodeWithTag("voice_corrected_transcript")
        raw.assertTextEquals("将绿二强度调到六十")
        corrected.assertTextEquals("将Lv2强度调到60")
        assertTrue(
            raw.fetchSemanticsNode().boundsInRoot.top <
                corrected.fetchSemanticsNode().boundsInRoot.top
        )
        rule.onNodeWithText("原始", substring = true).assertDoesNotExist()
        rule.onNodeWithText("纠正", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Raw", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Corrected", substring = true).assertDoesNotExist()
    }

    @Test
    fun identicalRawAndCorrectedTextStillRenderAsTwoLines() {
        rule.setContent {
            VoiceTranscriptLines(VoiceTranscript("将Lv2强度调到60", "将Lv2强度调到60"))
        }

        rule.onNodeWithTag("voice_raw_transcript")
            .assertExists()
            .assertTextEquals("将Lv2强度调到60")
        rule.onNodeWithTag("voice_corrected_transcript")
            .assertExists()
            .assertTextEquals("将Lv2强度调到60")
    }
}
