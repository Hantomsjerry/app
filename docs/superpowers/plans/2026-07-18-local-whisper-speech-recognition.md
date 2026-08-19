# Local Whisper Speech Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unreliable Android system recognizer with bundled multilingual Whisper speech-to-text, using tap-to-start/tap-to-stop recording and a 20-second limit while preserving the existing validated Qwen-to-TCP command flow.

**Architecture:** Capture bounded 16 kHz mono PCM through an injected recorder, transcribe it with a pinned `whisper.cpp` native library and verified bundled model, then forward final text through the existing deterministic parser and local Qwen fallback. Build Whisper in a separate Android Library module so its private ggml build does not collide with llama.cpp's ggml targets, and load/free Whisper and Qwen sequentially.

**Tech Stack:** Kotlin 2.2.10, Android 10+/API 29, coroutines, Jetpack Compose, Android `AudioRecord`, whisper.cpp v1.8.1, multilingual `ggml-small-q5_1.bin`, C++17/JNI, CMake 3.31.6, NDK 29.0.13113456, JUnit 4, AndroidJUnitRunner.

## Global Constraints

- Support Android 10 and later on arm64-v8a devices with 8 GB RAM and a mid-range Snapdragon-class processor.
- First microphone tap starts recording; the second tap stops and transcribes.
- Stop and transcribe automatically at exactly 20 seconds; never retain more than 320,000 samples.
- Reject recordings shorter than 0.3 seconds, represented as fewer than 4,800 samples at 16 kHz.
- Recognize Chinese, English, and mixed Chinese-English with automatic Whisper language detection.
- Bundle `ggml-small-q5_1.bin`, exact size `190085487` bytes and SHA-256 `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb`.
- Pin whisper.cpp to release `v1.8.1` (`a91dd3b`) and record upstream provenance and MIT license.
- Preserve deterministic-parser-first, Qwen-fallback-second, strict Kotlin validation, card Apply, and TCP compact JSON followed by exactly one `\n`.
- Do not add streaming partial transcripts, silence detection, cloud fallback, a new confirmation dialog, release signing, or APK distribution work.
- Whisper must release its native context before Qwen parsing starts; Qwen must release its native handle after every generation attempt.
- The workspace has no Git repository. Do not initialize one; replace commit steps with recorded verification checkpoints.

## File Map

- Modify `settings.gradle.kts`: include the isolated `:whisper-native` Android Library module.
- Create `whisper-native/build.gradle.kts`: configure API 29, arm64-v8a, CMake, and consumer packaging.
- Create `whisper-native/src/main/AndroidManifest.xml`: minimal library manifest.
- Create `whisper-native/src/main/cpp/CMakeLists.txt`: build pinned whisper.cpp and `libwhisper_jni.so` independently of the app's llama.cpp CMake graph.
- Create `whisper-native/src/main/cpp/whisper_jni.cpp`: one-call model load, PCM conversion, transcription, UTF-8 result, and guaranteed cleanup.
- Create `third_party/whisper.cpp/`: pinned upstream v1.8.1 source and license.
- Create `third_party/whisper.cpp/UPSTREAM.md`: provenance, version, checksum source, and local build flags.
- Modify `app/build.gradle.kts`: depend on `:whisper-native` and store `.bin` assets uncompressed.
- Create `app/src/main/assets/models/ggml-small-q5_1.bin`: verified multilingual model.
- Create `app/src/main/java/com/example/myapp/voice/WhisperModelStore.kt`: verified atomic model preparation.
- Create `app/src/main/java/com/example/myapp/voice/WhisperInferenceEngine.kt`: coroutine-safe native interface and wrapper.
- Create `app/src/main/java/com/example/myapp/voice/PcmRecorder.kt`: recorder contract, Android `AudioRecord` implementation, and sample bounds.
- Create `app/src/main/java/com/example/myapp/voice/LocalWhisperController.kt`: manual-stop/timeout orchestration and generation-safe callbacks.
- Modify `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`: explicit Recording and Transcribing states.
- Modify `app/src/main/java/com/example/myapp/voice/QwenInferenceEngine.kt`: release Qwen after each generate call.
- Modify `app/src/main/java/com/example/myapp/MainActivity.kt`: replace `AndroidSpeechController` orchestration and microphone click semantics.
- Remove `app/src/main/java/com/example/myapp/voice/AndroidSpeechController.kt` and obsolete system-recognizer-only tests after local integration is green.
- Modify `app/src/main/AndroidManifest.xml`: retain `RECORD_AUDIO`; remove the obsolete recognition-service query.
- Add focused JVM tests under `app/src/test/java/com/example/myapp/voice/` and device diagnostics under `app/src/androidTest/java/com/example/myapp/`.

---

### Task 1: Define Recording Bounds and PCM Recorder Contract

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/PcmRecorder.kt`
- Create: `app/src/test/java/com/example/myapp/voice/PcmRecordingPolicyTest.kt`
- Create: `app/src/test/java/com/example/myapp/voice/FakePcmRecorder.kt`

**Interfaces:**
- Produces: `object PcmRecordingPolicy` with `SAMPLE_RATE = 16_000`, `MIN_SAMPLES = 4_800`, `MAX_SAMPLES = 320_000`, and `MAX_DURATION_MILLIS = 20_000L`.
- Produces: `interface PcmRecorder { suspend fun start(); suspend fun stop(): ShortArray; suspend fun cancel() }`.
- Produces: `class AndroidPcmRecorder : PcmRecorder` using `AudioRecord` and an IO dispatcher.

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test fun exact_bounds_match_confirmed_durations() {
    assertEquals(4_800, PcmRecordingPolicy.MIN_SAMPLES)
    assertEquals(320_000, PcmRecordingPolicy.MAX_SAMPLES)
    assertEquals(20_000L, PcmRecordingPolicy.MAX_DURATION_MILLIS)
}

@Test fun short_recording_is_rejected() {
    assertFalse(PcmRecordingPolicy.isLongEnough(ShortArray(4_799)))
    assertTrue(PcmRecordingPolicy.isLongEnough(ShortArray(4_800)))
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*PcmRecordingPolicyTest'`

Expected: FAIL because `PcmRecordingPolicy` does not exist.

- [ ] **Step 3: Implement the minimal policy and recorder interface**

```kotlin
object PcmRecordingPolicy {
    const val SAMPLE_RATE = 16_000
    const val MIN_SAMPLES = 4_800
    const val MAX_SAMPLES = 320_000
    const val MAX_DURATION_MILLIS = 20_000L
    fun isLongEnough(samples: ShortArray): Boolean = samples.size >= MIN_SAMPLES
}

interface PcmRecorder {
    suspend fun start()
    suspend fun stop(): ShortArray
    suspend fun cancel()
}
```

- [ ] **Step 4: Write Android recorder behavior around the contract**

Use `AudioRecord.Builder`, `MediaRecorder.AudioSource.VOICE_RECOGNITION`, mono PCM 16-bit, and 16 kHz. Copy reads into a bounded `ShortArray(MAX_SAMPLES)`; treat negative reads as `PcmRecordingException`; make stop/cancel idempotently release `AudioRecord`; serialize lifecycle with a `Mutex`.

- [ ] **Step 5: Run focused and complete JVM tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

### Task 2: Add the Manual-Stop and 20-Second Session State Machine

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/LocalWhisperController.kt`
- Create: `app/src/test/java/com/example/myapp/voice/LocalWhisperControllerTest.kt`

**Interfaces:**
- Consumes: `PcmRecorder`, `WhisperInferenceEngine`, `CoroutineScope`, and injected `delayMillis: suspend (Long) -> Unit`.
- Produces: `start(): Long`, `stopAndTranscribe()`, `cancel()`, and `close()`.
- Produces callbacks `onSessionStarted(Long)`, `onRecordingStopped(Long)`, `onFinalText(SpeechRecognitionText)`, and `onError(SpeechRecognitionFailure)`.

- [ ] **Step 1: Write failing manual-stop, timeout, short-audio, and stale-result tests**

```kotlin
@Test fun second_action_stops_and_transcribes() = runTest {
    val recorder = FakePcmRecorder(ShortArray(8_000) { 1 })
    val engine = FakeWhisperEngine("把 Lv1 sensitivity 改为 32")
    val controller = controller(recorder, engine)
    controller.start()
    controller.stopAndTranscribe()
    advanceUntilIdle()
    assertEquals(1, recorder.stopCalls)
    assertEquals(listOf("把 Lv1 sensitivity 改为 32"), finalTexts)
}

@Test fun timeout_stops_once_at_twenty_seconds() = runTest {
    val controller = controller(FakePcmRecorder(ShortArray(8_000)), FakeWhisperEngine("text"))
    controller.start()
    advanceTimeBy(20_000)
    advanceUntilIdle()
    assertEquals(1, recorder.stopCalls)
}

@Test fun recording_below_minimum_never_loads_whisper() = runTest {
    val engine = FakeWhisperEngine("unused")
    val controller = controller(FakePcmRecorder(ShortArray(4_799)), engine)
    controller.start()
    controller.stopAndTranscribe()
    advanceUntilIdle()
    assertTrue(engine.calls.isEmpty())
    assertEquals(SpeechRecognitionFailureType.Audio, failures.single().type)
}
```

Add a test where session 1 native work completes after session 2 starts; only session 2 may emit final text.

- [ ] **Step 2: Run and verify RED**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*LocalWhisperControllerTest'`

Expected: FAIL because `LocalWhisperController` does not exist.

- [ ] **Step 3: Implement one active generation and one timeout job**

```kotlin
fun start(): Long {
    val next = generation.incrementAndGet()
    cancelActiveWork()
    activeGeneration = next
    recordingJob = scope.launch { recorder.start() }
    timeoutJob = scope.launch {
        delayMillis(PcmRecordingPolicy.MAX_DURATION_MILLIS)
        stopAndTranscribe(next)
    }
    onSessionStarted(next)
    return next
}
```

`stopAndTranscribe()` must atomically claim the active recording, cancel only the timeout job, stop the recorder once, emit `onRecordingStopped`, reject short audio, call Whisper on `Dispatchers.Default`, trim the result, map blank text to NoMatch, and gate every callback by generation.

- [ ] **Step 4: Run controller tests and all voice tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*voice*'`

Expected: PASS.

### Task 3: Add Verified Whisper Model Storage and Bundle the Model

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/WhisperModelStore.kt`
- Create: `app/src/test/java/com/example/myapp/voice/WhisperModelStoreTest.kt`
- Create: `app/src/main/assets/models/ggml-small-q5_1.bin`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `WhisperModelStore.prepare(): File`.
- Uses exact constants `FILE_NAME`, `ASSET_PATH`, `EXPECTED_BYTES = 190_085_487L`, and `EXPECTED_SHA256` from Global Constraints.

- [ ] **Step 1: Write failing model-store tests**

Mirror the proven `QwenModelStoreTest` cases with fake bytes: reuse verified final file, replace corrupt final file, delete stale partial file, reject size mismatch, reject hash mismatch, and serialize concurrent preparation.

```kotlin
@Test fun corrupt_asset_is_never_published() = runTest {
    val store = store(asset = "bad".byteInputStream(), expected = validBytes)
    assertThrows(ModelPreparationException::class.java) { runBlocking { store.prepare() } }
    assertFalse(File(modelDir, "ggml-small-q5_1.bin").exists())
    assertFalse(File(modelDir, "ggml-small-q5_1.bin.partial").exists())
}
```

- [ ] **Step 2: Run and verify RED**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*WhisperModelStoreTest'`

Expected: FAIL because `WhisperModelStore` does not exist.

- [ ] **Step 3: Implement chunked copy, SHA-256 verification, and atomic publish**

Follow `QwenModelStore`'s lock, `.partial`, 1 MiB buffer, `Files.move(..., ATOMIC_MOVE)`, and cleanup behavior, but use Whisper-specific messages and constants.

- [ ] **Step 4: Download and verify the official model**

Download `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin` to `app/src/main/assets/models/ggml-small-q5_1.bin`.

Run: `Get-FileHash app\src\main\assets\models\ggml-small-q5_1.bin -Algorithm SHA256`

Expected: `AE85E4A935D7A567BD102FE55AFC16BB595BDB618E11B2FC7591BC08120411BB`; file length `190085487`.

- [ ] **Step 5: Add `.bin` to uncompressed Android resources and run tests**

```kotlin
androidResources {
    noCompress += listOf("gguf", "bin")
}
```

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*WhisperModelStoreTest'`

Expected: PASS.

### Task 4: Build Pinned whisper.cpp in an Isolated Native Module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `whisper-native/build.gradle.kts`
- Create: `whisper-native/src/main/AndroidManifest.xml`
- Create: `whisper-native/src/main/cpp/CMakeLists.txt`
- Create: `whisper-native/src/main/cpp/whisper_jni.cpp`
- Create: `third_party/whisper.cpp/`
- Create: `third_party/whisper.cpp/UPSTREAM.md`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces `libwhisper_jni.so` for arm64-v8a.
- Produces JNI `nativeTranscribe(modelPath: String, pcm: ShortArray, threads: Int): String`.

- [ ] **Step 1: Vendor and document whisper.cpp v1.8.1**

Extract the official `v1.8.1` source archive into `third_party/whisper.cpp`, preserving `LICENSE`. `UPSTREAM.md` must record repository `https://github.com/ggml-org/whisper.cpp`, release `v1.8.1`, commit prefix `a91dd3b`, retrieval date `2026-07-18`, Android API 29, CPU-only arm64 build, and disabled examples/tests/tools.

- [ ] **Step 2: Create the independent Android Library module**

Add `include(":whisper-native")` to `settings.gradle.kts`. Configure namespace `com.example.myapp.whispernative`, compile SDK 36.1, min SDK 29, NDK `29.0.13113456`, arm64-v8a, CMake `3.31.6`, C++17, and `ANDROID_PLATFORM=android-29`. Add `implementation(project(":whisper-native"))` to the app.

- [ ] **Step 3: Configure a CPU-only whisper target**

In the isolated module CMake file set `WHISPER_BUILD_TESTS=OFF`, `WHISPER_BUILD_EXAMPLES=OFF`, `WHISPER_BUILD_SERVER=OFF`, `GGML_OPENMP=OFF`, `GGML_NATIVE=OFF`, `GGML_CPU_KLEIDIAI=OFF`, then `add_subdirectory` the pinned source and link `whisper_jni` against `whisper`, `android`, and `log`.

- [ ] **Step 4: Implement one-call JNI transcription with RAII cleanup**

```cpp
whisper_context_params context_params = whisper_context_default_params();
context_params.use_gpu = false;
whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), context_params);
if (ctx == nullptr) throw_io(env, "Unable to load Whisper model");
std::unique_ptr<whisper_context, decltype(&whisper_free)> guard(ctx, whisper_free);

whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
params.language = "auto";
params.translate = false;
params.no_timestamps = true;
params.print_progress = false;
params.print_realtime = false;
params.n_threads = threads;
```

Copy the Java short array, normalize each sample with `sample / 32768.0f`, call `whisper_full`, concatenate `whisper_full_get_segment_text`, trim whitespace, and convert valid UTF-8 to `jstring`. Release JNI array elements on every path and translate native failures to Java exceptions.

- [ ] **Step 5: Compile only the Whisper native module**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat :whisper-native:externalNativeBuildDebug`

Expected: BUILD SUCCESSFUL and one arm64-v8a `libwhisper_jni.so`.

### Task 5: Add the Coroutine-Safe Whisper Engine

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/WhisperInferenceEngine.kt`
- Create: `app/src/test/java/com/example/myapp/voice/NativeWhisperInferenceEngineTest.kt`

**Interfaces:**
- Produces: `interface WhisperInferenceEngine { suspend fun transcribe(samples: ShortArray): String; fun close() }`.
- Produces: `NativeWhisperBridgeApi.transcribe(modelPath, samples, threads): String` and `NativeWhisperInferenceEngine`.

- [ ] **Step 1: Write failing forwarding, serialization, error, cancellation, and close tests**

```kotlin
@Test fun prepares_model_and_forwards_exact_pcm() = runBlocking {
    val bridge = RecordingWhisperBridge("Lv1 sensitivity 改为 32")
    val engine = engine(bridge)
    val pcm = shortArrayOf(-32768, 0, 32767)
    assertEquals("Lv1 sensitivity 改为 32", engine.transcribe(pcm))
    assertArrayEquals(pcm, bridge.calls.single().samples)
    assertEquals(4, bridge.calls.single().threads)
}

@Test fun close_rejects_new_work_without_calling_jni() {
    val bridge = RecordingWhisperBridge("unused")
    val engine = engine(bridge)
    engine.close()
    assertThrows(IllegalStateException::class.java) { runBlocking { engine.transcribe(ShortArray(8_000)) } }
    assertTrue(bridge.calls.isEmpty())
}
```

- [ ] **Step 2: Run and verify RED**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*NativeWhisperInferenceEngineTest'`

Expected: FAIL because the engine does not exist.

- [ ] **Step 3: Implement serialized one-call native inference**

Use a `Mutex`, `Dispatchers.Default`, `WhisperModelStore.prepare()`, and `conservativeThreadCount().coerceIn(1, 4)`. `close()` marks the wrapper closed and returns promptly; there is no persistent native handle because JNI frees the context inside each call.

- [ ] **Step 4: Run engine and controller tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*Whisper*'`

Expected: PASS.

### Task 6: Extend Voice UI States and Replace System Recognition

**Files:**
- Modify: `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/VoiceUiStateTest.kt`
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Remove: `app/src/main/java/com/example/myapp/voice/AndroidSpeechController.kt`
- Remove or replace: `app/src/test/java/com/example/myapp/voice/AndroidSpeechRecognizerSessionTest.kt`
- Remove or replace: `app/src/test/java/com/example/myapp/voice/SpeechRecognitionBackendTest.kt`

**Interfaces:**
- Produces states `VoiceUiState.Recording(generation)` and `VoiceUiState.Transcribing(generation)`.
- Consumes `LocalWhisperController` callbacks and preserves existing Parsing, PreparingModel, Ready, Sending, Success, and Error states.

- [ ] **Step 1: Write failing reducer tests**

```kotlin
@Test fun stop_event_moves_recording_to_transcribing() {
    assertEquals(
        VoiceUiState.Transcribing(4),
        reduceVoiceState(VoiceUiState.Recording(4), VoiceEvent.RecordingStopped(4))
    )
}

@Test fun transcript_moves_transcribing_to_parsing() {
    assertEquals(
        VoiceUiState.Parsing(4, "Lv1 sensitivity 改为 32"),
        reduceVoiceState(VoiceUiState.Transcribing(4), VoiceEvent.FinalText(4, "Lv1 sensitivity 改为 32"))
    )
}
```

Also assert stale events are ignored and recognition errors from either new state enter Error.

- [ ] **Step 2: Run and verify RED**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*VoiceUiStateTest'`

Expected: FAIL because Recording/Transcribing and `RecordingStopped` do not exist.

- [ ] **Step 3: Implement reducer states and events**

Replace Listening with Recording and add Transcribing. NewSession enters Recording; RecordingStopped enters Transcribing; FinalText is accepted only by Transcribing; RecognitionFailed is accepted by both.

- [ ] **Step 4: Replace Compose orchestration**

Remember `WhisperModelStore`, `NativeWhisperInferenceEngine`, `AndroidPcmRecorder`, and `LocalWhisperController`. First microphone click from idle/error/success prepares a new generation and starts recording after permission checks. A click while Recording calls `stopAndTranscribe()` and does not clear state. Ignore clicks while Transcribing, Parsing, PreparingModel, or Sending. Keep the existing final-text parser job and Apply send path unchanged.

- [ ] **Step 5: Update card text and cleanup**

Show `正在录音，再次点击结束` in Recording and `正在识别语音` in Transcribing. On lifecycle stop and disposal cancel the controller and close the Whisper engine. Retain `RECORD_AUDIO`; remove `<queries>` for `android.speech.RecognitionService` because the app no longer uses it.

- [ ] **Step 6: Run all JVM tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL with all obsolete system-recognizer assumptions removed.

### Task 7: Release Qwen After Every Generation

**Files:**
- Modify: `app/src/test/java/com/example/myapp/voice/NativeQwenInferenceEngineTest.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/QwenInferenceEngine.kt`

**Interfaces:**
- Preserves `QwenInferenceEngine.generate(prompt, maxTokens)`.
- Changes native lifecycle so every successfully loaded handle is closed in the same serialized generation call.

- [ ] **Step 1: Change tests to require per-call load and close**

```kotlin
@Test fun each_generation_releases_its_native_model() = runBlocking {
    val bridge = RecordingBridge(loadResults = ArrayDeque(listOf(73L, 74L)))
    val engine = engine(bridge)
    engine.generate("first", 128)
    engine.generate("second", 7)
    assertEquals(listOf(73L, 74L), bridge.closedHandles)
    assertEquals(2, bridge.loads.size)
}

@Test fun generation_failure_still_releases_model() {
    val bridge = RecordingBridge(loadResult = 91L, generateBlock = { _, _, _ -> error("decode") })
    assertThrows(IllegalStateException::class.java) { runBlocking { engine(bridge).generate("x", 8) } }
    assertEquals(listOf(91L), bridge.closedHandles)
}
```

- [ ] **Step 2: Run and verify RED**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*NativeQwenInferenceEngineTest'`

Expected: FAIL because the engine currently caches one handle until close.

- [ ] **Step 3: Load and free inside `generate`**

```kotlin
return mutex.withLock {
    checkOpen()
    withContext(Dispatchers.Default) {
        val handle = loadModel(modelStore.prepare())
        try {
            bridge.generate(handle, prompt, maxTokens)
        } finally {
            bridge.close(handle)
        }
    }
}
```

Keep `close()` idempotent and nonblocking; after this change it only prevents new calls and waits for no persistent handle.

- [ ] **Step 4: Run Qwen and full JVM suites**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

### Task 8: Device Diagnostics and End-to-End Verification

**Files:**
- Modify: `app/src/androidTest/java/com/example/myapp/ExampleInstrumentedTest.kt`
- Add: `app/src/androidTest/assets/mixed-command.wav` only if a reproducible licensed/generated fixture is available.
- Modify only implementation files required by observed failures.

**Interfaces:**
- Consumes the complete local recording and transcription flow.
- Produces an Android Studio runnable debug build; no release APK packaging task.

- [ ] **Step 1: Keep the real microphone PCM diagnostic and replace system-recognizer diagnostic**

Delete `defaultSpeechRecognizerAcceptsAppRequest`. Add a model integrity test that calls `WhisperModelStore.prepare()` and asserts size/hash, plus a native smoke test that transcribes a non-silent 16 kHz fixture and asserts a nonblank result.

- [ ] **Step 2: Run clean JVM verification**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat clean testDebugUnitTest --rerun-tasks`

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 3: Build the complete debug app**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat assembleDebug assembleDebugAndroidTest --rerun-tasks`

Expected: BUILD SUCCESSFUL; APK contains `lib/arm64-v8a/libqwen_jni.so`, `lib/arm64-v8a/libwhisper_jni.so`, the GGUF model, and `assets/models/ggml-small-q5_1.bin` exactly once.

- [ ] **Step 4: Install and run automated tests on Vivo V2183A**

Install the debug and androidTest APKs with `-r -g`, then run `AndroidJUnitRunner` on serial `10AC6915CA001IF`. Expected: PCM, model integrity, and native fixture tests pass without using ClaudeRecognitionService.

- [ ] **Step 5: Manually verify physical-device behavior**

On the Vivo phone verify: first tap begins recording; second tap stops; recording below 0.3 seconds errors without inference; 20 seconds auto-stops; Chinese `把二号机的 Lv1 灵敏度调到 32`; English `set machine 3 lv2 strength to 97`; mixed `machine two 的 Lv1 sensitivity 改成 66`; lifecycle stop cancels; candidate requires card Apply; TCP sends JSON plus `\n`; `success` updates the exact integer UI value.

- [ ] **Step 6: Record performance and memory evidence**

Capture model preparation time, transcription latency for a 3-5 second command, Qwen fallback latency, and `dumpsys meminfo com.example.myapp` after Whisper and after Qwen. Confirm logs show Whisper cleanup before Qwen load and that the process remains foreground-stable on the 8 GB device.

- [ ] **Step 7: Record the no-Git verification checkpoint**

Update `.superpowers/sdd/progress.md` and the task report with exact test counts, build outputs, device model/API, transcription samples, measured timings, and any residual accuracy limitations. Do not initialize or modify version control metadata.
