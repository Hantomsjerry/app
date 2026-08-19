package com.example.myapp.voice

import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalSpeechControllerTest {
    @Test
    fun manualStopReturnsPromptlyThenFinalizesOnce() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(result = "recognized")
        val events = mutableListOf<String>()
        val controller = controller(recorder, engine, events)

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()

        assertEquals(0, recorder.stopCalls)
        runCurrent()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.finishCalls)
        assertEquals(
            listOf(
                "created:$generation",
                "recording:$generation",
                "stopped:$generation",
                "final:$generation:recognized"
            ),
            events
        )
    }

    @Test
    fun timeoutFinalizesAtExactlyTwentySeconds() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val controller = controller(recorder, engine)

        controller.start()
        runCurrent()
        advanceTimeBy(PcmRecordingPolicy.MAX_DURATION_MILLIS - 1)
        runCurrent()
        assertEquals(0, recorder.stopCalls)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.finishCalls)
    }

    @Test
    fun streamCreationTimeCountsInsideTheTwentySecondRecordingWindow() = runTest {
        val preparationEntered = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        val attempts = mutableListOf<SpeechFinalizationAttemptSource>()
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(startBlock = {
            preparationEntered.complete(Unit)
            releasePreparation.await()
        })
        val controller = controller(
            recorder,
            engine,
            finalizationAttemptObserver = SpeechFinalizationAttemptObserver(attempts::add)
        )

        controller.start()
        runCurrent()
        preparationEntered.await()
        assertEquals(1, recorder.startCalls)
        assertEquals(0, recorder.stopCalls)

        advanceTimeBy(PcmRecordingPolicy.MAX_DURATION_MILLIS)
        runCurrent()
        assertEquals(listOf(SpeechFinalizationAttemptSource.Timeout), attempts)

        releasePreparation.complete(Unit)
        runCurrent()
        assertEquals(1, recorder.startCalls)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.finishCalls)
    }

    @Test
    fun cancelDuringStreamCreationCancelsStartedRecorderAndTimeout() = runTest {
        val preparationEntered = CompletableDeferred<Unit>()
        val releasePreparation = CompletableDeferred<Unit>()
        val timerStarts = AtomicInteger()
        val attempts = mutableListOf<SpeechFinalizationAttemptSource>()
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(startBlock = {
            preparationEntered.complete(Unit)
            releasePreparation.await()
        })
        val controller = controller(
            recorder,
            engine,
            timerDelay = {
                timerStarts.incrementAndGet()
                awaitCancellation()
            },
            finalizationAttemptObserver = SpeechFinalizationAttemptObserver(attempts::add)
        )

        controller.start()
        runCurrent()
        preparationEntered.await()
        controller.cancel()
        runCurrent()

        assertEquals(1, recorder.startCalls)
        assertEquals(1, timerStarts.get())

        releasePreparation.complete(Unit)
        runCurrent()

        assertEquals(1, recorder.cancelCalls)
        assertEquals(emptyList<SpeechFinalizationAttemptSource>(), attempts)
    }

    @Test
    fun manualStopAndTimeoutRaceClaimsOneFinalization() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val attempts = mutableListOf<SpeechFinalizationAttemptSource>()
        val controller = controller(
            recorder,
            engine,
            finalizationAttemptObserver = SpeechFinalizationAttemptObserver(attempts::add)
        )

        controller.start()
        runCurrent()
        advanceTimeBy(PcmRecordingPolicy.MAX_DURATION_MILLIS)
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(listOf(SpeechFinalizationAttemptSource.Manual), attempts)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.finishCalls)
        assertFalse(recorder.overlappingCalls)
    }

    @Test
    fun recognizerEndpointCannotFinalizeBeforeManualStop() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(result = "Lv2强度调到60")
        val attempts = mutableListOf<SpeechFinalizationAttemptSource>()
        val finals = mutableListOf<SpeechRecognitionResult>()
        val controller = controller(
            recorder = recorder,
            engine = engine,
            finalizationAttemptObserver = SpeechFinalizationAttemptObserver(attempts::add),
            onFinalResult = finals::add
        )

        val generation = controller.start()
        runCurrent()

        assertTrue(attempts.isEmpty())
        assertEquals(0, recorder.stopCalls)
        assertEquals(0, engine.finishCalls)

        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(listOf(SpeechFinalizationAttemptSource.Manual), attempts)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.finishCalls)
        assertEquals(generation, finals.single().generation)
        assertEquals("Lv2强度调到60", finals.single().correction.correctedText)
    }

    @Test
    fun immediateStopWaitsForAsynchronousStartupThenFinalizes() = runTest {
        val startEntered = CompletableDeferred<Unit>()
        val startRelease = CompletableDeferred<Unit>()
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(startBlock = {
            startEntered.complete(Unit)
            startRelease.await()
        })
        val controller = controller(recorder, engine)

        controller.start()
        runCurrent()
        startEntered.await()
        controller.stopAndTranscribe()
        runCurrent()
        assertEquals(1, recorder.startCalls)

        startRelease.complete(Unit)
        runCurrent()

        assertEquals(1, recorder.startCalls)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.finishCalls)
    }

    @Test
    fun doubleStopDoesNotRepeatRecorderOrEngineFinalization() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val controller = controller(recorder, engine)

        controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.finishCalls)
    }

    @Test
    fun staleQueuedStopCannotFinalizeReplacementGeneration() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val finals = mutableListOf<SpeechRecognitionResult>()
        val controller = controller(recorder, engine, onFinalResult = finals::add)

        val staleGeneration = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        val currentGeneration = controller.start()
        runCurrent()

        assertTrue(currentGeneration > staleGeneration)
        assertEquals(0, recorder.stopCalls)
        assertEquals(0, engine.finishCalls)
        assertEquals(listOf("start", "cancel", "start"), recorder.operations)

        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.finishCalls)
        assertEquals(currentGeneration, finals.single().generation)
    }

    @Test
    fun sessionCreatedPrecedesRecorderAndStreamCreation() = runTest {
        val order = mutableListOf<String>()
        val recorder = FakeRecorder(startBlock = { order += "recorder-start" })
        val engine = FakeStreamingEngine(
            startBlock = { order += "engine-start" },
            operationLog = order
        )
        val controller = controller(
            recorder,
            engine,
            onSessionCreated = { order += "session-created" },
            onRecordingStarted = { order += "recording-started" }
        )

        controller.start()
        runCurrent()

        assertEquals(
            listOf("session-created", "recorder-start", "recording-started", "engine-start"),
            order
        )
    }

    @Test
    fun recorderBuffersFirstAudioWhileOnlineStreamIsBeingCreated() = runTest {
        val streamCreationEntered = CompletableDeferred<Unit>()
        val releaseStreamCreation = CompletableDeferred<Unit>()
        val firstChunk = ShortArray(PcmRecordingPolicy.MIN_SAMPLES) { 7 }
        val recorder = FakeRecorder(chunks = listOf(firstChunk))
        val engine = FakeStreamingEngine(startBlock = {
            streamCreationEntered.complete(Unit)
            releaseStreamCreation.await()
        })
        val controller = controller(recorder, engine)

        controller.start()
        runCurrent()
        streamCreationEntered.await()

        assertEquals(1, recorder.startCalls)
        assertTrue(engine.accepted.isEmpty())

        releaseStreamCreation.complete(Unit)
        runCurrent()

        assertTrue(engine.accepted.isEmpty())
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(listOf(firstChunk.toList()), engine.accepted.map(ShortArray::toList))
    }

    @Test
    fun recorderChunksAreSubmittedOnceOnlyAfterManualStop() = runTest {
        val chunks = listOf(
            ShortArray(1_600) { 1 },
            ShortArray(1_600) { 2 },
            ShortArray(1_600) { 3 }
        )
        val recorder = FakeRecorder(chunks = chunks)
        val engine = FakeStreamingEngine(recorderCallbackActive = recorder.callbackActive)
        val controller = controller(recorder, engine)

        controller.start()
        runCurrent()

        assertTrue(engine.accepted.isEmpty())
        controller.stopAndTranscribe()
        runCurrent()

        val submitted = engine.accepted.single()
        assertEquals(4_800, submitted.size)
        assertTrue(submitted.take(1_600).all { it.toInt() == 1 })
        assertTrue(submitted.slice(1_600 until 3_200).all { it.toInt() == 2 })
        assertTrue(submitted.drop(3_200).all { it.toInt() == 3 })
        assertFalse(engine.acceptedFromRecorderCallback.get())
    }

    @Test
    fun partialTextIsNotPublishedToUi() = runTest {
        val partials = mutableListOf<SpeechRecognitionText>()
        val engine = FakeStreamingEngine(partialText = "把 l v 1 灵敏度改为 32")
        val controller = controller(FakeRecorder(), engine, onPartialText = partials::add)

        val generation = controller.start()
        runCurrent()

        assertTrue(partials.isEmpty())
    }

    @Test
    fun recordingStoppedPrecedesQueueDrainAndFinalText() = runTest {
        val acceptEntered = CompletableDeferred<Unit>()
        val acceptRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val engine = FakeStreamingEngine(
            acceptBlock = {
                events += "accept-entered"
                acceptEntered.complete(Unit)
                withContext(NonCancellable) { acceptRelease.await() }
                events += "accept-finished"
            },
            finishBlock = {
                events += "finish"
                "done"
            }
        )
        val controller = controller(FakeRecorder(), engine, events)

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()
        acceptEntered.await()

        assertTrue(events.contains("stopped:$generation"))
        assertFalse(events.contains("finish"))
        assertFalse(events.any { it.startsWith("final:") })

        acceptRelease.complete(Unit)
        runCurrent()

        assertTrue(events.indexOf("stopped:$generation") < events.indexOf("accept-finished"))
        assertTrue(events.indexOf("accept-finished") < events.indexOf("finish"))
        assertTrue(events.indexOf("finish") < events.indexOf("final:$generation:done"))
    }

    @Test
    fun manualStopDecodeFailureUsesOneTerminalCleanupAndReleasesBufferedAudio() = runTest {
        val acceptEntered = CompletableDeferred<Unit>()
        val releaseAccept = CompletableDeferred<Unit>()
        val attempts = mutableListOf<SpeechFinalizationAttemptSource>()
        val events = mutableListOf<String>()
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(
            acceptBlock = {
                acceptEntered.complete(Unit)
                withContext(NonCancellable) { releaseAccept.await() }
            },
            acceptFailure = IllegalStateException("decode")
        )
        val controller = controller(
            recorder,
            engine,
            events,
            finalizationAttemptObserver = SpeechFinalizationAttemptObserver(attempts::add)
        )

        val generation = controller.start()
        runCurrent()
        val failedSession = currentSession(controller)
            ?: throw AssertionError("Expected an active session")
        controller.stopAndTranscribe()
        runCurrent()
        acceptEntered.await()

        assertEquals(1, events.count { it == "stopped:$generation" })
        assertTrue(events.none { it.startsWith("error:") })

        releaseAccept.complete(Unit)
        runCurrent()

        assertEquals(1, engine.accepted.size)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.cancelCalls)
        assertEquals(0, engine.finishCalls)
        assertEquals(1, events.count { it == "stopped:$generation" })
        assertEquals(1, events.count { it == "error:$generation:${SpeechRecognitionFailureType.Service}" })
        assertEquals(null, pcmBuffer(failedSession).take())

        controller.stopAndTranscribe()
        advanceTimeBy(PcmRecordingPolicy.MAX_DURATION_MILLIS + 1)
        runCurrent()

        assertEquals(listOf(SpeechFinalizationAttemptSource.Manual), attempts)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.cancelCalls)
        assertEquals(1, events.count { it.startsWith("error:") })
    }

    @Test
    fun partialTextNeverPublishesAfterStop() = runTest {
        assertPendingPartialIsSuppressed { controller -> controller.stopAndTranscribe() }
    }

    @Test
    fun partialTextNeverPublishesAfterCancel() = runTest {
        assertPendingPartialIsSuppressed(LocalSpeechController::cancel)
    }

    @Test
    fun partialTextNeverPublishesAfterNewSession() = runTest {
        assertPendingPartialIsSuppressed { controller -> controller.start() }
    }

    @Test
    fun shortRecordingCancelsStreamAndNeverFinishesIt() = runTest {
        val recorder = FakeRecorder(chunks = listOf(ShortArray(PcmRecordingPolicy.MIN_SAMPLES - 1)))
        val engine = FakeStreamingEngine()
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(recorder, engine, onError = errors::add)

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(0, engine.finishCalls)
        assertEquals(1, engine.cancelCalls)
        assertEquals(
            SpeechRecognitionFailure(
                generation,
                SpeechRecognitionFailureType.Audio,
                "录音时间太短，请重新录音"
            ),
            errors.single()
        )
    }

    @Test
    fun streamCreationFailureCancelsRecorderAndBufferedAudio() = runTest {
        val order = mutableListOf<String>()
        val recorder = FakeRecorder(startBlock = { order += "recorder-start" })
        val engine = FakeStreamingEngine(
            startBlock = { order += "model-prepare" },
            startFailure = ModelPreparationException("model")
        )
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(recorder, engine, onError = errors::add)

        val generation = controller.start()
        runCurrent()

        assertEquals(listOf("recorder-start", "model-prepare"), order)
        assertEquals(1, recorder.startCalls)
        assertEquals(1, recorder.cancelCalls)
        assertEquals(
            SpeechRecognitionFailure(
                generation,
                SpeechRecognitionFailureType.Unavailable,
                "本地语音模型准备失败"
            ),
            errors.single()
        )
    }

    @Test
    fun linkageFailureIsUnavailableAndTheNextAttemptCanRecord() = runTest {
        var failFirstStart = true
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(startBlock = {
            if (failFirstStart) {
                failFirstStart = false
                throw UnsatisfiedLinkError("sherpa-onnx-jni")
            }
        })
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val recordingGenerations = mutableListOf<Long>()
        val finals = mutableListOf<SpeechRecognitionResult>()
        val controller = controller(
            recorder,
            engine,
            onRecordingStarted = recordingGenerations::add,
            onFinalResult = finals::add,
            onError = errors::add
        )

        val failedGeneration = controller.start()
        runCurrent()

        assertEquals(1, recorder.startCalls)
        assertEquals(1, recorder.cancelCalls)
        assertEquals(0, engine.finishCalls)
        assertEquals(listOf(failedGeneration), recordingGenerations)
        assertTrue(finals.isEmpty())
        assertEquals(
            SpeechRecognitionFailure(
                failedGeneration,
                SpeechRecognitionFailureType.Unavailable,
                "\u672c\u5730\u8bed\u97f3\u6a21\u578b\u51c6\u5907\u5931\u8d25"
            ),
            errors.single()
        )

        val retryGeneration = controller.start()
        runCurrent()

        assertEquals(2, engine.startCalls)
        assertEquals(2, recorder.startCalls)
        assertEquals(listOf(failedGeneration, retryGeneration), recordingGenerations)
        assertEquals(1, errors.size)
    }

    @Test
    fun finalResultPreservesRawTextBeforeCorrection() = runTest {
        val finals = mutableListOf<SpeechRecognitionResult>()
        val controller = controller(
            FakeRecorder(),
            FakeStreamingEngine(result = "set l v 1 sensitivity to 32"),
            onFinalResult = finals::add
        )

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(generation, finals.single().generation)
        assertEquals("set l v 1 sensitivity to 32", finals.single().correction.rawText)
        assertEquals("set l v 1 sensitivity to 32", finals.single().correction.correctedText)
    }

    @Test
    fun decodeFailureStopsRecorderCancelsStreamAndPublishesServiceError() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(acceptFailure = IllegalStateException("decode"))
        val events = mutableListOf<String>()
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(recorder, engine, events, onError = errors::add)

        val generation = controller.start()
        runCurrent()

        assertEquals(0, recorder.stopCalls)
        assertTrue(errors.isEmpty())
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.cancelCalls)
        assertEquals(0, engine.finishCalls)
        assertEquals(1, errors.size)
        assertEquals(
            SpeechRecognitionFailure(
                generation,
                SpeechRecognitionFailureType.Service,
                "本地语音识别失败，请重试"
            ),
            errors.single()
        )
        assertTrue(events.indexOf("stopped:$generation") >= 0)
    }

    @Test
    fun decodeFailureAfterManualStopCancelsTimeoutAndReleasesBufferedAudio() = runTest {
        val attempts = mutableListOf<SpeechFinalizationAttemptSource>()
        val recorder = FakeRecorder(
            chunks = listOf(
                ShortArray(1_600) { 1 },
                ShortArray(1_600) { 2 },
                ShortArray(1_600) { 3 }
            )
        )
        val engine = FakeStreamingEngine(acceptFailure = IllegalStateException("decode"))
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(
            recorder,
            engine,
            finalizationAttemptObserver = SpeechFinalizationAttemptObserver(attempts::add),
            onError = errors::add
        )

        controller.start()
        runCurrent()
        val failedSession = currentSession(controller)
            ?: throw AssertionError("Expected an active session")
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.accepted.size)
        assertEquals(1, errors.size)
        assertEquals(null, pcmBuffer(failedSession).take())

        controller.stopAndTranscribe()
        advanceTimeBy(PcmRecordingPolicy.MAX_DURATION_MILLIS + 1)
        runCurrent()

        assertEquals(listOf(SpeechFinalizationAttemptSource.Manual), attempts)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.cancelCalls)
        assertEquals(0, engine.finishCalls)
        assertEquals(1, engine.accepted.size)
        assertEquals(1, errors.size)
    }

    @Test
    fun recorderStartFailureMapsToAudioAndCancelsStream() = runTest {
        val recorder = FakeRecorder(startFailure = PcmRecordingException("start"))
        val engine = FakeStreamingEngine()
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(recorder, engine, onError = errors::add)

        val generation = controller.start()
        runCurrent()

        assertEquals(0, engine.startCalls)
        assertEquals(0, engine.cancelCalls)
        assertEquals(
            SpeechRecognitionFailure(
                generation,
                SpeechRecognitionFailureType.Audio,
                "麦克风录音失败，请重试"
            ),
            errors.single()
        )
    }

    @Test
    fun recorderStopFailureMapsToAudioAndCancelsStream() = runTest {
        val recorder = FakeRecorder(stopFailure = PcmRecordingException("read"))
        val engine = FakeStreamingEngine()
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(recorder, engine, onError = errors::add)

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(1, engine.cancelCalls)
        assertEquals(0, engine.finishCalls)
        assertEquals(
            SpeechRecognitionFailure(
                generation,
                SpeechRecognitionFailureType.Audio,
                "麦克风录音失败，请重试"
            ),
            errors.single()
        )
    }

    @Test
    fun finishFailureMapsToServiceOnce() = runTest {
        val engine = FakeStreamingEngine(finishFailure = IllegalStateException("native"))
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(FakeRecorder(), engine, onError = errors::add)

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(
            listOf(
                SpeechRecognitionFailure(
                    generation,
                    SpeechRecognitionFailureType.Service,
                    "本地语音识别失败，请重试"
                )
            ),
            errors
        )
    }

    @Test
    fun blankFinalMapsToNoMatch() = runTest {
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(
            FakeRecorder(),
            FakeStreamingEngine(result = " \n\t "),
            onError = errors::add
        )

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(
            SpeechRecognitionFailure(
                generation,
                SpeechRecognitionFailureType.NoMatch,
                "未识别到有效语音"
            ),
            errors.single()
        )
    }

    @Test
    fun nonblankFinalIsTrimmed() = runTest {
        val finals = mutableListOf<SpeechRecognitionResult>()
        val controller = controller(
            FakeRecorder(),
            FakeStreamingEngine(result = "  recognized text \n"),
            onFinalResult = finals::add
        )

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals("recognized text", finals.single().correction.rawText)
        assertEquals("recognized text", finals.single().correction.correctedText)
        assertEquals(generation, finals.single().generation)
    }

    @Test
    fun finalResultContainsRawAndCorrectedText() = runTest {
        val finals = mutableListOf<SpeechRecognitionResult>()
        val controller = controller(
            FakeRecorder(),
            FakeStreamingEngine(result = "将绿二强度调到六十"),
            correct = { raw -> correction(raw, "将Lv2强度调到60") },
            onFinalResult = finals::add
        )

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(
            SpeechRecognitionResult(
                generation,
                correction("将绿二强度调到六十", "将Lv2强度调到60")
            ),
            finals.single()
        )
    }

    @Test
    fun ambiguousCorrectionIsDeliveredToTheConsumer() = runTest {
        val finals = mutableListOf<SpeechRecognitionResult>()
        val controller = controller(
            FakeRecorder(),
            FakeStreamingEngine(result = "模糊命令"),
            correct = { raw -> correction(raw, "模糊命令", ambiguous = true) },
            onFinalResult = finals::add
        )

        controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertTrue(finals.single().correction.ambiguous)
    }

    @Test
    fun correctionFailureMapsToServiceError() = runTest {
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(
            FakeRecorder(),
            FakeStreamingEngine(result = "recognized"),
            correct = { throw IllegalStateException("correction") },
            onError = errors::add
        )

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(
            SpeechRecognitionFailure(
                generation,
                SpeechRecognitionFailureType.Service,
                "本地语音识别失败，请重试"
            ),
            errors.single()
        )
    }

    @Test
    fun finalCorrectionWritesRawCorrectedAmbiguityElapsedAndReplacementsToDebugLog() = runTest {
        val logs = mutableListOf<String>()
        val controller = controller(
            FakeRecorder(),
            FakeStreamingEngine(result = "将绿二强度调到六十"),
            correct = { raw ->
                correction(
                    raw = raw,
                    corrected = "将Lv2强度调到60",
                    ambiguous = true,
                    replacements = listOf(
                        CorrectionReplacement(
                            start = 1,
                            endExclusive = 3,
                            source = "绿二",
                            replacement = "Lv2",
                            score = 0.9f,
                            reason = CorrectionReason.PINYIN
                        )
                    )
                )
            },
            debugLog = logs::add
        )

        controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        val log = logs.single()
        assertTrue(log.startsWith("voice raw=将绿二强度调到六十 corrected=将Lv2强度调到60 ambiguous=true elapsedMs="))
        assertTrue(log.contains("replacements=[CorrectionReplacement(start=1, endExclusive=3, source=绿二, replacement=Lv2, score=0.9, reason=PINYIN)]"))
    }

    @Test
    fun cancelDuringRecordingCancelsRecorderAndStreamWithoutResultOrFailure() = runTest {
        val events = mutableListOf<String>()
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val controller = controller(recorder, engine, events)

        controller.start()
        runCurrent()
        controller.cancel()
        runCurrent()

        assertEquals(1, recorder.cancelCalls)
        assertEquals(1, engine.cancelCalls)
        assertTrue(events.none { it.startsWith("final:") || it.startsWith("error:") })
    }

    @Test
    fun cancelDuringBlockedDecodeSuppressesLatePartialAndFailure() = runTest {
        val acceptEntered = CompletableDeferred<Unit>()
        val acceptRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val engine = FakeStreamingEngine(
            partialText = "late",
            acceptBlock = {
                acceptEntered.complete(Unit)
                withContext(NonCancellable) { acceptRelease.await() }
            }
        )
        val controller = controller(FakeRecorder(), engine, events)

        controller.start()
        runCurrent()
        acceptEntered.await()
        controller.cancel()
        runCurrent()
        acceptRelease.complete(Unit)
        runCurrent()

        assertTrue(events.none { it.startsWith("partial:") || it.startsWith("final:") || it.startsWith("error:") })
    }

    @Test
    fun staleFinalAfterNewStartCannotPublish() = runTest {
        val finishEntered = CompletableDeferred<Unit>()
        val finishRelease = CompletableDeferred<Unit>()
        val finishCalls = AtomicInteger()
        val finals = mutableListOf<SpeechRecognitionResult>()
        val engine = FakeStreamingEngine(finishBlock = {
            if (finishCalls.incrementAndGet() == 1) {
                finishEntered.complete(Unit)
                withContext(NonCancellable) { finishRelease.await() }
            }
            "result"
        })
        val controller = controller(FakeRecorder(), engine, onFinalResult = finals::add)

        val staleGeneration = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()
        finishEntered.await()
        val currentGeneration = controller.start()
        finishRelease.complete(Unit)
        runCurrent()

        assertTrue(currentGeneration > staleGeneration)
        assertTrue(finals.none { it.generation == staleGeneration })
    }

    @Test
    fun startingNewSessionCancelsPreviousRecorderAndStreamBeforeStartingAgain() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val controller = controller(recorder, engine)

        controller.start()
        runCurrent()
        controller.start()
        runCurrent()

        assertEquals(listOf("start", "cancel", "start"), recorder.operations)
        assertEquals(2, engine.startCalls)
        assertEquals(1, engine.cancelCalls)
        assertFalse(recorder.overlappingCalls)
    }

    @Test
    fun closeIsIdempotentSuppressesCallbacksAndPreventsStart() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val events = mutableListOf<String>()
        val controller = controller(recorder, engine, events)

        controller.start()
        runCurrent()
        closeAndDrain(controller)
        controller.close()

        assertEquals(1, engine.closeCalls)
        assertEquals(1, recorder.cancelCalls)
        assertTrue(events.none { it.startsWith("final:") || it.startsWith("error:") })
        assertThrows(IllegalStateException::class.java) { controller.start() }
    }

    @Test
    fun callbackCannotBeginAfterConcurrentInvalidationReturns() {
        val parentJob = SupervisorJob()
        val hookEntered = CountDownLatch(1)
        val releaseHook = CountDownLatch(1)
        val callbackStarted = CountDownLatch(1)
        val invalidationReturned = CountDownLatch(1)
        val callbackStartedAfterInvalidation = AtomicBoolean(false)
        val callbackOrder = AtomicInteger()
        val invalidationOrder = AtomicInteger()
        val sequence = AtomicInteger()
        val controller = LocalSpeechController(
            pcmRecorder = FakeRecorder(),
            speechEngine = FakeStreamingEngine(),
            scope = CoroutineScope(parentJob + Dispatchers.Default),
            callbackDispatcher = Dispatchers.Default,
            timerDelay = { awaitCancellation() },
            correctTranscript = { raw -> correction(raw, raw) },
            afterCallbackPermitBeforeEntry = {
                hookEntered.countDown()
                check(releaseHook.await(5, TimeUnit.SECONDS)) { "callback hook was not released" }
            },
            onSessionCreated = {
                callbackStartedAfterInvalidation.set(invalidationReturned.count == 0L)
                callbackOrder.set(sequence.incrementAndGet())
                callbackStarted.countDown()
            },
            onRecordingStarted = {},
            onPartialText = {},
            onRecordingStopped = {},
            onFinalResult = {},
            onError = {}
        )
        val invalidator = Thread {
            controller.cancel()
            invalidationOrder.set(sequence.incrementAndGet())
            invalidationReturned.countDown()
        }

        try {
            controller.start()
            assertTrue(hookEntered.await(1, TimeUnit.SECONDS))
            invalidator.start()
            assertFalse(invalidationReturned.await(150, TimeUnit.MILLISECONDS))

            releaseHook.countDown()
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))
            assertTrue(invalidationReturned.await(1, TimeUnit.SECONDS))
            assertFalse(callbackStartedAfterInvalidation.get())
            assertTrue(callbackOrder.get() < invalidationOrder.get())
        } finally {
            releaseHook.countDown()
            invalidator.join(5_000)
            controller.close()
            parentJob.cancel()
        }
    }

    @Test
    fun concurrentCloseWaitsForRecorderThenStreamCleanupBeforeClosingEngine() {
        val parentJob = SupervisorJob()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val recordingStarted = CountDownLatch(1)
        val recorderCleanupEntered = CountDownLatch(1)
        val releaseRecorderCleanup = CompletableDeferred<Unit>()
        val closeReturned = CountDownLatch(2)
        val closeFailures = ConcurrentLinkedQueue<Throwable>()
        val recorder = FakeRecorder(cancelBlock = {
            order += "recorder-cancel-enter"
            recorderCleanupEntered.countDown()
            withContext(NonCancellable) { releaseRecorderCleanup.await() }
            order += "recorder-cancel-exit"
        })
        val engine = FakeStreamingEngine(
            cancelBlock = { order += "stream-cancel" },
            closeBlock = { order += "engine-close" }
        )
        val controller = realController(
            parentJob = parentJob,
            recorder = recorder,
            engine = engine,
            onRecordingStarted = { recordingStarted.countDown() }
        )
        val first = closeThread(controller, closeReturned, closeFailures)
        val second = closeThread(controller, closeReturned, closeFailures)

        try {
            controller.start()
            assertTrue(recordingStarted.await(1, TimeUnit.SECONDS))
            first.start()
            assertTrue(recorderCleanupEntered.await(1, TimeUnit.SECONDS))
            second.start()

            assertFalse(closeReturned.await(150, TimeUnit.MILLISECONDS))
            assertEquals(0, engine.cancelCalls)
            assertEquals(0, engine.closeCalls)

            releaseRecorderCleanup.complete(Unit)
            assertTrue(closeReturned.await(2, TimeUnit.SECONDS))
            assertTrue(closeFailures.isEmpty())
            assertEquals(1, recorder.cancelCalls)
            assertEquals(1, engine.cancelCalls)
            assertEquals(1, engine.closeCalls)
            assertTrue(order.indexOf("recorder-cancel-exit") < order.indexOf("stream-cancel"))
            assertTrue(order.indexOf("stream-cancel") < order.indexOf("engine-close"))
        } finally {
            releaseRecorderCleanup.complete(Unit)
            first.join(5_000)
            second.join(5_000)
            parentJob.cancel()
        }
    }

    @Test
    fun closeWaitsForBlockedFinalSubmissionBeforeEngineCleanup() {
        val parentJob = SupervisorJob()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val recordingStarted = CountDownLatch(1)
        val acceptEntered = CountDownLatch(1)
        val releaseAccept = CompletableDeferred<Unit>()
        val recorderCleaned = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeFailures = ConcurrentLinkedQueue<Throwable>()
        val recorder = FakeRecorder(stopBlock = {
            order += "recorder-stop"
            recorderCleaned.countDown()
        })
        val engine = FakeStreamingEngine(
            acceptBlock = {
                acceptEntered.countDown()
                withContext(NonCancellable) { releaseAccept.await() }
                order += "accept-exit"
            },
            cancelBlock = { order += "stream-cancel" },
            closeBlock = { order += "engine-close" }
        )
        val controller = realController(
            parentJob = parentJob,
            recorder = recorder,
            engine = engine,
            onRecordingStarted = { recordingStarted.countDown() }
        )
        val closer = closeThread(controller, closeReturned, closeFailures)

        try {
            controller.start()
            assertTrue(recordingStarted.await(1, TimeUnit.SECONDS))
            controller.stopAndTranscribe()
            assertTrue(acceptEntered.await(1, TimeUnit.SECONDS))
            assertTrue(recorderCleaned.await(1, TimeUnit.SECONDS))
            closer.start()

            assertFalse(closeReturned.await(150, TimeUnit.MILLISECONDS))
            assertEquals(0, engine.closeCalls)

            releaseAccept.complete(Unit)
            assertTrue(closeReturned.await(2, TimeUnit.SECONDS))
            assertTrue(closeFailures.isEmpty())
            assertEquals(1, engine.closeCalls)
            assertTrue(order.indexOf("recorder-stop") < order.indexOf("accept-exit"))
            assertTrue(order.indexOf("accept-exit") < order.indexOf("engine-close"))
        } finally {
            releaseAccept.complete(Unit)
            closer.join(5_000)
            parentJob.cancel()
        }
    }

    @Test
    fun closeOnSingleThreadCallbackDispatcherDoesNotWaitForSuspendedProcessor() {
        val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "speech-close-callback-test").apply { isDaemon = true }
        }
        val callbackDispatcher = callbackExecutor.asCoroutineDispatcher()
        val parentJob = SupervisorJob()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val callbacks = Collections.synchronizedList(mutableListOf<String>())
        val recordingStarted = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val recorder = FakeRecorder(cancelBlock = { order += "recorder-cancel" })
        val engine = FakeStreamingEngine(
            cancelBlock = { order += "stream-cancel" },
            closeBlock = { order += "engine-close" }
        )
        val controller = LocalSpeechController(
            pcmRecorder = recorder,
            speechEngine = engine,
            scope = CoroutineScope(parentJob + Dispatchers.Default),
            callbackDispatcher = callbackDispatcher,
            timerDelay = { awaitCancellation() },
            correctTranscript = { raw -> correction(raw, raw) },
            onSessionCreated = { callbacks += "created" },
            onRecordingStarted = {
                callbacks += "recording"
                recordingStarted.countDown()
            },
            onPartialText = { callbacks += "partial" },
            onRecordingStopped = { callbacks += "stopped" },
            onFinalResult = { callbacks += "final" },
            onError = { callbacks += "error" }
        )

        try {
            controller.start()
            assertTrue(recordingStarted.await(1, TimeUnit.SECONDS))
            callbackExecutor.execute {
                try {
                    controller.close()
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                } finally {
                    closeReturned.countDown()
                }
            }

            assertTrue("close blocked its callback dispatcher", closeReturned.await(2, TimeUnit.SECONDS))
            assertEquals(null, closeFailure.get())
            assertEquals(1, recorder.cancelCalls)
            assertEquals(1, engine.cancelCalls)
            assertEquals(1, engine.closeCalls)
            assertTrue(order.indexOf("recorder-cancel") < order.indexOf("stream-cancel"))
            assertTrue(order.indexOf("stream-cancel") < order.indexOf("engine-close"))

            val callbacksAtClose = callbacks.toList()
            recorder.emit(ShortArray(128) { 9 })
            val dispatcherDrained = CountDownLatch(1)
            callbackExecutor.execute { dispatcherDrained.countDown() }
            assertTrue(dispatcherDrained.await(1, TimeUnit.SECONDS))
            assertEquals(callbacksAtClose, callbacks.toList())
        } finally {
            parentJob.cancel()
            callbackDispatcher.close()
            callbackExecutor.shutdownNow()
        }
    }

    @Test
    fun concurrentCloseCallersReceiveTheSameShutdownFailure() {
        val parentJob = SupervisorJob()
        val closeFailure = IllegalStateException("engine close failed")
        val engine = FakeStreamingEngine(closeFailure = closeFailure)
        val controller = realController(parentJob, FakeRecorder(), engine)
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val callers = List(2) {
            Thread {
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "close callers were not released" }
                try {
                    controller.close()
                } catch (failure: Throwable) {
                    failures += failure
                } finally {
                    returned.countDown()
                }
            }
        }

        try {
            callers.forEach(Thread::start)
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(returned.await(2, TimeUnit.SECONDS))

            assertEquals(2, failures.size)
            failures.forEach { assertSame(closeFailure, it) }
            assertEquals(1, engine.closeCalls)
        } finally {
            release.countDown()
            callers.forEach { it.join(5_000) }
            parentJob.cancel()
        }
    }

    @Test
    fun parentScopeCancellationCleansRecorderAndRejectsNewStarts() = runTest {
        val parentJob = Job()
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val controller = controller(
            recorder,
            engine,
            scope = CoroutineScope(backgroundScope.coroutineContext + parentJob)
        )

        controller.start()
        runCurrent()
        parentJob.cancel()
        runCurrent()

        assertEquals(1, recorder.cancelCalls)
        assertEquals(1, engine.cancelCalls)
        assertThrows(IllegalStateException::class.java) { controller.start() }
        closeAndDrain(controller)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun parentScopeCancellationCompletesRecorderAndStreamCleanupNonCancellably() = runTest {
        val parentJob = Job()
        val recorderCleanupEntered = CompletableDeferred<Unit>()
        val releaseRecorderCleanup = CompletableDeferred<Unit>()
        val recorderCleanupCompleted = CompletableDeferred<Unit>()
        val streamCleanupEntered = CompletableDeferred<Unit>()
        val releaseStreamCleanup = CompletableDeferred<Unit>()
        val streamCleanupCompleted = CompletableDeferred<Unit>()
        val recorder = FakeRecorder(cancelBlock = {
            recorderCleanupEntered.complete(Unit)
            releaseRecorderCleanup.await()
            recorderCleanupCompleted.complete(Unit)
        })
        val engine = FakeStreamingEngine(cancelBlock = {
            streamCleanupEntered.complete(Unit)
            releaseStreamCleanup.await()
            streamCleanupCompleted.complete(Unit)
        })
        val controller = controller(
            recorder,
            engine,
            scope = CoroutineScope(backgroundScope.coroutineContext + parentJob)
        )

        controller.start()
        runCurrent()
        parentJob.cancel()
        runCurrent()
        assertTrue(recorderCleanupEntered.isCompleted)
        assertFalse(recorderCleanupCompleted.isCompleted)

        releaseRecorderCleanup.complete(Unit)
        runCurrent()
        assertTrue(recorderCleanupCompleted.isCompleted)
        assertTrue(streamCleanupEntered.isCompleted)
        assertFalse(streamCleanupCompleted.isCompleted)

        releaseStreamCleanup.complete(Unit)
        runCurrent()
        assertTrue(streamCleanupCompleted.isCompleted)
        closeAndDrain(controller)
    }

    @Test
    fun callbackDispatcherPreservesLifecycleAndFinalOrdering() = runTest {
        val events = mutableListOf<String>()
        val engine = FakeStreamingEngine(
            partialText = "l v 2 strength",
            finishBlock = {
                events += "finish"
                "done"
            }
        )
        val controller = controller(FakeRecorder(), engine, events)

        val generation = controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(
            listOf(
                "created:$generation",
                "recording:$generation",
                "stopped:$generation",
                "finish",
                "final:$generation:done"
            ),
            events
        )
    }

    @Test
    fun throwingCallbacksAreContainedAndProcessorKeepsServingSessions() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine(partialText = "partial")
        val controller = controller(
            recorder,
            engine,
            onSessionCreated = { throw IllegalStateException("created callback") },
            onRecordingStarted = { throw IllegalStateException("recording callback") },
            onPartialText = { throw IllegalStateException("partial callback") },
            onRecordingStopped = { throw IllegalStateException("stopped callback") },
            onFinalResult = { throw IllegalStateException("final callback") },
            onError = { throw IllegalStateException("error callback") }
        )

        controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()
        controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(2, recorder.stopCalls)
        assertEquals(2, engine.finishCalls)
        assertFalse(recorder.overlappingCalls)
    }

    @Test
    fun recordingStoppedCallbackCanStartReplacementWithoutDeadlockOrOldFinal() = runTest {
        val recorder = FakeRecorder()
        val engine = FakeStreamingEngine()
        val finals = mutableListOf<SpeechRecognitionResult>()
        val replacements = mutableListOf<Long>()
        lateinit var controller: LocalSpeechController
        controller = controller(
            recorder,
            engine,
            onRecordingStopped = { replacements += controller.start() },
            onFinalResult = finals::add
        )

        controller.start()
        runCurrent()
        controller.stopAndTranscribe()
        runCurrent()

        assertEquals(1, replacements.size)
        assertEquals(listOf("start", "stop", "start"), recorder.operations)
        assertEquals(1, engine.cancelCalls)
        assertTrue(finals.isEmpty())
    }

    @Test
    fun reentrantRecordingCallbackCloseReturnsButExternalCloseWaitsForCallbackQuiescence() {
        val parentJob = SupervisorJob()
        val callbackCloseReturned = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackExited = CountDownLatch(1)
        val externalCloseReturned = CountDownLatch(1)
        val externalFailure = AtomicReference<Throwable?>()
        val engine = FakeStreamingEngine()
        lateinit var controller: LocalSpeechController
        controller = realController(
            parentJob = parentJob,
            recorder = FakeRecorder(),
            engine = engine,
            onRecordingStarted = {
                controller.close()
                callbackCloseReturned.countDown()
                check(releaseCallback.await(5, TimeUnit.SECONDS)) { "callback was not released" }
                callbackExited.countDown()
            }
        )
        val externalCloser = Thread {
            try {
                controller.close()
            } catch (failure: Throwable) {
                externalFailure.set(failure)
            } finally {
                externalCloseReturned.countDown()
            }
        }

        try {
            controller.start()
            assertTrue(callbackCloseReturned.await(1, TimeUnit.SECONDS))
            externalCloser.start()
            assertFalse(externalCloseReturned.await(150, TimeUnit.MILLISECONDS))
            assertEquals(0, engine.closeCalls)

            releaseCallback.countDown()
            assertTrue(callbackExited.await(1, TimeUnit.SECONDS))
            assertTrue(externalCloseReturned.await(2, TimeUnit.SECONDS))
            assertEquals(null, externalFailure.get())
            assertEquals(1, engine.closeCalls)
        } finally {
            releaseCallback.countDown()
            externalCloser.join(5_000)
            parentJob.cancel()
        }
    }

    @Test
    fun replacementCleanupFailurePublishesAudioErrorAndProcessorKeepsServingCommands() = runTest {
        val recorder = FakeRecorder(cancelFailures = 1)
        val engine = FakeStreamingEngine()
        val errors = mutableListOf<SpeechRecognitionFailure>()
        val controller = controller(recorder, engine, onError = errors::add)

        controller.start()
        runCurrent()
        val failedGeneration = controller.start()
        runCurrent()

        assertEquals(failedGeneration, errors.single().generation)
        assertEquals(SpeechRecognitionFailureType.Audio, errors.single().type)
        controller.start()
        runCurrent()
        assertTrue(recorder.startCalls >= 2)
    }

    private suspend fun TestScope.assertPendingPartialIsSuppressed(
        invalidate: (LocalSpeechController) -> Unit
    ) {
        val partialDeliveryEntered = CompletableDeferred<Unit>()
        val releasePartialDelivery = CompletableDeferred<Unit>()
        val callbackNumber = AtomicInteger()
        val partials = mutableListOf<SpeechRecognitionText>()
        val controller = controller(
            FakeRecorder(),
            FakeStreamingEngine(partialText = "l v 1"),
            beforeCallbackDelivery = {
                if (callbackNumber.incrementAndGet() == 3) {
                    partialDeliveryEntered.complete(Unit)
                    releasePartialDelivery.await()
                }
            },
            onPartialText = partials::add
        )

        val staleGeneration = controller.start()
        runCurrent()
        partialDeliveryEntered.await()
        invalidate(controller)
        releasePartialDelivery.complete(Unit)
        runCurrent()

        assertTrue(partials.none { it.generation == staleGeneration })
        closeAndDrain(controller)
    }

    private fun currentSession(controller: LocalSpeechController): Any? {
        val field = LocalSpeechController::class.java.getDeclaredField("currentSession")
        field.isAccessible = true
        return field.get(controller)
    }

    private fun correction(
        raw: String,
        corrected: String,
        ambiguous: Boolean = false,
        replacements: List<CorrectionReplacement> = emptyList()
    ) = CorrectionResult(
        rawText = raw,
        correctedText = corrected,
        confidence = 1.0f,
        ambiguous = ambiguous,
        replacements = replacements
    )

    private fun pcmBuffer(session: Any): PcmSessionBuffer {
        val field = session.javaClass.getDeclaredField("pcmBuffer")
        field.isAccessible = true
        return field.get(session) as PcmSessionBuffer
    }

    private fun TestScope.closeAndDrain(controller: LocalSpeechController) {
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val closer = Thread {
            try {
                controller.close()
            } catch (closeFailure: Throwable) {
                failure.set(closeFailure)
            } finally {
                returned.countDown()
            }
        }
        closer.start()
        repeat(100) {
            runCurrent()
            if (returned.await(20, TimeUnit.MILLISECONDS)) {
                closer.join(5_000)
                failure.get()?.let { throw it }
                return
            }
        }
        throw AssertionError("Controller close did not complete")
    }

    private fun TestScope.controller(
        recorder: FakeRecorder,
        engine: FakeStreamingEngine,
        events: MutableList<String> = mutableListOf(),
        scope: CoroutineScope = backgroundScope,
        callbackDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        timerDelay: suspend (Long) -> Unit = { delay(it) },
        beforeCallbackDelivery: suspend () -> Unit = {},
        afterCallbackPermitBeforeEntry: () -> Unit = {},
        finalizationAttemptObserver: SpeechFinalizationAttemptObserver =
            SpeechFinalizationAttemptObserver.None,
        onSessionCreated: (Long) -> Unit = { events += "created:$it" },
        onRecordingStarted: (Long) -> Unit = { events += "recording:$it" },
        onPartialText: (SpeechRecognitionText) -> Unit = {
            events += "partial:${it.generation}:${it.text}"
        },
        onRecordingStopped: (Long) -> Unit = { events += "stopped:$it" },
        correct: (String) -> CorrectionResult = { raw -> correction(raw, raw) },
        debugLog: (String) -> Unit = {},
        onFinalResult: (SpeechRecognitionResult) -> Unit = {
            events += "final:${it.generation}:${it.correction.correctedText}"
        },
        onError: (SpeechRecognitionFailure) -> Unit = {
            events += "error:${it.generation}:${it.type}"
        }
    ) = LocalSpeechController(
        pcmRecorder = recorder,
        speechEngine = engine,
        scope = scope,
        callbackDispatcher = callbackDispatcher,
        timerDelay = timerDelay,
        beforeCallbackDelivery = beforeCallbackDelivery,
        afterCallbackPermitBeforeEntry = afterCallbackPermitBeforeEntry,
        finalizationAttemptObserver = finalizationAttemptObserver,
        correctTranscript = correct,
        debugLog = debugLog,
        onSessionCreated = onSessionCreated,
        onRecordingStarted = onRecordingStarted,
        onPartialText = onPartialText,
        onRecordingStopped = onRecordingStopped,
        onFinalResult = onFinalResult,
        onError = onError
    )

    private fun realController(
        parentJob: Job,
        recorder: FakeRecorder,
        engine: FakeStreamingEngine,
        onRecordingStarted: (Long) -> Unit = {},
        onPartialText: (SpeechRecognitionText) -> Unit = {}
    ) = LocalSpeechController(
        pcmRecorder = recorder,
        speechEngine = engine,
        scope = CoroutineScope(parentJob + Dispatchers.Default),
        callbackDispatcher = Dispatchers.Default,
        timerDelay = { awaitCancellation() },
        onSessionCreated = {},
        onRecordingStarted = onRecordingStarted,
        onPartialText = onPartialText,
        onRecordingStopped = {},
        correctTranscript = { raw -> correction(raw, raw) },
        onFinalResult = {},
        onError = {}
    )

    private fun closeThread(
        controller: LocalSpeechController,
        returned: CountDownLatch,
        failures: ConcurrentLinkedQueue<Throwable>
    ) = Thread {
        try {
            controller.close()
        } catch (failure: Throwable) {
            failures += failure
        } finally {
            returned.countDown()
        }
    }

    private class FakeRecorder(
        private val chunks: List<ShortArray> = listOf(ShortArray(PcmRecordingPolicy.MIN_SAMPLES) { 1 }),
        private val startBlock: suspend () -> Unit = {},
        private val stopBlock: suspend () -> Unit = {},
        private val cancelBlock: suspend () -> Unit = {},
        private val startFailure: Throwable? = null,
        private val stopFailure: Throwable? = null,
        cancelFailures: Int = 0
    ) : PcmRecorder {
        private val activeCalls = AtomicInteger()
        private val overlapDetected = AtomicBoolean()
        private val remainingCancelFailures = AtomicInteger(cancelFailures)
        private var callback: ((ShortArray) -> Unit)? = null
        val callbackActive = AtomicBoolean(false)
        @Volatile
        var startCalls = 0
            private set
        @Volatile
        var stopCalls = 0
            private set
        @Volatile
        var cancelCalls = 0
            private set
        val operations: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val overlappingCalls: Boolean
            get() = overlapDetected.get()

        override suspend fun start(onSamples: (ShortArray) -> Unit) {
            operation<Unit>("start") {
                startCalls++
                startBlock()
                startFailure?.let { throw it }
                callback = onSamples
                chunks.forEach(::emit)
            }
        }

        override suspend fun stop(): Int = operation("stop") {
            stopCalls++
            stopBlock()
            stopFailure?.let { throw it }
            chunks.sumOf(ShortArray::size)
        }

        override suspend fun cancel() {
            operation<Unit>("cancel") {
                cancelCalls++
                cancelBlock()
                if (remainingCancelFailures.getAndUpdate { value -> maxOf(0, value - 1) } > 0) {
                    throw PcmRecordingException("cancel")
                }
            }
        }

        fun emit(samples: ShortArray) {
            callbackActive.set(true)
            try {
                callback?.invoke(samples.copyOf())
            } finally {
                callbackActive.set(false)
            }
        }

        private suspend fun <T> operation(name: String, block: suspend () -> T): T {
            if (activeCalls.incrementAndGet() > 1) overlapDetected.set(true)
            operations += name
            return try {
                block()
            } finally {
                activeCalls.decrementAndGet()
            }
        }
    }

    private class FakeStreamingEngine(
        private val result: String = "recognized",
        private val partialText: String? = null,
        private val startBlock: suspend () -> Unit = {},
        private val acceptBlock: suspend (ShortArray) -> Unit = {},
        private val finishBlock: (suspend () -> String)? = null,
        private val startFailure: Throwable? = null,
        private val acceptFailure: Throwable? = null,
        private val finishFailure: Throwable? = null,
        private val operationLog: MutableList<String>? = null,
        private val recorderCallbackActive: AtomicBoolean? = null,
        private val cancelBlock: suspend () -> Unit = {},
        private val closeBlock: () -> Unit = {},
        private val closeFailure: Throwable? = null
    ) : StreamingSpeechEngine {
        private var partialCallback: ((String) -> Unit)? = null
        var prepareCalls = 0
        var startCalls = 0
        var finishCalls = 0
        var cancelCalls = 0
        var closeCalls = 0
        val acceptedFromRecorderCallback = AtomicBoolean(false)
        val accepted: MutableList<ShortArray> = Collections.synchronizedList(mutableListOf())

        override suspend fun prepare() {
            prepareCalls++
        }

        override suspend fun startSession(onPartialText: (String) -> Unit) {
            startCalls++
            startBlock()
            startFailure?.let { throw it }
            partialCallback = onPartialText
        }

        override suspend fun acceptSamples(samples: ShortArray, sampleCount: Int) {
            if (recorderCallbackActive?.get() == true) acceptedFromRecorderCallback.set(true)
            operationLog?.add("accept")
            val validSamples = samples.copyOf(sampleCount)
            accepted += validSamples
            acceptBlock(validSamples)
            acceptFailure?.let { throw it }
            partialText?.let { partialCallback?.invoke(it) }
        }

        override suspend fun finishSession(): String {
            finishCalls++
            finishFailure?.let { throw it }
            return finishBlock?.invoke() ?: result
        }

        override suspend fun cancelSession() {
            cancelCalls++
            cancelBlock()
            partialCallback = null
        }

        override fun close() {
            closeCalls++
            closeBlock()
            partialCallback = null
            closeFailure?.let { throw it }
        }
    }
}
