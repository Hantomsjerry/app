# Task 6 RED/GREEN Report

Date: 2026-08-20
Branch: `codex/sensevoice-fuzzy-correction`
Task: Preserve raw and corrected voice transcripts through the voice state machine.

## RED

Tests were updated first in `VoiceUiStateTest.kt` to describe the Task 6
contract:

- `VoiceTranscript(raw, corrected)` is retained as one value.
- `FinalResult(generation, transcript, ambiguous)` replaces `FinalText`.
- Raw and corrected text remain together through Parsing, PreparingModel,
  Ready, Sending, Success, and parse/model Error states.
- Ambiguous correction becomes `Error("指令存在歧义", transcript)` and rejects
  parse-start and parsed-command events.
- New sessions reset to speech-model preparation without the old transcript.
- Stale final-result callbacks remain ignored by generation.

The required first test command was attempted once in the restricted sandbox,
but the Gradle wrapper could not open the existing user-cache lock file. The
same command was then run with approved Gradle-cache access:

```text
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.VoiceUiStateTest"
```

It failed for the intended RED reason before production changes:

```text
Unresolved reference 'FinalResult'
Unresolved reference 'VoiceTranscript'
Too many arguments for 'constructor(generation: Long, message: String)'
```

## GREEN

Implemented the smallest production change:

- Added `VoiceTranscript` and changed transcript-bearing state fields to use it.
- Replaced `VoiceEvent.FinalText` with `VoiceEvent.FinalResult`.
- Added optional transcript retention to `VoiceUiState.Error`.
- Made ambiguous final results transition directly to the Chinese ambiguity
  error, with no parse-start path.
- Preserved the transcript pair through all downstream reducer transitions.
- Kept the generation guard and new-session reset behavior unchanged.
- Migrated `MainActivity` only as required for compilation and temporary
  corrected-text-only display; the two-line UI remains Task 7 scope.
- Updated the existing Activity source-shape test to assert the new event
  contract and ambiguity ordering.

Focused reducer verification:

```text
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.VoiceUiStateTest"
BUILD SUCCESSFUL
```

Required focused state and command-safety verification:

```text
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.voice.VoiceUiStateTest" --tests "com.example.myapp.voice.ParameterVoiceUpdateTest" --tests "com.example.myapp.voice.VoiceCommandTest"
BUILD SUCCESSFUL
```

The full JVM suite first exposed two stale source-shape assertions expecting
the old `RecognitionFailed`/`FinalText` ambiguity sequence. After migrating
those assertions to `FinalResult(..., ambiguous = true)`, the targeted Activity
test passed:

```text
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.MainActivitySpeechScopeTest"
BUILD SUCCESSFUL
```

Final full JVM verification:

```text
.\gradlew.bat :app:testDebugUnitTest
299 tests completed
BUILD SUCCESSFUL
```

No ledger file was edited. No push was performed.
