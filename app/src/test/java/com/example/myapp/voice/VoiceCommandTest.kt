package com.example.myapp.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class VoiceCommandTest {
    @Test
    fun deviceWhitelistIsExact() {
        assertEquals(setOf("machine_1", "machine_2", "machine_3"), MachineDevice.values().map { it.wireName }.toSet())
    }

    @Test
    fun parameterWhitelistIsExact() {
        assertEquals(
            setOf(
                "lv1Sensitivity", "lv1Strength", "lv1Density", "enhancedInference", "lv1AreaMask",
                "minArea", "template", "lv2Strength", "lv3Strength", "actionDuration", "rejectDelay"
            ),
            VoiceParameter.values().map { it.wireName }.toSet()
        )
    }

    @Test
    fun parsesEveryDeviceInTheExactWhitelist() {
        MachineDevice.values().forEach { device ->
            val result = VoiceCommandCodec.parseStrict(json(device = device.wireName, parameter = "lv1Sensitivity", value = 0))
            assertTrue(result.isSuccess)
            assertEquals(device, result.getOrThrow().device)
        }
    }

    @Test
    fun parsesEveryWhitelistedParameterType() {
        val cases = listOf(
            "lv1Sensitivity" to "0",
            "lv1Strength" to "0",
            "lv1Density" to "0",
            "enhancedInference" to "true",
            "lv1AreaMask" to "false",
            "minArea" to "0",
            "template" to "\"400mmBase.engine\"",
            "lv2Strength" to "0",
            "lv3Strength" to "0",
            "actionDuration" to "0",
            "rejectDelay" to "0"
        )

        cases.forEach { (parameter, value) ->
            val command = VoiceCommandCodec.parseStrict(json(parameter = parameter, value = value)).getOrThrow()
            assertEquals(parameter, command.parameter.wireName)
        }
    }

    @Test
    fun acceptsEveryNumericBoundaryAndRejectsOneValueOutsideEachSide() {
        val ranges = mapOf(
            "lv1Sensitivity" to 100,
            "lv1Strength" to 120,
            "lv1Density" to 100,
            "minArea" to 10000,
            "lv2Strength" to 120,
            "lv3Strength" to 100,
            "actionDuration" to 2000,
            "rejectDelay" to 1500
        )

        ranges.forEach { (parameter, upper) ->
            assertTrue(VoiceCommandCodec.parseStrict(json(parameter = parameter, value = 0)).isSuccess)
            assertTrue(VoiceCommandCodec.parseStrict(json(parameter = parameter, value = upper)).isSuccess)
            assertFalse(VoiceCommandCodec.parseStrict(json(parameter = parameter, value = -1)).isSuccess)
            assertFalse(VoiceCommandCodec.parseStrict(json(parameter = parameter, value = upper + 1)).isSuccess)
        }
    }

    @Test
    fun acceptsOffStepNumericValuesAndExactTemplate() {
        assertTrue(VoiceCommandCodec.parseStrict(json(parameter = "lv1Strength", value = 119)).isSuccess)
        assertTrue(VoiceCommandCodec.parseStrict(json(parameter = "template", value = "\"400mmBase.engine\"")).isSuccess)
        assertFalse(VoiceCommandCodec.parseStrict(json(parameter = "template", value = "\"other.engine\"")).isSuccess)
    }

    @Test
    fun rejectsUnknownParameterAndPressure() {
        assertFalse(VoiceCommandCodec.parseStrict(json(parameter = "unknown", value = 1)).isSuccess)
        assertFalse(VoiceCommandCodec.parseStrict(json(parameter = "pressure", value = 1)).isSuccess)
    }

    @Test
    fun rejectsWrongActionAndUnknownDevice() {
        assertFalse(VoiceCommandCodec.parseStrict(json(action = "GET_PARAMETER")).isSuccess)
        assertFalse(VoiceCommandCodec.parseStrict(json(device = "machine_4")).isSuccess)
    }

    @Test
    fun rejectsWrongRootShapeAndValueTypes() {
        val valid = json()
        listOf(
            "{}",
            "{\"action\":\"SET_PARAMETER\",\"device\":\"machine_1\",\"parameter\":\"lv1Sensitivity\",\"value\":0,\"extra\":1}",
            "[${valid}]",
            "${valid} ${valid}",
            "```json\n$valid\n```",
            "prefix $valid",
            "${valid} suffix",
            json(parameter = "lv1Sensitivity", value = "\"0\""),
            json(parameter = "lv1Sensitivity", value = "1.5"),
            json(parameter = "lv1Sensitivity", value = "1.0"),
            json(parameter = "lv1Sensitivity", value = "1e0"),
            json(parameter = "lv1Sensitivity", value = "2147483648"),
            json(parameter = "lv1Sensitivity", value = "null"),
            json(parameter = "lv1Sensitivity", value = "true"),
            json(parameter = "enhancedInference", value = "1"),
            json(parameter = "template", value = "400mmBase.engine"),
            json(parameter = "lv1Sensitivity", value = "[]"),
            json(parameter = "lv1Sensitivity", value = "{\"nested\":1}"),
            "{\"action\":\"SET_PARAMETER\";\"device\":\"machine_1\",\"parameter\":\"lv1Sensitivity\",\"value\":0}",
            "{\"action\":\"SET_PARAMETER\",\"device\":\"machine_1\",\"parameter\":\"lv1Sensitivity\",\"value\":0,}",
            "{\"action\":\"SET_PARAMETER\",\"device\":\"machine_1\",\"parameter\":\"lv1Sensitivity\",\"value\":0, }"
        ).forEach { raw -> assertFalse("accepted: $raw", VoiceCommandCodec.parseStrict(raw).isSuccess) }
    }

    @Test
    fun rejectsAllNumericCoercionForms() {
        listOf("1.0", "1e0", "\"1\"", "2147483648", "-2147483649").forEach { value ->
            assertFalse(VoiceCommandCodec.parseStrict(json(parameter = "lv1Strength", value = value)).isSuccess)
        }
    }

    @Test
    fun serializesCanonicalCompactAndPrettyJson() {
        val command = SetParameterCommand(
            action = "SET_PARAMETER",
            device = MachineDevice.machine_2,
            parameter = VoiceParameter.lv1Strength,
            value = ParameterValue.IntValue(119)
        )
        val expected = "{\"action\":\"SET_PARAMETER\",\"device\":\"machine_2\",\"parameter\":\"lv1Strength\",\"value\":119}"
        assertEquals(expected, VoiceCommandCodec.compact(command))
        assertEquals(
            "{\n  \"action\": \"SET_PARAMETER\",\n  \"device\": \"machine_2\",\n  \"parameter\": \"lv1Strength\",\n  \"value\": 119\n}",
            VoiceCommandCodec.pretty(command)
        )
    }

    @Test
    fun rejectsInvalidCommandsDuringCompactAndPrettySerialization() {
        val invalidCommands = listOf(
            SetParameterCommand("GET_PARAMETER", MachineDevice.machine_1, VoiceParameter.lv1Strength, ParameterValue.IntValue(1)),
            SetParameterCommand("SET_PARAMETER", MachineDevice.machine_1, VoiceParameter.lv1Strength, ParameterValue.BooleanValue(true)),
            SetParameterCommand("SET_PARAMETER", MachineDevice.machine_1, VoiceParameter.lv1Strength, ParameterValue.IntValue(121)),
            SetParameterCommand("SET_PARAMETER", MachineDevice.machine_1, VoiceParameter.template, ParameterValue.StringValue("other.engine"))
        )

        invalidCommands.forEach { command ->
            assertThrows(IllegalArgumentException::class.java) { VoiceCommandCodec.compact(command) }
            assertThrows(IllegalArgumentException::class.java) { VoiceCommandCodec.pretty(command) }
        }
    }

    private fun json(
        action: String = "SET_PARAMETER",
        device: String = "machine_1",
        parameter: String = "lv1Sensitivity",
        value: Any = 0
    ): String = "{\"action\":\"$action\",\"device\":\"$device\",\"parameter\":\"$parameter\",\"value\":$value}"
}
