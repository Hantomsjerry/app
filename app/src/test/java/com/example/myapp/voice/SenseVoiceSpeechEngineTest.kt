package com.example.myapp.voice

import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SenseVoiceSpeechEngineTest {
    @Test
    fun completePcmIsAcceptedAndDecodedExactlyOnce() = runTest {
        val native = FakeOfflineRecognizer(result = "将Lv2强度调到60")
        val engine = testEngine(native)

        engine.prepare()
        engine.startSession { error("offline engine must not emit partial text") }
        engine.acceptSamples(shortArrayOf(1, 2, 3, 4), sampleCount = 3)
        val text = engine.finishSession()

        assertEquals(1, native.stream.acceptCalls)
        assertArrayEquals(floatArrayOf(1 / 32768f, 2 / 32768f, 3 / 32768f), native.stream.accepted, 0f)
        assertEquals(1, native.decodeCalls)
        assertEquals("将Lv2强度调到60", text)
        assertEquals(1, native.stream.releaseCalls)
        engine.close()
    }

    @Test
    fun recognizerIsReusedAcrossTwoSessions() = runTest {
        val native = FakeOfflineRecognizer(result = "recognized")
        var creates = 0
        val engine = testEngine(native) { creates++ }

        engine.startSession {}
        assertEquals("recognized", engine.finishSession())
        engine.startSession {}
        assertEquals("recognized", engine.finishSession())

        assertEquals(1, creates)
        assertEquals(2, native.streams.size)
        assertEquals(2, native.decodeCalls)
        assertTrue(native.streams.all { it.releaseCalls == 1 })
        engine.close()
        assertEquals(1, native.releaseCalls)
    }

    @Test
    fun cancelBeforeFinishReleasesTheStreamWithoutDecoding() = runTest {
        val native = FakeOfflineRecognizer()
        val engine = testEngine(native)

        engine.startSession {}
        engine.cancelSession()
        engine.cancelSession()

        assertEquals(0, native.decodeCalls)
        assertEquals(1, native.stream.releaseCalls)
        engine.close()
    }

    @Test
    fun decodeFailureReleasesTheStreamAndLeavesEngineReadyForAnotherSession() = runTest {
        val failure = IllegalStateException("decode failed")
        val native = FakeOfflineRecognizer(decodeFailure = failure)
        val engine = testEngine(native)
        engine.startSession {}

        val observed = runCatching { engine.finishSession() }.exceptionOrNull()

        assertSame(failure, observed)
        assertEquals(1, native.stream.releaseCalls)
        native.decodeFailure = null
        engine.startSession {}
        assertEquals("", engine.finishSession())
        engine.close()
    }

    @Test
    fun cancelledStartReleasesTheCreatedStream() = runTest {
        val streamCreated = CountDownLatch(1)
        val allowCreation = CountDownLatch(1)
        val native = FakeOfflineRecognizer(
            onStreamCreated = {
                streamCreated.countDown()
                check(allowCreation.await(2, TimeUnit.SECONDS))
            }
        )
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val engine = testEngine(native, dispatcher = dispatcher)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)

        try {
            val start = scope.async { engine.startSession {} }
            assertTrue(streamCreated.await(2, TimeUnit.SECONDS))
            start.cancel()
            allowCreation.countDown()

            val observed = runCatching { withTimeout(2_000) { start.await() } }.exceptionOrNull()

            assertTrue(observed is CancellationException)
            assertEquals(1, native.stream.releaseCalls)
        } finally {
            allowCreation.countDown()
            engine.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun concurrentCloseIsIdempotentAndReleasesNativeResourcesOnce() = runTest {
        val native = FakeOfflineRecognizer()
        var dispatcherCloseCalls = 0
        val engine = testEngine(native, closeDispatcher = { dispatcherCloseCalls++ })
        engine.startSession {}
        val returned = CountDownLatch(2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        listOf(
            Thread {
                runCatching { engine.close() }.exceptionOrNull()?.let(failures::add)
                returned.countDown()
            },
            Thread {
                runCatching { engine.close() }.exceptionOrNull()?.let(failures::add)
                returned.countDown()
            }
        ).forEach(Thread::start)

        assertTrue(returned.await(2, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty())
        assertEquals(1, native.stream.releaseCalls)
        assertEquals(1, native.releaseCalls)
        assertEquals(1, dispatcherCloseCalls)
    }

    @Test
    fun closeFromCallerDispatcherDoesNotDeadlockBlockedFinishSession() = runTest {
        val decodeEntered = CountDownLatch(1)
        val allowDecode = CountDownLatch(1)
        val callerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "finish-caller").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val nativeDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "finish-native").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val native = FakeOfflineRecognizer(
            onDecode = {
                decodeEntered.countDown()
                check(allowDecode.await(2, TimeUnit.SECONDS))
            }
        )
        val engine = testEngine(
            native = native,
            dispatcher = nativeDispatcher,
            closeDispatcher = nativeDispatcher::close
        )
        val callerScope = CoroutineScope(SupervisorJob() + callerDispatcher)
        val finishReturned = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)

        try {
            engine.startSession {}
            val finish = callerScope.async {
                try {
                    engine.finishSession()
                } finally {
                    finishReturned.countDown()
                }
            }
            assertTrue(decodeEntered.await(2, TimeUnit.SECONDS))
            val close = callerScope.async {
                closeEntered.countDown()
                try {
                    engine.close()
                } finally {
                    closeReturned.countDown()
                }
            }
            assertTrue(closeEntered.await(2, TimeUnit.SECONDS))

            allowDecode.countDown()

            assertTrue(finishReturned.await(1, TimeUnit.SECONDS))
            assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
            assertEquals("", withTimeout(1_000) { finish.await() })
            withTimeout(1_000) { close.await() }
            assertEquals(1, native.stream.releaseCalls)
            assertEquals(1, native.releaseCalls)
        } finally {
            allowDecode.countDown()
            callerScope.cancel()
            callerDispatcher.close()
            nativeDispatcher.close()
        }
    }

    @Test
    fun secondActiveSessionIsRejected() = runTest {
        val native = FakeOfflineRecognizer()
        val engine = testEngine(native)
        engine.startSession {}

        val observed = runCatching { engine.startSession {} }.exceptionOrNull()

        assertTrue(observed is IllegalStateException)
        assertEquals(1, native.streams.size)
        engine.close()
    }

    @Test
    fun nativeCallsStayOnTheProvidedSingleThreadDispatcher() = runTest {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "offline-native-thread").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val native = FakeOfflineRecognizer()
        val engine = testEngine(native, dispatcher = dispatcher)

        try {
            engine.prepare()
            engine.startSession {}
            engine.acceptSamples(shortArrayOf(1))
            engine.finishSession()
            engine.close()

            assertTrue(native.operationThreads.all { it.startsWith("offline-native-thread") })
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun officialConfigUsesPinnedSenseVoiceSettings() {
        val files = SenseVoiceModelFiles(
            model = File("test-model/model.int8.onnx"),
            tokens = File("test-model/tokens.txt")
        )

        val config: OfflineRecognizerConfig = buildSenseVoiceRecognizerConfig(files)

        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(0.0f, config.featConfig.dither)
        assertEquals(files.model.absolutePath, config.modelConfig.senseVoice.model)
        assertEquals("auto", config.modelConfig.senseVoice.language)
        assertTrue(config.modelConfig.senseVoice.useInverseTextNormalization)
        assertEquals(files.tokens.absolutePath, config.modelConfig.tokens)
        assertEquals(2, config.modelConfig.numThreads)
        assertEquals("cpu", config.modelConfig.provider)
        assertFalse(config.modelConfig.debug)
    }

    private fun testEngine(
        native: FakeOfflineRecognizer,
        dispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        closeDispatcher: () -> Unit = {},
        onCreate: () -> Unit = {}
    ) = SenseVoiceSpeechEngine(
        modelFilesProvider = ::modelFiles,
        recognizerFactory = SenseVoiceRecognizerFactory {
            onCreate()
            native
        },
        dispatcher = dispatcher,
        closeDispatcher = closeDispatcher
    )

    private fun modelFiles() = SenseVoiceModelFiles(
        model = File("test-model/model.int8.onnx"),
        tokens = File("test-model/tokens.txt")
    )

    private class FakeOfflineRecognizer(
        private val result: String = "",
        var decodeFailure: Throwable? = null,
        private val onStreamCreated: (FakeOfflineStream) -> Unit = {},
        private val onDecode: () -> Unit = {}
    ) : SenseVoiceRecognizerApi {
        val streams = mutableListOf<FakeOfflineStream>()
        val stream: FakeOfflineStream get() = streams.single()
        val operationThreads = Collections.synchronizedList(mutableListOf<String>())
        var decodeCalls = 0
        var releaseCalls = 0

        override fun createStream(): SenseVoiceStreamApi = FakeOfflineStream(this).also {
            operationThreads += Thread.currentThread().name
            streams += it
            onStreamCreated(it)
        }

        override fun decode(stream: SenseVoiceStreamApi) {
            requireOwned(stream)
            operationThreads += Thread.currentThread().name
            onDecode()
            decodeCalls++
            decodeFailure?.let { throw it }
        }

        override fun resultText(stream: SenseVoiceStreamApi): String {
            requireOwned(stream)
            operationThreads += Thread.currentThread().name
            return result
        }

        override fun release() {
            operationThreads += Thread.currentThread().name
            releaseCalls++
        }

        private fun requireOwned(stream: SenseVoiceStreamApi) {
            check((stream as FakeOfflineStream).owner === this) { "recognizer/stream mismatch" }
        }
    }

    private class FakeOfflineStream(
        val owner: FakeOfflineRecognizer
    ) : SenseVoiceStreamApi {
        var accepted = FloatArray(0)
        var acceptCalls = 0
        var releaseCalls = 0

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            owner.operationThreads += Thread.currentThread().name
            assertEquals(16_000, sampleRate)
            acceptCalls++
            accepted = samples.copyOf()
        }

        override fun release() {
            owner.operationThreads += Thread.currentThread().name
            releaseCalls++
        }
    }
}
