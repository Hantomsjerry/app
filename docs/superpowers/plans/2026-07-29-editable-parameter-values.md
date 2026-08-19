# Editable Parameter Values Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow every integer parameter value to be edited in place with the Android numeric keyboard while retaining exact integers and enforcing the existing UI ranges.

**Architecture:** Add one pure Kotlin commit function for parsing, fallback, and clamping, then reuse it from the shared Compose `Stepper`. Keep temporary text and focus state inside the stepper while the existing screen state remains the single source of truth after commit.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit 4, Gradle Android plugin

## Global Constraints

- Android minimum SDK remains 29.
- All eight numeric parameters remain integers.
- Existing sliders and plus/minus buttons remain available.
- Typed values are not rounded to a slider or button step.
- Empty or invalid input restores the previous value.
- Out-of-range input clamps to the parameter's current UI range.
- No new dependency is introduced.

---

### Task 1: Parameter Input Commit Rule

**Files:**
- Modify: `app/src/main/java/com/example/myapp/ParameterAdjuster.kt`
- Modify: `app/src/test/java/com/example/myapp/ParameterAdjusterTest.kt`

**Interfaces:**
- Consumes: `text: String`, `previousValue: Int`, `range: IntRange`
- Produces: `fun commitParameterInput(text: String, previousValue: Int, range: IntRange): Int`

- [x] **Step 1: Write failing parsing and validation tests**

```kotlin
@Test fun commitInput_preservesExactInRangeInteger() {
    assertEquals(32, commitParameterInput("32", 30, 0..100))
}

@Test fun commitInput_clampsValuesOutsideRange() {
    assertEquals(100, commitParameterInput("150", 30, 0..100))
    assertEquals(0, commitParameterInput("-5", 30, 0..100))
}

@Test fun commitInput_restoresPreviousValueForInvalidText() {
    assertEquals(30, commitParameterInput("", 30, 0..100))
    assertEquals(30, commitParameterInput("abc", 30, 0..100))
    assertEquals(30, commitParameterInput("2147483648", 30, 0..100))
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.ParameterAdjusterTest"`

Expected: compilation fails because `commitParameterInput` does not exist.

- [x] **Step 3: Implement the minimal pure Kotlin rule**

```kotlin
fun commitParameterInput(text: String, previousValue: Int, range: IntRange): Int =
    text.toIntOrNull()?.coerceIn(range) ?: previousValue
```

- [x] **Step 4: Run the focused test and verify GREEN**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.example.myapp.ParameterAdjusterTest"`

Expected: all `ParameterAdjusterTest` tests pass.

### Task 2: Editable Compose Stepper

**Files:**
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt:1086`

**Interfaces:**
- Consumes: `value: Int`, `range: IntRange`, existing minus/plus callbacks
- Produces: an editable `Stepper` that invokes `onValueChange(Int)` once when input is committed

- [x] **Step 1: Pass the integer value, range, and change callback from `ParameterControlRow` to `Stepper`**

```kotlin
Stepper(
    value = value,
    range = range,
    onValueChange = onValueChange,
    onMinus = { onValueChange(adjustParameterValue(value, -1, step, range)) },
    onPlus = { onValueChange(adjustParameterValue(value, 1, step, range)) }
)
```

- [x] **Step 2: Replace the center `Text` with a single-line `BasicTextField`**

Use `TextFieldValue` so focus acquisition can select the full current value. Configure `KeyboardType.Number`, `ImeAction.Done`, centered text, and the existing fixed dimensions and borders.

- [x] **Step 3: Commit on IME Done and focus loss**

Call `commitParameterInput(editor.text, value, range)`, synchronize the editor text with the committed integer, clear focus after IME Done, and avoid a duplicate state update when the same value is produced.

- [x] **Step 4: Synchronize external changes**

Use `LaunchedEffect(value)` to refresh the editor text after slider, plus/minus, voice, or server-related state changes while the field is not actively being edited.

- [x] **Step 5: Compile the Android app**

Run: `./gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL with no Compose compilation errors.

### Task 3: Regression Verification

**Files:**
- Verify only; no planned production changes

**Interfaces:**
- Consumes: completed Tasks 1 and 2
- Produces: evidence that existing parameter, TCP, and voice behavior remains intact

- [x] **Step 1: Run all JVM unit tests**

Run: `./gradlew.bat :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL and all tests pass.

- [x] **Step 2: Build the debug app for Android Studio**

Run: `./gradlew.bat :app:assembleDebug`

Expected: BUILD SUCCESSFUL. No APK packaging handoff is required; this verifies the project remains runnable from Android Studio.

- [x] **Step 3: Review the diff**

Confirm only the input helper, its tests, the shared stepper, and these design/plan documents changed.
