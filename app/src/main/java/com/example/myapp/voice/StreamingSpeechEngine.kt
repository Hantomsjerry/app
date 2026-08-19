package com.example.myapp.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface StreamingSpeechEngine : AutoCloseable {
    suspend fun prepare()
    suspend fun startSession(onPartialText: (String) -> Unit)
    suspend fun acceptSamples(samples: ShortArray, sampleCount: Int = samples.size)
    suspend fun finishSession(): String
    suspend fun cancelSession()
    override fun close()
}

fun pcm16ToFloat(samples: ShortArray, sampleCount: Int = samples.size): FloatArray {
    require(sampleCount in 0..samples.size) { "Invalid PCM sample count: $sampleCount" }
    return FloatArray(sampleCount) { index -> samples[index] / 32768.0f }
}

internal fun interface SherpaRecognizerFactory {
    fun create(files: SherpaOnnxModelFiles): SherpaRecognizerApi
}

internal interface SherpaRecognizerApi {
    fun createStream(): SherpaStreamApi
    fun isReady(stream: SherpaStreamApi): Boolean
    fun decode(stream: SherpaStreamApi)
    fun resultText(stream: SherpaStreamApi): String
    fun release()
}

internal interface SherpaStreamApi {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun inputFinished()
    fun release()
}

class SherpaOnnxStreamingEngine internal constructor(
    private val modelFilesProvider: suspend () -> SherpaOnnxModelFiles,
    private val recognizerFactory: SherpaRecognizerFactory,
    private val dispatcher: CoroutineDispatcher,
    private val closeDispatcher: () -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : StreamingSpeechEngine {
    constructor(modelStore: SherpaOnnxModelStore) : this(
        modelFilesProvider = modelStore::prepare,
        recognizerFactory = OfficialSherpaRecognizerFactory,
        ownedDispatcher = OwnedSpeechDispatcher()
    )

    private constructor(
        modelFilesProvider: suspend () -> SherpaOnnxModelFiles,
        recognizerFactory: SherpaRecognizerFactory,
        ownedDispatcher: OwnedSpeechDispatcher
    ) : this(
        modelFilesProvider = modelFilesProvider,
        recognizerFactory = recognizerFactory,
        dispatcher = ownedDispatcher.dispatcher,
        closeDispatcher = ownedDispatcher::close
    )

    private val closeStarted = AtomicBoolean(false)
    private val closeCompleted = CountDownLatch(1)
    private val stateMutex = Mutex()
    private val callbackGate = ReentrantLock()
    private val callbackDepth = ThreadLocal<Int>()
    @Volatile
    private var closeFailure: Throwable? = null
    private var recognizer: SherpaRecognizerApi? = null
    private var activeSession: ActiveSession? = null

    override suspend fun prepare() {
        stateMutex.withLock {
            ensureOpen()
            prepareRecognizerLocked()
        }
    }

    override suspend fun startSession(onPartialText: (String) -> Unit) {
        var committedSession: ActiveSession? = null
        try {
            stateMutex.withLock {
                ensureOpen()
                check(activeSession == null) { "A speech recognition session is already active" }
                val preparedRecognizer = prepareRecognizerLocked()
                ensureOpen()
                withContext(NonCancellable) {
                    val stream = withContext(dispatcher) {
                        preparedRecognizer.createStream()
                    }
                    ActiveSession(
                        recognizer = preparedRecognizer,
                        stream = stream,
                        callback = onPartialText
                    ).also { session ->
                        activeSession = session
                        committedSession = session
                    }
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (cancellation: CancellationException) {
            committedSession?.let { session -> cleanupCancelledStart(session, cancellation) }
            throw cancellation
        }
    }

    override suspend fun acceptSamples(samples: ShortArray, sampleCount: Int) {
        val deliveries = stateMutex.withLock {
            ensureOpen()
            val session = requireActiveSession()
            withContext(dispatcher) {
                session.stream.acceptWaveform(pcm16ToFloat(samples, sampleCount), SAMPLE_RATE)
                drainRecognizer(session)
            }
        }
        deliverPartials(deliveries)
    }

    override suspend fun finishSession(): String {
        val result = stateMutex.withLock {
            ensureOpen()
            val session = requireActiveSession()
            var primaryFailure: Throwable? = null
            try {
                withContext(dispatcher + NonCancellable) {
                    session.stream.inputFinished()
                    val deliveries = drainRecognizer(session)
                    FinishResult(
                        text = session.recognizer.resultText(session.stream).trim(),
                        deliveries = deliveries
                    )
                }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                if (activeSession === session) activeSession = null
                withContext(dispatcher + NonCancellable) {
                    releasePreservingFailure(session.stream::release, primaryFailure)
                }
            }
        }
        deliverPartials(result.deliveries)
        return result.text
    }

    override suspend fun cancelSession() {
        stateMutex.withLock {
            ensureOpen()
            val session = activeSession ?: return@withLock
            activeSession = null
            withContext(dispatcher + NonCancellable) {
                session.stream.release()
            }
        }
    }

    override fun close() {
        if (closeStarted.compareAndSet(false, true)) {
            performWinningClose()
            return
        }

        if (isInsideCallback()) return

        awaitCloseCompletion()
        awaitCallbackQuiescence()
        closeFailure?.let { throw it }
    }

    private fun performWinningClose() {
        var failure: Throwable? = null
        callbackGate.lock()
        try {
            try {
                runBlocking {
                    stateMutex.withLock {
                        releaseNativeResourcesLocked()
                    }
                }
            } catch (cleanupFailure: Throwable) {
                failure = cleanupFailure
            }

            try {
                closeDispatcher()
            } catch (dispatcherFailure: Throwable) {
                if (failure == null) {
                    failure = dispatcherFailure
                } else {
                    failure.addSuppressed(dispatcherFailure)
                }
            }
        } finally {
            closeFailure = failure
            closeCompleted.countDown()
            callbackGate.unlock()
        }
        failure?.let { throw it }
    }

    private fun awaitCloseCompletion() {
        var interrupted = false
        while (true) {
            try {
                closeCompleted.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun awaitCallbackQuiescence() {
        callbackGate.lock()
        callbackGate.unlock()
    }

    private fun isInsideCallback(): Boolean = (callbackDepth.get() ?: 0) > 0

    private fun ensureOpen() {
        check(!closeStarted.get()) { "Speech recognition engine is closed" }
    }

    private suspend fun prepareRecognizerLocked(): SherpaRecognizerApi {
        recognizer?.let { return it }
        val files = modelFilesProvider()
        ensureOpen()
        return withContext(NonCancellable) {
            withContext(dispatcher) {
                recognizerFactory.create(files)
            }.also { recognizer = it }
        }
    }

    private fun requireActiveSession(): ActiveSession =
        checkNotNull(activeSession) { "No speech recognition session is active" }

    private fun drainRecognizer(session: ActiveSession): List<PartialDelivery> {
        val deliveries = mutableListOf<PartialDelivery>()
        while (session.recognizer.isReady(session.stream)) {
            session.recognizer.decode(session.stream)
            collectChangedPartial(session, session.recognizer.resultText(session.stream))?.let(deliveries::add)
        }
        return deliveries
    }

    private fun collectChangedPartial(session: ActiveSession, rawText: String): PartialDelivery? {
        val text = rawText.trim()
        if (text.isEmpty() || text == session.lastEmittedPartial) return null

        val now = nowMillis()
        val lastEmission = session.lastPartialEmissionMillis
        if (lastEmission != null && now - lastEmission < PARTIAL_INTERVAL_MILLIS) return null

        session.lastEmittedPartial = text
        session.lastPartialEmissionMillis = now
        return PartialDelivery(session.callback, text)
    }

    private fun deliverPartials(deliveries: List<PartialDelivery>) {
        for (delivery in deliveries) {
            callbackGate.lock()
            try {
                if (closeStarted.get()) return
                val previousDepth = callbackDepth.get() ?: 0
                callbackDepth.set(previousDepth + 1)
                try {
                    delivery.callback(delivery.text)
                } catch (_: Exception) {
                    // Application callbacks are observational and cannot fail recognition.
                } finally {
                    if (previousDepth == 0) {
                        callbackDepth.remove()
                    } else {
                        callbackDepth.set(previousDepth)
                    }
                }
            } finally {
                callbackGate.unlock()
            }
        }
    }

    private suspend fun cleanupCancelledStart(
        session: ActiveSession,
        cancellation: CancellationException
    ) {
        try {
            withContext(NonCancellable) {
                stateMutex.withLock {
                    if (activeSession !== session) return@withLock
                    activeSession = null
                    withContext(dispatcher + NonCancellable) {
                        session.stream.release()
                    }
                }
            }
        } catch (cleanupFailure: Throwable) {
            cancellation.addSuppressed(cleanupFailure)
        }
    }

    private suspend fun releaseNativeResourcesLocked() {
        val session = activeSession
        val preparedRecognizer = recognizer
        activeSession = null
        recognizer = null

        withContext(dispatcher + NonCancellable) {
            var failure: Throwable? = null
            listOfNotNull(
                session?.stream?.let { it::release },
                preparedRecognizer?.let { it::release }
            ).forEach { release ->
                try {
                    release()
                } catch (releaseFailure: Throwable) {
                    if (failure == null) failure = releaseFailure else failure!!.addSuppressed(releaseFailure)
                }
            }
            failure?.let { throw it }
        }
    }

    private fun releasePreservingFailure(release: () -> Unit, primaryFailure: Throwable?) {
        try {
            release()
        } catch (releaseFailure: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(releaseFailure)
            } else {
                throw releaseFailure
            }
        }
    }

    private class OwnedSpeechDispatcher {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "sherpa-onnx-speech").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()

        fun close() {
            dispatcher.close()
        }
    }

    private data class ActiveSession(
        val recognizer: SherpaRecognizerApi,
        val stream: SherpaStreamApi,
        val callback: (String) -> Unit,
        var lastEmittedPartial: String = "",
        var lastPartialEmissionMillis: Long? = null
    )

    private data class PartialDelivery(
        val callback: (String) -> Unit,
        val text: String
    )

    private data class FinishResult(
        val text: String,
        val deliveries: List<PartialDelivery>
    )

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val PARTIAL_INTERVAL_MILLIS = 100L
    }
}

internal fun buildSherpaRecognizerConfig(files: SherpaOnnxModelFiles): OnlineRecognizerConfig =
    OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = files.encoder.absolutePath,
                decoder = files.decoder.absolutePath,
                joiner = files.joiner.absolutePath
            ),
            tokens = files.tokens.absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "zipformer",
            modelingUnit = "cjkchar+bpe",
            bpeVocab = files.bpeVocab.absolutePath
        ),
        enableEndpoint = false,
        decodingMethod = "modified_beam_search",
        maxActivePaths = 4,
        hotwordsFile = files.hotwords.absolutePath,
        hotwordsScore = 0.5f
    )

private object OfficialSherpaRecognizerFactory : SherpaRecognizerFactory {
    override fun create(files: SherpaOnnxModelFiles): SherpaRecognizerApi =
        OfficialSherpaRecognizerApi(
            OnlineRecognizer(
                assetManager = null,
                config = buildSherpaRecognizerConfig(files)
            )
        )
}

private class OfficialSherpaRecognizerApi(
    private val recognizer: OnlineRecognizer
) : SherpaRecognizerApi {
    override fun createStream(): SherpaStreamApi = OfficialSherpaStreamApi(recognizer.createStream())

    override fun isReady(stream: SherpaStreamApi): Boolean = recognizer.isReady(stream.officialStream())

    override fun decode(stream: SherpaStreamApi) {
        recognizer.decode(stream.officialStream())
    }

    override fun resultText(stream: SherpaStreamApi): String = recognizer.getResult(stream.officialStream()).text

    override fun release() {
        recognizer.release()
    }
}

private class OfficialSherpaStreamApi(
    val stream: OnlineStream
) : SherpaStreamApi {
    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        stream.acceptWaveform(samples, sampleRate)
    }

    override fun inputFinished() {
        stream.inputFinished()
    }

    override fun release() {
        stream.release()
    }
}

private fun SherpaStreamApi.officialStream(): OnlineStream =
    (this as? OfficialSherpaStreamApi)?.stream
        ?: throw IllegalArgumentException("Stream was not created by the official sherpa recognizer adapter")
