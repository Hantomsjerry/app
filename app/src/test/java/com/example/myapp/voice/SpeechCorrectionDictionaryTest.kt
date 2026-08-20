package com.example.myapp.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCorrectionDictionaryTest {
    @Test
    fun coversEachMachineDeviceAndVoiceParameterExactlyOnce() {
        val lexemes = SpeechCorrectionDictionary.lexemes

        assertEquals(
            lexemes.size,
            lexemes.map { it.canonical to it.category }.toSet().size
        )
        assertEquals(
            MachineDevice.values().map { it.wireName }.toSet(),
            lexemes.filter { it.category == CorrectionCategory.DEVICE }
                .map { it.canonical }
                .toSet()
        )
        assertEquals(
            VoiceParameter.values().map { it.wireName }.toSet(),
            lexemes.filter { it.category == CorrectionCategory.PARAMETER }
                .map { it.canonical }
                .toSet()
        )
    }

    @Test
    fun containsTheRequiredMachineLevelAndParameterAliases() {
        val aliasesByCanonical = SpeechCorrectionDictionary.lexemes
            .associate { it.canonical to it.aliases }
        val required = mapOf(
            "machine_1" to setOf("一号机", "1号机", "machine one", "machine 1"),
            "machine_2" to setOf("二号机", "2号机", "machine two", "machine 2"),
            "machine_3" to setOf("三号机", "3号机", "machine three", "machine 3"),
            "Lv1" to setOf("l v 1", "lv一", "一级"),
            "Lv2" to setOf("l v 2", "lv二", "二级", "绿二", "吕二"),
            "Lv3" to setOf("l v 3", "lv三", "三级"),
            "lv1Sensitivity" to setOf("Lv1灵敏度", "一级灵敏度"),
            "enhancedInference" to setOf("强化推理", "增强推理", "增强推断"),
            "rejectDelay" to setOf("剔除延时", "剔除延迟", "去除延时", "拒绝延迟", "剔除岩石")
        )

        required.forEach { (canonical, aliases) ->
            assertTrue(aliasesByCanonical.getValue(canonical).containsAll(aliases))
        }
        assertFalse(aliasesByCanonical.getValue("lv1Sensitivity").contains("一级灵敏度杜"))
    }

    @Test
    fun coversApprovedActionBooleanAndTemplateSemantics() {
        val semanticLexemes = SpeechCorrectionDictionary.lexemes
            .filter { it.category in setOf(
                CorrectionCategory.ACTION,
                CorrectionCategory.BOOLEAN,
                CorrectionCategory.TEMPLATE
            ) }
            .associate { (it.category to it.canonical) to it.preferredReplacement }

        assertEquals(
            mapOf(
                (CorrectionCategory.ACTION to "SET_PARAMETER") to "设置",
                (CorrectionCategory.BOOLEAN to "true") to "打开",
                (CorrectionCategory.BOOLEAN to "false") to "关闭",
                (CorrectionCategory.TEMPLATE to "400mmBase.engine") to "400mmBase.engine"
            ),
            semanticLexemes
        )
    }

    @Test
    fun containsRealisticActionBooleanAndTemplateAliases() {
        val aliasesBySemantic = SpeechCorrectionDictionary.lexemes
            .associate { (it.category to it.canonical) to it.aliases }

        assertTrue(
            aliasesBySemantic.getValue(CorrectionCategory.ACTION to "SET_PARAMETER")
                .containsAll(setOf("设置", "设为", "调整", "修改", "set", "change", "adjust"))
        )
        assertTrue(
            aliasesBySemantic.getValue(CorrectionCategory.BOOLEAN to "true")
                .containsAll(setOf("开启", "打开", "启用", "on", "true", "open", "enable"))
        )
        assertTrue(
            aliasesBySemantic.getValue(CorrectionCategory.BOOLEAN to "false")
                .containsAll(setOf("关闭", "关掉", "禁用", "off", "false", "close", "disable"))
        )
        assertTrue(
            aliasesBySemantic.getValue(CorrectionCategory.TEMPLATE to "400mmBase.engine")
                .containsAll(
                    setOf(
                        "400mmBase.engine",
                        "400 mm base engine",
                        "400mm base engine",
                        "400 millimeter base engine",
                        "四百毫米基础引擎"
                    )
                )
        )
    }

    @Test
    fun exposesDeterministicPreferredReplacementSurfaces() {
        val preferredByCanonical = SpeechCorrectionDictionary.lexemes
            .associate { it.canonical to it.preferredReplacement }

        assertEquals(
            mapOf(
                "lv1Sensitivity" to "Lv1灵敏度",
                "lv1Strength" to "Lv1强度",
                "lv1Density" to "Lv1浓淡",
                "enhancedInference" to "强化推理",
                "lv1AreaMask" to "Lv1面积屏蔽",
                "minArea" to "最小面积",
                "template" to "模板",
                "lv2Strength" to "Lv2强度",
                "lv3Strength" to "Lv3强度",
                "actionDuration" to "动作持续",
                "rejectDelay" to "剔除延时"
            ),
            preferredByCanonical.filterKeys { canonical ->
                VoiceParameter.values().any { it.wireName == canonical }
            }
        )
        listOf("machine_1", "machine_2", "machine_3", "Lv1", "Lv2", "Lv3").forEach { canonical ->
            assertEquals(canonical, preferredByCanonical.getValue(canonical))
        }
        SpeechCorrectionDictionary.lexemes
            .filter { it.category == CorrectionCategory.PARAMETER }
            .forEach { lexeme ->
                assertTrue(lexeme.aliases.contains(lexeme.preferredReplacement))
            }
    }

    @Test
    fun accessorsReturnTheAliasesForTheirWireNames() {
        assertTrue(SpeechCorrectionDictionary.aliases(MachineDevice.machine_2).contains("machine two"))
        assertTrue(SpeechCorrectionDictionary.aliases(VoiceParameter.rejectDelay).contains("拒绝延迟"))
    }
}
