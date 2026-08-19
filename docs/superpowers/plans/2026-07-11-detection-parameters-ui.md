# Detection Parameters UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the default Android greeting screen with a Jetpack Compose `检测参数` UI matching the provided reference image, without the left return arrow.

**Architecture:** Keep the implementation in the existing Compose app entry point. Split the screen into focused composable functions inside `MainActivity.kt` so the first version is easy to read and later can be moved into separate files when device communication is added.

**Tech Stack:** Kotlin, Android Jetpack Compose, Material 3, existing Gradle configuration.

## Global Constraints

- Use a single Jetpack Compose screen inside the existing `MainActivity`.
- Do not add new dependencies.
- The header must not include a left return arrow.
- This version implements local UI state only.
- Real device communication, speech recognition, persistence, and cloud sync are out of scope for this pass.

---

### Task 1: Compose Screen Replacement

**Files:**
- Modify: `app/src/main/java/com/example/myapp/MainActivity.kt`

**Interfaces:**
- Consumes: Existing `MyAppTheme` and `ComponentActivity.setContent`.
- Produces: `DetectionParametersScreen()` as the root screen composable.

- [ ] **Step 1: Replace default greeting content with the detection parameters screen**

Use `setContent { MyAppTheme(dynamicColor = false) { DetectionParametersScreen() } }`.

- [ ] **Step 2: Add local state model for numeric rows**

Use `rememberSaveable` values for:

```kotlin
var lv1Sensitivity by rememberSaveable { mutableFloatStateOf(30f) }
var lv1Strength by rememberSaveable { mutableFloatStateOf(100f) }
var lv1Density by rememberSaveable { mutableFloatStateOf(0f) }
var minArea by rememberSaveable { mutableFloatStateOf(5000f) }
var lv2Strength by rememberSaveable { mutableFloatStateOf(100f) }
var lv3Strength by rememberSaveable { mutableFloatStateOf(60f) }
var actionDuration by rememberSaveable { mutableFloatStateOf(1200f) }
var rejectDelay by rememberSaveable { mutableFloatStateOf(700f) }
```

- [ ] **Step 3: Add top status header**

Create `TopStatusHeader()` with blue background, centered title `检测参数`, right-side `已连接` pill, and `上位机同步` pill. Do not render a back arrow.

- [ ] **Step 4: Add content cards**

Create:

```kotlin
@Composable private fun VoiceTuningCard()
@Composable private fun DetectionSectionCard(...)
@Composable private fun RejectSectionCard(...)
```

Each card uses a white background, subtle shadow, compact padding, and Chinese copy from the reference.

- [ ] **Step 5: Add reusable row composables**

Create:

```kotlin
@Composable private fun ParameterControlRow(...)
@Composable private fun ToggleControlRow(...)
@Composable private fun TemplateControlRow()
@Composable private fun BottomActionBar()
```

`ParameterControlRow` renders label, color dot, slider, minus/value/plus stepper, and invokes `onValueChange`.

- [ ] **Step 6: Add local interactions**

Sliders, steppers, and switches update local Compose state. Buttons are clickable but do not call services.

- [ ] **Step 7: Preview the screen**

Replace the greeting preview with:

```kotlin
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun DetectionParametersPreview() {
    MyAppTheme(dynamicColor = false) {
        DetectionParametersScreen()
    }
}
```

### Task 2: Build Verification

**Files:**
- Verify: `app/src/main/java/com/example/myapp/MainActivity.kt`

**Interfaces:**
- Consumes: The implemented Compose screen from Task 1.
- Produces: A successful debug build.

- [ ] **Step 1: Run the Android debug build**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Fix compile issues if any**

If the build reports missing imports or incompatible Compose APIs, adjust `MainActivity.kt` using APIs available in the existing Compose/Material 3 setup.

- [ ] **Step 3: Re-run the debug build**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.
