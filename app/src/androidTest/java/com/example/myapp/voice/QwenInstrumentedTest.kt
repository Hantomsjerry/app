package com.example.myapp.voice

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapp.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QwenInstrumentedTest {
    @Test
    fun nativeQwenProducesValidatedMixedLanguageCommand() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()

        val nativeEngine = NativeQwenInferenceEngine(QwenModelStore(context))
        var rawOutput = ""
        val engine = object : QwenInferenceEngine {
            override suspend fun generate(prompt: String, maxTokens: Int): String =
                nativeEngine.generate(prompt, maxTokens).also { rawOutput = it }

            override fun close() = nativeEngine.close()
        }
        try {
            val parser = VoiceIntentParser(
                engine = engine,
                directParser = { DirectParseResult.NeedsModel }
            )
            val result = parser.parse("machine two 的 Lv1 sensitivity 改成 66")
            assertTrue("Qwen output was not strict JSON: $rawOutput", result.isSuccess)
            val command = result.getOrThrow()

            assertEquals("SET_PARAMETER", command.action)
            assertEquals(MachineDevice.machine_2, command.device)
            assertEquals(VoiceParameter.lv1Sensitivity, command.parameter)
            assertEquals(ParameterValue.IntValue(66), command.value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun nativeQwenCancellationStopsPromptly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.waitForIdleSync()

        val store = QwenModelStore(context)
        store.prepare()
        val engine = NativeQwenInferenceEngine(store)
        try {
            val generation = launch(Dispatchers.Default) {
                engine.generate(
                    "Return a detailed JSON command for machine two lv1 sensitivity 66.",
                    maxTokens = 128
                )
            }
            delay(2_000)
            val startedAt = System.nanoTime()
            generation.cancel()
            withTimeout(5_000) { generation.join() }
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("Qwen cancellation took ${elapsedMillis}ms", elapsedMillis < 5_000)
        } finally {
            engine.close()
        }
    }
}
