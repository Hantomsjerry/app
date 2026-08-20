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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SenseVoiceAccuracyInstrumentedTest {
    @Test
    fun decodesRepresentativeChineseCommandOnce() = decodeFixture("asr/zh_lv2_strength80.wav")

    @Test
    fun decodesRepresentativeMixedChineseEnglishCommandOnce() =
        decodeFixture("asr/en_machine2_sensitivity32.wav")

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
            val finalResults = listOf(corrector.correct(engine.finishSession()))
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
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", buffer.readFourCc())
        assertEquals("WAVE", buffer.readFourCc())

        var pcm: ByteArray? = null
        while (buffer.remaining() >= CHUNK_HEADER_BYTES) {
            val chunkId = buffer.readFourCc()
            val chunkSize = buffer.int
            assertTrue("Invalid $chunkId chunk length", chunkSize >= 0 && chunkSize <= buffer.remaining())
            val chunkEnd = buffer.position() + chunkSize
            when (chunkId) {
                "fmt " -> {
                    assertTrue("WAV fmt chunk is truncated", chunkSize >= PCM_FORMAT_BYTES)
                    assertEquals(PCM_FORMAT, buffer.short.toInt())
                    assertEquals(MONO_CHANNEL_COUNT, buffer.short.toInt())
                    assertEquals(SAMPLE_RATE, buffer.int)
                    buffer.int
                    buffer.short
                    assertEquals(PCM_BITS_PER_SAMPLE, buffer.short.toInt())
                }

                "data" -> pcm = ByteArray(chunkSize).also(buffer::get)
            }
            buffer.position(chunkEnd + chunkSize % 2)
        }

        val pcmBytes = requireNotNull(pcm) { "WAV fixture $assetPath has no data chunk" }
        assertEquals("WAV PCM data must contain 16-bit samples", 0, pcmBytes.size % PCM_SAMPLE_BYTES)
        return ShortArray(pcmBytes.size / PCM_SAMPLE_BYTES) { index ->
            val offset = index * PCM_SAMPLE_BYTES
            ((pcmBytes[offset].toInt() and 0xff) or (pcmBytes[offset + 1].toInt() shl 8)).toShort()
        }
    }

    private fun ByteBuffer.readFourCc(): String =
        ByteArray(FOUR_CC_BYTES).also(::get).toString(StandardCharsets.US_ASCII)

    private companion object {
        const val FOUR_CC_BYTES = 4
        const val CHUNK_HEADER_BYTES = 8
        const val PCM_FORMAT_BYTES = 16
        const val PCM_FORMAT = 1
        const val MONO_CHANNEL_COUNT = 1
        const val SAMPLE_RATE = 16_000
        const val PCM_BITS_PER_SAMPLE = 16
        const val PCM_SAMPLE_BYTES = 2
    }
}
