package com.example.myapp.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHotwordsTest {
    @Test
    fun fixedHotwordsCoverDevicesLevelsAndAllVoiceParameters() {
        val phrases = hotwordsFile()
            .readLines(Charsets.UTF_8)
            .filter(String::isNotBlank)
            .map { line -> line.substringBeforeLast(" :").trim() }
            .toSet()
        val expectedPhrasesByParameter = mapOf(
            VoiceParameter.lv1Sensitivity to listOf("灵敏度", "敏感度", "SENSITIVITY"),
            VoiceParameter.lv1Strength to listOf("强度", "STRENGTH"),
            VoiceParameter.lv1Density to listOf("浓淡", "密度", "DENSITY"),
            VoiceParameter.enhancedInference to listOf("强化推理", "增强推理", "增强推断", "ENHANCED INFERENCE"),
            VoiceParameter.lv1AreaMask to listOf("面积屏蔽", "区域屏蔽", "区域遮罩", "区域掩码", "AREA MASK"),
            VoiceParameter.minArea to listOf("最小面积", "MINIMUM AREA"),
            VoiceParameter.template to listOf("模板", "TEMPLATE"),
            VoiceParameter.lv2Strength to listOf("强度", "STRENGTH"),
            VoiceParameter.lv3Strength to listOf("强度", "STRENGTH"),
            VoiceParameter.actionDuration to listOf("动作持续时间", "动作时长", "ACTION DURATION"),
            VoiceParameter.rejectDelay to listOf("剔除延时", "拒绝延迟", "拒绝等待", "REJECT DELAY")
        )

        assertTrue(setOf("一号机", "二号机", "三号机").all(phrases::contains))
        assertTrue(setOf("MACHINE ONE", "MACHINE TWO", "MACHINE THREE").all(phrases::contains))
        assertTrue(setOf("LV1", "LV2", "LV3", "L V 1", "L V 2", "L V 3").all(phrases::contains))
        assertTrue(setOf("LEVEL ONE", "LEVEL TWO", "LEVEL THREE").all(phrases::contains))
        assertTrue(phrases.contains("400 M M BASE ENGINE"))
        assertEquals(VoiceParameter.values().toSet(), expectedPhrasesByParameter.keys)
        expectedPhrasesByParameter.values.flatten().forEach { phrase ->
            assertTrue("Missing hotword: $phrase", phrases.contains(phrase))
        }
    }

    private fun hotwordsFile(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { it.parentFile }
            .map { directory ->
                File(
                    directory,
                    "app/src/main/assets/models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/hotwords.txt"
                )
            }
            .firstOrNull(File::isFile)
            ?: error("Unable to locate hotwords.txt from $userDirectory")
    }
}
