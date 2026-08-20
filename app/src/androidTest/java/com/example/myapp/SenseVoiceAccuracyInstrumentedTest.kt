package com.example.myapp

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapp.voice.AndroidIcuPinyinEncoder
import com.example.myapp.voice.FuzzyTranscriptCorrector
import com.example.myapp.voice.SenseVoiceModelStore
import com.example.myapp.voice.SenseVoiceSpeechEngine
import com.example.myapp.voice.SpeechCorrectionDictionary
import com.example.myapp.voice.WavPcmParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SenseVoiceAccuracyInstrumentedTest {
    @Test
    fun decodesRepresentativeChineseCommandOnce() = decodeFixture("asr/zh_reject_delay700.wav")

    @Test
    fun decodesRepresentativeMixedChineseEnglishCommandOnce() =
        decodeFixture("asr/zh_lv2_strength80.wav")

    private fun decodeFixture(assetPath: String) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = SenseVoiceSpeechEngine(SenseVoiceModelStore(context))
        val corrector = FuzzyTranscriptCorrector(
            dictionary = SpeechCorrectionDictionary.lexemes,
            pinyinEncoder = AndroidIcuPinyinEncoder()
        )
        val partialResults = mutableListOf<String>()
        try {
            engine.prepare()
            engine.startSession(partialResults::add)
            engine.acceptSamples(load16kMonoPcm(assetPath))

            val startedAt = SystemClock.elapsedRealtime()
            val finalResults = mutableListOf(corrector.correct(engine.finishSession()))
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val result = finalResults.single()

            Log.i(
                "SenseVoiceAccuracy",
                "raw=${result.rawText} corrected=${result.correctedText} " +
                    "ambiguous=${result.ambiguous} elapsedMs=$elapsedMs"
            )
            assertTrue(result.rawText.isNotBlank())
            assertTrue(result.correctedText.isNotBlank())
            assertFalse(result.ambiguous)
            assertEquals(1, finalResults.size)
            assertThrows(IllegalStateException::class.java) { runBlocking { engine.finishSession() } }
            assertEquals(1, finalResults.size)
            assertTrue(partialResults.isEmpty())
            assertTrue(elapsedMs >= 0)
        } finally {
            engine.close()
        }
    }

    private fun load16kMonoPcm(assetPath: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(assetPath)
            .use { it.readBytes() }
        return WavPcmParser.parse16kMonoPcm(bytes)
    }

}
