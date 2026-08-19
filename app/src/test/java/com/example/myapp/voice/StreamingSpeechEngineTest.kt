package com.example.myapp.voice

import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingSpeechEngineTest {
    @Test
    fun pcm16ConversionUsesSherpaRange() {
        assertArrayEquals(
            floatArrayOf(-1.0f, 0.0f, 32767f / 32768f),
            pcm16ToFloat(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)),
            0.000001f
        )
    }

    @Test
    fun prepareCreatesRecognizerOnlyOnce() = runTest {
        val fixture = fixture()

        fixture.engine.prepare()
        fixture.engine.prepare()

        assertEquals(1, fixture.factory.createCalls)
        fixture.engine.close()
    }

    @Test
    fun acceptFeeds16000HzAndDecodesUntilNotReady() = runTest {
        val fixture = fixture()
        val partials = mutableListOf<String>()
        fixture.engine.startSession(partials::add)
        fixture.recognizer.queueResults(" first ", "second")

        fixture.engine.acceptSamples(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE))

        val accepted = fixture.recognizer.streams.single().accepted.single()
        assertEquals(16_000, accepted.sampleRate)
        assertArrayEquals(
            floatArrayOf(-1.0f, 0.0f, 32767f / 32768f),
            accepted.samples,
            0.000001f
        )
        assertEquals(2, fixture.recognizer.decodeCalls)
        assertEquals(listOf("first"), partials)
        fixture.engine.close()
    }

    @Test
    fun changedPartialTextIsEmittedAtMostEvery100Millis() = runTest {
        var nowMillis = 1_000L
        val fixture = fixture(nowMillis = { nowMillis })
        val partials = mutableListOf<String>()
        fixture.engine.startSession(partials::add)

        fixture.recognizer.queueResults("Lv1")
        fixture.engine.acceptSamples(shortArrayOf(1))
        nowMillis += 50
        fixture.recognizer.queueResults("Lv1 strength")
        fixture.engine.acceptSamples(shortArrayOf(2))
        nowMillis += 50
        fixture.recognizer.queueResults("Lv1 strength")
        fixture.engine.acceptSamples(shortArrayOf(3))
        nowMillis += 100
        fixture.recognizer.queueResults("  ", "Lv1 strength")
        fixture.engine.acceptSamples(shortArrayOf(4))

        assertEquals(listOf("Lv1", "Lv1 strength"), partials)
        fixture.engine.close()
    }

    @Test
    fun finishAdds800MillisTailThenInputFinishedAndReturnsTrimmedFinal() = runTest {
        val fixture = fixture()
        fixture.engine.startSession {}
        fixture.recognizer.queueResults(" partial ", " final text ")

        val finalText = fixture.engine.finishSession()

        val stream = fixture.recognizer.streams.single()
        val tail = stream.accepted.single()
        assertEquals(16_000, tail.sampleRate)
        assertEquals(12_800, tail.samples.size)
        assertTrue(tail.samples.all { it == 0.0f })
        assertEquals(listOf("accept", "inputFinished", "release"), stream.lifecycle)
        assertEquals(2, fixture.recognizer.decodeCalls)
        assertEquals("final text", finalText)
        assertEquals(1, stream.releaseCalls)
        fixture.engine.close()
    }

    @Test
    fun cancelReleasesOnlyCurrentStreamWithoutFinalText() = runTest {
        val fixture = fixture()
        fixture.engine.startSession {}

        fixture.engine.cancelSession()
        fixture.engine.cancelSession()

        val stream = fixture.recognizer.streams.single()
        assertEquals(1, stream.releaseCalls)
        assertEquals(0, stream.inputFinishedCalls)
        assertEquals(0, fixture.recognizer.resultCalls)
        fixture.engine.close()
    }

    @Test
    fun closeReleasesStreamRecognizerAndDispatcherOnce() = runTest {
        val fixture = fixture()
        fixture.engine.startSession {}

        fixture.engine.close()
        fixture.engine.close()

        assertEquals(1, fixture.recognizer.streams.single().releaseCalls)
        assertEquals(1, fixture.recognizer.releaseCalls)
        assertEquals(1, fixture.dispatcherCloseCalls())
    }

    @Test
    fun secondSessionCannotReuseFirstSessionStream() = runTest {
        val fixture = fixture()
        fixture.engine.startSession {}
        val first = fixture.recognizer.streams.single()
        fixture.engine.cancelSession()

        fixture.engine.startSession {}
        val second = fixture.recognizer.streams.last()

        assertEquals(2, fixture.recognizer.streams.size)
        assertNotSame(first, second)
        assertEquals(1, first.releaseCalls)
        assertFalse(second.released)
        fixture.engine.close()
    }

    @Test
    fun concurrentPrepareCallsCreateAndReleaseExactlyOneRecognizer() = runTest {
        val provider = FirstCallBlockingModelProvider()
        val fixture = fixture(modelFilesProvider = provider::provide)
        val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.engine.prepare() }
        provider.firstCallEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.engine.prepare() }

        provider.releaseFirstCall.complete(Unit)
        first.await()
        second.await()
        fixture.engine.close()

        assertEquals(1, provider.callCount)
        assertEquals(1, fixture.factory.createCalls)
        assertEquals(1, fixture.factory.recognizers.single().releaseCalls)
    }

    @Test
    fun suspendedPrepareCannotOverwriteRecognizerAndStreamCreatedByStart() = runTest {
        val provider = FirstCallBlockingModelProvider()
        val fixture = fixture(modelFilesProvider = provider::provide)
        val prepare = async(start = CoroutineStart.UNDISPATCHED) { fixture.engine.prepare() }
        provider.firstCallEntered.await()
        val start = async(start = CoroutineStart.UNDISPATCHED) { fixture.engine.startSession {} }

        provider.releaseFirstCall.complete(Unit)
        prepare.await()
        start.await()
        val stream = fixture.factory.allStreams().single()
        stream.owner.queueResults("ready")
        fixture.engine.acceptSamples(shortArrayOf(1))
        fixture.engine.cancelSession()
        fixture.engine.close()

        assertEquals(1, fixture.factory.createCalls)
        assertEquals(1, stream.releaseCalls)
        assertEquals(1, stream.owner.releaseCalls)
    }

    @Test
    fun suspendedStartCannotCreateSecondRecognizerAfterPrepareResumesFirst() = runTest {
        val provider = FirstCallBlockingModelProvider()
        val fixture = fixture(modelFilesProvider = provider::provide)
        val start = async(start = CoroutineStart.UNDISPATCHED) { fixture.engine.startSession {} }
        provider.firstCallEntered.await()
        val prepare = async(start = CoroutineStart.UNDISPATCHED) { fixture.engine.prepare() }

        provider.releaseFirstCall.complete(Unit)
        start.await()
        prepare.await()
        fixture.engine.cancelSession()
        fixture.engine.close()

        assertEquals(1, fixture.factory.createCalls)
        assertEquals(1, fixture.factory.allStreams().single().releaseCalls)
        assertEquals(1, fixture.factory.recognizers.single().releaseCalls)
    }

    @Test
    fun concurrentStartsCommitOneSessionAndReleaseItsOnlyStream() = runTest {
        val provider = FirstCallBlockingModelProvider()
        val fixture = fixture(modelFilesProvider = provider::provide)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.engine.startSession {} }
        }
        provider.firstCallEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.engine.startSession {} }
        }

        provider.releaseFirstCall.complete(Unit)
        val outcomes = listOf(first.await(), second.await())
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is IllegalStateException })
        assertEquals(1, fixture.factory.createCalls)
        assertEquals(1, fixture.factory.allStreams().size)

        fixture.engine.cancelSession()
        fixture.engine.close()
        assertEquals(1, fixture.factory.allStreams().single().releaseCalls)
        assertEquals(1, fixture.factory.recognizers.single().releaseCalls)
    }

    @Test
    fun cancelWaitsForSuspendedStartAndLeavesNoHiddenSession() = runTest {
        val provider = FirstCallBlockingModelProvider()
        val fixture = fixture(modelFilesProvider = provider::provide)
        val start = async(start = CoroutineStart.UNDISPATCHED) { fixture.engine.startSession {} }
        provider.firstCallEntered.await()
        val cancel = async(start = CoroutineStart.UNDISPATCHED) { fixture.engine.cancelSession() }

        provider.releaseFirstCall.complete(Unit)
        start.await()
        cancel.await()
        fixture.engine.startSession {}
        fixture.engine.cancelSession()
        fixture.engine.close()

        assertEquals(1, fixture.factory.createCalls)
        assertEquals(2, fixture.factory.allStreams().size)
        assertTrue(fixture.factory.allStreams().all { it.releaseCalls == 1 })
        assertEquals(1, fixture.factory.recognizers.single().releaseCalls)
    }

    @Test
    fun promptCancellationAfterStreamCreationReleasesOnlyThatInvocationStream() = runTest {
        val streamCreated = CountDownLatch(1)
        val allowCreateToReturn = CountDownLatch(1)
        val recognizer = FakeRecognizer(
            onStreamCreated = {
                streamCreated.countDown()
                check(allowCreateToReturn.await(2, TimeUnit.SECONDS))
            }
        )
        val nativeExecutor = Executors.newSingleThreadExecutor()
        val callerExecutor = Executors.newSingleThreadExecutor()
        val nativeDispatcher = nativeExecutor.asCoroutineDispatcher()
        val callerDispatcher = callerExecutor.asCoroutineDispatcher()
        val fixture = fixture(
            recognizerFactory = FakeRecognizerFactory { recognizer },
            dispatcher = nativeDispatcher,
            closeDispatcher = nativeDispatcher::close
        )
        val scope = CoroutineScope(callerDispatcher + SupervisorJob())

        try {
            val start = scope.async { fixture.engine.startSession {} }
            assertTrue(streamCreated.await(2, TimeUnit.SECONDS))
            start.cancel()
            allowCreateToReturn.countDown()

            val failure = runCatching { withTimeout(2_000) { start.await() } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, recognizer.streams.single().releaseCalls)

            fixture.engine.startSession {}
            fixture.engine.cancelSession()
            assertEquals(2, recognizer.streams.size)
            assertTrue(recognizer.streams.all { it.releaseCalls == 1 })
        } finally {
            allowCreateToReturn.countDown()
            fixture.engine.close()
            scope.cancel()
            callerDispatcher.close()
            nativeDispatcher.close()
        }
        assertEquals(1, recognizer.releaseCalls)
    }

    @Test
    fun partialCallbackCanCloseEngineWithoutNativeDispatcherReentry() = runTest {
        val nativeDispatcher = RejectReentrantDispatcher()
        val fixture = fixture(
            dispatcher = nativeDispatcher,
            closeDispatcher = nativeDispatcher::close
        )
        fixture.engine.startSession { fixture.engine.close() }
        fixture.recognizer.queueResults("partial")

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                fixture.engine.acceptSamples(shortArrayOf(1))
            }
        }

        assertEquals(1, fixture.recognizer.streams.single().releaseCalls)
        assertEquals(1, fixture.recognizer.releaseCalls)
        assertEquals(1, fixture.dispatcherCloseCalls())
    }

    @Test
    fun throwingPartialCallbackDoesNotAbortDecodeOrFinalResult() = runTest {
        val fixture = fixture()
        fixture.engine.startSession { throw IllegalStateException("callback failure") }
        fixture.recognizer.queueResults("partial")

        fixture.engine.acceptSamples(shortArrayOf(1))
        fixture.recognizer.queueResults("final text")
        val finalText = fixture.engine.finishSession()

        assertEquals("final text", finalText)
        assertEquals(2, fixture.recognizer.decodeCalls)
        assertEquals(1, fixture.recognizer.streams.single().releaseCalls)
        fixture.engine.close()
    }

    @Test
    fun closeDuringFirstPartialSuppressesAllRemainingCallbacks() = runTest {
        val nativeDispatcher = RejectReentrantDispatcher()
        var now = 1_000L
        val fixture = fixture(
            nowMillis = { now.also { now += 100L } },
            dispatcher = nativeDispatcher,
            closeDispatcher = nativeDispatcher::close
        )
        val partials = mutableListOf<String>()
        fixture.engine.startSession { text ->
            partials += text
            fixture.engine.close()
        }
        fixture.recognizer.queueResults("first", "second")

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                fixture.engine.acceptSamples(shortArrayOf(1))
            }
        }

        assertEquals(listOf("first"), partials)
        assertEquals(1, fixture.recognizer.streams.single().releaseCalls)
        assertEquals(1, fixture.recognizer.releaseCalls)
    }

    @Test
    fun concurrentExternalCloseCallersWaitThroughNativeAndDispatcherCloseAndShareFailure() = runTest {
        val decodeEntered = CountDownLatch(1)
        val allowDecode = CountDownLatch(1)
        val dispatcherCloseEntered = CountDownLatch(1)
        val allowDispatcherClose = CountDownLatch(1)
        val closeFailure = IllegalStateException("dispatcher close failure")
        val recognizer = FakeRecognizer(
            onDecode = {
                decodeEntered.countDown()
                check(allowDecode.await(5, TimeUnit.SECONDS))
            }
        )
        val nativeExecutor = Executors.newSingleThreadExecutor()
        val nativeDispatcher = nativeExecutor.asCoroutineDispatcher()
        val fixture = fixture(
            recognizerFactory = FakeRecognizerFactory { recognizer },
            dispatcher = nativeDispatcher,
            closeDispatcher = {
                dispatcherCloseEntered.countDown()
                check(allowDispatcherClose.await(5, TimeUnit.SECONDS))
                throw closeFailure
            }
        )
        fixture.engine.startSession {}
        recognizer.queueResults("partial")
        val accept = operationThread("accept-held-native") {
            kotlinx.coroutines.runBlocking { fixture.engine.acceptSamples(shortArrayOf(1)) }
        }

        try {
            accept.thread.start()
            assertTrue(decodeEntered.await(2, TimeUnit.SECONDS))

            val firstClose = operationThread("first-external-close") { fixture.engine.close() }
            firstClose.thread.start()
            assertThreadWaiting(firstClose.thread)

            val secondClose = operationThread("second-external-close") { fixture.engine.close() }
            secondClose.thread.start()
            assertThreadWaiting(secondClose.thread)
            assertEquals(1L, firstClose.returned.count)
            assertEquals(1L, secondClose.returned.count)

            allowDecode.countDown()
            assertTrue(dispatcherCloseEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1L, firstClose.returned.count)
            assertEquals(1L, secondClose.returned.count)

            allowDispatcherClose.countDown()
            assertTrue(firstClose.returned.await(2, TimeUnit.SECONDS))
            assertTrue(secondClose.returned.await(2, TimeUnit.SECONDS))
            assertSame(closeFailure, firstClose.failure.get())
            assertSame(closeFailure, secondClose.failure.get())
            assertEquals(1, recognizer.streams.single().releaseCalls)
            assertEquals(1, recognizer.releaseCalls)
            assertEquals(1, fixture.dispatcherCloseCalls())
        } finally {
            allowDecode.countDown()
            allowDispatcherClose.countDown()
            accept.thread.join(2_000)
            nativeDispatcher.close()
        }
    }

    @Test
    fun callbackReentrantCloseDoesNotWaitForExternalWinningClose() = runTest {
        val callbackEntered = CountDownLatch(1)
        val allowCallbackClose = CountDownLatch(1)
        val callbackCloseReturned = CountDownLatch(1)
        val allowCallbackExit = CountDownLatch(1)
        val fixture = fixture()
        fixture.engine.startSession {
            callbackEntered.countDown()
            check(allowCallbackClose.await(5, TimeUnit.SECONDS))
            fixture.engine.close()
            callbackCloseReturned.countDown()
            check(allowCallbackExit.await(5, TimeUnit.SECONDS))
        }
        fixture.recognizer.queueResults("partial")
        val accept = operationThread("callback-reentrant-accept") {
            kotlinx.coroutines.runBlocking { fixture.engine.acceptSamples(shortArrayOf(1)) }
        }
        val externalClose = operationThread("external-winning-close") { fixture.engine.close() }

        try {
            accept.thread.start()
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            externalClose.thread.start()
            assertThreadWaiting(externalClose.thread)

            allowCallbackClose.countDown()
            assertTrue(callbackCloseReturned.await(2, TimeUnit.SECONDS))
            assertThreadWaiting(externalClose.thread)
            assertEquals(1L, externalClose.returned.count)

            allowCallbackExit.countDown()
            assertTrue(accept.returned.await(2, TimeUnit.SECONDS))
            assertTrue(externalClose.returned.await(2, TimeUnit.SECONDS))
            assertEquals(null, accept.failure.get())
            assertEquals(null, externalClose.failure.get())
            assertEquals(1, fixture.recognizer.streams.single().releaseCalls)
            assertEquals(1, fixture.recognizer.releaseCalls)
            assertEquals(1, fixture.dispatcherCloseCalls())
        } finally {
            allowCallbackClose.countDown()
            allowCallbackExit.countDown()
        }
    }

    @Test
    fun externalCloseWaitsForCallbackWinnerToLeaveCallbackGate() = runTest {
        val callbackCloseReturned = CountDownLatch(1)
        val allowCallbackExit = CountDownLatch(1)
        val fixture = fixture()
        fixture.engine.startSession {
            fixture.engine.close()
            callbackCloseReturned.countDown()
            check(allowCallbackExit.await(5, TimeUnit.SECONDS))
        }
        fixture.recognizer.queueResults("partial")
        val accept = operationThread("callback-winning-accept") {
            kotlinx.coroutines.runBlocking { fixture.engine.acceptSamples(shortArrayOf(1)) }
        }
        val externalClose = operationThread("external-close-waiter") { fixture.engine.close() }

        try {
            accept.thread.start()
            assertTrue(callbackCloseReturned.await(2, TimeUnit.SECONDS))
            externalClose.thread.start()
            assertThreadWaiting(externalClose.thread)
            assertEquals(1L, externalClose.returned.count)

            allowCallbackExit.countDown()
            assertTrue(accept.returned.await(2, TimeUnit.SECONDS))
            assertTrue(externalClose.returned.await(2, TimeUnit.SECONDS))
            assertEquals(null, accept.failure.get())
            assertEquals(null, externalClose.failure.get())
            assertEquals(1, fixture.recognizer.streams.single().releaseCalls)
            assertEquals(1, fixture.recognizer.releaseCalls)
            assertEquals(1, fixture.dispatcherCloseCalls())
        } finally {
            allowCallbackExit.countDown()
        }
    }

    @Test
    fun fatalCallbackErrorPropagatesBeyondIsolationBoundary() = runTest {
        val fixture = fixture()
        val fatal = FatalCallbackError()
        fixture.engine.startSession { throw fatal }
        fixture.recognizer.queueResults("partial")

        val observed = runCatching {
            fixture.engine.acceptSamples(shortArrayOf(1))
        }.exceptionOrNull()

        assertSame(fatal, observed)
        fixture.engine.close()
        assertEquals(1, fixture.recognizer.streams.single().releaseCalls)
        assertEquals(1, fixture.recognizer.releaseCalls)
    }

    @Test
    fun officialConfigUsesPinnedBilingualZipformerSettings() {
        val files = modelFiles()

        val config: OnlineRecognizerConfig = buildSherpaRecognizerConfig(files)

        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(0.0f, config.featConfig.dither)
        assertEquals(files.encoder.absolutePath, config.modelConfig.transducer.encoder)
        assertEquals(files.decoder.absolutePath, config.modelConfig.transducer.decoder)
        assertEquals(files.joiner.absolutePath, config.modelConfig.transducer.joiner)
        assertEquals(files.tokens.absolutePath, config.modelConfig.tokens)
        assertEquals(2, config.modelConfig.numThreads)
        assertEquals("cpu", config.modelConfig.provider)
        assertEquals("zipformer", config.modelConfig.modelType)
        assertEquals("cjkchar+bpe", config.modelConfig.modelingUnit)
        assertEquals(files.bpeVocab.absolutePath, config.modelConfig.bpeVocab)
        assertFalse(config.enableEndpoint)
        assertEquals("modified_beam_search", config.decodingMethod)
        assertEquals(4, config.maxActivePaths)
        assertEquals(files.hotwords.absolutePath, config.hotwordsFile)
        assertEquals(0.5f, config.hotwordsScore)
    }

    private fun fixture(
        nowMillis: () -> Long = { 1_000L },
        modelFilesProvider: suspend () -> SherpaOnnxModelFiles = { modelFiles() },
        recognizerFactory: FakeRecognizerFactory = FakeRecognizerFactory(),
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        closeDispatcher: (() -> Unit)? = null
    ): Fixture {
        var dispatcherCloseCalls = 0
        val engine = SherpaOnnxStreamingEngine(
            modelFilesProvider = modelFilesProvider,
            recognizerFactory = recognizerFactory,
            dispatcher = dispatcher,
            closeDispatcher = {
                dispatcherCloseCalls++
                closeDispatcher?.invoke()
            },
            nowMillis = nowMillis
        )
        return Fixture(engine, recognizerFactory) { dispatcherCloseCalls }
    }

    private fun modelFiles() = SherpaOnnxModelFiles(
        encoder = File("test-model/encoder.int8.onnx"),
        decoder = File("test-model/decoder.int8.onnx"),
        joiner = File("test-model/joiner.int8.onnx"),
        tokens = File("test-model/tokens.txt"),
        bpeVocab = File("test-model/bpe.vocab"),
        hotwords = File("test-model/hotwords.txt")
    )

    private class Fixture(
        val engine: SherpaOnnxStreamingEngine,
        val factory: FakeRecognizerFactory,
        val dispatcherCloseCalls: () -> Int
    ) {
        val recognizer: FakeRecognizer get() = factory.recognizers.single()
    }

    private class FakeRecognizerFactory(
        private val createRecognizer: () -> FakeRecognizer = { FakeRecognizer() }
    ) : SherpaRecognizerFactory {
        var createCalls = 0
        val recognizers = Collections.synchronizedList(mutableListOf<FakeRecognizer>())

        override fun create(files: SherpaOnnxModelFiles): SherpaRecognizerApi {
            createCalls++
            return createRecognizer().also(recognizers::add)
        }

        fun allStreams(): List<FakeStream> = recognizers.flatMap { it.streams }
    }

    private class FakeRecognizer(
        private val onStreamCreated: (FakeStream) -> Unit = {},
        private val onDecode: () -> Unit = {}
    ) : SherpaRecognizerApi {
        val streams = Collections.synchronizedList(mutableListOf<FakeStream>())
        private val pendingResults = ArrayDeque<String>()
        private var currentResult = ""
        var decodeCalls = 0
        var resultCalls = 0
        var releaseCalls = 0

        fun queueResults(vararg results: String) {
            pendingResults.addAll(results)
        }

        override fun createStream(): SherpaStreamApi = FakeStream(this).also { stream ->
            streams += stream
            onStreamCreated(stream)
        }

        override fun isReady(stream: SherpaStreamApi): Boolean {
            requireOwned(stream)
            return pendingResults.isNotEmpty()
        }

        override fun decode(stream: SherpaStreamApi) {
            requireOwned(stream)
            onDecode()
            decodeCalls++
            currentResult = pendingResults.removeFirst()
        }

        override fun resultText(stream: SherpaStreamApi): String {
            requireOwned(stream)
            resultCalls++
            return currentResult
        }

        override fun release() {
            releaseCalls++
        }

        private fun requireOwned(stream: SherpaStreamApi) {
            check((stream as FakeStream).owner === this) { "recognizer/stream mismatch" }
        }
    }

    private class FakeStream(val owner: FakeRecognizer) : SherpaStreamApi {
        data class AcceptedWaveform(val samples: FloatArray, val sampleRate: Int)

        val accepted = mutableListOf<AcceptedWaveform>()
        val lifecycle = mutableListOf<String>()
        var inputFinishedCalls = 0
        var releaseCalls = 0
        val released: Boolean get() = releaseCalls > 0

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            accepted += AcceptedWaveform(samples.copyOf(), sampleRate)
            lifecycle += "accept"
        }

        override fun inputFinished() {
            inputFinishedCalls++
            lifecycle += "inputFinished"
        }

        override fun release() {
            releaseCalls++
            lifecycle += "release"
        }
    }

    private inner class FirstCallBlockingModelProvider {
        val firstCallEntered = CompletableDeferred<Unit>()
        val releaseFirstCall = CompletableDeferred<Unit>()
        var callCount = 0

        suspend fun provide(): SherpaOnnxModelFiles {
            callCount++
            if (callCount == 1) {
                firstCallEntered.complete(Unit)
                releaseFirstCall.await()
            }
            return modelFiles()
        }
    }

    private class RejectReentrantDispatcher : CoroutineDispatcher(), AutoCloseable {
        @Volatile
        private var workerThread: Thread? = null
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "test-sherpa-native").apply {
                isDaemon = true
                workerThread = this
            }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(Thread.currentThread() !== workerThread) {
                "Attempted to dispatch synchronously back onto the native thread"
            }
            executor.execute(block)
        }

        override fun close() {
            executor.shutdownNow()
        }
    }

    private data class OperationThread(
        val thread: Thread,
        val returned: CountDownLatch,
        val failure: AtomicReference<Throwable?>
    )

    private fun operationThread(name: String, operation: () -> Unit): OperationThread {
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread({
            try {
                operation()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                returned.countDown()
            }
        }, name).apply { isDaemon = true }
        return OperationThread(thread, returned, failure)
    }

    private fun assertThreadWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            when (thread.state) {
                Thread.State.WAITING,
                Thread.State.TIMED_WAITING,
                Thread.State.BLOCKED -> return
                Thread.State.TERMINATED -> throw AssertionError("${thread.name} returned instead of waiting")
                else -> Thread.yield()
            }
        }
        throw AssertionError("${thread.name} did not enter a waiting state; state=${thread.state}")
    }

    private class FatalCallbackError : Error("fatal callback error")
}
