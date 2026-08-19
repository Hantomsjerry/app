# sherpa-onnx Streaming ASR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the bundled local Whisper recognizer with sherpa-onnx streaming bilingual Zipformer INT8 recognition, live partial text, `modified_beam_search`, and fixed domain hotwords.

**Architecture:** Keep the existing 16 kHz `AudioRecord` and voice-command safety pipeline, but stream copied PCM chunks through a session-scoped queue into a reusable sherpa-onnx recognizer. Isolate native APIs behind `StreamingSpeechEngine`, keep model verification in `SherpaOnnxModelStore`, and generalize the current controller so partial, final, cancellation, and timeout events retain generation safety.

**Tech Stack:** Kotlin 2.x, Jetpack Compose Material 3, kotlinx.coroutines, sherpa-onnx 1.13.2 Android AAR, JUnit 4, Android Gradle Plugin 9.2.1

## Global Constraints

- Android minimum SDK remains 29 and packaged ABI remains `arm64-v8a`.
- Use `sherpa-onnx-1.13.2.aar` with SHA-256 `aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245`.
- Use only the INT8 files from `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16`.
- Recognition remains local and supports Chinese, English, and mixed Chinese-English expressions.
- Decoder settings are `16000` Hz, feature dimension `80`, CPU provider, `2` threads, `modified_beam_search`, `4` active paths, endpoint detection disabled, `cjkchar+bpe`, and hotword score `2.0`.
- Recording remains push-to-talk with a hard 20-second limit.
- Partial text is display-only; only final text may enter deterministic parsing, Qwen, Kotlin validation, or TCP sending.
- Fixed hotwords are bundled with the App; no hotword settings UI is added.
- Existing Qwen, Kotlin whitelist validation, user “应用” action, and TCP protocol remain unchanged.
- No physical-device or emulator runtime recognition test is required; verification stops at JVM tests, compilation, APK build, and APK content inspection.
- The current workspace is not a valid Git repository (`git status` returns `fatal: not a git repository`), so commit steps are intentionally omitted. Do not initialize or repair Git as part of this feature.

---

## File Map

**Create**

- `app/libs/sherpa-onnx-1.13.2.aar`: pinned official Android runtime.
- `app/src/main/assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/`: INT8 model files, token files, and hotwords.
- `app/src/main/java/com/example/myapp/voice/SherpaOnnxModelStore.kt`: verified multi-file model preparation.
- `app/src/main/java/com/example/myapp/voice/SpeechTranscriptNormalizer.kt`: controlled `L V 1/2/3` normalization.
- `app/src/main/java/com/example/myapp/voice/StreamingSpeechEngine.kt`: engine contract, PCM conversion, sherpa adapter, and recognizer factory boundary.
- `app/src/main/java/com/example/myapp/voice/LocalSpeechController.kt`: streaming recording/session lifecycle.
- Corresponding JVM tests under `app/src/test/java/com/example/myapp/voice/`.

**Modify**

- `app/build.gradle.kts`: local AAR and ONNX packaging.
- `settings.gradle.kts`: remove `:whisper-native`.
- `app/src/main/java/com/example/myapp/voice/PcmRecorder.kt`: emit chunks and return recorded sample count.
- `app/src/main/java/com/example/myapp/voice/VoiceIntentParser.kt`: normalize before deterministic/Qwen parsing.
- `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`: speech-model preparation and partial-text states.
- `app/src/main/java/com/example/myapp/MainActivity.kt`: instantiate sherpa components and show live partial text.
- Existing tests for recorder, UI state, parser, and main screen source contracts.

**Delete after replacement is green**

- `app/src/main/java/com/example/myapp/voice/WhisperInferenceEngine.kt`
- `app/src/main/java/com/example/myapp/voice/WhisperModelStore.kt`
- `app/src/main/java/com/example/myapp/voice/LocalWhisperController.kt`
- Whisper-specific JVM tests.
- `app/src/main/assets/models/ggml-small-q5_1.bin`
- `whisper-native/`
- `third_party/whisper.cpp/`

---

### Task 1: Vendor the Runtime and Model Assets

**Files:**
- Create: `app/libs/sherpa-onnx-1.13.2.aar`
- Create: `app/src/main/assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/*`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: official v1.13.2 AAR and official small bilingual model release.
- Produces: compile-time `com.k2fsa.sherpa.onnx` API and the six filesystem assets used by Task 4.

- [ ] **Step 1: Record a fresh baseline**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`; record the test count from `app/build/test-results/testDebugUnitTest/TEST-*.xml`.

- [ ] **Step 2: Download and verify the pinned AAR outside the project**

```powershell
$temp = Join-Path $env:TEMP 'myapp2-sherpa-onnx'
New-Item -ItemType Directory -Force -Path $temp | Out-Null
$aar = Join-Path $temp 'sherpa-onnx-1.13.2.aar'
Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar' -OutFile $aar
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $aar).Hash.ToLowerInvariant()
if ($hash -ne 'aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245') { throw "Unexpected AAR SHA-256: $hash" }
```

Expected: no exception and a 54 MB AAR.

- [ ] **Step 3: Copy the verified AAR into `app/libs`**

```powershell
New-Item -ItemType Directory -Force -Path 'app/libs' | Out-Null
Copy-Item -LiteralPath $aar -Destination 'app/libs/sherpa-onnx-1.13.2.aar' -Force
```

- [ ] **Step 4: Download and extract the official model archive into the temporary directory**

```powershell
$archive = Join-Path $temp 'sherpa-model.tar.bz2'
Invoke-WebRequest 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2' -OutFile $archive
tar -xjf $archive -C $temp
```

Expected: the extracted directory contains INT8 and FP32 ONNX files, `tokens.txt`, and `bpe.model`.

- [ ] **Step 5: Copy only required INT8 assets and download the matching `bpe.vocab`**

```powershell
$modelName = 'sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16'
$source = Join-Path $temp $modelName
$target = Join-Path 'app/src/main/assets/models' $modelName
New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'encoder-epoch-99-avg-1.int8.onnx') -Destination $target
Copy-Item -LiteralPath (Join-Path $source 'decoder-epoch-99-avg-1.int8.onnx') -Destination $target
Copy-Item -LiteralPath (Join-Path $source 'joiner-epoch-99-avg-1.int8.onnx') -Destination $target
Copy-Item -LiteralPath (Join-Path $source 'tokens.txt') -Destination $target
Invoke-WebRequest 'https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t/resolve/main/data/lang_char_bpe/bpe.vocab?download=true' -OutFile (Join-Path $target 'bpe.vocab')
Get-ChildItem -LiteralPath $target | Select-Object Name,Length,@{Name='SHA256';Expression={(Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()}}
```

Expected: exactly five files at this point; save the printed lengths and hashes for `SherpaOnnxModelStore.AssetManifest` in Task 3.

- [ ] **Step 6: Add the local AAR and ONNX no-compress rule**

Add to `app/build.gradle.kts`:

```kotlin
androidResources {
    noCompress += "gguf"
    noCompress += "bin"
    noCompress += "onnx"
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
}
```

Keep existing entries and add only the new `onnx` and AAR lines.

- [ ] **Step 7: Verify the official Kotlin API compiles**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL` before any production reference to sherpa classes is added.

---

### Task 2: Fixed Hotwords and Transcript Normalization

**Files:**
- Create: `app/src/main/assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/hotwords.txt`
- Create: `app/src/main/java/com/example/myapp/voice/SpeechTranscriptNormalizer.kt`
- Create: `app/src/test/java/com/example/myapp/voice/SpeechTranscriptNormalizerTest.kt`
- Create: `app/src/test/java/com/example/myapp/voice/VoiceHotwordsTest.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/VoiceIntentParser.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/VoiceIntentParserTest.kt`

**Interfaces:**
- Produces: `object SpeechTranscriptNormalizer { fun normalize(text: String): String }`.
- Produces: a UTF-8 `hotwords.txt` accepted by sherpa's `cjkchar+bpe` tokenizer.
- Changes: `VoiceIntentParser.parse()` normalizes once before both direct parsing and Qwen prompting.

- [ ] **Step 1: Write failing normalizer tests**

```kotlin
class SpeechTranscriptNormalizerTest {
    @Test fun normalizesSpacedLvForms() {
        assertEquals("把二号机的 Lv1 灵敏度调整到97", SpeechTranscriptNormalizer.normalize("把二号机的 l v 1 灵敏度调整到97"))
        assertEquals("machine three Lv2 strength 80", SpeechTranscriptNormalizer.normalize("machine three L V TWO strength 80"))
        assertEquals("Lv3 强度", SpeechTranscriptNormalizer.normalize("L   V   3 强度"))
    }

    @Test fun leavesLettersInsideWordsUntouched() {
        assertEquals("level vivid love 1", SpeechTranscriptNormalizer.normalize("level vivid love 1"))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.myapp.voice.SpeechTranscriptNormalizerTest'
```

Expected: compilation fails because `SpeechTranscriptNormalizer` does not exist.

- [ ] **Step 3: Implement boundary-safe normalization**

```kotlin
object SpeechTranscriptNormalizer {
    private val spacedLv = Regex(
        pattern = """(?i)(?<![A-Za-z0-9])l\s+v\s+(1|2|3|one|two|three)(?![A-Za-z0-9])"""
    )
    private val levels = mapOf("one" to "1", "two" to "2", "three" to "3")

    fun normalize(text: String): String = spacedLv.replace(text) { match ->
        val rawLevel = match.groupValues[1].lowercase()
        "Lv${levels[rawLevel] ?: rawLevel}"
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run the same focused test. Expected: all normalizer tests pass.

- [ ] **Step 5: Write the failing parser integration test**

Add to `VoiceIntentParserTest.kt` a fake Qwen engine that fails if called, then assert:

```kotlin
@Test fun spacedLvTranscriptUsesDeterministicParserWithoutQwen() = runTest {
    val parser = VoiceIntentParser(FailIfCalledQwenEngine())
    val result = parser.parse("把二号机的 l v 1 灵敏度调整到 32").getOrThrow()
    assertEquals(MachineDevice.machine_2, result.device)
    assertEquals(VoiceParameter.lv1Sensitivity, result.parameter)
    assertEquals(ParameterValue.IntValue(32), result.value)
}
```

- [ ] **Step 6: Normalize before both parser paths**

Change `VoiceIntentParser.parse()` to compute:

```kotlin
val normalizedTranscript = SpeechTranscriptNormalizer.normalize(transcript)
return when (val directResult = directParser(normalizedTranscript)) {
    // Existing branches remain, but Qwen promptFor also receives normalizedTranscript.
}
```

Run `VoiceIntentParserTest`; expected: all tests pass.

- [ ] **Step 7: Write the failing hotword coverage test**

`VoiceHotwordsTest` must load the source asset, strip trailing ` :score`, and assert that all three device families, `L V 1/2/3`, every `VoiceParameter`, and the user-requested Chinese phrases have at least one mapped phrase. The key assertion is:

```kotlin
assertEquals(VoiceParameter.values().toSet(), expectedPhrasesByParameter.keys)
expectedPhrasesByParameter.values.flatten().forEach { phrase ->
    assertTrue("Missing hotword: $phrase", phrases.contains(phrase))
}
```

Run the test. Expected: FAIL because `hotwords.txt` does not exist.

- [ ] **Step 8: Create the fixed UTF-8 hotword asset**

Use this complete initial content:

```text
一号机 :3.0
二号机 :3.0
三号机 :3.0
MACHINE ONE :3.0
MACHINE TWO :3.0
MACHINE THREE :3.0
LV1 :3.5
LV2 :3.5
LV3 :3.5
L V 1 :3.5
L V 2 :3.5
L V 3 :3.5
LEVEL ONE :3.0
LEVEL TWO :3.0
LEVEL THREE :3.0
灵敏度 :3.0
敏感度 :3.0
强度 :2.5
浓淡 :3.0
密度 :2.5
最小面积 :3.0
增强推理 :3.0
增强推断 :3.0
面积屏蔽 :3.0
区域屏蔽 :3.0
区域遮罩 :3.0
区域掩码 :3.0
动作持续时间 :3.0
动作时长 :3.0
剔除延时 :3.0
拒绝延迟 :3.0
拒绝等待 :3.0
模板 :3.0
TEMPLATE :3.0
SENSITIVITY :2.5
STRENGTH :2.5
DENSITY :2.5
MINIMUM AREA :3.0
ENHANCED INFERENCE :3.0
AREA MASK :3.0
ACTION DURATION :3.0
REJECT DELAY :3.0
400 M M BASE ENGINE :3.0
```

- [ ] **Step 9: Verify hotwords and normalization together**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.myapp.voice.SpeechTranscriptNormalizerTest' --tests 'com.example.myapp.voice.VoiceIntentParserTest' --tests 'com.example.myapp.voice.VoiceHotwordsTest'
```

Expected: all three test classes pass.

---

### Task 3: Verified Multi-file Model Store

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/SherpaOnnxModelStore.kt`
- Create: `app/src/test/java/com/example/myapp/voice/SherpaOnnxModelStoreTest.kt`

**Interfaces:**
- Produces: `data class SherpaOnnxModelFiles(val encoder: File, val decoder: File, val joiner: File, val tokens: File, val bpeVocab: File, val hotwords: File)`.
- Produces: `class SherpaOnnxModelStore { suspend fun prepare(): SherpaOnnxModelFiles }`.
- Uses: six `AssetManifest` entries with exact path, file name, length, and SHA-256 captured from Task 1 plus `hotwords.txt` from Task 2.

- [ ] **Step 1: Write failing store tests using small in-memory assets**

Cover these independent cases:

```kotlin
@Test fun prepareCopiesAndReturnsAllSixVerifiedFiles() = runTest { /* six manifests; assert content */ }
@Test fun prepareReusesFilesWhenLengthAndHashMatch() = runTest { /* open count remains one */ }
@Test fun truncatedFileIsReplacedAtomically() = runTest { /* seed bad final and stale .partial */ }
@Test fun hashMismatchDeletesPartialAndThrowsModelPreparationException() = runTest { /* wrong expected hash */ }
@Test fun concurrentPrepareForSameDirectoryCopiesEachAssetOnce() = runTest { /* async prepare twice */ }
```

Use temporary directories and injected `openAsset: (String) -> InputStream`; no Android `Context` is needed in JVM tests.

- [ ] **Step 2: Run and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.myapp.voice.SherpaOnnxModelStoreTest'
```

Expected: compilation fails because the store and model file types do not exist.

- [ ] **Step 3: Implement the model file contract and manifest loop**

The public constructor uses:

```kotlin
constructor(context: Context) : this(
    modelDirectory = File(context.filesDir, "models/$MODEL_DIRECTORY"),
    manifests = ASSET_MANIFESTS,
    openAsset = context.assets::open
)
```

For each manifest, copy `assetPath` to `<name>.partial`, verify exact length and SHA-256, then publish with `Files.move(..., StandardCopyOption.ATOMIC_MOVE)`. Return a `SherpaOnnxModelFiles` only after every entry is verified.

- [ ] **Step 4: Fill exact production lengths and hashes**

Run:

```powershell
$target = 'app/src/main/assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16'
Get-ChildItem -File -LiteralPath $target | Sort-Object Name | ForEach-Object {
    [PSCustomObject]@{
        Name = $_.Name
        Length = $_.Length
        SHA256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    }
}
```

Transcribe all six exact values into `ASSET_MANIFESTS`; do not derive trusted hashes from files already copied into private storage.

- [ ] **Step 5: Verify GREEN and regression against existing model-store tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.myapp.voice.SherpaOnnxModelStoreTest' --tests 'com.example.myapp.voice.QwenModelStoreTest' --tests 'com.example.myapp.voice.WhisperModelStoreTest'
```

Expected: all tests pass before Whisper removal.

---

### Task 4: Streaming Engine and Official sherpa Adapter

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/StreamingSpeechEngine.kt`
- Create: `app/src/test/java/com/example/myapp/voice/StreamingSpeechEngineTest.kt`

**Interfaces:**
- Produces: `StreamingSpeechEngine.prepare/startSession/acceptSamples/finishSession/cancelSession/close`.
- Produces: `fun pcm16ToFloat(samples: ShortArray): FloatArray`.
- Internal boundary: `SherpaRecognizerFactory`, `SherpaRecognizerApi`, and `SherpaStreamApi` so JVM tests never load native libraries.
- Consumes: `SherpaOnnxModelFiles` from Task 3.

- [ ] **Step 1: Write failing PCM conversion tests**

```kotlin
@Test fun pcm16ConversionUsesSherpaRange() {
    assertArrayEquals(
        floatArrayOf(-1.0f, 0.0f, 32767f / 32768f),
        pcm16ToFloat(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)),
        0.000001f
    )
}
```

Run the focused test and verify failure because the function is absent.

- [ ] **Step 2: Implement and verify PCM conversion**

```kotlin
fun pcm16ToFloat(samples: ShortArray): FloatArray =
    FloatArray(samples.size) { index -> samples[index] / 32768.0f }
```

Run the focused test; expected: PASS.

- [ ] **Step 3: Write failing engine lifecycle tests with fake recognizer/stream**

Cover:

```kotlin
@Test fun prepareCreatesRecognizerOnlyOnce() = runTest { /* prepare twice */ }
@Test fun acceptFeeds16000HzAndDecodesUntilNotReady() = runTest { /* ready sequence true,true,false */ }
@Test fun changedPartialTextIsEmittedAtMostEvery100Millis() = runTest { /* injected clock */ }
@Test fun finishAdds800MillisTailThenInputFinishedAndReturnsTrimmedFinal() = runTest { /* 12800 zeros */ }
@Test fun cancelReleasesOnlyCurrentStreamWithoutFinalText() = runTest { /* verify release */ }
@Test fun closeReleasesStreamRecognizerAndDispatcherOnce() = runTest { /* idempotent close */ }
@Test fun secondSessionCannotReuseFirstSessionStream() = runTest { /* distinct stream identities */ }
```

- [ ] **Step 4: Run and verify RED**

Expected: compilation fails because `StreamingSpeechEngine` and `SherpaOnnxStreamingEngine` do not exist.

- [ ] **Step 5: Implement the engine contract and serialized session state**

Use this exact contract:

```kotlin
interface StreamingSpeechEngine : AutoCloseable {
    suspend fun prepare()
    suspend fun startSession(onPartialText: (String) -> Unit)
    suspend fun acceptSamples(samples: ShortArray)
    suspend fun finishSession(): String
    suspend fun cancelSession()
    override fun close()
}
```

All recognizer and stream calls run on one owned single-thread coroutine dispatcher. `finishSession()` accepts `FloatArray(12_800)`, calls `inputFinished()`, drains `isReady/decode`, reads final text, and releases the stream in `finally`.

- [ ] **Step 6: Implement the v1.13.2 adapter and exact config**

The production factory constructs:

```kotlin
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
    hotwordsScore = 2.0f
)
```

Wrap `OnlineRecognizer`, `OnlineStream.acceptWaveform`, `inputFinished`, `isReady`, `decode`, `getResult().text`, and both `release()` methods. Do not expose sherpa types through the public engine contract.

- [ ] **Step 7: Verify engine tests and Kotlin compilation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.myapp.voice.StreamingSpeechEngineTest'
.\gradlew.bat :app:compileDebugKotlin
```

Expected: both commands succeed without loading the native library in JVM tests.

---

### Task 5: Stream PCM Chunks Without Blocking AudioRecord

**Files:**
- Modify: `app/src/main/java/com/example/myapp/voice/PcmRecorder.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/AndroidPcmRecorderTest.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/PcmRecordingPolicyTest.kt`

**Interfaces:**
- Changes `PcmRecorder.start()` to `suspend fun start(onSamples: (ShortArray) -> Unit)`.
- Changes `PcmRecorder.stop()` to return the exact recorded sample count as `Int`.
- Keeps `cancel()` and `PcmRecordingPolicy.MIN_SAMPLES/MAX_SAMPLES/MAX_DURATION_MILLIS`.

- [ ] **Step 1: Rewrite recorder tests first for chunk delivery**

Add assertions that:

```kotlin
@Test fun readChunksAreCopiedAndDeliveredInOrder() = runTest { /* mutate fake read buffer later; delivered chunks stay unchanged */ }
@Test fun stopReturnsTotalDeliveredSampleCount() = runTest { /* reads 1024 then 700; returns 1724 */ }
@Test fun cancelStopsWithoutReturningOrDeliveringAfterCancellation() = runTest { /* no late callback */ }
@Test fun maximumSampleCountStillStopsAt320000() = runTest { /* exact cap */ }
```

Update existing fake callers to pass an `onSamples` callback. Run `AndroidPcmRecorderTest`; expected: compile failure against the old interface.

- [ ] **Step 2: Replace the accumulating recording buffer with chunk callbacks**

The new interface is:

```kotlin
interface PcmRecorder {
    suspend fun start(onSamples: (ShortArray) -> Unit)
    suspend fun stop(): Int
    suspend fun cancel()
}
```

For every positive `AudioRecord.read`, call `onSamples(readBuffer.copyOf(samplesRead))`, then increment `sampleCount`. Never call ONNX from the AudioRecord read coroutine.

- [ ] **Step 3: Preserve existing race and cleanup guarantees**

Keep the mutex ownership transfer, stop/join/release ordering, release-once flag, startup serialization, read error mapping, and 320,000 sample cap. `cancel()` discards the count, while `stop()` returns it.

- [ ] **Step 4: Run focused recorder tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.myapp.voice.AndroidPcmRecorderTest' --tests 'com.example.myapp.voice.PcmRecordingPolicyTest'
```

Expected: all recorder and policy tests pass.

---

### Task 6: Generalize the Controller for Live Streaming

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/LocalSpeechController.kt`
- Create: `app/src/test/java/com/example/myapp/voice/LocalSpeechControllerTest.kt`
- Keep temporarily: `LocalWhisperController.kt` and its tests until Task 8.

**Interfaces:**
- Consumes: streaming `PcmRecorder` from Task 5 and `StreamingSpeechEngine` from Task 4.
- Produces callbacks: `onSessionCreated`, `onRecordingStarted`, `onPartialText`, `onRecordingStopped`, `onFinalText`, and `onError`.
- Preserves: manual/timeout single-finalization claim, 20-second timer, generation isolation, and non-cancellable recorder cleanup.
- Owns: the injected `StreamingSpeechEngine`; `close()` cancels the recorder/session and closes the engine exactly once.

- [ ] **Step 1: Port existing controller tests under the new name before production code**

Copy behavioral coverage from `LocalWhisperControllerTest` and adapt fake interfaces. Preserve tests for immediate stop during startup, manual/timeout races, double stop, recorder failures, blank final text, cancellation, stale callbacks, close, callback dispatcher ordering, and processor failure.

Run `LocalSpeechControllerTest`; expected: compilation fails because the controller does not exist.

- [ ] **Step 2: Add failing streaming-specific tests**

Cover:

```kotlin
@Test fun sessionCreatedPrecedesModelPreparationAndRecordingStarted() = runTest { /* event order */ }
@Test fun recorderChunksReachEngineInOrderOnConsumerJob() = runTest { /* three distinct chunks */ }
@Test fun partialTextIsNormalizedBeforeCallback() = runTest { /* "l v 1" becomes "Lv1" */ }
@Test fun recordingStoppedPrecedesQueueDrainAndFinalText() = runTest { /* blocked accept */ }
@Test fun partialTextNeverPublishesAfterStopCancelOrNewSession() = runTest { /* stale generations */ }
@Test fun shortRecordingCancelsStreamAndNeverFinishesIt() = runTest { /* count below 4800 */ }
@Test fun modelPreparationFailureOccursBeforeRecorderStart() = runTest { /* startSession throws */ }
@Test fun decodeFailureStopsRecorderCancelsStreamAndPublishesServiceError() = runTest { /* accept throws */ }
```

- [ ] **Step 3: Implement session-owned audio queue and consumer**

Each `Session` owns `Channel<ShortArray>(Channel.UNLIMITED)` and an inference consumer job. Recorder callbacks only execute:

```kotlin
if (session.isValid()) {
    session.audioChunks.trySend(samples)
}
```

Start order is: publish session created, `engine.startSession`, start recorder, publish recording started, then start consuming buffered chunks. Stop order is: stop recorder, close chunk channel, publish recording stopped, join consumer, check minimum sample count, then `finishSession`.

- [ ] **Step 4: Preserve error taxonomy**

Map model-store preparation errors to `Unavailable / "本地语音模型准备失败"`, recorder failures to `Audio / "麦克风录音失败，请重试"`, blank finals to `NoMatch / "未识别到有效语音"`, and stream/native failures to `Service / "本地语音识别失败，请重试"`.

- [ ] **Step 5: Run the complete new controller test class**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.myapp.voice.LocalSpeechControllerTest'
```

Expected: all legacy-equivalent and streaming-specific controller cases pass.

---

### Task 7: Extend Voice State and Compose for Partial Text

**Files:**
- Modify: `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/VoiceUiStateTest.kt`
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`
- Modify: `app/src/test/java/com/example/myapp/MainActivityWhisperScopeTest.kt` by renaming it to `MainActivitySpeechScopeTest.kt` and changing source assertions.

**Interfaces:**
- Produces: `VoiceUiState.PreparingSpeechModel(generation)`.
- Changes: `VoiceUiState.Recording(generation, partialTranscript = "")`.
- Adds: `VoiceEvent.RecordingStarted(generation)` and `VoiceEvent.PartialText(generation, transcript)`.

- [ ] **Step 1: Write failing reducer tests**

```kotlin
@Test fun newSessionWaitsForSpeechModel() {
    assertEquals(VoiceUiState.PreparingSpeechModel(5), reduceVoiceState(VoiceUiState.Idle, VoiceEvent.NewSession(5)))
}

@Test fun recordingStartsOnlyAfterControllerCallback() {
    assertEquals(VoiceUiState.Recording(5, ""), reduceVoiceState(VoiceUiState.PreparingSpeechModel(5), VoiceEvent.RecordingStarted(5)))
}

@Test fun currentPartialUpdatesOnlyRecording() {
    assertEquals(VoiceUiState.Recording(5, "Lv1 灵敏度"), reduceVoiceState(VoiceUiState.Recording(5, ""), VoiceEvent.PartialText(5, "Lv1 灵敏度")))
}
```

Also assert stale partials and partials in `Transcribing/Parsing/Ready` are ignored.

- [ ] **Step 2: Run reducer tests and verify RED**

Expected: compilation fails on the new state/event types.

- [ ] **Step 3: Implement reducer and microphone action changes**

`NewSession` enters `PreparingSpeechModel`; that state accepts only `RecordingStarted` and `RecognitionFailed`. `Recording` accepts `PartialText`, `RecordingStopped`, and `RecognitionFailed`. `microphoneActionFor(PreparingSpeechModel)` is `Ignore`; recording remains `StopAndTranscribe`.

Run `VoiceUiStateTest`; expected: all tests pass.

- [ ] **Step 4: Replace Whisper construction in `DetectionParametersScreen`**

Use:

```kotlin
val speechModelStore = remember(applicationContext) { SherpaOnnxModelStore(applicationContext) }
val speechEngine = remember(speechModelStore) { SherpaOnnxStreamingEngine(speechModelStore) }
val pcmRecorder = remember { AndroidPcmRecorder() }
val localSpeechController = remember(pcmRecorder, speechEngine) {
    LocalSpeechController(
        pcmRecorder = pcmRecorder,
        speechEngine = speechEngine,
        transcriptNormalizer = SpeechTranscriptNormalizer::normalize,
        // Existing generation-safe callbacks plus recording-started and partial-text callbacks.
    )
}
```

Add a `LaunchedEffect(speechEngine)` that calls `runCatching { speechEngine.prepare() }`; startup failure is retried and surfaced when the user starts a session.

- [ ] **Step 5: Wire callbacks without changing Qwen or TCP semantics**

- `onSessionCreated` begins the voice generation and dispatches `NewSession`.
- `onRecordingStarted` dispatches `RecordingStarted`.
- `onPartialText` dispatches `PartialText` only after tracker resolution.
- `onRecordingStopped`, final parsing, apply, and TCP callbacks keep their existing generation checks.
- Lifecycle stop and cancellation call `localSpeechController.cancel()`. `DisposableEffect` calls `localSpeechController.close()` and does not call `speechEngine.close()` separately, because the controller owns the engine.

- [ ] **Step 6: Show preparation and live partial text in the existing card**

In `VoiceTuningCard`:

```kotlin
val transcript = when (state) {
    is VoiceUiState.Recording -> state.partialTranscript
    // Existing final transcript states remain unchanged.
    else -> existingTranscriptOrEmpty
}
```

Use status text `正在加载语音模型` for `PreparingSpeechModel`, `正在聆听，再次点击结束` for recording, and `正在整理识别结果` for `Transcribing`. Do not add a card, dialog, or settings surface.

- [ ] **Step 7: Replace source-contract tests**

Rename the current Whisper-specific main activity source test and assert that `MainActivity.kt` contains `LocalSpeechController`, `SherpaOnnxStreamingEngine`, `VoiceEvent.PartialText`, lifecycle cancellation, and close calls, while containing no `LocalWhisperController` or `NativeWhisperInferenceEngine` references.

- [ ] **Step 8: Run state, parser, and main source tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.myapp.voice.VoiceUiStateTest' --tests 'com.example.myapp.voice.VoiceIntentParserTest' --tests 'com.example.myapp.MainActivitySpeechScopeTest'
```

Expected: all tests pass.

---

### Task 8: Remove Whisper and Complete the Replacement

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `app/src/main/java/com/example/myapp/voice/QwenInferenceEngine.kt` comment only
- Delete: all Whisper files listed in the File Map.

**Interfaces:**
- Removes: `WhisperInferenceEngine`, `WhisperModelStore`, `LocalWhisperController`, JNI module, model, and tests.
- Keeps: the general `SpeechRecognitionText`, failure types, recorder, Qwen, parser, validation, and TCP interfaces.

- [ ] **Step 1: Prove the new path is green before deletion**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:compileDebugKotlin
```

Expected: both succeed while old and new implementations coexist.

- [ ] **Step 2: Remove Gradle references**

Delete `implementation(project(":whisper-native"))` from `app/build.gradle.kts` and `include(":whisper-native")` from `settings.gradle.kts`. Update the Qwen comment from “以便 Whisper 阶段” to wording about releasing the large model after each request.

- [ ] **Step 3: Verify destructive targets are inside this workspace**

```powershell
$root = (Resolve-Path '.').Path
$targets = @(
    'app/src/main/assets/models/ggml-small-q5_1.bin',
    'whisper-native',
    'third_party/whisper.cpp'
)
$resolved = $targets | ForEach-Object { (Resolve-Path -LiteralPath $_).Path }
if ($resolved | Where-Object { -not $_.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase) }) { throw 'Refusing to remove a path outside the workspace' }
$resolved
```

Expected: every printed path starts with `D:\code\android\MyApp2`.

- [ ] **Step 4: Delete obsolete binary/module directories and source files**

Use native PowerShell `Remove-Item -LiteralPath` only on the verified paths. Delete the three Whisper Kotlin sources and three Whisper-specific test files with `apply_patch`. Do not remove Qwen assets or JNI.

- [ ] **Step 5: Search for stale production references**

```powershell
rg -n 'Whisper|whisper-native|whisper_jni|ggml-small-q5_1' app/src/main app/build.gradle.kts settings.gradle.kts
```

Expected: no matches. Documentation and historical design plans may still mention Whisper.

- [ ] **Step 6: Run all JVM tests after deletion**

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, zero failed/error/skipped tests, and all new streaming tests included.

---

### Task 9: Final Build and APK Content Verification

**Files:**
- Verify only; production changes are made only if a verification exposes a defect.

**Interfaces:**
- Consumes: complete sherpa streaming implementation.
- Produces: build evidence suitable for opening and running from Android Studio, without device testing.

- [ ] **Step 1: Run one fresh combined verification**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Count JVM results**

```powershell
$files = Get-ChildItem 'app/build/test-results/testDebugUnitTest' -Filter 'TEST-*.xml'
$tests = $failures = $errors = $skipped = 0
foreach ($file in $files) {
    [xml]$xml = Get-Content $file.FullName
    $tests += [int]$xml.testsuite.tests
    $failures += [int]$xml.testsuite.failures
    $errors += [int]$xml.testsuite.errors
    $skipped += [int]$xml.testsuite.skipped
}
"tests=$tests failures=$failures errors=$errors skipped=$skipped"
```

Expected: `failures=0 errors=0 skipped=0`.

- [ ] **Step 3: Inspect packaged native libraries and model assets**

```powershell
$apk = 'app/build/outputs/apk/debug/app-debug.apk'
$entries = & jar tf $apk
$entries | Select-String 'sherpa|onnx|whisper|ggml-small'
```

Required entries:

```text
lib/arm64-v8a/libsherpa-onnx-jni.so
lib/arm64-v8a/libonnxruntime.so
assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/encoder-epoch-99-avg-1.int8.onnx
assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/decoder-epoch-99-avg-1.int8.onnx
assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/joiner-epoch-99-avg-1.int8.onnx
assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/tokens.txt
assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/bpe.vocab
assets/models/sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16/hotwords.txt
```

Forbidden entries: `libwhisper_jni.so`, `ggml-small-q5_1.bin`, any FP32 Zipformer encoder/joiner/decoder, and native libraries for non-arm64 ABIs.

- [ ] **Step 4: Review requirements line by line**

Confirm from code/tests that recording streams while active, partial text is display-only, final text alone enters the parser, fixed hotwords include `L V 1`, 20-second timeout remains, Qwen/TCP are unchanged, and no runtime device claim is made.

- [ ] **Step 5: Report the verification boundary clearly**

Final report must state the exact JVM test count and build commands. It must also state that sherpa native loading, actual recognition accuracy, hotword gain, and latency were not tested on a phone or emulator by user request.
