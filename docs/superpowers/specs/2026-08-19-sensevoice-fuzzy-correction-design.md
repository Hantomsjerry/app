# SenseVoice Offline ASR and Fuzzy Correction Design

## Objective

Replace the current streaming bilingual Zipformer recognizer with the bundled
`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` model. Recognition
will operate on one complete, manually bounded recording. A global fuzzy
correction stage will improve machine-control terms before intent parsing.

The recognition card temporarily displays two unlabeled lines for testing:

1. The raw SenseVoice transcript.
2. The corrected transcript.

Only the corrected transcript is parsed. The raw transcript is also retained in
debug logs. The temporary first line can be removed later without changing the
recognition or parsing pipeline.

## Scope

- Bundle `model.int8.onnx` and `tokens.txt` with the app.
- Remove the old Zipformer model, BPE vocabulary, and hotword assets.
- Replace the online recognizer adapter with sherpa-onnx `OfflineRecognizer`.
- Keep the existing manual start/stop recording interaction and 20-second cap.
- Keep the recognizer resident while the page is active.
- Add global fuzzy transcript correction with ambiguity detection.
- Preserve deterministic intent parsing, local Qwen fallback, parameter range
  validation, and user-triggered application of the recognized command.
- Do not add a correction settings screen.

## Architecture

The end-to-end flow is:

```text
Manual PCM recording
  -> one complete utterance buffer
  -> SenseVoice OfflineRecognizer
  -> raw transcript (UI first line and debug log)
  -> fuzzy transcript correction
  -> corrected transcript (UI second line)
  -> deterministic intent parser
  -> local Qwen fallback when deterministic parsing needs it
  -> device, parameter type, and range validation
  -> user taps Apply
  -> TCP JSON command
```

SenseVoice uses automatic language selection and inverse text normalization so
spoken Chinese and English numbers can be emitted in a parser-friendly form.
Recognition and correction remain fully local.

## Components

### SenseVoiceModelStore

`SenseVoiceModelStore` prepares two bundled assets in the app-private model
directory:

- `model.int8.onnx`
- `tokens.txt`

Each manifest entry records its exact byte length and SHA-256. Preparation uses
the existing partial-file and atomic-publication behavior. A verified existing
copy is reused, while stale or invalid files are replaced safely.

### SenseVoiceSpeechEngine

`SenseVoiceSpeechEngine` owns one resident `OfflineRecognizer` on a dedicated
single-thread dispatcher. Preparing the engine loads the model once. Each
recording creates one `OfflineStream`, accepts the valid PCM prefix once,
decodes once, reads one final result, and releases the stream.

No partial transcript, endpoint detector, online stream, modified beam search,
or native hotword file remains in this path. Cancel, failure, controller close,
and page destruction all release the active stream. Closing the engine releases
the resident recognizer.

The existing engine-facing interface may retain session-oriented method names
to minimize controller churn, but its implementation and tests must enforce the
offline one-shot contract.

### SpeechCorrectionDictionary

The correction dictionary contains canonical terms and accepted aliases for:

- `machine_1`, `machine_2`, and `machine_3`
- Chinese and English device names
- `Lv1`, `Lv2`, and `Lv3`
- all supported parameter names
- action verbs and setting phrases
- enable/disable expressions
- number expressions and existing template names

Existing one-off ASR repairs in `SpeechTranscriptNormalizer` move into this
dictionary where appropriate. Deterministic punctuation, spacing, repeated
number, and mixed `L V 1` normalization remains separate.

### FuzzyTranscriptCorrector

`FuzzyTranscriptCorrector` scans contiguous spans throughout the transcript and
compares them with canonical dictionary entries. It combines:

- exact alias matches;
- Pinyin and near-homophone similarity;
- normalized character edit distance;
- repeated-span detection;
- Chinese/English mixed-form normalization;
- contextual compatibility between devices, levels, parameters, actions, and
  values.

Number normalization runs after lexical correction to avoid discarding the
original spoken-number structure too early. The Pinyin converter is hidden
behind a small interface so it can be tested independently and its underlying
implementation can be replaced without changing correction policy.

The initial minimum accepted candidate score is `0.82`. A correction is
ambiguous when the two best candidates are valid and their score difference is
less than `0.08`. There is no limit on the number or proportion of replacements
in one sentence. Ambiguity, rather than replacement count, blocks application.

The result contract is:

```kotlin
data class CorrectionResult(
    val rawText: String,
    val correctedText: String,
    val confidence: Float,
    val ambiguous: Boolean,
    val replacements: List<CorrectionReplacement>
)
```

Each replacement records its source span, replacement text, score, and reason.
This provides enough information for tests and debug logs without exposing
technical details in the production UI.

### LocalSpeechController and UI

`LocalSpeechController` coordinates model preparation, recording, offline
recognition, correction, and intent parsing. Its UI state carries the raw and
corrected transcripts separately.

The recognition card displays the two values directly, without `Raw`,
`Corrected`, `原始`, or `纠正` labels. The raw transcript is above the corrected
transcript. Both lines remain visible even when they are identical. Starting a
new recording clears both lines and the previous correction decision.

Only `correctedText` is supplied to `VoiceIntentParser`. An ambiguous correction
keeps both lines visible, reports that the instruction is ambiguous, and
disables application of the voice result.

## Lifecycle and Error Handling

- While the recognizer is loading, microphone interaction is disabled.
- A model-load failure is surfaced as a voice initialization failure. Re-entering
  the page can retry initialization.
- Recording stops on the second microphone tap or at the existing 20-second
  hard limit.
- While offline decoding is running, another recognition cannot start.
- Empty recognition is treated as a failed recognition and is not parsed.
- Ambiguous correction is displayed but cannot be applied.
- Parse failure or safety-validation failure preserves both transcript lines,
  reports the reason, and does not mutate parameter values.
- Page destruction stops recording and releases the PCM buffer and active
  offline stream.
- Controller shutdown also releases the resident recognizer and dispatcher.
- Raw text, corrected text, replacement details, ambiguity, and elapsed
  recognition time are written to debug logs without changing TCP payloads.

## Safety Rules

Global fuzzy matching can alter any part of the sentence, but it cannot bypass
the existing command safety boundary:

- The target must resolve to exactly one of the three supported machines.
- The parameter must resolve to exactly one supported parameter.
- Values must have the correct integer or Boolean type.
- Integer values must remain within the UI parameter range.
- Ambiguous correction or parsing blocks application.
- Negated or unsupported instructions remain rejected.
- No command is sent until the user taps the existing Apply action in the voice
  card.

## Model and Packaging

The app will contain only the SenseVoice INT8 ASR model. The old Zipformer
encoder, decoder, joiner, tokens, BPE vocabulary, and hotword files are deleted.
The local Qwen model remains unchanged.

The existing sherpa-onnx `1.13.2` AAR already exposes the required offline
recognizer and SenseVoice configuration classes, so an AAR upgrade is not part
of this change unless implementation verification finds a concrete
compatibility defect.

## Verification

### Unit tests

- Model manifest validation, successful preparation, verified-file reuse,
  partial cleanup, hash failure, and path confinement.
- One resident recognizer with one offline stream per utterance.
- Full valid PCM prefix accepted once and decoded once.
- Stream and recognizer cleanup on success, cancellation, failure, and close.
- Exact aliases, Pinyin similarity, near homophones, edit distance, repeated
  phrases, mixed Chinese/English level names, and number normalization.
- Ambiguity when the top two candidates differ by less than `0.08`.
- No replacement-count or replacement-ratio rejection.
- Raw text above corrected text and both lines retained when identical.
- Only corrected text passed to intent parsing.
- Ambiguous, invalid, and out-of-range results cannot be applied.
- A new recording clears both transcript lines.

### Android instrumentation

An emulator test decodes bundled representative WAV utterances and records raw
text, corrected text, and elapsed time. It verifies that native initialization,
one-shot decoding, result extraction, and release work on Android. Emulator
timing is used to catch hangs and major regressions, not to predict mid-range
Snapdragon performance.

### Build verification

Run the complete debug unit test suite and create the debug APK. Confirm that
the APK contains the SenseVoice model and tokens and does not contain any old
Zipformer or hotword assets.

