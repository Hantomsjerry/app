package com.example.myapp.voice

import org.json.JSONObject
import org.json.JSONTokener

/** 语音协议允许寻址的设备白名单。 */
enum class MachineDevice(val wireName: String) {
    machine_1("machine_1"),
    machine_2("machine_2"),
    machine_3("machine_3");

    companion object {
        fun fromWireName(value: String): MachineDevice? = values().singleOrNull { it.wireName == value }
    }
}

/** 语音可修改的参数白名单；数值参数同时携带允许范围。 */
enum class VoiceParameter(val wireName: String, val minimum: Int? = null, val maximum: Int? = null) {
    lv1Sensitivity("lv1Sensitivity", 0, 100),
    lv1Strength("lv1Strength", 0, 120),
    lv1Density("lv1Density", 0, 100),
    enhancedInference("enhancedInference"),
    lv1AreaMask("lv1AreaMask"),
    minArea("minArea", 0, 10000),
    template("template"),
    lv2Strength("lv2Strength", 0, 120),
    lv3Strength("lv3Strength", 0, 100),
    actionDuration("actionDuration", 0, 2000),
    rejectDelay("rejectDelay", 0, 1500);

    companion object {
        fun fromWireName(value: String): VoiceParameter? = values().singleOrNull { it.wireName == value }
    }
}

/** 保留 JSON 值的精确类型，禁止模型输出在整数、布尔和字符串之间隐式转换。 */
sealed class ParameterValue {
    data class IntValue(val value: Int) : ParameterValue()
    data class BooleanValue(val value: Boolean) : ParameterValue()
    data class StringValue(val value: String) : ParameterValue()
}

data class SetParameterCommand(
    val action: String,
    val device: MachineDevice,
    val parameter: VoiceParameter,
    val value: ParameterValue
)

/** 语音命令的安全边界：验证 JSON 语法、精确字段集、白名单、类型和取值范围。 */
object VoiceCommandCodec {
    private val requiredKeys = setOf("action", "device", "parameter", "value")

    fun parseStrict(raw: String): Result<SetParameterCommand> = runCatching {
        // 先用严格语法器拒绝 JSONTokener 可能容忍的非标准写法。
        StrictJsonGrammar(raw).parseObject()
        val tokener = JSONTokener(raw)
        val root = tokener.nextValue() as? JSONObject ?: error("root must be an object")
        require(tokener.nextClean() == '\u0000')
        require(root.length() == requiredKeys.size && root.keys().asSequence().toSet() == requiredKeys)

        val action = root.get("action") as? String ?: error("action must be a string")
        require(action == "SET_PARAMETER")
        val deviceName = root.get("device") as? String ?: error("device must be a string")
        val device = MachineDevice.fromWireName(deviceName) ?: error("unknown device")
        val parameterName = root.get("parameter") as? String ?: error("parameter must be a string")
        val parameter = VoiceParameter.fromWireName(parameterName) ?: error("unknown parameter")

        SetParameterCommand(action, device, parameter, parseValue(root.get("value"), parameter))
    }

    fun compact(command: SetParameterCommand): String = serialize(command, pretty = false)

    fun pretty(command: SetParameterCommand): String = serialize(command, pretty = true)

    private fun parseValue(raw: Any, parameter: VoiceParameter): ParameterValue {
        if (raw === JSONObject.NULL) error("value must not be null")
        return when {
            parameter.minimum != null && raw is Int -> {
                require(raw in parameter.minimum..parameter.maximum!!)
                ParameterValue.IntValue(raw)
            }
            parameter.minimum != null -> error("value must be an integer")
            parameter == VoiceParameter.enhancedInference || parameter == VoiceParameter.lv1AreaMask ->
                if (raw is Boolean) ParameterValue.BooleanValue(raw) else error("value must be boolean")
            parameter == VoiceParameter.template ->
                if (raw is String && raw == "400mmBase.engine") ParameterValue.StringValue(raw)
                else error("value must be the approved template")
            else -> error("unsupported parameter")
        }
    }

    private fun serialize(command: SetParameterCommand, pretty: Boolean): String {
        validate(command)
        val fields = listOf(
            "action" to JSONObject.quote(command.action),
            "device" to JSONObject.quote(command.device.wireName),
            "parameter" to JSONObject.quote(command.parameter.wireName),
            "value" to valueJson(command.value)
        )
        return if (pretty) {
            fields.joinToString(",\n", "{\n", "\n}") { (key, value) ->
                "  " + JSONObject.quote(key) + ": " + value
            }
        } else {
            fields.joinToString(",", "{", "}") { (key, value) ->
                JSONObject.quote(key) + ":" + value
            }
        }
    }

    private fun validate(command: SetParameterCommand) {
        require(command.action == "SET_PARAMETER")
        when {
            command.parameter.minimum != null -> {
                val value = command.value as? ParameterValue.IntValue
                    ?: throw IllegalArgumentException("value must be an integer")
                require(value.value in command.parameter.minimum..command.parameter.maximum!!)
            }
            command.parameter == VoiceParameter.enhancedInference || command.parameter == VoiceParameter.lv1AreaMask ->
                require(command.value is ParameterValue.BooleanValue)
            command.parameter == VoiceParameter.template -> {
                val value = command.value as? ParameterValue.StringValue
                    ?: throw IllegalArgumentException("value must be a string")
                require(value.value == "400mmBase.engine")
            }
            else -> throw IllegalArgumentException("unsupported parameter")
        }
    }

    private fun valueJson(value: ParameterValue): String = when (value) {
        is ParameterValue.IntValue -> value.value.toString()
        is ParameterValue.BooleanValue -> value.value.toString()
        is ParameterValue.StringValue -> JSONObject.quote(value.value)
    }
}

/** 仅接受 RFC 风格 JSON 结构，拒绝尾随内容、错误转义和宽松数字格式。 */
private class StrictJsonGrammar(private val input: String) {
    private var index = 0

    fun parseObject() {
        skipWhitespace()
        parseObjectValue()
        skipWhitespace()
        require(index == input.length)
    }

    private fun parseValue() {
        skipWhitespace()
        require(index < input.length)
        when (input[index]) {
            '{' -> parseObjectValue()
            '[' -> parseArrayValue()
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> error("invalid JSON value")
        }
    }

    private fun parseObjectValue() {
        index++
        skipWhitespace()
        if (consume('}')) return
        while (true) {
            require(index < input.length && input[index] == '"')
            parseString()
            skipWhitespace()
            require(consume(':'))
            parseValue()
            skipWhitespace()
            if (consume('}')) return
            require(consume(','))
            skipWhitespace()
        }
    }

    private fun parseArrayValue() {
        index++
        skipWhitespace()
        if (consume(']')) return
        while (true) {
            parseValue()
            skipWhitespace()
            if (consume(']')) return
            require(consume(','))
            skipWhitespace()
        }
    }

    private fun parseString() {
        require(consume('"'))
        while (index < input.length) {
            when (val character = input[index++]) {
                '"' -> return
                '\\' -> {
                    require(index < input.length)
                    when (input[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            require(index < input.length && input[index++].isHexDigit())
                        }
                        else -> error("invalid JSON escape")
                    }
                }
                else -> require(character >= ' ')
            }
        }
        error("unterminated JSON string")
    }

    private fun parseNumber() {
        if (consume('-')) require(index < input.length)
        if (consume('0')) {
            require(index >= input.length || input[index] !in '0'..'9')
        } else {
            require(index < input.length && input[index] in '1'..'9')
            while (index < input.length && input[index].isDigit()) index++
        }
        if (consume('.')) {
            require(index < input.length && input[index].isDigit())
            while (index < input.length && input[index].isDigit()) index++
        }
        if (index < input.length && input[index] in "eE") {
            index++
            if (index < input.length && input[index] in "+-") index++
            require(index < input.length && input[index].isDigit())
            while (index < input.length && input[index].isDigit()) index++
        }
    }

    private fun parseLiteral(literal: String) {
        require(input.regionMatches(index, literal, 0, literal.length))
        index += literal.length
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index] in " \t\r\n") index++
    }

    private fun consume(expected: Char): Boolean =
        index < input.length && input[index] == expected && run { index++; true }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
