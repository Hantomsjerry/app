package com.example.myapp.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

internal fun interface SenseVoiceRecognizerFactory {
    fun create(files: SenseVoiceModelFiles): SenseVoiceRecognizerApi
}

internal interface SenseVoiceRecognizerApi {
    fun createStream(): SenseVoiceStreamApi
    fun decode(stream: SenseVoiceStreamApi)
    fun resultText(stream: SenseVoiceStreamApi): String
    fun release()
}

internal interface SenseVoiceStreamApi {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun release()
}

class SenseVoiceSpeechEngine internal constructor(
    private val modelFilesProvider: suspend () -> SenseVoiceModelFiles,
    private val recognizerFactory: SenseVoiceRecognizerFactory,
    private val dispatcher: CoroutineDispatcher,
    private val closeDispatcher: () -> Unit
) : StreamingSpeechEngine {
    constructor(modelStore: SenseVoiceModelStore) : this(
        modelFilesProvider = modelStore::prepare,
        recognizerFactory = OfficialSenseVoiceRecognizerFactory,
        ownedDispatcher = OwnedSpeechDispatcher()
    )

    private constructor(
        modelFilesProvider: suspend () -> SenseVoiceModelFiles,
        recognizerFactory: SenseVoiceRecognizerFactory,
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
    @Volatile
    private var closeFailure: Throwable? = null
    private var recognizer: SenseVoiceRecognizerApi? = null
    private var activeSession: ActiveSession? = null
    private var finishingSession: ActiveSession? = null

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
                    ActiveSession(preparedRecognizer, stream).also { session ->
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
        stateMutex.withLock {
            ensureOpen()
            val session = requireActiveSession()
            withContext(dispatcher) {
                session.stream.acceptWaveform(pcm16ToFloat(samples, sampleCount), SAMPLE_RATE)
            }
        }
    }

    override suspend fun finishSession(): String {
        val session = stateMutex.withLock {
            ensureOpen()
            requireActiveSession().also {
                activeSession = null
                finishingSession = it
            }
        }
        try {
            return withContext(dispatcher + NonCancellable) {
                var primaryFailure: Throwable? = null
                try {
                    session.recognizer.decode(session.stream)
                    session.recognizer.resultText(session.stream).trim()
                } catch (failure: Throwable) {
                    primaryFailure = failure
                    throw failure
                } finally {
                    releaseSessionPreservingFailure(session, primaryFailure)
                }
            }
        } finally {
            stateMutex.withLock {
                if (finishingSession === session) finishingSession = null
            }
        }
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

        awaitCloseCompletion()
        closeFailure?.let { throw it }
    }

    private fun performWinningClose() {
        var failure: Throwable? = null
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
        } finally {
            closeFailure = failure
            closeCompleted.countDown()
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

    private fun ensureOpen() {
        check(!closeStarted.get()) { "Speech recognition engine is closed" }
    }

    private suspend fun prepareRecognizerLocked(): SenseVoiceRecognizerApi {
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
        val sessions = listOfNotNull(activeSession, finishingSession)
        val preparedRecognizer = recognizer
        activeSession = null
        finishingSession = null
        recognizer = null

        withContext(dispatcher + NonCancellable) {
            var failure: Throwable? = null
            listOfNotNull(
                *sessions.map { session -> { releaseSessionPreservingFailure(session, null) } }.toTypedArray(),
                preparedRecognizer?.let { it::release }
            ).forEach { release ->
                try {
                    release()
                } catch (releaseFailure: Throwable) {
                    if (failure == null) failure = releaseFailure else failure.addSuppressed(releaseFailure)
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

    private fun releaseSessionPreservingFailure(session: ActiveSession, primaryFailure: Throwable?) {
        if (session.releaseStarted.compareAndSet(false, true)) {
            releasePreservingFailure(session.stream::release, primaryFailure)
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
        val recognizer: SenseVoiceRecognizerApi,
        val stream: SenseVoiceStreamApi,
        val releaseStarted: AtomicBoolean = AtomicBoolean(false)
    )

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

internal fun buildSenseVoiceRecognizerConfig(files: SenseVoiceModelFiles): OfflineRecognizerConfig =
    OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80, dither = 0.0f),
        modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = files.model.absolutePath,
                language = "auto",
                useInverseTextNormalization = true
            ),
            tokens = files.tokens.absolutePath,
            numThreads = 2,
            provider = "cpu"
        )
    )

private object OfficialSenseVoiceRecognizerFactory : SenseVoiceRecognizerFactory {
    override fun create(files: SenseVoiceModelFiles): SenseVoiceRecognizerApi =
        OfficialSenseVoiceRecognizerApi(
            OfflineRecognizer(
                assetManager = null,
                config = buildSenseVoiceRecognizerConfig(files)
            )
        )
}

private class OfficialSenseVoiceRecognizerApi(
    private val recognizer: OfflineRecognizer
) : SenseVoiceRecognizerApi {
    override fun createStream(): SenseVoiceStreamApi = OfficialSenseVoiceStreamApi(recognizer.createStream())

    override fun decode(stream: SenseVoiceStreamApi) {
        recognizer.decode(stream.officialStream())
    }

    override fun resultText(stream: SenseVoiceStreamApi): String = recognizer.getResult(stream.officialStream()).text

    override fun release() {
        recognizer.release()
    }
}

private class OfficialSenseVoiceStreamApi(
    val stream: OfflineStream
) : SenseVoiceStreamApi {
    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        stream.acceptWaveform(samples, sampleRate)
    }

    override fun release() {
        stream.release()
    }
}

private fun SenseVoiceStreamApi.officialStream(): OfflineStream =
    (this as? OfficialSenseVoiceStreamApi)?.stream
        ?: throw IllegalArgumentException("Stream was not created by the official SenseVoice recognizer adapter")
