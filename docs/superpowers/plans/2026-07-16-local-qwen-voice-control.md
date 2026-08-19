# Local Qwen Voice Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add mixed Chinese/English voice commands that are parsed locally with Qwen2.5-1.5B, strictly validated, applied from the existing voice card, sent as newline-delimited JSON, and reflected exactly in the parameter UI after server success.

**Architecture:** Keep probabilistic speech/model output behind a pure Kotlin command boundary. A platform speech controller emits text, a deterministic parser handles explicit commands before a local llama.cpp engine handles conversational phrasing, and a strict validator is the only producer of sendable commands. Compose owns the interaction state and updates integer parameter state only after the existing TCP client receives `success`.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose Material 3, Android `SpeechRecognizer`, coroutines, `org.json`, llama.cpp native C++ through JNI, Qwen2.5-1.5B-Instruct Q4_K_M GGUF, JUnit 4, Gradle/Android Studio.

## Global Constraints

- Support Android 10 and later by changing the project to `minSdk = 29`; execute local Qwen only on 64-bit ARM devices with enough storage.
- Recognize mixed Chinese/English expressions; fully English and guaranteed offline speech recognition are outside scope.
- Supported devices are exactly `machine_1`, `machine_2`, and `machine_3`; default to `machine_1`.
- One voice command changes exactly one parameter.
- Every numeric parameter is an integer and accepts every in-range integer regardless of UI button increment.
- Never send unvalidated model output and never retry a machine command automatically.
- The user confirms by tapping `Apply` in the voice card; do not add another confirmation dialog.
- Send compact UTF-8 JSON followed by exactly one `\n` and update UI only after a trimmed case-insensitive `success` response.
- Bundle official `qwen2.5-1.5b-instruct-q4_k_m.gguf` as an uncompressed asset; expected SHA-256 is `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e`.
- Pin llama.cpp to one tested commit in `third_party/llama.cpp/UPSTREAM.md`; do not track moving `master` implicitly.
- Native build requires Android SDK NDK and CMake, which are not currently installed on this workstation.
- The host currently has no usable `git` executable. Run commit steps only if Git becomes available; otherwise record that they were skipped without modifying unrelated files.

## File Map

- Modify `app/src/main/java/com/example/myapp/MainActivity.kt`: integer UI state, voice orchestration, successful command-to-state mapping.
- Create `app/src/main/java/com/example/myapp/voice/VoiceCommand.kt`: typed devices, parameters, values, command model, ranges, codec, validator.
- Create `app/src/main/java/com/example/myapp/voice/DeterministicVoiceParser.kt`: normalized mixed-language direct-command parser and multi-change detection.
- Create `app/src/main/java/com/example/myapp/voice/VoiceIntentParser.kt`: direct-parser-first orchestration and Qwen prompt construction.
- Create `app/src/main/java/com/example/myapp/voice/QwenInferenceEngine.kt`: engine interface plus Android native implementation.
- Create `app/src/main/java/com/example/myapp/voice/QwenModelStore.kt`: asset copy, file length/hash verification, and atomic publication.
- Create `app/src/main/java/com/example/myapp/voice/AndroidSpeechController.kt`: `SpeechRecognizer` lifecycle adapter.
- Create `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`: explicit idle/listening/loading/parsing/ready/sending/success/error states.
- Create `app/src/main/cpp/CMakeLists.txt` and `app/src/main/cpp/qwen_jni.cpp`: arm64 llama.cpp JNI bridge.
- Create `third_party/llama.cpp/`: pinned upstream llama.cpp sources and notices needed by the native build.
- Create `app/src/main/assets/models/qwen2.5-1.5b-instruct-q4_k_m.gguf`: official bundled model.
- Modify `app/build.gradle.kts`: NDK/CMake, ABI filter, uncompressed GGUF, native source, and test dependencies.
- Modify `app/src/main/AndroidManifest.xml`: microphone permission and speech recognition service query.
- Modify `app/src/main/java/com/example/myapp/communication/ParameterCommand.kt`: make `lv3Strength` an integer in footer payloads.
- Add focused unit tests under `app/src/test/java/com/example/myapp/voice/` and update existing parameter/codec tests.

---

### Task 1: Convert Parameter State and Footer JSON to Exact Integers

**Files:**
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`
- Modify: `app/src/main/java/com/example/myapp/ParameterAdjuster.kt`
- Modify: `app/src/main/java/com/example/myapp/communication/ParameterCommand.kt`
- Modify: `app/src/test/java/com/example/myapp/ParameterAdjusterTest.kt`
- Modify: `app/src/test/java/com/example/myapp/communication/ParameterCommandCodecTest.kt`

**Interfaces:**
- Produces: `adjustParameterValue(value: Int, direction: Int, step: Int, range: IntRange): Int`
- Produces: `DetectionParameters.lv3Strength: Int`; all other numeric fields remain `Int`.

- [ ] **Step 1: Change tests to require off-step integer preservation**

```kotlin
@Test fun increment_preserves_off_step_value() {
    assertEquals(37, adjustParameterValue(32, 1, 5, 0..100))
}

@Test fun footer_encodes_lv3_as_integer() {
    val json = JSONObject(ParameterCommandCodec.encode(operation, 1L, parameters.copy(lv3Strength = 65)).compactJson)
    assertEquals(65, json.getJSONObject("parameters").getInt("lv3Strength"))
}
```

- [ ] **Step 2: Run the focused tests and verify type/expectation failures**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*ParameterAdjusterTest' --tests '*ParameterCommandCodecTest'`

Expected: FAIL because the current adjuster uses `Float` and `lv3Strength` is encoded as `Double`.

- [ ] **Step 3: Implement integer adjustment and integer screen state**

```kotlin
fun adjustParameterValue(value: Int, direction: Int, step: Int, range: IntRange): Int =
    (value + direction * step).coerceIn(range)
```

Change all eight numeric Compose states and `ParameterControlRow` inputs to `Int`. Feed sliders with `value.toFloat()`, round gesture results with `roundToInt()`, and display `value.toString()`. Keep increments of `5` for the Lv strength/sensitivity/density controls and `100` for area/duration/delay controls. Change `lv3Strength` in `DetectionParameters` and its codec to `Int`.

- [ ] **Step 4: Run all existing unit tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest`

Expected: PASS with every footer numeric value encoded as a JSON integer.

- [ ] **Step 5: Commit when Git is available**

```powershell
git add app/src/main/java/com/example/myapp/MainActivity.kt app/src/main/java/com/example/myapp/ParameterAdjuster.kt app/src/main/java/com/example/myapp/communication/ParameterCommand.kt app/src/test
git commit -m "refactor: store detection parameters as integers"
```

### Task 2: Build the Strict Voice Command Security Boundary

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/VoiceCommand.kt`
- Create: `app/src/test/java/com/example/myapp/voice/VoiceCommandTest.kt`

**Interfaces:**
- Produces: `enum class MachineDevice(val wireName: String)`
- Produces: `sealed interface ParameterValue` with `IntegerValue`, `BooleanValue`, and `StringValue`.
- Produces: `data class SetParameterCommand(action: String, device: MachineDevice, parameter: VoiceParameter, value: ParameterValue)`
- Produces: `VoiceCommandCodec.parseStrict(raw: String): Result<SetParameterCommand>`
- Produces: `VoiceCommandCodec.compact(command): String` and `pretty(command): String`.

- [ ] **Step 1: Write boundary, type, and exact-key tests**

```kotlin
@Test fun accepts_off_step_integer_inside_range() {
    val command = VoiceCommandCodec.parseStrict("""{"action":"SET_PARAMETER","device":"machine_1","parameter":"lv1Sensitivity","value":32}""").getOrThrow()
    assertEquals(IntegerValue(32), command.value)
}

@Test fun rejects_fractional_integer_parameter() {
    assertTrue(VoiceCommandCodec.parseStrict("""{"action":"SET_PARAMETER","device":"machine_1","parameter":"lv3Strength","value":65.6}""").isFailure)
}

@Test fun rejects_extra_key() {
    assertTrue(VoiceCommandCodec.parseStrict("""{"action":"SET_PARAMETER","device":"machine_1","parameter":"minArea","value":4500,"note":"x"}""").isFailure)
}
```

Add parameterized cases for every lower/upper bound, out-of-range value, all three devices, booleans, the sole template string, unknown `pressure`, Markdown fences, trailing prose, arrays, nested values, and multiple objects.

- [ ] **Step 2: Run the new test class and verify missing-symbol failures**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*VoiceCommandTest'`

Expected: FAIL because the voice command types do not exist.

- [ ] **Step 3: Implement exact schema parsing and canonical serialization**

```kotlin
private val requiredKeys = setOf("action", "device", "parameter", "value")

fun parseStrict(raw: String): Result<SetParameterCommand> = runCatching {
    require(raw.trim().startsWith("{") && raw.trim().endsWith("}"))
    val json = JSONObject(raw)
    require(json.keys().asSequence().toSet() == requiredKeys)
    require(json.getString("action") == "SET_PARAMETER")
    val device = MachineDevice.fromWireName(json.getString("device"))
    val parameter = VoiceParameter.fromWireName(json.getString("parameter"))
    val value = parameter.readAndValidate(json.get("value"))
    SetParameterCommand("SET_PARAMETER", device, parameter, value)
}
```

Implement the exact ranges from the design, reject `Double`/`Float` for integer parameters, and serialize keys in `action`, `device`, `parameter`, `value` order.

- [ ] **Step 4: Run the boundary tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*VoiceCommandTest'`

Expected: PASS.

- [ ] **Step 5: Commit when Git is available**

```powershell
git add app/src/main/java/com/example/myapp/voice/VoiceCommand.kt app/src/test/java/com/example/myapp/voice/VoiceCommandTest.kt
git commit -m "feat: add strict voice command validation"
```

### Task 3: Add Mixed-Language Normalization and Deterministic Parsing

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/DeterministicVoiceParser.kt`
- Create: `app/src/test/java/com/example/myapp/voice/DeterministicVoiceParserTest.kt`

**Interfaces:**
- Produces: `sealed interface DirectParseResult { data class Parsed(val command: SetParameterCommand); data object NeedsModel; data class Rejected(val message: String) }`
- Produces: `DeterministicVoiceParser.parse(transcript: String): DirectParseResult`

- [ ] **Step 1: Write direct-command and ambiguity tests**

```kotlin
@Test fun parses_mixed_language_with_default_device() {
    assertEquals(command("machine_1", "lv1Sensitivity", 32), parsed("把 Lv 1 sensitivity 改为 32"))
}

@Test fun parses_chinese_device_and_boolean() {
    assertEquals(command("machine_2", "enhancedInference", true), parsed("二号机 enhanced inference 打开"))
}

@Test fun rejects_two_parameter_changes() {
    assertIs<DirectParseResult.Rejected>(parser.parse("lv1灵敏度改32，最小面积改4500"))
}
```

Cover canonical camelCase identifiers, `LV 1`, Chinese parameter names, controlled English aliases, Chinese numerals for devices, `on/off`, and all ranges.

- [ ] **Step 2: Run the parser tests and verify failure**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*DeterministicVoiceParserTest'`

Expected: FAIL because the parser does not exist.

- [ ] **Step 3: Implement normalization and alias tables**

```kotlin
private fun normalize(text: String) = text
    .lowercase(Locale.ROOT)
    .replace(Regex("lv\\s*([123])"), "lv$1")
    .replace('，', ',')
    .replace(Regex("\\s+"), " ")
    .trim()

private val deviceAliases = mapOf(
    "一号机" to MachineDevice.Machine1,
    "二号机" to MachineDevice.Machine2,
    "三号机" to MachineDevice.Machine3,
    "machine_1" to MachineDevice.Machine1,
    "machine_2" to MachineDevice.Machine2,
    "machine_3" to MachineDevice.Machine3
)
```

Require exactly one recognized parameter and exactly one value expression. Return `NeedsModel` for conversational text that cannot be proven safe; return `Rejected` when multiple parameter aliases are detected.

- [ ] **Step 4: Run direct parser and command tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*voice*'`

Expected: PASS.

- [ ] **Step 5: Commit when Git is available**

```powershell
git add app/src/main/java/com/example/myapp/voice/DeterministicVoiceParser.kt app/src/test/java/com/example/myapp/voice/DeterministicVoiceParserTest.kt
git commit -m "feat: parse direct mixed-language voice commands"
```

### Task 4: Add Qwen Prompt Orchestration Behind an Interface

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/QwenInferenceEngine.kt`
- Create: `app/src/main/java/com/example/myapp/voice/VoiceIntentParser.kt`
- Create: `app/src/test/java/com/example/myapp/voice/VoiceIntentParserTest.kt`

**Interfaces:**
- Produces: `interface QwenInferenceEngine { suspend fun generate(prompt: String, maxTokens: Int = 128): String; fun close() }`
- Produces: `VoiceIntentParser(engine, directParse).parse(transcript: String): Result<SetParameterCommand>`, where `directParse` defaults to `DeterministicVoiceParser()::parse`.

- [ ] **Step 1: Write fallback, prompt, and invalid-output tests with a fake engine**

```kotlin
private class FakeEngine(private val output: String) : QwenInferenceEngine {
    var prompts = emptyList<String>()
    override suspend fun generate(prompt: String, maxTokens: Int): String { prompts += prompt; return output }
    override fun close() = Unit
}

@Test fun direct_parse_does_not_invoke_qwen() = runTest {
    val engine = FakeEngine("unused")
    val parser = VoiceIntentParser(engine)
    val command = parser.parse("lv1Sensitivity改为32").getOrThrow()
    assertEquals(MachineDevice.Machine1, command.device)
    assertEquals(IntegerValue(32), command.value)
    assertTrue(engine.prompts.isEmpty())
}

@Test fun qwen_output_still_passes_strict_validator() = runTest {
    val engine = FakeEngine("""{"action":"SET_PARAMETER","device":"machine_1","parameter":"minArea","value":4500,"note":"unsafe"}""")
    val parser = VoiceIntentParser(engine) { DirectParseResult.NeedsModel }
    assertTrue(parser.parse("麻烦调整一下最小检测区域").isFailure)
}

@Test fun prompt_declares_default_machine_and_every_allowed_parameter() = runTest {
    val engine = FakeEngine("""{"action":"SET_PARAMETER","device":"machine_1","parameter":"minArea","value":4500}""")
    VoiceIntentParser(engine) { DirectParseResult.NeedsModel }.parse("调整检测面积").getOrThrow()
    val prompt = engine.prompts.single()
    assertTrue(prompt.contains("default machine_1"))
    VoiceParameter.entries.forEach { assertTrue(prompt.contains(it.wireName)) }
}
```

- [ ] **Step 2: Add coroutine test dependency and run failing tests**

Add `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")` to `app/build.gradle.kts`.

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*VoiceIntentParserTest'`

Expected: FAIL because orchestration is not implemented.

- [ ] **Step 3: Implement direct-first parsing and a fixed deterministic prompt**

```kotlin
suspend fun parse(transcript: String): Result<SetParameterCommand> = when (val direct = directParser.parse(transcript)) {
    is DirectParseResult.Parsed -> Result.success(direct.command)
    is DirectParseResult.Rejected -> Result.failure(IllegalArgumentException(direct.message))
    DirectParseResult.NeedsModel -> VoiceCommandCodec.parseStrict(engine.generate(buildPrompt(transcript), 128))
}
```

The system prompt must enumerate exact devices, parameters, types, ranges, default `machine_1`, one-change-only behavior, and output-only JSON. Include mixed-language examples and an explicit instruction to emit an invalid sentinel object for unsupported parameters; the Kotlin validator still rejects that sentinel.

- [ ] **Step 4: Run all voice unit tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*voice*'`

Expected: PASS.

- [ ] **Step 5: Commit when Git is available**

```powershell
git add app/build.gradle.kts app/src/main/java/com/example/myapp/voice app/src/test/java/com/example/myapp/voice
git commit -m "feat: orchestrate local qwen intent parsing"
```

### Task 5: Add Model Asset Preparation and Integrity Verification

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/QwenModelStore.kt`
- Create: `app/src/test/java/com/example/myapp/voice/QwenModelStoreTest.kt`
- Create: `app/src/main/assets/models/qwen2.5-1.5b-instruct-q4_k_m.gguf`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces: `QwenModelStore.prepare(): File`
- Uses constants `ASSET_PATH`, `FILE_NAME`, `EXPECTED_BYTES`, and `EXPECTED_SHA256` pinned to the official file.

- [ ] **Step 1: Write atomic copy and hash tests using small fake assets**

```kotlin
@get:Rule val temporaryFolder = TemporaryFolder()
private val validBytes = "valid-model".toByteArray()
private val validHash = sha256(validBytes)
private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

@Test fun valid_existing_file_is_reused() {
    File(temporaryFolder.root, "model.gguf").writeBytes(validBytes)
    var opened = false
    val store = QwenModelStore(temporaryFolder.root, "model.gguf", validBytes.size.toLong(), validHash) {
        opened = true
        validBytes.inputStream()
    }
    assertArrayEquals(validBytes, store.prepare().readBytes())
    assertFalse(opened)
}

@Test fun corrupt_existing_file_is_replaced_atomically() {
    File(temporaryFolder.root, "model.gguf").writeText("bad")
    val store = QwenModelStore(temporaryFolder.root, "model.gguf", validBytes.size.toLong(), validHash) {
        validBytes.inputStream()
    }
    assertArrayEquals(validBytes, store.prepare().readBytes())
    assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
}

@Test fun bad_asset_hash_fails_and_deletes_partial_file() {
    val store = QwenModelStore(temporaryFolder.root, "model.gguf", 3L, validHash) {
        byteArrayOf(1, 2, 3).inputStream()
    }
    assertThrows(IllegalStateException::class.java) { store.prepare() }
    assertFalse(File(temporaryFolder.root, "model.gguf.partial").exists())
    assertFalse(File(temporaryFolder.root, "model.gguf").exists())
}
```

Inject `openAsset: () -> InputStream` and `sha256: (File) -> String` so JVM tests do not require Android assets.

- [ ] **Step 2: Run the model-store tests and verify failure**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*QwenModelStoreTest'`

Expected: FAIL because `QwenModelStore` does not exist.

- [ ] **Step 3: Implement chunked copy, verification, and atomic rename**

```kotlin
assetInput.use { input -> partial.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) } }
require(partial.length() == expectedBytes)
require(sha256(partial).equals(expectedSha256, ignoreCase = true))
check(partial.renameTo(finalFile))
```

Never delete a previously verified final model until the replacement partial file has passed both checks.

- [ ] **Step 4: Configure uncompressed assets and fetch the official model**

In `app/build.gradle.kts` add:

```kotlin
androidResources { noCompress += "gguf" }
```

Download from `https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf`, then verify:

```powershell
Get-FileHash app\src\main\assets\models\qwen2.5-1.5b-instruct-q4_k_m.gguf -Algorithm SHA256
```

Expected: `6A1A2EB6D15622BF3C96857206351BA97E1AF16C30D7A74EE38970E434E9407E`.

- [ ] **Step 5: Run model-store tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*QwenModelStoreTest'`

Expected: PASS.

- [ ] **Step 6: Commit when Git is available**

```powershell
git add app/build.gradle.kts app/src/main/java/com/example/myapp/voice/QwenModelStore.kt app/src/test/java/com/example/myapp/voice/QwenModelStoreTest.kt app/src/main/assets/models/qwen2.5-1.5b-instruct-q4_k_m.gguf
git commit -m "feat: bundle and verify qwen model"
```

### Task 6: Integrate Pinned llama.cpp for Android 10 arm64

**Files:**
- Create: `third_party/llama.cpp/`
- Create: `third_party/llama.cpp/UPSTREAM.md`
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/qwen_jni.cpp`
- Modify: `app/src/main/java/com/example/myapp/voice/QwenInferenceEngine.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Produces JNI methods `nativeLoadModel(path, contextSize, threads): Long`, `nativeGenerate(handle, prompt, maxTokens): String`, and `nativeClose(handle)`.
- Produces `class NativeQwenInferenceEngine(modelStore: QwenModelStore, dispatcher: CoroutineDispatcher = Dispatchers.Default) : QwenInferenceEngine`.

- [ ] **Step 1: Install Android NDK and CMake from SDK Manager**

Install NDK `29.0.13113456` and CMake `3.31.6`, and pin both versions in `app/build.gradle.kts`. Verify directories `C:\Users\han\AppData\Local\Android\Sdk\ndk\29.0.13113456` and `C:\Users\han\AppData\Local\Android\Sdk\cmake\3.31.6` exist.

Expected: Gradle can locate both packages without modifying global PATH.

- [ ] **Step 2: Vendor one tested llama.cpp revision and record provenance**

`third_party/llama.cpp/UPSTREAM.md` must contain the repository URL, exact commit SHA, retrieval date, MIT license location, local build flags, and any compatibility patches. Keep upstream source changes isolated and documented.

- [ ] **Step 3: Configure the native arm64 build**

```kotlin
defaultConfig {
    ndk { abiFilters += "arm64-v8a" }
    externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
}
externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
```

Set `ANDROID_PLATFORM=29`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`, `GGML_NATIVE=OFF`, `LLAMA_BUILD_TESTS=OFF`, `LLAMA_BUILD_EXAMPLES=OFF`, and build only the libraries needed by `qwen_jni`.

- [ ] **Step 4: Implement native lifecycle and deterministic generation**

The bridge must initialize the backend once, load the model with memory mapping, create a 1024-token context, apply the model chat template to the fixed prompt, tokenize with special tokens, decode in bounded batches, and sample deterministically with temperature `0`. Stop at EOS/EOG or 128 generated tokens, convert UTF-8 pieces safely, and throw Java exceptions on load/decode errors. Guard each handle with a mutex and free context/model exactly once.

```cpp
extern "C" JNIEXPORT jlong JNICALL Java_com_example_myapp_voice_NativeQwenBridge_nativeLoadModel(JNIEnv *, jclass, jstring path, jint context_size, jint threads);
extern "C" JNIEXPORT jstring JNICALL Java_com_example_myapp_voice_NativeQwenBridge_nativeGenerate(JNIEnv *, jclass, jlong handle, jstring prompt, jint max_tokens);
extern "C" JNIEXPORT void JNICALL Java_com_example_myapp_voice_NativeQwenBridge_nativeClose(JNIEnv *, jclass, jlong handle);
```

- [ ] **Step 5: Implement the coroutine-safe Kotlin wrapper**

```kotlin
override suspend fun generate(prompt: String, maxTokens: Int): String = mutex.withLock {
    withContext(dispatcher) {
        val liveHandle = handle.takeIf { it != 0L } ?: NativeQwenBridge.load(modelStore.prepare(), 1024)
        NativeQwenBridge.generate(liveHandle, prompt, maxTokens)
    }
}
```

Make `close()` idempotent and prevent generation after close.

- [ ] **Step 6: Compile the native library before UI integration**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat externalNativeBuildDebug`

Expected: BUILD SUCCESSFUL and an arm64 `libqwen_jni.so` under app intermediates.

- [ ] **Step 7: Commit when Git is available**

```powershell
git add third_party/llama.cpp app/src/main/cpp app/src/main/java/com/example/myapp/voice/QwenInferenceEngine.kt app/build.gradle.kts
git commit -m "feat: run qwen locally with llama cpp"
```

### Task 7: Add Android Speech Recognition and Voice UI State

**Files:**
- Create: `app/src/main/java/com/example/myapp/voice/AndroidSpeechController.kt`
- Create: `app/src/main/java/com/example/myapp/voice/VoiceUiState.kt`
- Create: `app/src/test/java/com/example/myapp/voice/VoiceUiStateTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `AndroidSpeechController.start(localeTag: String = "zh-CN")`, `cancel()`, and `close()`.
- Produces callbacks `onPartial`, `onFinal`, and `onError`.
- Produces the sealed `VoiceUiState` states from the design and `reduceVoiceState(state, event): VoiceUiState`.

- [ ] **Step 1: Write state transition tests around a pure reducer**

```kotlin
@Test fun final_transcript_enters_parsing() {
    assertEquals(
        VoiceUiState.Parsing(1L, "Lv1 灵敏度改为32"),
        reduceVoiceState(VoiceUiState.Listening(1L, ""), VoiceEvent.FinalText(1L, "Lv1 灵敏度改为32"))
    )
}
@Test fun stale_generation_is_ignored() {
    val current = VoiceUiState.Parsing(2L, "二号机 Lv1 灵敏度改为32")
    assertEquals(current, reduceVoiceState(current, VoiceEvent.Parsed(1L, command)))
}

@Test fun send_failure_preserves_ready_candidate() {
    val sending = VoiceUiState.Sending(3L, "Lv1 灵敏度改为32", command)
    val result = reduceVoiceState(sending, VoiceEvent.SendFailed(3L, "服务器无响应"))
    assertEquals(VoiceUiState.Ready(3L, "Lv1 灵敏度改为32", command, "服务器无响应"), result)
}
```

- [ ] **Step 2: Run tests and verify failure**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*VoiceUiStateTest'`

Expected: FAIL because states and reducer do not exist.

- [ ] **Step 3: Implement states, generation IDs, and speech controller**

Configure `RecognizerIntent.EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM`, `EXTRA_LANGUAGE = "zh-CN"`, `EXTRA_PARTIAL_RESULTS = true`, and `EXTRA_MAX_RESULTS = 3`. Emit the best final nonblank result, translate platform error codes to concise Chinese messages, and always invoke `SpeechRecognizer.destroy()` from `close()` on the main thread.

- [ ] **Step 4: Add manifest declarations**

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<queries>
    <intent><action android:name="android.speech.RecognitionService" /></intent>
</queries>
```

- [ ] **Step 5: Run state and all JVM tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest`

Expected: PASS.

- [ ] **Step 6: Commit when Git is available**

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/com/example/myapp/voice app/src/test/java/com/example/myapp/voice
git commit -m "feat: add speech recognition state flow"
```

### Task 8: Wire the Voice Card, TCP Send, and Exact UI Update

**Files:**
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`
- Modify: `app/src/main/java/com/example/myapp/communication/TcpParameterClient.kt` only if a generic newline command method is needed.
- Create: `app/src/test/java/com/example/myapp/voice/ParameterUpdateTest.kt`

**Interfaces:**
- Produces: `DetectionParameters.withVoiceCommand(command): DetectionParameters` for testable update mapping.
- Consumes: `VoiceIntentParser`, `VoiceUiState`, `VoiceCommandCodec.compact`, and existing `TcpParameterClient.sendAndReceive(wireText: String)`.

- [ ] **Step 1: Write one update test per parameter type**

```kotlin
@Test fun sensitivity_updates_exact_off_step_integer() {
    assertEquals(32, defaults.withVoiceCommand(command("lv1Sensitivity", 32)).lv1Sensitivity)
}

@Test fun boolean_and_template_update_only_their_fields() {
    assertEquals(defaults.copy(enhancedInference = true), defaults.withVoiceCommand(command("enhancedInference", true)))
    assertEquals(defaults.copy(template = "400mmBase.engine"), defaults.withVoiceCommand(command("template", "400mmBase.engine")))
}
```

Cover all eleven parameters and assert the source state is unchanged on simulated send failure.

- [ ] **Step 2: Run mapping tests and verify failure**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest --tests '*ParameterUpdateTest'`

Expected: FAIL because update mapping does not exist.

- [ ] **Step 3: Implement pure parameter update mapping**

```kotlin
fun DetectionParameters.withVoiceCommand(command: SetParameterCommand): DetectionParameters = when (command.parameter) {
    VoiceParameter.Lv1Sensitivity -> copy(lv1Sensitivity = command.integerValue())
    VoiceParameter.Lv1Strength -> copy(lv1Strength = command.integerValue())
    VoiceParameter.Lv1Density -> copy(lv1Density = command.integerValue())
    VoiceParameter.EnhancedInference -> copy(enhancedInference = command.booleanValue())
    VoiceParameter.Lv1AreaMask -> copy(lv1AreaMask = command.booleanValue())
    VoiceParameter.MinArea -> copy(minArea = command.integerValue())
    VoiceParameter.Template -> copy(template = command.stringValue())
    VoiceParameter.Lv2Strength -> copy(lv2Strength = command.integerValue())
    VoiceParameter.Lv3Strength -> copy(lv3Strength = command.integerValue())
    VoiceParameter.ActionDuration -> copy(actionDuration = command.integerValue())
    VoiceParameter.RejectDelay -> copy(rejectDelay = command.integerValue())
}
```

The implementation must use an exhaustive `when`; no reflection or string-based property assignment.

- [ ] **Step 4: Replace the static voice card with feature-complete states**

Keep the existing visual structure. The microphone starts recognition after runtime permission grant. Show transcript and canonical command summary in the card. Enable the existing `Apply` label only in `Ready`, show progress for loading/parsing/sending, and expose retryable errors without adding a modal confirmation.

- [ ] **Step 5: Wire Apply to the existing connection and TCP client**

If disconnected, preserve `Ready` and open connection settings. If connected, serialize the validated command, send it through the same single-flight client with `\n`, and wait for `success`. Only then assign every Compose state from `currentParameters.withVoiceCommand(command)`. On failure, restore `Ready` with the error message and do not mutate parameters.

- [ ] **Step 6: Add runtime permission and lifecycle cleanup**

Use `rememberLauncherForActivityResult(RequestPermission())`, create the controller once with `remember`, cancel recognition/inference on new microphone sessions, and close speech/native resources in `DisposableEffect.onDispose`. Use generation IDs so old callbacks cannot replace newer state.

- [ ] **Step 7: Run all unit tests**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat testDebugUnitTest`

Expected: PASS.

- [ ] **Step 8: Commit when Git is available**

```powershell
git add app/src/main/java/com/example/myapp/MainActivity.kt app/src/main/java/com/example/myapp/communication/TcpParameterClient.kt app/src/test/java/com/example/myapp/voice/ParameterUpdateTest.kt
git commit -m "feat: apply validated voice commands from voice card"
```

### Task 9: End-to-End Build and Physical Device Verification

**Files:**
- Modify only files needed to fix failures found by the checks above.

**Interfaces:**
- Consumes the complete voice control feature.
- Produces a project that opens and runs from Android Studio without a separately packaged release APK.

- [ ] **Step 1: Run the complete JVM suite from a clean task graph**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat clean testDebugUnitTest --rerun-tasks`

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 2: Build the debug app including native code and bundled model**

Run: `$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat assembleDebug --rerun-tasks`

Expected: BUILD SUCCESSFUL; APK contains arm64 native libraries and one uncompressed GGUF asset.

- [ ] **Step 3: Verify artifact contents and model hash**

Run `Get-FileHash` on the source asset and inspect the debug APK archive for `assets/models/qwen2.5-1.5b-instruct-q4_k_m.gguf` and `lib/arm64-v8a/libqwen_jni.so`.

Expected: the source hash matches the pinned SHA-256 and both entries exist exactly once.

- [ ] **Step 4: Run focused physical-device scenarios from Android Studio**

Verify on Android 10+ arm64 hardware: permission denial/retry; mixed command `把 Lv1 sensitivity 改为 32`; default `machine_1`; explicit `二号机`; boolean switch; `minArea=4501`; rejected decimal; rejected `pressure`; rejected two-change command; disconnect preserving candidate; NetAssist `success\n`; no UI mutation on server error; second inference reuses the copied model.

- [ ] **Step 5: Record measured model behavior**

Record first-copy time, first-load time, direct-parser latency, Qwen parser latency, peak process memory if available, and whether Android kills the app under normal foreground use. Do not weaken validation to improve model acceptance.

- [ ] **Step 6: Final commit when Git is available**

```powershell
git add app third_party docs/superpowers
git commit -m "feat: complete local qwen voice control"
```
