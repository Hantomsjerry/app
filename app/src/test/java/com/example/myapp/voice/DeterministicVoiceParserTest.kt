package com.example.myapp.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicVoiceParserTest {
    @Test
    fun parsesLv1SensitivityWithDefaultDeviceAndNormalizedPunctuation() {
        assertParsed(
            transcript = "  LV 1 sensitivity\uff0c\u6539\u4e3a 32  ",
            parameter = VoiceParameter.lv1Sensitivity,
            value = ParameterValue.IntValue(32)
        )
    }

    @Test
    fun parsesConversationalChinesePrefixAroundAnEnglishAlias() {
        assertParsed(
            transcript = "\u8bf7\u628a Lv 1 sensitivity \u6539\u4e3a 32",
            parameter = VoiceParameter.lv1Sensitivity,
            value = ParameterValue.IntValue(32)
        )
    }

    @Test
    fun parsesChineseDeviceAliasAndBooleanValue() {
        assertParsed(
            transcript = "\u4e8c\u53f7\u673a enhanced inference \u6253\u5f00",
            device = MachineDevice.machine_2,
            parameter = VoiceParameter.enhancedInference,
            value = ParameterValue.BooleanValue(true)
        )
    }

    @Test
    fun parsesCanonicalDeviceAndMinimumAreaOffStepValue() {
        assertParsed(
            transcript = "machine_3 minArea \u8bbe\u7f6e 4501",
            device = MachineDevice.machine_3,
            parameter = VoiceParameter.minArea,
            value = ParameterValue.IntValue(4501)
        )
    }

    @Test
    fun parsesCanonicalBooleanFalseAndExactTemplate() {
        assertParsed(
            transcript = "lv1AreaMask false",
            parameter = VoiceParameter.lv1AreaMask,
            value = ParameterValue.BooleanValue(false)
        )
        assertParsed(
            transcript = "template 400mmBase.engine",
            parameter = VoiceParameter.template,
            value = ParameterValue.StringValue("400mmBase.engine")
        )
    }

    @Test
    fun parsesExactTemplateWithTerminalAsciiPeriod() {
        assertParsed(
            transcript = "template 400mmBase.engine.",
            parameter = VoiceParameter.template,
            value = ParameterValue.StringValue("400mmBase.engine")
        )
    }

    @Test
    fun parsesEveryCanonicalNumericParameter() {
        val cases = listOf(
            VoiceParameter.lv1Sensitivity to 32,
            VoiceParameter.lv1Strength to 119,
            VoiceParameter.lv1Density to 41,
            VoiceParameter.minArea to 4501,
            VoiceParameter.lv2Strength to 77,
            VoiceParameter.lv3Strength to 63,
            VoiceParameter.actionDuration to 1001,
            VoiceParameter.rejectDelay to 701
        )

        cases.forEach { (parameter, value) ->
            assertParsed(
                transcript = "${parameter.wireName} $value",
                parameter = parameter,
                value = ParameterValue.IntValue(value)
            )
        }
    }

    @Test
    fun parsesControlledEnglishAndChineseParameterAliases() {
        val cases = listOf(
            "Lv1 strength 12" to (VoiceParameter.lv1Strength to 12),
            "Lv1 density 13" to (VoiceParameter.lv1Density to 13),
            "\u4e00\u7ea7\u7075\u654f\u5ea6 14" to (VoiceParameter.lv1Sensitivity to 14),
            "\u4e00\u7ea7\u5f3a\u5ea6 15" to (VoiceParameter.lv1Strength to 15),
            "\u4e00\u7ea7\u5bc6\u5ea6 16" to (VoiceParameter.lv1Density to 16),
            "area mask on" to (VoiceParameter.lv1AreaMask to true),
            "minimum area 17" to (VoiceParameter.minArea to 17),
            "Lv2 strength 18" to (VoiceParameter.lv2Strength to 18),
            "Lv3 strength 19" to (VoiceParameter.lv3Strength to 19),
            "action duration 20" to (VoiceParameter.actionDuration to 20),
            "reject delay 21" to (VoiceParameter.rejectDelay to 21)
        )

        cases.forEach { (transcript, expected) ->
            val value = when (val raw = expected.second) {
                is Boolean -> ParameterValue.BooleanValue(raw)
                is Int -> ParameterValue.IntValue(raw)
                else -> error("unexpected test value")
            }
            assertParsed(transcript, parameter = expected.first, value = value)
        }
    }

    @Test
    fun parsesChineseIntegerParameterValuesFromOfflineAsr() {
        assertParsed(
            transcript = "把二号机的一级灵敏度调整到九十七",
            device = MachineDevice.machine_2,
            parameter = VoiceParameter.lv1Sensitivity,
            value = ParameterValue.IntValue(97)
        )
        assertParsed(
            transcript = "把一号机的一级强度调整到八十",
            device = MachineDevice.machine_1,
            parameter = VoiceParameter.lv1Strength,
            value = ParameterValue.IntValue(80)
        )
    }

    @Test
    fun parsesControlledEnglishWordIntegerFromOfflineAsr() {
        assertParsed(
            transcript = "machine_2 level 1 sensitivity thirty two",
            device = MachineDevice.machine_2,
            parameter = VoiceParameter.lv1Sensitivity,
            value = ParameterValue.IntValue(32)
        )
    }

    @Test
    fun rejectsEnglishHundredChainThatWrapsIntoAllowedRange() {
        val overflowingValue = buildString {
            append("one")
            repeat(16) { append(" hundred") }
        }

        assertRejected("lv2Strength $overflowingValue")
    }

    @Test
    fun rejectsRepeatedEnglishUnitSequencesAcrossAPropertyLikeRange() {
        (2..32).forEach { repetitionCount ->
            val repeatedHundreds = List(repetitionCount) { "hundred" }.joinToString(" ")
            assertRejected("minArea one $repeatedHundreds")
        }
        (2..16).forEach { repetitionCount ->
            val repeatedThousands = List(repetitionCount) { "thousand" }.joinToString(" ")
            assertRejected("actionDuration one $repeatedThousands")
        }
    }

    @Test
    fun keepsValidEnglishHundredAndThousandForms() {
        assertParsed(
            transcript = "lv2Strength one hundred twenty",
            parameter = VoiceParameter.lv2Strength,
            value = ParameterValue.IntValue(120)
        )
        assertParsed(
            transcript = "actionDuration sixteen hundred",
            parameter = VoiceParameter.actionDuration,
            value = ParameterValue.IntValue(1600)
        )
        assertParsed(
            transcript = "rejectDelay one thousand five hundred",
            parameter = VoiceParameter.rejectDelay,
            value = ParameterValue.IntValue(1500)
        )
    }

    @Test
    fun parsesMixedLvAndChineseStrengthAliasesWithoutQwen() {
        assertParsed(
            transcript = "Lv2强度改为80",
            parameter = VoiceParameter.lv2Strength,
            value = ParameterValue.IntValue(80)
        )
        assertParsed(
            transcript = "Lv3 强度调整到60",
            parameter = VoiceParameter.lv3Strength,
            value = ParameterValue.IntValue(60)
        )
    }

    @Test
    fun parsesUiRejectDelayPhrasesWithoutQwen() {
        listOf("剔除延时", "剔除延迟", "去除延时", "剔除岩石").forEach { alias ->
            assertParsed(
                transcript = "${alias}调整到700",
                parameter = VoiceParameter.rejectDelay,
                value = ParameterValue.IntValue(700)
            )
        }
    }

    @Test
    fun parsesCentralizedExistingAliasesWithoutChangingCommandBehavior() {
        assertParsed(
            transcript = "增强推断 打开",
            parameter = VoiceParameter.enhancedInference,
            value = ParameterValue.BooleanValue(true)
        )
        assertParsed(
            transcript = "拒绝延迟调整到700",
            parameter = VoiceParameter.rejectDelay,
            value = ParameterValue.IntValue(700)
        )
    }

    @Test
    fun doesNotTreatConversationalYiDianAsNumericValueOne() {
        assertEquals(
            DirectParseResult.NeedsModel,
            DeterministicVoiceParser.parse("Lv2强度调高一点")
        )
    }

    @Test
    fun acceptsEveryNumericBoundaryAndRejectsOutOfRangeValues() {
        val ranges = mapOf(
            VoiceParameter.lv1Sensitivity to 100,
            VoiceParameter.lv1Strength to 120,
            VoiceParameter.lv1Density to 100,
            VoiceParameter.minArea to 10000,
            VoiceParameter.lv2Strength to 120,
            VoiceParameter.lv3Strength to 100,
            VoiceParameter.actionDuration to 2000,
            VoiceParameter.rejectDelay to 1500
        )

        ranges.forEach { (parameter, upper) ->
            assertParsed(parameter.wireName + " 0", parameter, ParameterValue.IntValue(0))
            assertParsed(parameter.wireName + " " + upper, parameter, ParameterValue.IntValue(upper))
            assertRejected(parameter.wireName + " -1")
            assertRejected(parameter.wireName + " " + (upper + 1))
        }
    }

    @Test
    fun rejectsDecimalValuesWithoutProducingACommand() {
        val result = DeterministicVoiceParser.parse("lv1Strength 1.5")
        assertFalse(result is DirectParseResult.Parsed)
    }

    @Test
    fun rejectsTwoDifferentParameterChangesEvenWithOneValue() {
        val result = DeterministicVoiceParser.parse("lv1Strength and minArea 12")
        assertTrue(result is DirectParseResult.Rejected)
    }

    @Test
    fun doesNotParseUnknownPressureParameter() {
        val result = DeterministicVoiceParser.parse("\u538b\u529b 12")
        assertFalse(result is DirectParseResult.Parsed)
    }

    @Test
    fun asksModelForBlankOrValueLessText() {
        assertEquals(DirectParseResult.NeedsModel, DeterministicVoiceParser.parse(""))
        assertEquals(
            DirectParseResult.NeedsModel,
            DeterministicVoiceParser.parse("what is lv1 sensitivity")
        )
    }

    @Test
    fun rejectsDeviceAliasPrefixesAndUnsupportedExplicitDevices() {
        assertRejected("machine_10 enhancedInference on")
        assertRejected("machine_4 lv1Strength 12")
    }

    @Test
    fun rejectsChineseDeviceSuperstringsAndUnsupportedExplicitForms() {
        assertRejected("11\u53f7\u673a enhancedInference on")
        assertRejected("4\u53f7\u673a enhancedInference on")
        assertRejected("\u4e00\u53f7\u673a\u5668 enhancedInference on")
    }

    @Test
    fun rejectsEveryMalformedDeviceLikeStringInsteadOfDefaulting() {
        val malformedDevices = listOf(
            "machine_",
            "machine-",
            "machine.",
            "machine-4",
            "machine_1.0",
            "machine4",
            "machine_0",
            "machine_01",
            "\u56db\u53f7\u673a",
            "4\u53f7\u673a",
            "11\u53f7\u673a",
            "\u4e00\u53f7\u673a-4",
            "\u4e00\u53f7\u673a_4"
        )

        malformedDevices.forEach { device ->
            assertRejected("$device enhancedInference on")
        }
    }

    @Test
    fun rejectsEveryCanonicalAndChineseAliasWithDeviceTokenSuffixes() {
        val aliases = listOf(
            "machine_1", "machine_2", "machine_3",
            "1\u53f7\u673a", "2\u53f7\u673a", "3\u53f7\u673a",
            "\u4e00\u53f7\u673a", "\u4e8c\u53f7\u673a", "\u4e09\u53f7\u673a"
        )
        val invalidSuffixes = listOf("0", "x", "_", "-")

        aliases.forEach { alias ->
            invalidSuffixes.forEach { suffix ->
                assertRejected("$alias$suffix enhancedInference on")
            }
        }
    }

    @Test
    fun rejectsEveryCanonicalAndChineseAliasWithUnsupportedSymbolSuffixes() {
        val aliases = listOf(
            "machine_1", "machine_2", "machine_3",
            "1\u53f7\u673a", "2\u53f7\u673a", "3\u53f7\u673a",
            "\u4e00\u53f7\u673a", "\u4e8c\u53f7\u673a", "\u4e09\u53f7\u673a"
        )
        val invalidSuffixes = listOf("/4", "\\4", ":4", "@4", "#4", "+4", "=4")

        aliases.forEach { alias ->
            invalidSuffixes.forEach { suffix ->
                assertRejected("$alias$suffix enhancedInference on")
            }
        }
    }

    @Test
    fun rejectsColonAndPeriodNumericContinuationsAfterEveryExactAlias() {
        val aliases = listOf(
            "machine_1", "machine_2", "machine_3",
            "1\u53f7\u673a", "2\u53f7\u673a", "3\u53f7\u673a",
            "\u4e00\u53f7\u673a", "\u4e8c\u53f7\u673a", "\u4e09\u53f7\u673a"
        )

        aliases.forEach { alias ->
            listOf(":4", ".4").forEach { suffix ->
                assertRejected("$alias$suffix enhancedInference on")
            }
        }
    }

    @Test
    fun acceptsColonAndAsciiPeriodOnlyAsTerminalSentencePunctuation() {
        val aliases = listOf(
            "machine_1", "machine_2", "machine_3",
            "1\u53f7\u673a", "2\u53f7\u673a", "3\u53f7\u673a",
            "\u4e00\u53f7\u673a", "\u4e8c\u53f7\u673a", "\u4e09\u53f7\u673a"
        )

        aliases.forEach { alias ->
            listOf(":", ".").forEach { punctuation ->
                assertParsed(
                    transcript = "$alias$punctuation enhancedInference on",
                    device = when {
                        alias.endsWith("1") || alias.endsWith("1\u53f7\u673a") || alias.endsWith("\u4e00\u53f7\u673a") -> MachineDevice.machine_1
                        alias.endsWith("2") || alias.endsWith("2\u53f7\u673a") || alias.endsWith("\u4e8c\u53f7\u673a") -> MachineDevice.machine_2
                        else -> MachineDevice.machine_3
                    },
                    parameter = VoiceParameter.enhancedInference,
                    value = ParameterValue.BooleanValue(true)
                )
            }
        }
    }

    @Test
    fun detectsMachineMarkersAfterChinesePrefixes() {
        assertParsed(
            transcript = "\u8bf7machine_2 enhancedInference on",
            device = MachineDevice.machine_2,
            parameter = VoiceParameter.enhancedInference,
            value = ParameterValue.BooleanValue(true)
        )
        assertRejected("\u8bf7machine_4 enhancedInference on")
    }

    @Test
    fun validatesMachineMarkerPrefixesAcrossAsciiCjkAndUnicodeLetters() {
        listOf(
            "submachine_2",
            "xmachine_2",
            "\u041f\u0440\u0438machine_2"
        ).forEach { prefix ->
            assertRejected("$prefix enhancedInference on")
        }

        assertParsed(
            transcript = ",machine_2 enhancedInference on",
            device = MachineDevice.machine_2,
            parameter = VoiceParameter.enhancedInference,
            value = ParameterValue.BooleanValue(true)
        )
        assertParsed(
            transcript = "\u8bf7machine_2 enhancedInference on",
            device = MachineDevice.machine_2,
            parameter = VoiceParameter.enhancedInference,
            value = ParameterValue.BooleanValue(true)
        )
    }

    @Test
    fun rejectsMachineMarkersAfterSymbolPrefixes() {
        listOf(
            "sub-machine_2",
            "sub/machine_2",
            "sub\\machine_2",
            "sub@machine_2",
            "sub#machine_2",
            "sub+machine_2",
            "sub:machine_2"
        ).forEach { transcript ->
            assertRejected("$transcript enhancedInference on")
        }
    }

    @Test
    fun acceptsMachineMarkersOnlyAfterAllowedPrefixes() {
        listOf(
            "machine_2 enhancedInference on",
            " machine_2 enhancedInference on",
            ",machine_2 enhancedInference on",
            "\uff0cmachine_2 enhancedInference on",
            "\u3002machine_2 enhancedInference on",
            "\u8bf7machine_2 enhancedInference on"
        ).forEach { transcript ->
            assertParsed(
                transcript = transcript,
                device = MachineDevice.machine_2,
                parameter = VoiceParameter.enhancedInference,
                value = ParameterValue.BooleanValue(true)
            )
        }
    }

    @Test
    fun acceptsNormalizedSentencePunctuationAfterExactDeviceAliases() {
        val punctuation = listOf(",", "\uFF0C", "\u3002", "\uFF01", "\uFF1F", "!", "?")
        punctuation.forEach { mark ->
            assertParsed(
                transcript = "machine_1$mark enhancedInference on",
                device = MachineDevice.machine_1,
                parameter = VoiceParameter.enhancedInference,
                value = ParameterValue.BooleanValue(true)
            )
            assertParsed(
                transcript = "\u4e00\u53f7\u673a$mark enhancedInference on",
                device = MachineDevice.machine_1,
                parameter = VoiceParameter.enhancedInference,
                value = ParameterValue.BooleanValue(true)
            )
        }
    }

    @Test
    fun parsesEveryExactSupportedCanonicalAndChineseDeviceAlias() {
        val supportedDevices = listOf(
            "machine_1" to MachineDevice.machine_1,
            "machine_2" to MachineDevice.machine_2,
            "machine_3" to MachineDevice.machine_3,
            "1\u53f7\u673a" to MachineDevice.machine_1,
            "2\u53f7\u673a" to MachineDevice.machine_2,
            "3\u53f7\u673a" to MachineDevice.machine_3,
            "\u4e00\u53f7\u673a" to MachineDevice.machine_1,
            "\u4e8c\u53f7\u673a" to MachineDevice.machine_2,
            "\u4e09\u53f7\u673a" to MachineDevice.machine_3
        )

        supportedDevices.forEach { (device, expectedDevice) ->
            assertParsed(
                transcript = "$device enhancedInference on",
                device = expectedDevice,
                parameter = VoiceParameter.enhancedInference,
                value = ParameterValue.BooleanValue(true)
            )
        }
    }

    @Test
    fun acceptsSafeChineseGrammarAroundExactChineseDeviceAliases() {
        assertParsed(
            transcript = "\u628a\u4e00\u53f7\u673a enhancedInference on",
            device = MachineDevice.machine_1,
            parameter = VoiceParameter.enhancedInference,
            value = ParameterValue.BooleanValue(true)
        )
        assertParsed(
            transcript = "\u4e8c\u53f7\u673a\u7684 enhancedInference on",
            device = MachineDevice.machine_2,
            parameter = VoiceParameter.enhancedInference,
            value = ParameterValue.BooleanValue(true)
        )
    }

    @Test
    fun requiresChineseGrammarContinuationToLeadToAControlledParameterAlias() {
        assertParsed(
            transcript = "\u4e00\u53f7\u673a\u7684 lv1Strength 12",
            device = MachineDevice.machine_1,
            parameter = VoiceParameter.lv1Strength,
            value = ParameterValue.IntValue(12)
        )
        assertRejected("\u4e00\u53f7\u673a\u7684 4 enhancedInference on")
        assertRejected("\u4e00\u53f7\u673a\u7684 x enhancedInference on")
    }

    @Test
    fun rejectsMultipleOrConflictingDeviceMarkers() {
        assertRejected("machine_1 and machine_2 enhancedInference on")
        assertRejected("\u4e00\u53f7\u673a\u548c\u4e8c\u53f7\u673a enhancedInference on")
        assertRejected("machine_1\u548c\u4e00\u53f7\u673a enhancedInference on")
    }

    @Test
    fun defaultsOnlyWhenNoDeviceMarkerExists() {
        assertParsed(
            transcript = "enhancedInference on",
            parameter = VoiceParameter.enhancedInference,
            value = ParameterValue.BooleanValue(true)
        )
    }

    @Test
    fun doesNotRecognizeParameterAliasInsideAWord() {
        val result = DeterministicVoiceParser.parse("contemplate 400mmBase.engine")
        assertFalse(result is DirectParseResult.Parsed)
    }

    @Test
    fun rejectsChineseParameterAliasesInsideLongerTokens() {
        assertFalse(
            DeterministicVoiceParser.parse("\u589e\u5f3a\u63a8\u74062 on") is DirectParseResult.Parsed
        )
        assertFalse(
            DeterministicVoiceParser.parse("\u4e00\u7ea7\u5f3a\u5ea6\u5668 12") is DirectParseResult.Parsed
        )
    }

    @Test
    fun requiresExactTemplateTokenWithARealTemplateAlias() {
        assertRejected("template 400mmBase.engineX")
        assertFalse(DeterministicVoiceParser.parse("templateX 400mmBase.engine") is DirectParseResult.Parsed)
        assertRejected("template x400mmBase.engine")
    }

    @Test
    fun rejectsEveryDecimalShapeBeforeIntegerExtraction() {
        listOf(".5", "1.", "1.0", "-1.5", "+1.5").forEach { value ->
            assertRejected("lv1Strength $value")
        }
    }

    @Test
    fun doesNotTreatChineseNegationAsBooleanTrue() {
        assertRejected("enhancedInference \u672a\u5f00\u542f")
        assertRejected("lv1AreaMask \u672a\u6253\u5f00")
    }

    @Test
    fun rejectsSeparatedEnglishAndChineseNegationBeforeBooleanValues() {
        listOf(
            "do not 打开 强化推理",
            "never 打开 强化推理",
            "不 打开 强化推理",
            "不要 关闭 强化推理"
        ).forEach(::assertRejected)
    }

    @Test
    fun rejectsConflictingBooleanTokens() {
        assertRejected("enhancedInference on off")
        assertRejected("lv1AreaMask \u5f00\u542f \u5173\u95ed")
        assertRejected("enhancedInference \u5f00\u542f false")
    }

    @Test
    fun rejectsChineseBooleanTokensAdjacentToUnicodeOrAsciiWordCharacters() {
        listOf(
            "x\u5f00\u542f",
            "\u5f00\u542f2",
            "a\u5173\u95ed",
            "\u5173\u95edx"
        ).forEach { value ->
            assertRejected("enhancedInference $value")
        }
    }

    @Test
    fun rejectsTwoOccurrencesOfTheSameParameterAlias() {
        assertTrue(
            DeterministicVoiceParser.parse("lv1Strength 12 then lv1Strength 13") is DirectParseResult.Rejected
        )
    }

    @Test
    fun rejectsTwoDifferentAliasesForTheSameParameter() {
        assertTrue(
            DeterministicVoiceParser.parse("lv1Strength and Lv1 strength 12") is DirectParseResult.Rejected
        )
    }

    private fun assertParsed(
        transcript: String,
        parameter: VoiceParameter,
        value: ParameterValue,
        device: MachineDevice = MachineDevice.machine_1
    ) {
        val result = DeterministicVoiceParser.parse(transcript)
        assertEquals(DirectParseResult.Parsed(SetParameterCommand("SET_PARAMETER", device, parameter, value)), result)
    }

    private fun assertRejected(transcript: String) {
        assertTrue(
            "Expected Rejected for: $transcript",
            DeterministicVoiceParser.parse(transcript) is DirectParseResult.Rejected
        )
    }
}
