package com.example.myapp.communication

import org.json.JSONObject

/** 界面当前全部检测参数的不可变快照。 */
data class DetectionParameters(
    val lv1Sensitivity: Int,
    val lv1Strength: Int,
    val lv1Density: Int,
    val enhancedInference: Boolean,
    val lv1AreaMask: Boolean,
    val minArea: Int,
    val template: String,
    val lv2Strength: Int,
    val lv3Strength: Int,
    val actionDuration: Int,
    val rejectDelay: Int
)

enum class ParameterOperation(val wireName: String, val displayName: String) {
    Save("save_parameters", "保存参数"),
    Apply("apply_parameters", "应用参数"),
    Sync("sync_to_device", "同步到设备")
}

/** 同一条命令的预览形式、紧凑形式以及最终换行分帧形式。 */
data class EncodedParameterCommand(
    val operation: ParameterOperation,
    val timestamp: Long,
    val prettyJson: String,
    val compactJson: String,
    val wireText: String
)

object ParameterCommandCodec {
    /**
     * 将某一时刻的完整参数快照编码成上位机协议。
     * TCP 使用换行作为消息边界，因此 [wireText] 只在末尾追加一个换行符。
     */
    fun encode(
        operation: ParameterOperation,
        timestamp: Long,
        parameters: DetectionParameters
    ): EncodedParameterCommand {
        val values = JSONObject()
            .put("lv1Sensitivity", parameters.lv1Sensitivity)
            .put("lv1Strength", parameters.lv1Strength)
            .put("lv1Density", parameters.lv1Density)
            .put("enhancedInference", parameters.enhancedInference)
            .put("lv1AreaMask", parameters.lv1AreaMask)
            .put("minArea", parameters.minArea)
            .put("template", parameters.template)
            .put("lv2Strength", parameters.lv2Strength)
            .put("lv3Strength", parameters.lv3Strength)
            .put("actionDuration", parameters.actionDuration)
            .put("rejectDelay", parameters.rejectDelay)
        val root = JSONObject()
            .put("type", operation.wireName)
            .put("timestamp", timestamp)
            .put("parameters", values)
        val compact = root.toString()
        return EncodedParameterCommand(
            operation = operation,
            timestamp = timestamp,
            prettyJson = root.toString(2),
            compactJson = compact,
            wireText = "$compact\n"
        )
    }

    fun isSuccessResponse(response: String): Boolean =
        response.trim().equals("success", ignoreCase = true)
}
