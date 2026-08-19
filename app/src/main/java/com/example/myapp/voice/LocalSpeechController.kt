package com.example.myapp.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SpeechFinalizationAttemptSource {
    Manual,
    Timeout
}

internal fun interface SpeechFinalizationAttemptObserver {
    fun onAttempt(source: SpeechFinalizationAttemptSource)

    companion object {
        val None = SpeechFinalizationAttemptObserver {}
    }
}

/**
 * Owns one streaming speech session at a time and keeps AudioRecord callbacks free of inference work.
 */
class LocalSpeechController internal constructor(
    private val pcmRecorder: PcmRecorder,
    private val speechEngine: StreamingSpeechEngine,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val timerDelay: suspend (Long) -> Unit = { delay(it) },
    private val beforeCallbackDelivery: suspend () -> Unit = {},
    private val afterCallbackPermitBeforeEntry: () -> Unit = {},
    private val finalizationAttemptObserver: SpeechFinalizationAttemptObserver =
        SpeechFinalizationAttemptObserver.None,
    private val transcriptNormalizer: (String) -> String = SpeechTranscriptNormalizer::normalize,
    private val onSessionCreated: (Long) -> Unit,
    private val onRecordingStarted: (Long) -> Unit,
    private val onPartialText: (SpeechRecognitionText) -> Unit,
    private val onRecordingStopped: (Long) -> Unit,
    private val onFinalText: (SpeechRecognitionText) -> Unit,
    private val onError: (SpeechRecognitionFailure) -> Unit
) : AutoCloseable {
    private val stateLock = Any()
    private val callbackGate = ReentrantLock()
    private val callbackDepth = ThreadLocal<Int>()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val callbackDeliveries = Channel<CallbackDelivery>(Channel.UNLIMITED)
    private val controllerJob = SupervisorJob(scope.coroutineContext[Job])
    private val controllerScope = CoroutineScope(
        scope.coroutineContext.minusKey(Job) + controllerJob
    )
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closeStarted = AtomicBoolean(false)
    private val closeCompleted = CountDownLatch(1)
    private val processorFailure = AtomicReference<Throwable?>()
    private val processorSession = AtomicReference<Session?>()
    @Volatile
    private var closeFailure: Throwable? = null

    private var generation = 0L
    private var currentSession: Session? = null
    private var timeoutJob: Job? = null
    private var acceptingCommands = true
    private var closed = false

    @Suppress("unused")
    private val callbackProcessor = controllerScope.launch(
        context = callbackDispatcher,
        start = CoroutineStart.UNDISPATCHED
    ) {
        try {
            processCallbacks()
        } finally {
            callbackDeliveries.close()
            while (true) {
                callbackDeliveries.tryReceive().getOrNull()?.result?.complete(false) ?: break
            }
        }
    }

    @Suppress("unused")
    private val commandProcessor = controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
        processCommands()
    }

    fun start(): Long {
        val session = callbackGate.withLock {
            synchronized(stateLock) {
                check(!closed) { "Local speech controller is closed" }
                check(acceptingCommands && controllerJob.isActive) {
                    "Local speech controller processor is unavailable"
                }

                timeoutJob?.cancel()
                currentSession?.invalidateLocked()
                Session(++generation).also { nextSession ->
                    currentSession = nextSession
                    check(enqueueLocked(Command.Start(nextSession))) {
                        "Local speech controller processor is unavailable"
                    }
                }
            }
        }

        dispatchCallback(
            session = session,
            result = session.createdDelivery,
            isAllowed = { isCurrent(session) },
            callback = { onSessionCreated(session.generation) }
        )
        return session.generation
    }

    fun stopAndTranscribe() {
        val session = synchronized(stateLock) { currentSession } ?: return
        requestFinalization(session, SpeechFinalizationAttemptSource.Manual)
    }

    fun cancel() {
        callbackGate.withLock {
            synchronized(stateLock) {
                if (closed) return
                generation++
                currentSession?.invalidateLocked()
                currentSession = null
                timeoutJob?.cancel()
                timeoutJob = null
                if (!acceptingCommands || !controllerJob.isActive) {
                    acceptingCommands = false
                    return
                }
                enqueueLocked(Command.Cancel)
            }
        }
    }

    override fun close() {
        initiateClose()
        if (isInsideCallback()) return
        awaitCloseCompletion()
        closeFailure?.let { throw it }
    }

    private fun initiateClose() {
        if (!closeStarted.compareAndSet(false, true)) return

        callbackGate.withLock {
            synchronized(stateLock) {
                closed = true
                acceptingCommands = false
                generation++
                currentSession?.invalidateLocked()
                currentSession = null
                timeoutJob?.cancel()
                timeoutJob = null
            }
        }
        commands.close()
        commandProcessor.cancel()
        shutdownScope.launch {
            completeClose()
        }
    }

    private suspend fun completeClose() {
        var failure: Throwable? = null
        try {
            commandProcessor.join()
            failure = combineFailures(failure, processorFailure.get())

            callbackDeliveries.close()
            callbackProcessor.cancel()
            drainCallbackDeliveries()
            callbackGate.withLock {
                drainCallbackDeliveries()
            }

            try {
                speechEngine.close()
            } catch (closeError: Throwable) {
                failure = combineFailures(failure, closeError)
            }
        } catch (shutdownError: Throwable) {
            failure = combineFailures(failure, shutdownError)
        } finally {
            controllerJob.cancel()
            closeFailure = failure
            closeCompleted.countDown()
        }
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

    private fun isInsideCallback(): Boolean = (callbackDepth.get() ?: 0) > 0

    private fun requestFinalization(
        session: Session,
        source: SpeechFinalizationAttemptSource
    ) {
        callbackGate.withLock {
            synchronized(stateLock) {
                if (!isCurrentLocked(session) || session.stopClaimed) return
                if (!acceptingCommands || !controllerJob.isActive) {
                    acceptingCommands = false
                    currentSession?.invalidateLocked()
                    currentSession = null
                    return
                }
                session.stopClaimed = true
                finalizationAttemptObserver.onAttempt(source)
                session.stopPartialsLocked()
                timeoutJob?.cancel()
                timeoutJob = null
                enqueueLocked(Command.Stop(session))
            }
        }
    }

    private fun enqueueLocked(command: Command): Boolean {
        if (!acceptingCommands || !controllerJob.isActive) return false
        val result = commands.trySend(command)
        if (result.isFailure) {
            acceptingCommands = false
            currentSession?.invalidateLocked()
            currentSession = null
            return false
        }
        return true
    }

    private suspend fun processCommands() {
        var activeSession: Session? = null
        var processingFailure: Throwable? = null
        try {
            for (command in commands) {
                when (command) {
                    is Command.Start -> {
                        val previous = activeSession
                        if (previous != null) {
                            val cleanupSucceeded = cleanupActiveSession(
                                previous,
                                cancelRecorderResource = true
                            )
                            activeSession = null
                            processorSession.compareAndSet(previous, null)
                            if (!cleanupSucceeded) {
                                publishRecorderFailure(command.session)
                                continue
                            }
                        }
                        processorSession.set(command.session)
                        if (!startSession(command.session)) {
                            processorSession.compareAndSet(command.session, null)
                            continue
                        }
                        activeSession = command.session
                        if (isFinalizationClaimed(command.session)) {
                            finalizeRecording(command.session)
                            activeSession = null
                            processorSession.compareAndSet(command.session, null)
                        }
                    }

                    is Command.Stop -> {
                        if (activeSession === command.session && isCurrent(command.session)) {
                            finalizeRecording(command.session)
                            activeSession = null
                            processorSession.compareAndSet(command.session, null)
                        }
                    }

                    is Command.DecodeFailed -> {
                        if (activeSession === command.session && isCurrent(command.session)) {
                            finalizeDecodeFailure(command.session)
                            activeSession = null
                            processorSession.compareAndSet(command.session, null)
                        }
                    }

                    Command.Cancel -> {
                        activeSession?.let {
                            cleanupActiveSession(it, cancelRecorderResource = true)
                        }
                        processorSession.set(null)
                        activeSession = null
                    }
                }
            }
        } catch (_: CancellationException) {
            // close() and parent-scope cancellation converge on the cleanup path below.
        } catch (failure: Throwable) {
            processingFailure = failure
        } finally {
            val cleanupFailure = processorCleanup(activeSession)
            processorFailure.set(combineFailures(processingFailure, cleanupFailure))
        }
    }

    private suspend fun startSession(session: Session): Boolean {
        if (!session.createdDelivery.await() || !isCurrent(session)) return false

        session.recorderLifecycleStarted.set(true)
        try {
            pcmRecorder.start { samples ->
                if (session.acceptsAudio()) {
                    session.audioChunks.trySend(samples.copyOf())
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            cancelTimeout(session)
            cancelRecorder(session)
            session.stopAudio()
            session.audioChunks.close()
            publishRecorderFailure(session)
            return false
        }

        if (!isCurrent(session)) {
            cleanupActiveSession(session, cancelRecorderResource = true)
            return false
        }

        armTimeout(session)
        if (!publishIfCurrent(session) { onRecordingStarted(session.generation) }) {
            cleanupActiveSession(session, cancelRecorderResource = true)
            return false
        }
        if (!isCurrent(session)) {
            cleanupActiveSession(session, cancelRecorderResource = true)
            return false
        }

        try {
            speechEngine.startSession { rawText -> publishPartial(session, rawText) }
            session.streamActive.set(true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: ModelPreparationException) {
            cancelTimeout(session)
            cancelRecorder(session)
            session.stopAudio()
            session.audioChunks.close()
            cancelStream(session)
            publishFailure(
                session,
                SpeechRecognitionFailureType.Unavailable,
                MODEL_PREPARATION_MESSAGE
            )
            return false
        } catch (_: LinkageError) {
            cancelTimeout(session)
            cancelRecorder(session)
            session.stopAudio()
            session.audioChunks.close()
            cancelStream(session)
            publishFailure(
                session,
                SpeechRecognitionFailureType.Unavailable,
                MODEL_PREPARATION_MESSAGE
            )
            return false
        } catch (_: Exception) {
            cancelTimeout(session)
            cancelRecorder(session)
            session.stopAudio()
            session.audioChunks.close()
            cancelStream(session)
            publishFailure(
                session,
                SpeechRecognitionFailureType.Service,
                TRANSCRIPTION_FAILURE_MESSAGE
            )
            return false
        }

        if (!isCurrent(session)) {
            cleanupActiveSession(session, cancelRecorderResource = true)
            return false
        }

        session.consumerJob = controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            consumeAudio(session)
        }
        return true
    }

    private suspend fun consumeAudio(session: Session) {
        try {
            for (samples in session.audioChunks) {
                speechEngine.acceptSamples(samples)
            }
        } catch (_: CancellationException) {
            // Cancellation is the expected path for cancel, replacement, parent teardown, and close.
        } catch (failure: Exception) {
            if (session.consumerFailure.compareAndSet(null, failure)) {
                prepareDecodeFailure(session)
                commands.trySend(Command.DecodeFailed(session))
            }
        }
    }

    private fun publishPartial(session: Session, rawText: String) {
        if (!session.acceptsPartials() || !isCurrent(session)) return
        val text = transcriptNormalizer(rawText)
        if (text.isBlank()) return
        dispatchCallback(
            session = session,
            result = CompletableDeferred(),
            isAllowed = { isCurrent(session) && session.acceptsPartials() },
            callback = {
                onPartialText(SpeechRecognitionText(session.generation, text))
            }
        )
    }

    private suspend fun finalizeRecording(session: Session) {
        stopPartials(session)
        if (!session.claimRecorderCleanup()) return
        val sampleCount = try {
            withContext(NonCancellable) { pcmRecorder.stop() }
        } catch (_: Exception) {
            session.stopAudio()
            session.audioChunks.close()
            cancelAndJoinConsumer(session)
            cancelStream(session)
            publishRecorderFailure(session)
            session.terminal.set(true)
            return
        }
        session.stopAudio()
        session.audioChunks.close()

        if (!publishIfCurrent(session) { onRecordingStopped(session.generation) }) {
            cancelAndJoinConsumer(session)
            cancelStream(session)
            session.terminal.set(true)
            return
        }

        joinConsumer(session)
        if (session.consumerFailure.get() != null) {
            finalizeDecodeFailure(
                session = session,
                recordingStoppedPublished = true,
                consumerJoined = true
            )
            return
        }
        if (!isCurrent(session)) {
            cancelStream(session)
            session.terminal.set(true)
            return
        }
        if (!PcmRecordingPolicy.isLongEnough(sampleCount)) {
            cancelStream(session)
            publishFailure(
                session,
                SpeechRecognitionFailureType.Audio,
                SHORT_RECORDING_MESSAGE
            )
            session.terminal.set(true)
            return
        }

        val text = try {
            transcriptNormalizer(speechEngine.finishSession().trim())
        } catch (cancellation: CancellationException) {
            cancelStream(session)
            session.terminal.set(true)
            return
        } catch (_: Exception) {
            session.streamActive.set(false)
            publishServiceFailure(session)
            session.terminal.set(true)
            return
        }
        session.streamActive.set(false)
        session.terminal.set(true)

        if (text.isBlank()) {
            publishFailure(
                session,
                SpeechRecognitionFailureType.NoMatch,
                NO_MATCH_MESSAGE
            )
        } else {
            publishIfCurrent(session) {
                onFinalText(SpeechRecognitionText(session.generation, text))
            }
        }
    }

    private suspend fun finalizeDecodeFailure(
        session: Session,
        recordingStoppedPublished: Boolean = false,
        consumerJoined: Boolean = false
    ) {
        if (!session.terminal.compareAndSet(false, true)) return
        cancelTimeout(session)
        stopPartials(session)
        if (session.claimRecorderCleanup()) {
            try {
                withContext(NonCancellable) { pcmRecorder.stop() }
            } catch (_: Exception) {
                // The decode failure remains the primary user-visible failure.
            }
        }
        session.stopAudio()
        session.audioChunks.close()
        discardQueuedAudio(session)
        if (!recordingStoppedPublished) {
            publishIfCurrent(session) { onRecordingStopped(session.generation) }
        }
        if (!consumerJoined) joinConsumer(session)
        discardQueuedAudio(session)
        cancelStream(session)
        val delivered = publishTerminalServiceFailure(session)
        if (!delivered) retireTerminalSession(session)
    }

    private suspend fun cleanupActiveSession(
        session: Session,
        cancelRecorderResource: Boolean
    ): Boolean {
        stopPartials(session)

        var recorderSucceeded = true
        if (cancelRecorderResource) {
            recorderSucceeded = cancelRecorder(session) == null
        }
        session.stopAudio()
        session.audioChunks.close()
        cancelAndJoinConsumer(session)
        cancelStream(session)
        session.terminal.set(true)
        return recorderSucceeded
    }

    private suspend fun cancelAndJoinConsumer(session: Session) {
        session.consumerJob?.cancel()
        joinConsumer(session)
        discardQueuedAudio(session)
    }

    private suspend fun joinConsumer(session: Session) {
        withContext(NonCancellable) {
            listOfNotNull(session.consumerJob).joinAll()
        }
    }

    private suspend fun cancelStream(session: Session): Throwable? {
        if (!session.streamActive.compareAndSet(true, false)) return null
        return try {
            withContext(NonCancellable) { speechEngine.cancelSession() }
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    private suspend fun processorCleanup(activeSession: Session?): Throwable? =
        withContext(NonCancellable) {
            val fallbackSession = callbackGate.withLock {
                synchronized(stateLock) {
                    acceptingCommands = false
                    val session = currentSession
                    activeSession?.invalidateLocked()
                    currentSession?.invalidateLocked()
                    currentSession = null
                    timeoutJob?.cancel()
                    timeoutJob = null
                    session
                }
            }
            commands.close()

            var failure: Throwable? = null
            val session = activeSession ?: processorSession.getAndSet(null) ?: fallbackSession
            if (session != null) {
                failure = combineFailures(failure, cancelRecorder(session))
                session.stopAudio()
                session.audioChunks.close()
                session.consumerJob?.cancel()
                try {
                    joinConsumer(session)
                } catch (consumerError: Throwable) {
                    failure = combineFailures(failure, consumerError)
                }
                discardQueuedAudio(session)
                failure = combineFailures(failure, cancelStream(session))
                session.terminal.set(true)
            }

            callbackDeliveries.close()
            controllerJob.cancel()
            failure
        }

    private suspend fun cancelRecorder(session: Session): Throwable? {
        if (!session.claimRecorderCleanup()) return null
        return try {
            withContext(NonCancellable) { pcmRecorder.cancel() }
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    private fun discardQueuedAudio(session: Session) {
        while (session.audioChunks.tryReceive().isSuccess) {
            // Dropping references releases queued PCM immediately on terminal paths.
        }
    }

    private fun drainCallbackDeliveries() {
        while (true) {
            val delivery = callbackDeliveries.tryReceive().getOrNull() ?: return
            delivery.result.complete(false)
        }
    }

    private fun prepareDecodeFailure(session: Session) {
        callbackGate.withLock {
            synchronized(stateLock) {
                if (!isCurrentLocked(session) || session.terminal.get()) return
                session.stopClaimed = true
                session.stopPartialsLocked()
                timeoutJob?.cancel()
                timeoutJob = null
            }
            session.stopAudio()
            session.audioChunks.close()
        }
    }

    private fun retireTerminalSession(session: Session) {
        callbackGate.withLock {
            synchronized(stateLock) {
                if (currentSession !== session) return
                session.invalidateLocked()
                currentSession = null
                timeoutJob?.cancel()
                timeoutJob = null
            }
        }
    }

    private fun stopPartials(session: Session) {
        callbackGate.withLock {
            session.stopPartialsLocked()
        }
    }

    private fun combineFailures(primary: Throwable?, next: Throwable?): Throwable? {
        if (primary == null) return next
        if (next != null && next !== primary) primary.addSuppressed(next)
        return primary
    }

    private suspend fun publishRecorderFailure(session: Session) {
        publishFailure(
            session,
            SpeechRecognitionFailureType.Audio,
            RECORDER_FAILURE_MESSAGE
        )
    }

    private suspend fun publishServiceFailure(session: Session) {
        publishFailure(
            session,
            SpeechRecognitionFailureType.Service,
            TRANSCRIPTION_FAILURE_MESSAGE
        )
    }

    private suspend fun publishTerminalServiceFailure(session: Session): Boolean {
        if (!session.createdDelivery.await()) return false
        val result = CompletableDeferred<Boolean>()
        dispatchCallback(
            session = session,
            result = result,
            isAllowed = { isCurrent(session) },
            callback = {
                retireTerminalSession(session)
                onError(
                    SpeechRecognitionFailure(
                        session.generation,
                        SpeechRecognitionFailureType.Service,
                        TRANSCRIPTION_FAILURE_MESSAGE
                    )
                )
            }
        )
        return result.await()
    }

    private suspend fun publishFailure(
        session: Session,
        type: SpeechRecognitionFailureType,
        message: String
    ) {
        publishIfCurrent(session) {
            onError(SpeechRecognitionFailure(session.generation, type, message))
        }
    }

    private suspend fun publishIfCurrent(session: Session, callback: () -> Unit): Boolean {
        if (!session.createdDelivery.await()) return false
        val result = CompletableDeferred<Boolean>()
        dispatchCallback(
            session = session,
            result = result,
            isAllowed = { isCurrent(session) },
            callback = callback
        )
        return result.await()
    }

    private fun dispatchCallback(
        session: Session,
        result: CompletableDeferred<Boolean>,
        isAllowed: () -> Boolean,
        callback: () -> Unit
    ) {
        val delivery = CallbackDelivery(session, result, isAllowed, callback)
        if (callbackDeliveries.trySend(delivery).isFailure) result.complete(false)
    }

    private suspend fun processCallbacks() {
        for (delivery in callbackDeliveries) {
            try {
                beforeCallbackDelivery()
                callbackGate.withLock {
                    if (!delivery.isAllowed()) {
                        delivery.result.complete(false)
                        return@withLock
                    }
                    val permit = delivery.session.deliveryToken.tryClaim()
                    if (permit == null || !delivery.isAllowed()) {
                        permit?.release()
                        delivery.result.complete(false)
                        return@withLock
                    }
                    try {
                        afterCallbackPermitBeforeEntry()
                        val previousDepth = callbackDepth.get() ?: 0
                        callbackDepth.set(previousDepth + 1)
                        try {
                            safeCallback(delivery.callback)
                        } finally {
                            if (previousDepth == 0) {
                                callbackDepth.remove()
                            } else {
                                callbackDepth.set(previousDepth)
                            }
                        }
                    } finally {
                        permit.release()
                        delivery.result.complete(true)
                    }
                }
            } catch (cancellation: CancellationException) {
                delivery.result.complete(false)
                throw cancellation
            } catch (_: Exception) {
                delivery.result.complete(false)
            }
        }
    }

    private fun safeCallback(callback: () -> Unit) {
        try {
            callback()
        } catch (_: Exception) {
            // Application callbacks observe state but do not own controller lifecycle.
        }
    }

    private fun isCurrent(session: Session): Boolean = synchronized(stateLock) {
        isCurrentLocked(session)
    }

    private fun isCurrentLocked(session: Session): Boolean =
        !closed && acceptingCommands && controllerJob.isActive && currentSession === session

    private fun isFinalizationClaimed(session: Session): Boolean = synchronized(stateLock) {
        session.stopClaimed
    }

    private fun cancelTimeout(session: Session) {
        synchronized(stateLock) {
            if (currentSession === session) {
                timeoutJob?.cancel()
                timeoutJob = null
            }
        }
    }

    private fun armTimeout(session: Session) {
        val nextTimeout = controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                timerDelay(PcmRecordingPolicy.MAX_DURATION_MILLIS)
                requestFinalization(session, SpeechFinalizationAttemptSource.Timeout)
            } catch (_: CancellationException) {
                // Session replacement, cancellation, and close all retire the timer.
            } catch (_: Exception) {
                // An injected timer cannot own the controller lifecycle.
            }
        }
        synchronized(stateLock) {
            if (isCurrentLocked(session) && !session.stopClaimed) {
                timeoutJob = nextTimeout
            } else {
                nextTimeout.cancel()
            }
        }
    }

    private class Session(val generation: Long) {
        val audioChunks = Channel<ShortArray>(Channel.UNLIMITED)
        val createdDelivery = CompletableDeferred<Boolean>()
        val deliveryToken = DeliveryToken()
        val valid = AtomicBoolean(true)
        val audioAllowed = AtomicBoolean(true)
        val partialsAllowed = AtomicBoolean(true)
        val recorderLifecycleStarted = AtomicBoolean(false)
        val recorderCleanupClaimed = AtomicBoolean(false)
        val streamActive = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val consumerFailure = AtomicReference<Throwable?>()
        var consumerJob: Job? = null
        var stopClaimed = false

        fun acceptsAudio(): Boolean = valid.get() && audioAllowed.get()

        fun acceptsPartials(): Boolean = valid.get() && partialsAllowed.get()

        fun stopAudio() {
            audioAllowed.set(false)
        }

        fun stopPartialsLocked() {
            partialsAllowed.set(false)
        }

        fun claimRecorderCleanup(): Boolean =
            recorderLifecycleStarted.get() && recorderCleanupClaimed.compareAndSet(false, true)

        fun invalidateLocked() {
            valid.set(false)
            stopAudio()
            stopPartialsLocked()
            deliveryToken.invalidate()
        }
    }

    private class DeliveryToken {
        private val state = AtomicReference(DeliveryState(active = true, inFlight = 0))

        fun tryClaim(): DeliveryPermit? {
            while (true) {
                val current = state.get()
                if (!current.active) return null
                val claimed = current.copy(inFlight = current.inFlight + 1)
                if (state.compareAndSet(current, claimed)) return DeliveryPermit(this)
            }
        }

        fun invalidate() {
            while (true) {
                val current = state.get()
                if (!current.active) return
                if (state.compareAndSet(current, current.copy(active = false))) return
            }
        }

        fun release() {
            while (true) {
                val current = state.get()
                check(current.inFlight > 0) { "Callback delivery permit underflow" }
                if (state.compareAndSet(current, current.copy(inFlight = current.inFlight - 1))) return
            }
        }
    }

    private class DeliveryPermit(private val token: DeliveryToken) {
        fun release() {
            token.release()
        }
    }

    private data class DeliveryState(val active: Boolean, val inFlight: Int)

    private data class CallbackDelivery(
        val session: Session,
        val result: CompletableDeferred<Boolean>,
        val isAllowed: () -> Boolean,
        val callback: () -> Unit
    )

    private sealed interface Command {
        data class Start(val session: Session) : Command
        data class Stop(val session: Session) : Command
        data class DecodeFailed(val session: Session) : Command
        data object Cancel : Command
    }

    private companion object {
        const val SHORT_RECORDING_MESSAGE = "录音时间太短，请重新录音"
        const val RECORDER_FAILURE_MESSAGE = "麦克风录音失败，请重试"
        const val MODEL_PREPARATION_MESSAGE = "本地语音模型准备失败"
        const val TRANSCRIPTION_FAILURE_MESSAGE = "本地语音识别失败，请重试"
        const val NO_MATCH_MESSAGE = "未识别到有效语音"
    }
}
