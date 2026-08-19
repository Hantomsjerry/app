# Detection Parameters UI Design

## Goal

Build the first screen of a machine remote-control Android app, matching the provided screenshot as closely as practical in the existing Kotlin Jetpack Compose project.

This first version focuses on the UI and local interaction only. Real device communication, speech recognition, persistence, and cloud sync are out of scope for this pass.

## Chosen Approach

Use a single Jetpack Compose screen inside the existing `MainActivity`. This matches the current project setup, keeps the change small, and makes later remote-control integration straightforward.

## Screen Structure

The screen is titled `检测参数`.

The top blue header includes:

- Center title: `检测参数`
- Right-side connected status pill: `已连接`
- Right-side sync pill: `上位机同步`
- No left return arrow

The main content uses a light gray background and stacked white cards:

- Voice tuning card
- Detection parameters card
- Reject parameters card

The bottom action area contains three buttons:

- `保存参数`
- `应用参数`
- `同步到设备`

## Components

### Voice Tuning Card

Shows the section title `语音调参`, helper copy, an example command, a recognized result strip, and a circular microphone button. The microphone is visual only in this version.

### Parameter Rows

Each numeric parameter row contains:

- Chinese label
- Colored indicator dot when shown in the reference
- Slider-like control with matching accent color
- Stepper control with minus button, value, and plus button

Initial values:

- `Lv1 灵敏度`: 30
- `Lv1 强度`: 100
- `Lv1 浓淡`: 0
- `最小面积`: 5000
- `Lv2 强度`: 100
- `Lv3 强度`: 60.0
- `动作持续`: 1200
- `剔除延时`: 700

### Toggle Rows

The rows `强化推理` and `是否打开Lv1面积屏蔽` use local Compose switch state.

### Template Row

The `模版` row displays `400mmBase.engine` in a dropdown-style field with an adjacent information button. It is visual only in this version.

## Interaction

Local UI state is enough for this pass:

- Sliders can be dragged.
- Plus and minus controls adjust their row value.
- Toggles can be switched.
- Bottom buttons provide normal press feedback but do not call real services yet.

## Implementation Notes

Use small Compose functions so the screen stays readable:

- `DetectionParametersScreen`
- `TopStatusHeader`
- `VoiceTuningCard`
- `ParameterSectionCard`
- `RejectSectionCard`
- `ParameterControlRow`
- `ToggleControlRow`
- `TemplateControlRow`
- `BottomActionBar`

Use Material 3 and Compose primitives already available in the project. Avoid adding new dependencies for this UI pass.

## Testing

Build the debug app after implementation. If possible, run Compose preview or install/run manually in Android Studio to compare the screen against the reference image.
