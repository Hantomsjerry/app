# SenseVoice Offline ASR and Fuzzy Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace streaming Zipformer ASR with bundled SenseVoice offline recognition, globally correct likely transcript errors, and show raw and corrected text on two unlabeled UI lines before applying a validated command.

**Architecture:** Keep the existing manual 20-second PCM capture and session-oriented engine interface, but back it with one resident sherpa-onnx `OfflineRecognizer` and one disposable `OfflineStream` per utterance. Run the final transcript through a shared control vocabulary and a global fuzzy candidate search, preserve raw and corrected text as separate state values, and block parsing whenever the best correction is ambiguous.

**Tech Stack:** Kotlin, Android 10+ (API 29), Jetpack Compose, Kotlin coroutines, Android ICU `Transliterator`, sherpa-onnx `1.13.2`, JUnit 4, AndroidX Compose UI tests

**Spec:** `docs/superpowers/specs/2026-08-19-sensevoice-fuzzy-correction-design.md`

## Global Constraints

- Bundle exactly `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` for ASR.
- Use `model.int8.onnx` with SHA-256 `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51` and byte length `239233841`.
- Configure SenseVoice language as `auto`, inverse text normalization as enabled, sample rate as `16000`, feature dimension as `80`, CPU provider, and two inference threads.
- Remove all old Zipformer encoder, decoder, joiner, BPE, token, and hotword assets after the SenseVoice path passes tests.
- Keep manual second-tap finalization and the existing 20-second recording cap.
- Keep one recognizer resident while the page is alive; release each offline stream and PCM payload after its utterance.
- Display raw text above corrected text with no labels; keep both lines even when equal.
- Parse only corrected text. A correction is ambiguous when two valid candidates differ by less than `0.08`; accepted candidates require a score of at least `0.82`.
- Do not impose a replacement-count or replacement-ratio limit.
- Preserve deterministic parsing, local Qwen fallback, integer/range safety checks, and explicit user Apply.
- Do not push any branch, commit, model, or APK to GitHub or another remote repository.
- Existing unrelated or previous-session working-tree changes must not be reverted.

---

## File Structure

### New files

- `app/src/main/java/com/example/myapp/voice/SenseVoiceModelStore.kt`: prepares and verifies the two SenseVoice runtime assets.
- `app/src/main/java/com/example/myapp/voice/SenseVoiceSpeechEngine.kt`: adapts sherpa-onnx offline recognizer and stream lifecycles to the existing engine interface.
- `app/src/main/java/com/example/myapp/voice/SpeechCorrectionDictionary.kt`: shared canonical device, parameter, action, Boolean, and template vocabulary.
- `app/src/main/java/com/example/myapp/voice/PinyinEncoder.kt`: injectable Pinyin interface and Android ICU implementation.
- `app/src/main/java/com/example/myapp/voice/FuzzyTranscriptCorrector.kt`: candidate generation, scoring, global non-overlap selection, and ambiguity reporting.
- `app/src/main/java/com/example/myapp/voice/VoiceTranscriptLines.kt`: small Compose component rendering the raw and corrected lines.
- Matching unit and Android instrumentation tests named after each new component.

### Modified files

- `app/src/main/java/com/example/myapp/voice/StreamingSpeechEngine.kt`: retain only the shared engine contract and PCM conversion helper; remove online Sherpa implementation.
- `app/src/main/java/com/example/myapp/voice/LocalSpeechController.kt`: return raw plus corrected results and invoke the corrector after one-shot decoding.
- `app/src/main/java/com/example/myapp/voice/SpeechTranscriptNormalizer.kt`: retain deterministic formatting and remove corrections moved into the shared dictionary.
- `app/src/main/java/com/example/myapp/voice/DeterministicVoiceParser.kt`: consume shared aliases rather than duplicate private alias maps.
- `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`: carry a raw/corrected transcript pair and preserve it through parsing, sending, success, and errors.
- `app/src/main/java/com/example/myapp/MainActivity.kt`: construct SenseVoice components, parse corrected text only, and render the two transcript lines.
- Existing unit and instrumentation tests: migrate online assumptions to offline one-shot behavior.

### Deleted files/assets

- `app/src/main/java/com/example/myapp/voice/SherpaOnnxModelStore.kt`
- `app/src/test/java/com/example/myapp/voice/SherpaOnnxModelStoreTest.kt`
- `app/src/test/java/com/example/myapp/voice/VoiceHotwordsTest.kt`
- `app/src/androidTest/java/com/example/myapp/SherpaAccuracyInstrumentedTest.kt`
- `app/src/main/assets/models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/`

---

### Task 1: Bundle and Verify the SenseVoice Model

**Files:**
- Create: `app/src/main/assets/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/model.int8.onnx`
- Create: `app/src/main/assets/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/tokens.txt`
- Create: `app/src/main/java/com/example/myapp/voice/SenseVoiceModelStore.kt`
- Create: `app/src/test/java/com/example/myapp/voice/SenseVoiceModelStoreTest.kt`
- Keep temporarily: `app/src/main/java/com/example/myapp/voice/SherpaOnnxModelStore.kt`
- Keep temporarily: `app/src/test/java/com/example/myapp/voice/SherpaOnnxModelStoreTest.kt`

**Interfaces:**
- Produces: `data class SenseVoiceModelFiles(val model: File, val tokens: File)`
- Produces: `class SenseVoiceModelStore(context: Context) { suspend fun prepare(): SenseVoiceModelFiles }`
- Reuses: existing `ModelPreparationException`, atomic copy pattern, SHA-256 helper, and per-directory coroutine mutex behavior.

- [ ] **Step 1: Write failing model-store tests**

Cover successful preparation, verified-file reuse, stale `.partial` cleanup, wrong-length rejection, wrong-hash rejection, failed atomic publication cleanup, and plain-file/path confinement. Use tiny byte arrays and injected manifests so tests do not read the 239MB production model.

```kotlin
private val assets = mapOf(
    "model.int8.onnx" to "sense-model".toByteArray(),
    "tokens.txt" to "<blank> 0".toByteArray()
)

@Test
fun preparesExactlyModelAndTokens() = runTest {
    val store = testStore(assets)

    val files = store.prepare()

    assertArrayEquals(assets.getValue("model.int8.onnx"), files.model.readBytes())
    assertArrayEquals(assets.getValue("tokens.txt"), files.tokens.readBytes())
}
```

- [ ] **Step 2: Run the model-store tests and verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.SenseVoiceModelStoreTest"
```

Expected: compilation fails because `SenseVoiceModelStore` and `SenseVoiceModelFiles` do not exist.

- [ ] **Step 3: Implement the two-file model store**

Use these production manifest values:

```kotlin
private const val MODEL_DIRECTORY =
    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

AssetManifest(
    assetPath = "models/$MODEL_DIRECTORY/model.int8.onnx",
    fileName = "model.int8.onnx",
    expectedBytes = 239_233_841L,
    sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
)
```

Download the official release archive into a temporary directory, extract it,
copy only `model.int8.onnx` and `tokens.txt` into the new asset directory, then
calculate the token file's exact byte length and SHA-256 for its manifest.

```powershell
$archive = Join-Path $env:TEMP 'sensevoice-2024-07-17.tar.bz2'
curl.exe -L --fail -o $archive 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2'
tar -xf $archive -C $env:TEMP
Get-FileHash "$env:TEMP\sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17\tokens.txt" -Algorithm SHA256
(Get-Item "$env:TEMP\sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17\tokens.txt").Length
```

Before deleting or replacing assets, resolve both the old and new model paths
and verify they are descendants of `app/src/main/assets/models`.

- [ ] **Step 4: Run focused and existing model-store tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.SenseVoiceModelStoreTest" --tests "com.example.myapp.voice.QwenModelStoreTest"
```

Expected: all model preparation tests pass.

- [ ] **Step 5: Commit locally without pushing**

```powershell
git add -- app/src/main/assets/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17 app/src/main/java/com/example/myapp/voice/SenseVoiceModelStore.kt app/src/test/java/com/example/myapp/voice/SenseVoiceModelStoreTest.kt
git commit -m "feat: bundle verified SenseVoice model"
```

Do not run `git push`.

---

### Task 2: Replace Online Sherpa Decoding with One-Shot Offline Decoding

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/SenseVoiceSpeechEngine.kt`
- Create: `app/src/test/java/com/example/myapp/voice/SenseVoiceSpeechEngineTest.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/StreamingSpeechEngine.kt`
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt:100-102,183-184`
- Delete: `app/src/test/java/com/example/myapp/voice/StreamingSpeechEngineTest.kt`
- Delete: `app/src/main/java/com/example/myapp/voice/SherpaOnnxModelStore.kt`
- Delete: `app/src/test/java/com/example/myapp/voice/SherpaOnnxModelStoreTest.kt`

**Interfaces:**
- Consumes: `SenseVoiceModelStore.prepare(): SenseVoiceModelFiles`
- Keeps: `StreamingSpeechEngine.prepare()`, `startSession((String) -> Unit)`, `acceptSamples(ShortArray, Int)`, `finishSession(): String`, `cancelSession()`, and `close()`.
- Produces: `class SenseVoiceSpeechEngine(modelStore: SenseVoiceModelStore) : StreamingSpeechEngine`
- Produces: `internal fun buildSenseVoiceRecognizerConfig(files: SenseVoiceModelFiles): OfflineRecognizerConfig`

- [ ] **Step 1: Write failing offline lifecycle and configuration tests**

Use fake recognizer/stream adapters and verify:

```kotlin
@Test
fun completePcmIsAcceptedAndDecodedExactlyOnce() = runTest {
    val native = FakeOfflineRecognizer(result = "将Lv2强度调到60")
    val engine = testEngine(native)

    engine.prepare()
    engine.startSession { fail("offline engine must not emit partial text") }
    engine.acceptSamples(shortArrayOf(1, 2, 3, 4), sampleCount = 3)
    val text = engine.finishSession()

    assertEquals(1, native.stream.acceptCalls)
    assertArrayEquals(floatArrayOf(1 / 32768f, 2 / 32768f, 3 / 32768f), native.stream.accepted, 0f)
    assertEquals(1, native.decodeCalls)
    assertEquals("将Lv2强度调到60", text)
    assertEquals(1, native.stream.releaseCalls)
}
```

Also verify recognizer reuse across two sessions, cancel-before-finish, decode
failure cleanup, start cancellation cleanup, idempotent concurrent close, and
rejection of a second active session.

- [ ] **Step 2: Run the offline engine tests and verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.SenseVoiceSpeechEngineTest"
```

Expected: compilation fails because the offline engine does not exist.

- [ ] **Step 3: Implement the offline adapter and config**

The production adapter must call only this per-utterance sequence:

```kotlin
val stream = recognizer.createStream()
stream.acceptWaveform(pcm16ToFloat(samples, sampleCount), 16_000)
recognizer.decode(stream)
val text = recognizer.getResult(stream).text.trim()
stream.release()
```

Build the recognizer with:

```kotlin
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
```

Keep all native calls on the owned single-thread dispatcher. Keep the existing
close/callback synchronization behavior, but remove `OnlineRecognizer`,
`OnlineStream`, `isReady`, `inputFinished`, partial throttling, modified beam
search, and hotword configuration.

At the same time, change only the speech construction imports and two remembered
objects in `DetectionParametersScreen` to `SenseVoiceModelStore` and
`SenseVoiceSpeechEngine`. This keeps the app compiling before the later
correction/UI integration. Once that construction is migrated, delete the old
model-store class and its tests.

- [ ] **Step 4: Run offline engine and controller regression tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.SenseVoiceSpeechEngineTest" --tests "com.example.myapp.voice.LocalSpeechControllerTest"
```

Expected: offline engine tests pass; controller tests remain green with the
unchanged session interface.

- [ ] **Step 5: Commit locally without pushing**

```powershell
git add -- app/src/main/java/com/example/myapp/MainActivity.kt app/src/main/java/com/example/myapp/voice/StreamingSpeechEngine.kt app/src/main/java/com/example/myapp/voice/SenseVoiceSpeechEngine.kt app/src/main/java/com/example/myapp/voice/SherpaOnnxModelStore.kt app/src/test/java/com/example/myapp/voice/SenseVoiceSpeechEngineTest.kt app/src/test/java/com/example/myapp/voice/StreamingSpeechEngineTest.kt app/src/test/java/com/example/myapp/voice/SherpaOnnxModelStoreTest.kt
git commit -m "feat: decode speech with offline SenseVoice"
```

---

### Task 3: Centralize the Machine-Control Vocabulary and Pinyin Encoding

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/SpeechCorrectionDictionary.kt`
- Create: `app/src/main/java/com/example/myapp/voice/PinyinEncoder.kt`
- Create: `app/src/test/java/com/example/myapp/voice/SpeechCorrectionDictionaryTest.kt`
- Create: `app/src/androidTest/java/com/example/myapp/AndroidIcuPinyinEncoderInstrumentedTest.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/DeterministicVoiceParser.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/DeterministicVoiceParserTest.kt`

**Interfaces:**
- Produces: `enum class CorrectionCategory { DEVICE, LEVEL, PARAMETER, ACTION, BOOLEAN, TEMPLATE }`
- Produces: `data class CorrectionLexeme(val canonical: String, val aliases: Set<String>, val category: CorrectionCategory)`
- Produces: `object SpeechCorrectionDictionary { val lexemes: List<CorrectionLexeme>; fun aliases(parameter: VoiceParameter): Set<String>; fun aliases(device: MachineDevice): Set<String> }`
- Produces: `fun interface PinyinEncoder { fun encode(text: String): String }`
- Produces: `class AndroidIcuPinyinEncoder : PinyinEncoder`

- [ ] **Step 1: Write failing dictionary coverage tests**

Assert unique canonical/category pairs and exact coverage of all three devices
and all `VoiceParameter` values. Include at least these aliases:

```kotlin
val required = mapOf(
    "machine_1" to setOf("一号机", "1号机", "machine one", "machine 1"),
    "machine_2" to setOf("二号机", "2号机", "machine two", "machine 2"),
    "machine_3" to setOf("三号机", "3号机", "machine three", "machine 3"),
    "Lv1" to setOf("l v 1", "lv一", "一级"),
    "Lv2" to setOf("l v 2", "lv二", "二级", "绿二", "吕二"),
    "Lv3" to setOf("l v 3", "lv三", "三级"),
    "enhancedInference" to setOf("强化推理", "增强推理", "增强推断"),
    "rejectDelay" to setOf("剔除延时", "剔除延迟", "去除延时", "拒绝延迟", "剔除岩石")
)
```

Move the remaining existing device and parameter aliases from
`DeterministicVoiceParser` unchanged into the shared dictionary so parser
coverage cannot regress.

- [ ] **Step 2: Run dictionary and parser tests and verify the new tests fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.SpeechCorrectionDictionaryTest" --tests "com.example.myapp.voice.DeterministicVoiceParserTest"
```

Expected: dictionary symbols are unresolved.

- [ ] **Step 3: Implement the shared dictionary and parser migration**

Replace private parser maps with calls to `SpeechCorrectionDictionary.aliases`.
Keep parser matching boundaries and safety behavior unchanged.

Implement production Pinyin conversion with Android ICU:

```kotlin
class AndroidIcuPinyinEncoder : PinyinEncoder {
    private val transliterator = Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower()")

    override fun encode(text: String): String = transliterator
        .transliterate(text)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
}
```

The fuzzy corrector unit tests will inject a deterministic fake encoder; only
the Android instrumentation test exercises ICU.

- [ ] **Step 4: Verify ICU output on Android and parser compatibility**

Instrumentation expectations should compare stable normalized prefixes, not
tone-mark formatting:

```kotlin
assertEquals("lv2qiangdu", encoder.encode("Lv2强度"))
assertEquals(encoder.encode("剔除延时"), encoder.encode("提出岩石"))
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.SpeechCorrectionDictionaryTest" --tests "com.example.myapp.voice.DeterministicVoiceParserTest"
.\gradlew.bat -Px86EmulatorBuild=true :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapp.AndroidIcuPinyinEncoderInstrumentedTest
```

- [ ] **Step 5: Commit locally without pushing**

```powershell
git add -- app/src/main/java/com/example/myapp/voice/SpeechCorrectionDictionary.kt app/src/main/java/com/example/myapp/voice/PinyinEncoder.kt app/src/main/java/com/example/myapp/voice/DeterministicVoiceParser.kt app/src/test/java/com/example/myapp/voice/SpeechCorrectionDictionaryTest.kt app/src/test/java/com/example/myapp/voice/DeterministicVoiceParserTest.kt app/src/androidTest/java/com/example/myapp/AndroidIcuPinyinEncoderInstrumentedTest.kt
git commit -m "refactor: share voice correction vocabulary"
```

---

### Task 4: Implement Global Fuzzy Transcript Correction

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/FuzzyTranscriptCorrector.kt`
- Create: `app/src/test/java/com/example/myapp/voice/FuzzyTranscriptCorrectorTest.kt`
- Modify: `app/src/main/java/com/example/myapp/voice/SpeechTranscriptNormalizer.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/SpeechTranscriptNormalizerTest.kt`

**Interfaces:**
- Consumes: `SpeechCorrectionDictionary.lexemes`
- Consumes: `PinyinEncoder.encode(String): String`
- Produces: `data class CorrectionReplacement(val start: Int, val endExclusive: Int, val source: String, val replacement: String, val score: Float, val reason: CorrectionReason)`
- Produces: `enum class CorrectionReason { EXACT_ALIAS, PINYIN, EDIT_DISTANCE, REPETITION, CONTEXT }`
- Produces: `data class CorrectionResult(val rawText: String, val correctedText: String, val confidence: Float, val ambiguous: Boolean, val replacements: List<CorrectionReplacement>)`
- Produces: `class FuzzyTranscriptCorrector(...){ fun correct(rawText: String): CorrectionResult }`

- [ ] **Step 1: Write failing correction tests**

Cover exact aliases, Pinyin matches, edit-distance matches, repeated phrases,
mixed Chinese/English forms, no-op text, unlimited replacement count, and
ambiguity:

```kotlin
@Test
fun correctsObservedLv2AndRejectDelayErrorsAcrossSentence() {
    val result = corrector.correct("把二号几的绿二强度调到60并把剔除岩石改成700")

    assertEquals("把machine_2的Lv2强度调到60并把剔除延时改成700", result.correctedText)
    assertFalse(result.ambiguous)
    assertTrue(result.replacements.size >= 3)
}

@Test
fun equalPlausibleCandidatesAreAmbiguous() {
    val result = correctorWithEntries(
        lexeme("甲", "架"),
        lexeme("价", "架")
    ).correct("架")

    assertTrue(result.ambiguous)
}

@Test
fun correctionCountDoesNotCauseRejection() {
    val result = corrector.correct("一号几绿二剔除岩石增强推断动作持序")

    assertFalse(result.ambiguous)
    assertTrue(result.replacements.size > 3)
}
```

- [ ] **Step 2: Run the correction tests and verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.FuzzyTranscriptCorrectorTest"
```

Expected: correction types do not exist.

- [ ] **Step 3: Implement candidate generation and scoring**

Normalize formatting first, then enumerate substrings whose normalized length
is within two characters/tokens of each lexeme alias. Score each candidate as:

```kotlin
val score = when {
    normalizedSource in normalizedAliases -> 1.0f
    else -> (
        0.55f * normalizedSimilarity(pinyin(source), pinyin(alias)) +
        0.35f * normalizedSimilarity(source, alias) +
        0.10f * contextCompatibility(category, surroundingText)
    )
}
```

`normalizedSimilarity(a, b)` is `1 - levenshtein(a,b) / max(a.length,b.length)`
and returns `1` for two empty values. Drop scores below `0.82`. For each source
span, mark it ambiguous when the top two distinct canonical replacements differ
by less than `0.08`.

Use dynamic programming over source indices to select the highest total score
set of non-overlapping replacements. Prefer, in order: higher total score,
longer covered source length, fewer replacements, then canonical lexical order.
Propagate ambiguity if any selected span is ambiguous. Apply replacements from
right to left so source indexes remain valid.

After lexical replacement, run deterministic number/repetition normalization.
When no replacements occur, return the normalized text, confidence `1.0f`, and
`ambiguous = false`. Do not add any replacement-count or ratio check.

- [ ] **Step 4: Remove migrated hard-coded decoder repairs**

Delete direct substitutions such as `关闭币避强化推理` and `剔除岩石` from
`SpeechTranscriptNormalizer`. Retain wake-phrase removal, CJK repetition
collapse, repeated Chinese number phrase collapse, punctuation/spacing, spoken
machine forms, and `L V 1/2/3` formatting. Update normalizer tests to assert
formatting only; move lexical-error assertions to the corrector tests.

- [ ] **Step 5: Run correction, normalizer, and parser suites**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.FuzzyTranscriptCorrectorTest" --tests "com.example.myapp.voice.SpeechTranscriptNormalizerTest" --tests "com.example.myapp.voice.DeterministicVoiceParserTest" --tests "com.example.myapp.voice.VoiceIntentParserTest"
```

Expected: all tests pass, including negation rejection and single-parameter
commands.

- [ ] **Step 6: Commit locally without pushing**

```powershell
git add -- app/src/main/java/com/example/myapp/voice/FuzzyTranscriptCorrector.kt app/src/main/java/com/example/myapp/voice/SpeechTranscriptNormalizer.kt app/src/test/java/com/example/myapp/voice/FuzzyTranscriptCorrectorTest.kt app/src/test/java/com/example/myapp/voice/SpeechTranscriptNormalizerTest.kt
git commit -m "feat: correct voice transcripts with fuzzy matching"
```

---

### Task 5: Integrate Correction into the Buffered Speech Controller

**Files:**
- Modify: `app/src/main/java/com/example/myapp/voice/LocalSpeechController.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/LocalSpeechControllerTest.kt`
- Keep: `app/src/main/java/com/example/myapp/voice/PcmSessionBuffer.kt`
- Keep: `app/src/test/java/com/example/myapp/voice/PcmSessionBufferTest.kt`

**Interfaces:**
- Consumes: `FuzzyTranscriptCorrector.correct(String): CorrectionResult`
- Produces: `data class SpeechRecognitionResult(val generation: Long, val correction: CorrectionResult)`
- Replaces callback: `onFinalText: (SpeechRecognitionText) -> Unit` with `onFinalResult: (SpeechRecognitionResult) -> Unit`
- Adds injectable debug sink: `debugLog: (String) -> Unit`

- [ ] **Step 1: Write failing controller result tests**

```kotlin
@Test
fun finalCallbackContainsRawAndCorrectedText() = runTest {
    engine.finalText = "将绿二强度调到六十"
    val controller = controller(
        correct = { raw -> correction(raw, "将Lv2强度调到60") }
    )

    controller.start()
    recorder.emit(longEnoughPcm())
    controller.stopAndTranscribe()
    advanceUntilIdle()

    assertEquals("将绿二强度调到六十", finalResult.correction.rawText)
    assertEquals("将Lv2强度调到60", finalResult.correction.correctedText)
}
```

Also verify an ambiguous result is delivered rather than parsed inside the
controller, blank raw results still emit `NoMatch`, correction exceptions emit
service failure, stale generations cannot deliver results, PCM is detached
before native acceptance, and PCM/stream cleanup remains correct on cancel,
timeout, stop failure, decode failure, and close.

- [ ] **Step 2: Run focused controller tests and verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.LocalSpeechControllerTest" --tests "com.example.myapp.voice.PcmSessionBufferTest"
```

Expected: the callback/result assertions fail until the new result contract is
implemented.

- [ ] **Step 3: Implement correction after final offline recognition**

Use the raw engine result without pre-normalizing it:

```kotlin
val recognitionStartedAtMs = nowMillis()
submitBufferedAudio(session)
val rawText = speechEngine.finishSession().trim()
if (rawText.isBlank()) {
    publishNoMatch(session)
    return
}
val recognitionElapsedMs = nowMillis() - recognitionStartedAtMs
val correction = transcriptCorrector.correct(rawText)
debugLog("voice raw=$rawText corrected=${correction.correctedText} " +
    "ambiguous=${correction.ambiguous} elapsedMs=$recognitionElapsedMs " +
    "replacements=${correction.replacements}")
onFinalResult(SpeechRecognitionResult(session.generation, correction))
```

Remove obsolete partial delivery from the controller's active path while
retaining a no-op partial callback in `StreamingSpeechEngine.startSession` for
interface compatibility. Keep PCM release and generation guards unchanged.

- [ ] **Step 4: Run controller, engine, and PCM suites**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.LocalSpeechControllerTest" --tests "com.example.myapp.voice.SenseVoiceSpeechEngineTest" --tests "com.example.myapp.voice.PcmSessionBufferTest" --tests "com.example.myapp.voice.PcmRecordingPolicyTest"
```

Expected: all lifecycle and correction handoff tests pass.

- [ ] **Step 5: Commit locally without pushing**

```powershell
git add -- app/src/main/java/com/example/myapp/voice/LocalSpeechController.kt app/src/main/java/com/example/myapp/voice/PcmSessionBuffer.kt app/src/test/java/com/example/myapp/voice/LocalSpeechControllerTest.kt app/src/test/java/com/example/myapp/voice/PcmSessionBufferTest.kt
git commit -m "feat: correct buffered speech results"
```

---

### Task 6: Preserve Both Transcripts Through the Voice State Machine

**Files:**
- Modify: `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`
- Modify: `app/src/test/java/com/example/myapp/voice/VoiceUiStateTest.kt`

**Interfaces:**
- Produces: `data class VoiceTranscript(val raw: String, val corrected: String)`
- Replaces event: `VoiceEvent.FinalText(generation, transcript)` with `VoiceEvent.FinalResult(generation, transcript: VoiceTranscript, ambiguous: Boolean)`
- Extends: `VoiceUiState.Error(generation, message, transcript: VoiceTranscript? = null)`
- Carries `VoiceTranscript` through `Parsing`, `PreparingModel`, `Ready`, `Sending`, and `Success`.

- [ ] **Step 1: Write failing reducer tests**

```kotlin
@Test
fun finalResultKeepsRawAboveCorrectedThroughReadyAndSend() {
    val transcript = VoiceTranscript("将绿二强度调到六十", "将Lv2强度调到60")
    val parsing = reduceVoiceState(
        VoiceUiState.Transcribing(3),
        VoiceEvent.FinalResult(3, transcript, ambiguous = false)
    )

    assertEquals(VoiceUiState.Parsing(3, transcript), parsing)
    val ready = reduceVoiceState(parsing, VoiceEvent.ParsedCommand(3, command()))
    assertEquals(transcript, (ready as VoiceUiState.Ready).transcript)
}

@Test
fun ambiguousCorrectionBecomesNonApplicableErrorWithTranscript() {
    val transcript = VoiceTranscript("原句", "候选句")

    assertEquals(
        VoiceUiState.Error(3, "指令存在歧义", transcript),
        reduceVoiceState(
            VoiceUiState.Transcribing(3),
            VoiceEvent.FinalResult(3, transcript, ambiguous = true)
        )
    )
}
```

Also verify parse/model failures preserve the pair, a new session clears it,
stale events are ignored, and only `Ready` enables Apply.

- [ ] **Step 2: Run state tests and verify they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.VoiceUiStateTest"
```

- [ ] **Step 3: Implement the transcript pair and reducer transitions**

All downstream states must copy the entire `VoiceTranscript`. An ambiguous
`FinalResult` transitions directly to `Error` with message `指令存在歧义` and
the transcript pair; no parse-start event is accepted from that state.

- [ ] **Step 4: Run state and command safety tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.VoiceUiStateTest" --tests "com.example.myapp.voice.ParameterVoiceUpdateTest" --tests "com.example.myapp.voice.VoiceCommandTest"
```

- [ ] **Step 5: Commit locally without pushing**

```powershell
git add -- app/src/main/java/com/example/myapp/voice/VoiceUiState.kt app/src/test/java/com/example/myapp/voice/VoiceUiStateTest.kt
git commit -m "feat: retain raw and corrected voice text"
```

---

### Task 7: Wire SenseVoice and Render Two Unlabeled Transcript Lines

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/VoiceTranscriptLines.kt`
- Create: `app/src/androidTest/java/com/example/myapp/VoiceTranscriptLinesInstrumentedTest.kt`
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`

**Interfaces:**
- Consumes: `SenseVoiceModelStore`, `SenseVoiceSpeechEngine`, `FuzzyTranscriptCorrector`, and `AndroidIcuPinyinEncoder`.
- Produces: `@Composable internal fun VoiceTranscriptLines(transcript: VoiceTranscript, modifier: Modifier = Modifier)`
- Defines semantics tags: `voice_raw_transcript` and `voice_corrected_transcript` for test selection only; no visible labels.

- [ ] **Step 1: Write failing Compose line-order tests**

```kotlin
@Test
fun rawTextIsAboveCorrectedTextWithoutVisibleLabels() {
    rule.setContent {
        VoiceTranscriptLines(VoiceTranscript("将绿二强度调到六十", "将Lv2强度调到60"))
    }

    rule.onNodeWithTag("voice_raw_transcript").assertTextEquals("将绿二强度调到六十")
    rule.onNodeWithTag("voice_corrected_transcript").assertTextEquals("将Lv2强度调到60")
    rule.onNodeWithText("原始", substring = true).assertDoesNotExist()
    rule.onNodeWithText("纠正", substring = true).assertDoesNotExist()
}
```

Add a case where both lines are identical and both nodes still exist.

- [ ] **Step 2: Run the Compose test and verify it fails**

```powershell
.\gradlew.bat -Px86EmulatorBuild=true :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapp.VoiceTranscriptLinesInstrumentedTest
```

- [ ] **Step 3: Wire production objects and corrected-only parsing**

Replace construction in `DetectionParametersScreen`:

```kotlin
val speechModelStore = remember(applicationContext) { SenseVoiceModelStore(applicationContext) }
val speechEngine = remember(speechModelStore) { SenseVoiceSpeechEngine(speechModelStore) }
val transcriptCorrector = remember {
    FuzzyTranscriptCorrector(
        dictionary = SpeechCorrectionDictionary.lexemes,
        pinyinEncoder = AndroidIcuPinyinEncoder()
    )
}
```

On `SpeechRecognitionResult`, dispatch `FinalResult` with both strings. Start
the parse coroutine only when the reducer entered `Parsing`, and call exactly:

```kotlin
voiceIntentParser.parse(result.correction.correctedText)
```

Never pass `rawText` to the parser or Qwen.

- [ ] **Step 4: Render the transcript pair in every transcript-bearing state**

Replace the single-string extraction in `VoiceTuningCard` with a nullable
`VoiceTranscript`. Render `VoiceTranscriptLines` before the pending JSON command.
Do not render labels. Keep typography compact enough for the existing card and
allow each line to wrap without overlapping the command or status row.

- [ ] **Step 5: Run unit, Compose, and debug build checks**

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat -Px86EmulatorBuild=true :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapp.VoiceTranscriptLinesInstrumentedTest
.\gradlew.bat :app:assembleDebug
```

Expected: unit and Compose tests pass and the debug APK builds.

- [ ] **Step 6: Commit locally without pushing**

```powershell
git add -- app/src/main/java/com/example/myapp/MainActivity.kt app/src/main/java/com/example/myapp/voice/VoiceTranscriptLines.kt app/src/androidTest/java/com/example/myapp/VoiceTranscriptLinesInstrumentedTest.kt
git commit -m "feat: show raw and corrected voice transcripts"
```

---

### Task 8: Replace Accuracy Instrumentation and Remove Zipformer Remnants

**Files:**
- Create: `app/src/androidTest/java/com/example/myapp/SenseVoiceAccuracyInstrumentedTest.kt`
- Delete: `app/src/androidTest/java/com/example/myapp/SherpaAccuracyInstrumentedTest.kt`
- Delete: `app/src/test/java/com/example/myapp/voice/VoiceHotwordsTest.kt`
- Delete: `app/src/main/assets/models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/`
- Modify as needed: imports and comments containing old model/class names.

**Interfaces:**
- Consumes: production `SenseVoiceSpeechEngine`, bundled model assets, and representative 16kHz mono WAV fixtures.
- Produces: instrumentation log fields `raw`, `corrected`, `ambiguous`, and `elapsedMs`.

- [ ] **Step 1: Write the SenseVoice instrumentation test before deleting the old one**

Test at least one Chinese command and one mixed Chinese/English command. The
test must assert nonblank raw and corrected text, one final result only, and a
nonnegative elapsed time. Do not assert emulator speed as a product threshold.

```kotlin
assertTrue(result.rawText.isNotBlank())
assertTrue(result.correctedText.isNotBlank())
assertFalse(result.ambiguous)
assertTrue(elapsedMs >= 0)
Log.i("SenseVoiceAccuracy", "raw=${result.rawText} corrected=${result.correctedText} elapsedMs=$elapsedMs")
```

- [ ] **Step 2: Run the SenseVoice test on the emulator**

```powershell
.\gradlew.bat -Px86EmulatorBuild=true :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapp.SenseVoiceAccuracyInstrumentedTest
```

Expected: native recognizer initializes, each fixture decodes once, and result
objects are released without a crash. Record actual text for tuning dictionary
aliases, but do not weaken ambiguity or safety checks merely to force a pass.

- [ ] **Step 3: Remove old assets, tests, imports, and references**

Verify resolved deletion targets are descendants of
`app/src/main/assets/models` before recursive deletion. Remove old Zipformer
instrumentation, hotword tests, model folder, imports, class construction, and
comments. Do not remove unrelated Qwen assets.

- [ ] **Step 4: Prove no Zipformer or online Sherpa path remains**

```powershell
rg -n "Zipformer|zipformer|OnlineRecognizer|OnlineStream|modified_beam_search|hotwords\.txt|SherpaOnnxStreamingEngine|SherpaOnnxModelStore" app
```

Expected: no matches, except historical text outside `app` if present.

- [ ] **Step 5: Run fresh full verification**

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
.\gradlew.bat -Px86EmulatorBuild=true :app:connectedDebugAndroidTest
```

Inspect the APK:

```powershell
tar -tf app\build\outputs\apk\debug\app-debug.apk | Select-String 'sense-voice|model.int8.onnx|tokens.txt|zipformer|hotwords'
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
```

Expected: SenseVoice model and tokens are present; Zipformer and hotword assets
are absent. Report test counts, APK path, APK size, and SHA-256.

- [ ] **Step 6: Review the final working tree and commit locally without pushing**

```powershell
git status --short
git diff --check
git diff --stat
git add -- app/src/androidTest/java/com/example/myapp/SenseVoiceAccuracyInstrumentedTest.kt app/src/androidTest/java/com/example/myapp/SherpaAccuracyInstrumentedTest.kt app/src/test/java/com/example/myapp/voice/VoiceHotwordsTest.kt app/src/main/assets/models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20
git commit -m "test: verify SenseVoice voice control flow"
```

Confirm no unrelated files are staged before committing. Do not push to any
remote.
