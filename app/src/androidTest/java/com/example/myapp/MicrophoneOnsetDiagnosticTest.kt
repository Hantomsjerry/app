package com.example.myapp

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.myapp.voice.SherpaOnnxModelStore
import com.example.myapp.voice.SherpaOnnxStreamingEngine
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicrophoneOnsetDiagnosticTest {
    @get:Rule
    val microphonePermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    @Ignore("Manual microphone onset diagnostic; reports measurements via failure")
    fun capturesVoiceRecognitionOnsetAndRawTranscript() = runBlocking {
        runDiagnostic(
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            fileName = "microphone-onset-voice-recognition.wav"
        )
    }

    @Test
    @Ignore("Manual microphone onset diagnostic; reports measurements via failure")
    fun capturesMicOnsetAndRawTranscript() = runBlocking {
        runDiagnostic(
            audioSource = MediaRecorder.AudioSource.MIC,
            fileName = "microphone-onset-mic.wav"
        )
    }

    private suspend fun runDiagnostic(audioSource: Int, fileName: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = capturePcm(audioSource)
        val wav = File(context.getExternalFilesDir(null), fileName)
        writeWav(wav, samples)

        val engine = SherpaOnnxStreamingEngine(SherpaOnnxModelStore(context))
        val partials = mutableListOf<String>()
        val transcript = try {
            engine.prepare()
            engine.startSession(partials::add)
            samples.asList().chunked(1_024).forEach { chunk ->
                engine.acceptSamples(chunk.toShortArray())
            }
            engine.finishSession()
        } finally {
            engine.close()
        }

        val rms = windowRms(samples, windowSamples = 320)
        val firstVoiceWindow = rms.indexOfFirst { it >= VOICE_RMS_THRESHOLD }
        fail(
            "wav=${wav.absolutePath} " +
                "firstVoiceMs=${if (firstVoiceWindow < 0) -1 else firstVoiceWindow * 20} " +
                "firstSecondRms=${rms.take(50).joinToString(prefix = "[", postfix = "]") { "%.0f".format(it) }} " +
                "partials=$partials final=$transcript"
        )
    }

    private fun capturePcm(audioSource: Int): ShortArray {
        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minimumBufferBytes > 0)
        val recorder = AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minimumBufferBytes, 2_048))
            .build()
        val result = ShortArray(SAMPLE_RATE * CAPTURE_SECONDS)
        var offset = 0
        try {
            require(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            while (offset < result.size) {
                val read = recorder.read(
                    result,
                    offset,
                    minOf(1_024, result.size - offset),
                    AudioRecord.READ_BLOCKING
                )
                require(read > 0) { "AudioRecord read failed: $read" }
                offset += read
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
        return result
    }

    private fun windowRms(samples: ShortArray, windowSamples: Int): List<Double> =
        samples.asList().chunked(windowSamples).map { window ->
            sqrt(window.sumOf { sample -> sample.toDouble() * sample } / window.size)
        }

    private fun writeWav(file: File, samples: ShortArray) {
        val dataBytes = samples.size * Short.SIZE_BYTES
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + dataBytes))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1).toInt())
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE * Short.SIZE_BYTES))
            output.writeShort(java.lang.Short.reverseBytes(Short.SIZE_BYTES.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(dataBytes))
            samples.forEach { sample ->
                output.writeShort(java.lang.Short.reverseBytes(sample).toInt())
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CAPTURE_SECONDS = 8
        const val VOICE_RMS_THRESHOLD = 300.0
    }
}
