# Local Whisper Speech Recognition Design

## Goal

Replace Android's system `SpeechRecognizer` with bundled, on-device speech-to-text so the app can recognize Chinese, English, and mixed Chinese-English parameter commands on Android 10+ devices without relying on an installed recognition service.

## Confirmed Constraints

- Bundle `whisper.cpp` and the multilingual `small-q5_1` model with the app.
- Support Android 10 and later on arm64-v8a devices with 8 GB RAM and a mid-range Snapdragon-class processor.
- First microphone tap starts recording; the second tap stops and transcribes.
- Automatically stop and transcribe after 20 seconds.
- Preserve the existing transcript-to-command flow: deterministic parser first, local Qwen fallback second, strict Kotlin validation, card-level Apply action, then TCP JSON followed by `\n`.
- Do not show an additional confirmation dialog for voice commands.
- Keep Whisper and Qwen model residency sequential to reduce peak memory.

## Selected Approach

Vendor the official `whisper.cpp` v1.8.1 source under `third_party/whisper.cpp` and build it as a separate `whisper_jni` shared library in an isolated Android Library module. The separate CMake graph prevents whisper.cpp's private ggml targets from colliding with llama.cpp's ggml targets. Bundle `ggml-small-q5_1.bin` under `app/src/main/assets/models` and copy it to verified app-private storage on first use.

This is preferred over a third-party AAR because it fixes the native ABI and upstream version inside the project. It is preferred over a cloud STT API because it requires no provider account, API key, network availability, or server changes.

## Architecture

### Recording

`LocalWhisperController` replaces `AndroidSpeechController` at the UI boundary while preserving generation-tagged callbacks. It owns one active recording/transcription session at a time.

`PcmRecorder` records 16 kHz, mono, PCM 16-bit audio from `MediaRecorder.AudioSource.VOICE_RECOGNITION` on an IO coroutine. It exposes start, stop-and-return-samples, and cancel operations. Recording stops when the user taps the microphone again or when a monotonic 20-second timer expires.

Recordings shorter than 0.3 seconds are rejected before native inference. The recorder enforces a hard sample limit corresponding to 20 seconds, so scheduler delay cannot grow the audio buffer without bound.

### Model Storage

`WhisperModelStore` follows the existing `QwenModelStore` safety pattern:

- copy the bundled asset to a `.partial` file;
- verify exact byte length and SHA-256;
- atomically publish it into app-private model storage;
- reuse an already verified file;
- remove stale or invalid files before retrying.

The Gradle Android resources configuration stores `.bin` model assets without compression.

### Native Inference

`NativeWhisperInferenceEngine` uses a focused JNI bridge in `whisper_jni.cpp`. One transcription call:

1. loads the verified multilingual model;
2. converts PCM 16-bit samples to normalized float samples;
3. runs Whisper with automatic language detection and transcription mode;
4. concatenates trimmed text segments as UTF-8;
5. frees the Whisper context before returning or throwing.

Inference is serialized. Cancellation prevents stale Kotlin callbacks from reaching the UI; a native call already in progress is allowed to finish and must still release its context.

### Qwen Memory Lifecycle

`NativeQwenInferenceEngine` loads Qwen within each serialized `generate` call and closes the native handle in `finally`. It no longer keeps the 1.5B model resident between voice commands. Whisper is released before the transcript is passed to `VoiceIntentParser`, preventing simultaneous Whisper and Qwen model residency during the normal flow.

### UI State

The existing voice generation guard remains the source of truth for stale-result suppression. Voice state gains explicit recording and transcription phases:

- `Recording`: microphone is active; a second microphone tap finalizes the recording.
- `Transcribing`: microphone is stopped and Whisper is running; microphone input is disabled.
- Existing parsing, model preparation, ready, sending, success, and error states continue unchanged.

The voice card displays concise status text for recording, local transcription, Qwen preparation, and failures. No new dialog is introduced.

## Data Flow

1. User taps the microphone.
2. The app requests or verifies `RECORD_AUDIO` permission and checks the system microphone switch.
3. `PcmRecorder` begins bounded PCM capture.
4. The user taps again, or the 20-second limit expires.
5. The recorder stops and returns samples.
6. `WhisperModelStore` prepares and verifies the bundled model.
7. `NativeWhisperInferenceEngine` returns mixed-language transcript text and releases Whisper.
8. `DeterministicVoiceParser` attempts a direct parse.
9. If required, Qwen loads, returns one strict JSON command, and unloads.
10. Kotlin validates device, parameter, value type, and allowed integer range.
11. The card shows the candidate command.
12. The user taps Apply; the app sends compact JSON plus `\n` over the existing TCP client.

## Error Handling

- Missing permission: retain the existing permission request and denial message.
- Muted system microphone: reject before recording.
- Audio initialization/read failure: stop and release `AudioRecord`, then show a recording error.
- Recording shorter than 0.3 seconds: show a short-recording message without loading Whisper.
- Empty or whitespace-only transcript: show that no valid speech was recognized.
- Model copy, size, or checksum failure: show a local speech-model preparation error.
- Native load or inference failure: show a local speech-recognition error and release native resources.
- Cancellation or lifecycle stop: discard samples/results, release recorder and native resources, and return the card to idle.
- Stale generation callbacks: ignore them without changing current UI state.

## Testing

### Unit Tests

- Recording session state transitions for start, manual stop, 20-second timeout, cancel, and stale completion.
- Hard sample limit and minimum recording duration decisions.
- Whisper model copy, checksum verification, stale partial cleanup, and concurrent preparation.
- Native bridge lifecycle: load, transcribe, free exactly once on success, error, cancellation, and close.
- Voice UI reducer behavior for Recording and Transcribing phases.
- Qwen native handle is released after every generation and after generation failure.

### Android Device Tests

- `AudioRecord` can capture non-empty 16 kHz mono PCM with the app permission.
- The bundled Whisper model can be prepared and loaded on arm64-v8a.
- A fixed speech WAV fixture containing a mixed Chinese-English command produces non-empty text.
- On the Vivo V2183A, manually verify start tap, stop tap, 20-second auto-stop, cancellation on lifecycle stop, and the full transcript-to-command-card path.

The emulator is used for lifecycle and integration smoke tests only. Recognition accuracy is accepted on the physical Vivo device.

## Out of Scope

- Streaming partial Whisper transcripts.
- Automatic silence detection.
- Cloud speech-to-text fallback.
- User-selectable speech models or languages.
- APK release signing or distribution packaging.
