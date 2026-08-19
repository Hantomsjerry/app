package com.example.myapp.voice

/** 语音卡片从录音到服务端确认的完整、互斥 UI 状态。 */
sealed interface VoiceUiState {
    data object Idle : VoiceUiState

    data class PreparingSpeechModel(val generation: Long) : VoiceUiState

    data class Recording(
        val generation: Long,
        val partialTranscript: String = ""
    ) : VoiceUiState

    data class Transcribing(val generation: Long) : VoiceUiState

    data class Parsing(
        val generation: Long,
        val transcript: String
    ) : VoiceUiState

    data class PreparingModel(
        val generation: Long,
        val transcript: String
    ) : VoiceUiState

    data class Ready(
        val generation: Long,
        val transcript: String,
        val command: SetParameterCommand,
        val retryError: String? = null
    ) : VoiceUiState

    data class Sending(
        val generation: Long,
        val transcript: String,
        val command: SetParameterCommand
    ) : VoiceUiState

    data class Success(
        val generation: Long,
        val transcript: String,
        val command: SetParameterCommand
    ) : VoiceUiState

    data class Error(
        val generation: Long,
        val message: String
    ) : VoiceUiState
}

/** 异步组件提交给纯状态归约器的事件；generation 用于淘汰过期回调。 */
sealed interface VoiceEvent {
    val generation: Long

    data class NewSession(override val generation: Long) : VoiceEvent
    data class RecordingStarted(override val generation: Long) : VoiceEvent
    data class PartialText(override val generation: Long, val transcript: String) : VoiceEvent
    data class RecordingStopped(override val generation: Long) : VoiceEvent
    data class FinalText(override val generation: Long, val transcript: String) : VoiceEvent
    data class ModelPreparationStarted(override val generation: Long) : VoiceEvent
    data class ParsedCommand(override val generation: Long, val command: SetParameterCommand) : VoiceEvent
    data class RecognitionFailed(override val generation: Long, val message: String) : VoiceEvent
    data class ParseFailed(override val generation: Long, val message: String) : VoiceEvent
    data class ModelFailed(override val generation: Long, val message: String) : VoiceEvent
    data class SendStarted(override val generation: Long) : VoiceEvent
    data class SendFailed(override val generation: Long, val message: String) : VoiceEvent
    data class SendSucceeded(override val generation: Long) : VoiceEvent
}

fun reduceVoiceState(state: VoiceUiState, event: VoiceEvent): VoiceUiState {
    // 新会话只能向前推进，重复或倒退的 generation 不得覆盖当前状态。
    if (event is VoiceEvent.NewSession) {
        return if (event.generation > state.generationOrNull().orZero()) {
            VoiceUiState.PreparingSpeechModel(event.generation)
        } else {
            state
        }
    }

    // 录音、推理或网络返回得再晚，也不能修改已经被替换的会话。
    if (event.generation != state.generationOrNull()) return state

    return when (state) {
        VoiceUiState.Idle -> state
        is VoiceUiState.PreparingSpeechModel -> when (event) {
            is VoiceEvent.RecordingStarted -> VoiceUiState.Recording(event.generation)
            is VoiceEvent.RecognitionFailed -> VoiceUiState.Error(event.generation, event.message)
            else -> state
        }
        is VoiceUiState.Recording -> when (event) {
            is VoiceEvent.PartialText -> state.copy(partialTranscript = event.transcript)
            is VoiceEvent.RecordingStopped -> VoiceUiState.Transcribing(event.generation)
            is VoiceEvent.RecognitionFailed -> VoiceUiState.Error(event.generation, event.message)
            else -> state
        }
        is VoiceUiState.Transcribing -> when (event) {
            is VoiceEvent.FinalText -> VoiceUiState.Parsing(event.generation, event.transcript)
            is VoiceEvent.RecognitionFailed -> VoiceUiState.Error(event.generation, event.message)
            else -> state
        }
        is VoiceUiState.Parsing -> when (event) {
            is VoiceEvent.ModelPreparationStarted -> VoiceUiState.PreparingModel(event.generation, state.transcript)
            is VoiceEvent.ParsedCommand -> VoiceUiState.Ready(event.generation, state.transcript, event.command)
            is VoiceEvent.ParseFailed -> VoiceUiState.Error(event.generation, event.message)
            else -> state
        }
        is VoiceUiState.PreparingModel -> when (event) {
            is VoiceEvent.ParsedCommand -> VoiceUiState.Ready(event.generation, state.transcript, event.command)
            is VoiceEvent.ModelFailed -> VoiceUiState.Error(event.generation, event.message)
            is VoiceEvent.ParseFailed -> VoiceUiState.Error(event.generation, event.message)
            else -> state
        }
        is VoiceUiState.Ready -> when (event) {
            is VoiceEvent.SendStarted -> VoiceUiState.Sending(event.generation, state.transcript, state.command)
            else -> state
        }
        is VoiceUiState.Sending -> when (event) {
            is VoiceEvent.SendFailed -> VoiceUiState.Ready(
                event.generation,
                state.transcript,
                state.command,
                event.message
            )
            is VoiceEvent.SendSucceeded -> VoiceUiState.Success(event.generation, state.transcript, state.command)
            else -> state
        }
        is VoiceUiState.Success, is VoiceUiState.Error -> state
    }
}

private fun VoiceUiState.generationOrNull(): Long? = when (this) {
    VoiceUiState.Idle -> null
    is VoiceUiState.PreparingSpeechModel -> generation
    is VoiceUiState.Recording -> generation
    is VoiceUiState.Transcribing -> generation
    is VoiceUiState.Parsing -> generation
    is VoiceUiState.PreparingModel -> generation
    is VoiceUiState.Ready -> generation
    is VoiceUiState.Sending -> generation
    is VoiceUiState.Success -> generation
    is VoiceUiState.Error -> generation
}

private fun Long?.orZero(): Long = this ?: 0L

enum class VoiceMicrophoneAction {
    StartRecording,
    StopAndTranscribe,
    Ignore
}

enum class SpeechRecognizerStatus {
    Loading,
    Ready,
    Failed
}

fun microphoneEnabledFor(
    state: VoiceUiState,
    recognizerStatus: SpeechRecognizerStatus
): Boolean = when (microphoneActionFor(state)) {
    VoiceMicrophoneAction.Ignore -> false
    VoiceMicrophoneAction.StopAndTranscribe -> true
    VoiceMicrophoneAction.StartRecording -> recognizerStatus == SpeechRecognizerStatus.Ready
}

/** 将复杂 UI 状态收敛为麦克风按钮允许执行的三种动作。 */
fun microphoneActionFor(state: VoiceUiState): VoiceMicrophoneAction = when (state) {
    VoiceUiState.Idle,
    is VoiceUiState.Ready,
    is VoiceUiState.Success,
    is VoiceUiState.Error -> VoiceMicrophoneAction.StartRecording

    is VoiceUiState.Recording -> VoiceMicrophoneAction.StopAndTranscribe
    is VoiceUiState.PreparingSpeechModel -> VoiceMicrophoneAction.Ignore
    is VoiceUiState.Transcribing,
    is VoiceUiState.Parsing,
    is VoiceUiState.PreparingModel,
    is VoiceUiState.Sending -> VoiceMicrophoneAction.Ignore
}
