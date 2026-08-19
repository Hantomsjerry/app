package com.example.myapp

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapp.voice.SherpaOnnxModelStore
import com.example.myapp.voice.SherpaOnnxStreamingEngine
import com.example.myapp.voice.SherpaOnnxModelFiles
import com.example.myapp.voice.DeterministicVoiceParser
import com.example.myapp.voice.DirectParseResult
import com.example.myapp.voice.MachineDevice
import com.example.myapp.voice.ParameterValue
import com.example.myapp.voice.SetParameterCommand
import com.example.myapp.voice.SpeechTranscriptNormalizer
import com.example.myapp.voice.VoiceParameter
import com.example.myapp.voice.buildSherpaRecognizerConfig
import com.example.myapp.voice.pcm16ToFloat
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaAccuracyInstrumentedTest {
    @Test
    fun transcribesReferenceAudioOnDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = SherpaOnnxStreamingEngine(SherpaOnnxModelStore(instrumentation.targetContext))
        val cases = listOf(
            ReferenceCase(
                "asr/zh_machine2_sensitivity97.wav",
                command(MachineDevice.machine_2, VoiceParameter.lv1Sensitivity, 97)
            ),
            ReferenceCase(
                "asr/zh_machine1_strength80.wav",
                command(MachineDevice.machine_1, VoiceParameter.lv1Strength, 80)
            ),
            ReferenceCase(
                "asr/en_machine2_sensitivity32.wav",
                command(MachineDevice.machine_2, VoiceParameter.lv1Sensitivity, 32)
            ),
            ReferenceCase(
                "asr/zh_reject_delay700.wav",
                command(MachineDevice.machine_1, VoiceParameter.rejectDelay, 700)
            ),
            ReferenceCase(
                "asr/zh_lv2_strength80.wav",
                command(MachineDevice.machine_1, VoiceParameter.lv2Strength, 80)
            )
        )

        try {
            val transcripts = cases.map { case ->
                val partials = mutableListOf<String>()
                engine.startSession(partials::add)
                readPcm16(case.assetPath).asList().chunked(1_024).forEach { chunk ->
                    engine.acceptSamples(chunk.toShortArray())
                }
                val finalText = engine.finishSession()
                Log.i(LOG_TAG, "asset=${case.assetPath} partials=$partials final=$finalText")
                case to finalText
            }

            val failures = mutableListOf<String>()
            val actual = transcripts.mapNotNull { (case, finalText) ->
                val normalized = SpeechTranscriptNormalizer.normalize(finalText)
                when (val parsed = DeterministicVoiceParser.parse(normalized)) {
                    is DirectParseResult.Parsed -> parsed.command
                    else -> {
                        failures += "asset=${case.assetPath} raw=$finalText normalized=$normalized parsed=$parsed"
                        null
                    }
                }
            }

            if (failures.isNotEmpty()) fail(failures.joinToString(separator = "\n"))

            assertEquals(cases.map(ReferenceCase::expectedCommand), actual)
        } finally {
            engine.close()
        }
    }

    @Test
    @Ignore("Run one decoding configuration per instrumentation process on OEM devices")
    fun comparesHotwordAndDecodingConfigurations() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val files = SherpaOnnxModelStore(instrumentation.targetContext).prepare()
        val cases = listOf(
            "asr/zh_machine2_sensitivity97.wav",
            "asr/zh_machine1_strength80.wav",
            "asr/en_machine2_sensitivity32.wav"
        )
        val base = buildSherpaRecognizerConfig(files)
        val variants = listOf(
            "current-hotwords" to base,
            "no-hotwords-beam" to base.copy(hotwordsFile = ""),
            "no-hotwords-greedy" to base.copy(hotwordsFile = "", decodingMethod = "greedy_search")
        )

        val matrix = variants.associate { (name, config) ->
            val recognizer = OnlineRecognizer(assetManager = null, config = config)
            name to try {
                cases.associateWith { asset -> transcribe(recognizer, readPcm16(asset)) }
            } finally {
                recognizer.release()
            }
        }
        fail(matrix.toString())
    }

    @Test
    @Ignore("Diagnostic decoder ablation; intentionally reports transcripts via failure")
    fun transcribesWithoutHotwordsUsingModifiedBeamSearch() = runBlocking {
        diagnoseSingleVariant("no-hotwords-beam", decodingMethod = "modified_beam_search")
    }

    @Test
    @Ignore("Diagnostic decoder ablation; intentionally reports transcripts via failure")
    fun transcribesWithoutHotwordsUsingGreedySearch() = runBlocking {
        diagnoseSingleVariant("no-hotwords-greedy", decodingMethod = "greedy_search")
    }

    @Test
    @Ignore("Diagnostic tail-padding ablation; intentionally reports transcripts via failure")
    fun transcribesWithoutHotwordsUsingTwoSecondTail() = runBlocking {
        diagnoseSingleVariant(
            name = "no-hotwords-beam-tail-2s",
            decodingMethod = "modified_beam_search",
            tailSamples = 32_000
        )
    }

    @Test
    @Ignore("Diagnostic tail-padding ablation; intentionally reports transcripts via failure")
    fun transcribesWithoutHotwordsUsingFourSecondTail() = runBlocking {
        diagnoseSingleVariant(
            name = "no-hotwords-beam-tail-4s",
            decodingMethod = "modified_beam_search",
            tailSamples = 64_000
        )
    }

    @Test
    @Ignore("Diagnostic candidate-model ablation; intentionally reports transcripts via failure")
    fun transcribesLargerBilingualCandidateWithoutHotwords() = runBlocking {
        val candidateFiles = candidateModelFiles()

        val recognizer = OnlineRecognizer(
            assetManager = null,
            config = buildSherpaRecognizerConfig(candidateFiles).copy(hotwordsFile = "")
        )
        val assets = listOf(
            "asr/zh_machine2_sensitivity97.wav",
            "asr/zh_machine1_strength80.wav",
            "asr/en_machine2_sensitivity32.wav"
        )
        val results = try {
            assets.associateWith { asset -> transcribe(recognizer, readPcm16(asset)) }
        } finally {
            recognizer.release()
        }
        fail("larger-bilingual-no-hotwords=$results")
    }

    @Test
    @Ignore("Diagnostic hotword-score ablation; intentionally reports transcripts via failure")
    fun transcribesLargerBilingualCandidateWithLowHotwordScores() = runBlocking {
        val candidateFiles = candidateModelFiles()
        val lowScoreHotwords = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "low-score-hotwords.txt"
        )
        lowScoreHotwords.writeText(
            candidateFiles.hotwords.readLines(Charsets.UTF_8)
                .filter(String::isNotBlank)
                .joinToString(separator = "\n", postfix = "\n") { line ->
                    "${line.substringBeforeLast(" :").trim()} :0.5"
                },
            Charsets.UTF_8
        )
        val recognizer = OnlineRecognizer(
            assetManager = null,
            config = buildSherpaRecognizerConfig(candidateFiles).copy(
                hotwordsFile = lowScoreHotwords.absolutePath,
                hotwordsScore = 0.5f,
                maxActivePaths = 4
            )
        )
        val assets = listOf(
            "asr/zh_machine2_sensitivity97.wav",
            "asr/zh_machine1_strength80.wav",
            "asr/en_machine2_sensitivity32.wav"
        )
        val results = try {
            assets.associateWith { asset ->
                val raw = transcribe(recognizer, readPcm16(asset))
                val normalized = com.example.myapp.voice.SpeechTranscriptNormalizer.normalize(raw)
                val parsed = com.example.myapp.voice.DeterministicVoiceParser.parse(normalized)
                "raw=$raw | normalized=$normalized | parsed=$parsed"
            }
        } finally {
            recognizer.release()
        }
        fail("larger-bilingual-low-hotwords=$results")
    }

    private suspend fun candidateModelFiles(): SherpaOnnxModelFiles {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val candidateDirectory = File(
            instrumentation.targetContext.filesDir,
            "candidate-bilingual-2023-02-20"
        )
        val productionFiles = SherpaOnnxModelStore(instrumentation.targetContext).prepare()
        val files = SherpaOnnxModelFiles(
            encoder = File(candidateDirectory, "encoder-epoch-99-avg-1.int8.onnx"),
            decoder = File(candidateDirectory, "decoder-epoch-99-avg-1.int8.onnx"),
            joiner = File(candidateDirectory, "joiner-epoch-99-avg-1.int8.onnx"),
            tokens = File(candidateDirectory, "tokens.txt"),
            bpeVocab = File(candidateDirectory, "bpe.vocab"),
            hotwords = productionFiles.hotwords
        )
        require(
            listOf(files.encoder, files.decoder, files.joiner, files.tokens, files.bpeVocab)
                .all(File::isFile)
        ) { "Candidate model files are missing from $candidateDirectory" }
        return files
    }

    private suspend fun diagnoseSingleVariant(
        name: String,
        decodingMethod: String,
        tailSamples: Int = 12_800
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val files = SherpaOnnxModelStore(instrumentation.targetContext).prepare()
        val config = buildSherpaRecognizerConfig(files).copy(
            hotwordsFile = "",
            decodingMethod = decodingMethod
        )
        val recognizer = OnlineRecognizer(assetManager = null, config = config)
        val assets = listOf(
            "asr/zh_machine2_sensitivity97.wav",
            "asr/zh_machine1_strength80.wav",
            "asr/en_machine2_sensitivity32.wav"
        )
        val results = try {
            assets.associateWith { asset -> transcribe(recognizer, readPcm16(asset), tailSamples) }
        } finally {
            recognizer.release()
        }
        fail("$name=$results")
    }

    private fun transcribe(
        recognizer: OnlineRecognizer,
        samples: ShortArray,
        tailSamples: Int = 12_800
    ): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(pcm16ToFloat(samples), 16_000)
            stream.acceptWaveform(FloatArray(tailSamples), 16_000)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun readPcm16(assetPath: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open(assetPath).use { it.readBytes() }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = littleEndianInt(bytes, offset + 4)
            val chunkStart = offset + 8
            if (chunkId == "data") {
                require(chunkSize >= 0 && chunkStart + chunkSize <= bytes.size)
                return ByteBuffer.wrap(bytes, chunkStart, chunkSize)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .let { buffer -> ShortArray(buffer.remaining()).also(buffer::get) }
            }
            offset = chunkStart + chunkSize + (chunkSize and 1)
        }
        error("No PCM data chunk in $assetPath")
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int

    private data class ReferenceCase(
        val assetPath: String,
        val expectedCommand: SetParameterCommand
    )

    private fun command(
        device: MachineDevice,
        parameter: VoiceParameter,
        value: Int
    ) = SetParameterCommand(
        action = "SET_PARAMETER",
        device = device,
        parameter = parameter,
        value = ParameterValue.IntValue(value)
    )

    private companion object {
        const val LOG_TAG = "SherpaAccuracy"
    }
}
