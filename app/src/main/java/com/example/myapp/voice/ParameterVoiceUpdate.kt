package com.example.myapp.voice

import com.example.myapp.communication.DetectionParameters
import com.example.myapp.communication.ParameterCommandCodec

/** 服务端响应经过确认后，对当前参数快照产生的更新结果。 */
sealed interface ParameterVoiceUpdate {
    data class Applied(val parameters: DetectionParameters) : ParameterVoiceUpdate

    data object Rejected : ParameterVoiceUpdate
}

data class SharedParameterSendGate(
    val canPrepareFooterCommand: Boolean,
    val canApplyVoiceCommand: Boolean
)

fun sharedParameterSendGate(
    voiceSending: Boolean,
    footerPending: Boolean,
    footerSending: Boolean
): SharedParameterSendGate {
    // 手动整包命令与语音单参数命令共用一个发送槽。
    val sendSlotAvailable = !voiceSending && !footerPending && !footerSending
    return SharedParameterSendGate(
        canPrepareFooterCommand = sendSlotAvailable,
        canApplyVoiceCommand = sendSlotAvailable
    )
}

fun DetectionParameters.withVoiceCommand(command: SetParameterCommand): DetectionParameters =
    when (command.parameter) {
        VoiceParameter.lv1Sensitivity -> copy(lv1Sensitivity = command.value.integerValue())
        VoiceParameter.lv1Strength -> copy(lv1Strength = command.value.integerValue())
        VoiceParameter.lv1Density -> copy(lv1Density = command.value.integerValue())
        VoiceParameter.enhancedInference -> copy(
            enhancedInference = command.value.booleanValue()
        )
        VoiceParameter.lv1AreaMask -> copy(lv1AreaMask = command.value.booleanValue())
        VoiceParameter.minArea -> copy(minArea = command.value.integerValue())
        VoiceParameter.template -> copy(template = command.value.stringValue())
        VoiceParameter.lv2Strength -> copy(lv2Strength = command.value.integerValue())
        VoiceParameter.lv3Strength -> copy(lv3Strength = command.value.integerValue())
        VoiceParameter.actionDuration -> copy(actionDuration = command.value.integerValue())
        VoiceParameter.rejectDelay -> copy(rejectDelay = command.value.integerValue())
    }

fun DetectionParameters.voiceUpdateForResponse(
    command: SetParameterCommand,
    response: String
): ParameterVoiceUpdate = if (ParameterCommandCodec.isSuccessResponse(response)) {
    // 只有服务端明确返回 success，候选值才真正写入 UI 参数快照。
    ParameterVoiceUpdate.Applied(withVoiceCommand(command))
} else {
    ParameterVoiceUpdate.Rejected
}

private fun ParameterValue.integerValue(): Int =
    (this as? ParameterValue.IntValue)?.value
        ?: throw IllegalArgumentException("voice parameter value must be an integer")

private fun ParameterValue.booleanValue(): Boolean =
    (this as? ParameterValue.BooleanValue)?.value
        ?: throw IllegalArgumentException("voice parameter value must be a boolean")

private fun ParameterValue.stringValue(): String =
    (this as? ParameterValue.StringValue)?.value
        ?: throw IllegalArgumentException("voice parameter value must be a string")
