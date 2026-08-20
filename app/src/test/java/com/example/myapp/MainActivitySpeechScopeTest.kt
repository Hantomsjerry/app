package com.example.myapp

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MainActivitySpeechScopeTest {
    @Test
    fun mainActivityOwnsOnlyTheStreamingSpeechController() {
        val source = mainActivitySource()

        assertTrue(source.contains("LocalSpeechController"))
        assertTrue(source.contains("SenseVoiceSpeechEngine"))
        assertTrue(source.contains("SenseVoiceModelStore"))
        assertFalse(source.contains("LocalWhisperController"))
        assertFalse(source.contains("NativeWhisperInferenceEngine"))
        assertFalse(source.contains("speechEngine.close()"))
    }

    @Test
    fun localSpeechControllerDoesNotUseComposeCoroutineScope() {
        val construction = fragmentBetween(
            mainActivitySource(),
            "val localSpeechController",
            "onSessionCreated"
        )

        assertFalse(construction.contains("coroutineScope"))
        assertFalse(Regex("""\bscope\s*=""").containsMatchIn(construction))
    }

    @Test
    fun lifecycleStopCancelsTheSpeechController() {
        val lifecycleScope = fragmentBetween(
            mainActivitySource(),
            "if (event == Lifecycle.Event.ON_STOP)",
            "lifecycleOwner.lifecycle.addObserver"
        )

        assertTrue(lifecycleScope.contains("localSpeechController.cancel()"))
    }

    @Test
    fun compositionDisposalUsesOneCleanupRunnableForExecutorAndFallback() {
        val source = mainActivitySource()
        val cleanup = fragmentBetween(
            source,
            "val shutdownCleanup = remember(localSpeechController, qwenEngine, client)",
            "DisposableEffect(client, localSpeechController, qwenEngine, shutdownExecutor)"
        )
        val disposal = fragmentBetween(
            source,
            "DisposableEffect(client, localSpeechController, qwenEngine, shutdownExecutor)",
            "LaunchedEffect(client)"
        )
        val cleanupStart = requiredIndex(cleanup, "Runnable {")
        val controllerClose = requiredIndex(cleanup, "runCatching { localSpeechController.close() }")
        val qwenClose = requiredIndex(cleanup, "runCatching { qwenEngine.close() }")
        val clientClose = requiredIndex(cleanup, "runCatching { client.close() }")

        assertTrue(source.contains("Executors.newSingleThreadExecutor"))
        assertTrue(source.contains("isDaemon = true"))
        assertTrue(cleanupStart < controllerClose)
        assertTrue(controllerClose < qwenClose)
        assertTrue(qwenClose < clientClose)
        assertTrue(disposal.contains("shutdownSubmitted.compareAndSet(false, true)"))
        assertTrue(disposal.contains("shutdownExecutor.execute(shutdownCleanup)"))
        assertTrue(disposal.contains("shutdownExecutor.shutdown()"))
        assertTrue(
            Regex(
                """catch\s*\(\s*_:\s*RejectedExecutionException\s*\)\s*\{[\s\S]*?Thread\(shutdownCleanup, "voice-resource-close-fallback"\)[\s\S]*?isDaemon\s*=\s*true[\s\S]*?\.start\(\)"""
            ).containsMatchIn(disposal)
        )
        assertFalse(disposal.contains("localSpeechController.close()"))
    }

    @Test
    fun everySpeechCallbackMapsItsGenerationBeforeDispatch() {
        val callbacks = controllerConstruction()

        assertTrackerBeforeEvent(
            callbacks = callbacks,
            callbackName = "onSessionCreated",
            nextCallbackName = "onRecordingStarted",
            trackerCall = "voiceGenerationTracker.begin",
            eventName = "VoiceEvent.NewSession"
        )
        assertTrackerBeforeEvent(
            callbacks = callbacks,
            callbackName = "onRecordingStarted",
            nextCallbackName = "onPartialText",
            trackerCall = "voiceGenerationTracker.resolve",
            eventName = "VoiceEvent.RecordingStarted"
        )
        assertTrackerBeforeEvent(
            callbacks = callbacks,
            callbackName = "onPartialText",
            nextCallbackName = "onRecordingStopped",
            trackerCall = "voiceGenerationTracker.resolve",
            eventName = "VoiceEvent.PartialText"
        )
        assertTrackerBeforeEvent(
            callbacks = callbacks,
            callbackName = "onRecordingStopped",
            nextCallbackName = "onFinalResult",
            trackerCall = "voiceGenerationTracker.resolve",
            eventName = "VoiceEvent.RecordingStopped"
        )
        assertTrackerBeforeEvent(
            callbacks = callbacks,
            callbackName = "onFinalResult",
            nextCallbackName = "onError",
            trackerCall = "voiceGenerationTracker.resolve",
            eventName = "VoiceEvent.FinalText"
        )
        assertTrackerBeforeEvent(
            callbacks = callbacks,
            callbackName = "onError",
            nextCallbackName = null,
            trackerCall = "voiceGenerationTracker.resolve",
            eventName = "VoiceEvent.RecognitionFailed"
        )
    }

    @Test
    fun ambiguousCorrectionStopsBeforeModelPreparationAndParsing() {
        val callback = fragmentBetween(
            controllerConstruction(),
            "onFinalResult = {",
            "onError = {"
        )
        val ambiguityCheck = requiredIndex(callback, "if (result.correction.ambiguous)")
        val recognitionFailure = requiredIndex(callback, "VoiceEvent.RecognitionFailed")
        val finalText = requiredIndex(callback, "VoiceEvent.FinalText")
        val modelPreparation = requiredIndex(callback, "VoiceEvent.ModelPreparationStarted")
        val parser = requiredIndex(callback, "voiceIntentParser.parse")
        val gate = callback.substring(ambiguityCheck, finalText)

        assertTrue(ambiguityCheck < recognitionFailure)
        assertTrue(recognitionFailure < finalText)
        assertTrue(finalText < modelPreparation)
        assertTrue(modelPreparation < parser)
        assertTrue(gate.contains("\\u6307\\u4ee4\\u5b58\\u5728\\u6b67\\u4e49"))
        assertTrue(gate.contains("return@LocalSpeechController"))
    }

    @Test
    fun productionControllerForwardsCorrectionDebugLogsToAndroidLog() {
        val source = mainActivitySource()
        val construction = fragmentBetween(
            source,
            "val localSpeechController",
            "onSessionCreated"
        )

        assertTrue(source.contains("import android.util.Log"))
        assertTrue(
            construction.contains(
                "debugLog = { message -> Log.d(\"LocalSpeechController\", message) }"
            )
        )
    }

    private fun assertTrackerBeforeEvent(
        callbacks: String,
        callbackName: String,
        nextCallbackName: String?,
        trackerCall: String,
        eventName: String
    ) {
        val callback = fragmentBetween(
            callbacks,
            "$callbackName = {",
            nextCallbackName?.let { "$it = {" } ?: "fun cancelVoiceWork"
        )

        assertTrue(requiredIndex(callback, trackerCall) < requiredIndex(callback, eventName))
    }

    private fun controllerConstruction(): String = fragmentBetween(
        mainActivitySource(),
        "val localSpeechController",
        "fun dispatchVoiceFailure"
    )

    private fun fragmentBetween(source: String, startMarker: String, endMarker: String): String {
        val start = requiredIndex(source, startMarker)
        val contentStart = start + startMarker.length
        val end = source.indexOf(endMarker, contentStart)
        if (end < contentStart) {
            fail("Missing or reversed end marker '$endMarker' after '$startMarker'")
        }
        return source.substring(contentStart, end)
    }

    private fun requiredIndex(source: String, marker: String): Int {
        val index = source.indexOf(marker)
        if (index < 0) fail("Missing marker '$marker'")
        return index
    }

    private fun mainActivitySource(): String {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val sourceFile = generateSequence(File(userDirectory)) { it.parentFile }
            .map { directory ->
                File(
                    directory,
                    "app/src/main/java/com/example/myapp/MainActivity.kt"
                )
            }
            .firstOrNull(File::isFile)
            ?: error("Unable to locate MainActivity.kt from $userDirectory")

        return sourceFile.readText()
    }
}
