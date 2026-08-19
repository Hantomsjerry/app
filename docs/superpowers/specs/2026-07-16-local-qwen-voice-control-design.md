# Local Qwen Voice Control Design

## Goal

Add bilingual mixed Chinese/English voice control to the existing machine-parameter Android app. Android speech recognition converts speech to text, a local Qwen model converts the text to one fixed command, Kotlin validates the command, and the user explicitly applies it from the voice card. A successful TCP response updates the matching UI value.

The supported devices are `machine_1`, `machine_2`, and `machine_3`. If the user does not name a device, the command targets `machine_1`.

## User Flow

1. The user taps the microphone in the existing voice card.
2. The app requests microphone permission when needed and starts Android `SpeechRecognizer` in free-form Chinese mode. Mixed terms such as `Lv1 sensitivity` remain valid input.
3. The recognized text is shown in the voice card.
4. A deterministic parser handles simple commands first. Other valid utterances are sent to the local Qwen parser.
5. The candidate JSON is parsed and validated by Kotlin. The model never accesses UI state or the TCP client directly.
6. A valid candidate appears in the voice card with an enabled `Apply` action. There is no separate confirmation dialog.
7. Tapping `Apply` sends the compact JSON followed by `\n` through the existing TCP connection.
8. After the server returns `success`, the app updates the exact corresponding UI value and shows a short success state. On failure, the UI parameter remains unchanged.

Only one parameter can be changed by one voice command. A request containing multiple parameter changes is rejected with guidance to issue separate commands.

## Speech Recognition

Use the platform `SpeechRecognizer` and `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` rather than bundling an offline speech model.

- Request `android.permission.RECORD_AUDIO` at runtime.
- Use free-form recognition with Chinese as the primary locale so Chinese sentences containing identifiers such as `Lv1`, `minArea`, and English parameter words can be recognized.
- Use final recognition results for command parsing. Partial results may be displayed as listening feedback but cannot enable `Apply`.
- Normalize whitespace, letter case, common punctuation, Chinese numerals, and speech-service variants such as `LV 1` before parsing.
- Stop and release the recognizer with the screen lifecycle.
- Permission denial, no speech, recognizer unavailability, and network/service errors are visible in the voice card and never create a command.

Fully English sentences and guaranteed offline speech recognition are outside this version.

## Local Qwen Runtime

Use `llama.cpp` through its Android JNI/Kotlin binding with the official `Qwen2.5-1.5B-Instruct-GGUF` `Q4_K_M` model.

- Bundle `qwen2.5-1.5b-instruct-q4_k_m.gguf` with the app. The model is approximately 1.12 GB.
- Package the GGUF as an uncompressed APK asset, then copy it once to app-private storage because the native runtime needs a filesystem path.
- Verify the copied file size and a pinned SHA-256 value before marking the model ready.
- Load lazily on first voice use and keep one engine instance alive while the screen is active.
- Show model preparation/loading state in the voice card and disable the microphone and `Apply` while required work is in progress.
- Use an approximately 1024-token context and cap generated output at 128 tokens. Run inference off the main thread and allow cancellation.
- Use deterministic generation settings and a strict system prompt that requests exactly one JSON object with no Markdown or explanation.

The simple parser is intentionally placed before Qwen. Commands such as `lv1Sensitivity改为32` should be fast and deterministic, while Qwen handles aliases, word order, and more conversational phrasing.

## Command Protocol

The only accepted command shape is:

```json
{
  "action": "SET_PARAMETER",
  "device": "machine_1",
  "parameter": "lv1Sensitivity",
  "value": 32
}
```

- `action` must equal `SET_PARAMETER` exactly.
- `device` must be one of the three device IDs.
- `parameter` must be one supported canonical parameter name.
- `value` must have the exact type required by that parameter.
- Missing keys, extra keys, arrays, nested values, trailing prose, Markdown fences, and multiple JSON objects are rejected.
- The exact validated JSON displayed in the card is the command sent over TCP.

## Parameter Whitelist

All numeric values are integers. Slider step sizes do not constrain valid commands or stored values.

| Parameter | Type | Allowed value |
| --- | --- | --- |
| `lv1Sensitivity` | Integer | `0..100` |
| `lv1Strength` | Integer | `0..120` |
| `lv1Density` | Integer | `0..100` |
| `enhancedInference` | Boolean | `true` or `false` |
| `lv1AreaMask` | Boolean | `true` or `false` |
| `minArea` | Integer | `0..10000` |
| `template` | String | `400mmBase.engine` |
| `lv2Strength` | Integer | `0..120` |
| `lv3Strength` | Integer | `0..100` |
| `actionDuration` | Integer | `0..2000` |
| `rejectDelay` | Integer | `0..1500` |

The parser supports canonical identifiers plus controlled Chinese and English aliases. Examples include `Lv1 灵敏度`, `一级 sensitivity`, `最小面积`, `action duration`, and `剔除延时`. Device aliases include `一号机`, `二号机`, `三号机`, `machine one`, and the canonical IDs. Boolean aliases include `开启`, `打开`, `on`, `关闭`, and `off`.

Parameters not represented by the current UI are unsafe. For example, `pressure` is rejected even if Qwen emits a plausible command.

## Integer UI State

Convert every numeric parameter in screen state and the communication model to `Int`, including `lv3Strength`.

- Sliders map their continuous gesture position to the nearest integer.
- Plus and minus controls retain their current increment sizes but clamp only at the range endpoints.
- A voice value such as `32` or `4501` is stored and displayed exactly even though it is not aligned to a button increment.
- Footer save/apply/sync JSON sends all numeric parameters as JSON integers.

This removes the current floating-point representation and ensures speech commands, UI state, and TCP JSON use the same exact values.

## Voice Card States

The existing voice card remains the sole interaction surface and has explicit states:

- `Idle`: microphone enabled, example text visible.
- `Listening`: animated/listening indication and cancelable recognizer session.
- `Recognized`: transcript visible while parsing starts.
- `Preparing model`: first-use asset copy or model load progress.
- `Parsing`: microphone and `Apply` disabled.
- `Ready`: transcript, normalized command summary, and enabled `Apply` action.
- `Sending`: `Apply` disabled to prevent duplicates.
- `Success`: applied value and device shown briefly; the candidate is then cleared.
- `Error`: actionable permission, recognition, parsing, validation, connection, or server error; no UI parameter changes occur.

Starting a new recognition session clears the previous candidate. If the TCP client is disconnected, a valid candidate may remain visible, but tapping `Apply` opens the existing connection settings dialog rather than discarding the command.

## Architecture

### Speech Controller

Owns the platform recognizer, permission-independent recognition state, and lifecycle cleanup. It emits transcript or typed recognition errors and does not know about Qwen, parameters, or TCP.

### Intent Parser

Exposes one suspendable operation from transcript to raw candidate text. It first tries the deterministic parser, then delegates to a `QwenInferenceEngine` when needed. Model loading and inference are serialized so only one generation runs at a time.

### Command Codec and Validator

Parses strict JSON into a typed `SetParameterCommand`, checks the exact key set, device, parameter, value type, and range, and produces canonical compact and pretty JSON. This module is pure Kotlin and is the security boundary between probabilistic model output and machine control.

### UI Parameter Updater

Maps each validated canonical parameter to the corresponding Compose state update. It runs only after a `success` response and preserves the candidate when sending fails so the user can retry deliberately.

### Existing TCP Client

The existing client remains responsible for newline framing, bounded reads, timeouts, and connection state. Voice commands use the same single-flight send discipline as footer operations, but use the `SET_PARAMETER` protocol instead of the footer parameter envelope.

## Failure and Concurrency Rules

- Never retry a machine command automatically.
- Never send model output before Kotlin validation and a user tap on `Apply`.
- Reject multiple requested changes even if Qwen emits one of them.
- A new microphone action cancels stale recognition or parsing work before starting another session.
- Screen stop cancels recognition, generation, and sends, unloads or releases native resources as appropriate, and closes TCP according to the existing lifecycle policy.
- Model copy/load failures preserve the original asset and retry only after another explicit microphone action.
- A late result from an older recognition or inference generation cannot replace a newer candidate.

## Testing and Verification

Pure Kotlin unit tests cover:

- Device aliases and the default `machine_1` behavior.
- Canonical parameter names and mixed Chinese/English aliases.
- Chinese numeral and spacing normalization for identifiers such as `Lv 1`.
- Every integer lower and upper boundary, non-integer rejection, and exact off-step values.
- Boolean and template values.
- Strict key-set, action, device, parameter, type, range, extra-content, and multi-change rejection.
- Exact compact JSON plus TCP newline framing.
- Mapping each successful command to the correct UI update.

Android tests or focused manual verification cover permission flow, recognizer lifecycle, card states, model asset copy/load, cancellation, rotation or recreation behavior, and a NetAssist `success\n` round trip on a physical Android 10+ arm64 device.

Final verification runs all unit tests and `assembleDebug` from the project used by Android Studio. APK installation or release packaging is not required.
