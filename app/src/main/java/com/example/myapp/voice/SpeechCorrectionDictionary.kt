package com.example.myapp.voice

enum class CorrectionCategory {
    DEVICE,
    LEVEL,
    PARAMETER,
    ACTION,
    BOOLEAN,
    TEMPLATE
}

data class CorrectionLexeme(
    val canonical: String,
    val aliases: Set<String>,
    val category: CorrectionCategory
)

object SpeechCorrectionDictionary {
    val lexemes: List<CorrectionLexeme> = listOf(
        CorrectionLexeme("machine_1", setOf("machine_1", "1号机", "一号机", "machine one", "machine 1"), CorrectionCategory.DEVICE),
        CorrectionLexeme("machine_2", setOf("machine_2", "2号机", "二号机", "machine two", "machine 2"), CorrectionCategory.DEVICE),
        CorrectionLexeme("machine_3", setOf("machine_3", "3号机", "三号机", "machine three", "machine 3"), CorrectionCategory.DEVICE),
        CorrectionLexeme("Lv1", setOf("Lv1", "lv1", "l v 1", "lv一", "一级"), CorrectionCategory.LEVEL),
        CorrectionLexeme("Lv2", setOf("Lv2", "lv2", "l v 2", "lv二", "二级", "绿二", "吕二"), CorrectionCategory.LEVEL),
        CorrectionLexeme("Lv3", setOf("Lv3", "lv3", "l v 3", "lv三", "三级"), CorrectionCategory.LEVEL),
        CorrectionLexeme(
            "lv1Sensitivity",
            setOf(
                "lv1Sensitivity", "Lv1 sensitivity", "level 1 sensitivity",
                "Lv1灵敏度", "Lv1 灵敏度", "Lv1敏感度", "Lv1 敏感度", "一级灵敏度", "一级敏感度"
            ),
            CorrectionCategory.PARAMETER
        ),
        CorrectionLexeme(
            "lv1Strength",
            setOf("lv1Strength", "Lv1 strength", "level 1 strength", "Lv1强度", "Lv1 强度", "一级强度"),
            CorrectionCategory.PARAMETER
        ),
        CorrectionLexeme(
            "lv1Density",
            setOf(
                "lv1Density", "Lv1 density", "level 1 density", "Lv1浓淡", "Lv1 浓淡", "Lv1密度", "Lv1 密度", "一级密度"
            ),
            CorrectionCategory.PARAMETER
        ),
        CorrectionLexeme(
            "enhancedInference",
            setOf("enhancedInference", "enhanced inference", "强化推理", "增强推理", "增强推断"),
            CorrectionCategory.PARAMETER
        ),
        CorrectionLexeme(
            "lv1AreaMask",
            setOf("lv1AreaMask", "area mask", "区域掩码", "区域遮罩", "区域屏蔽"),
            CorrectionCategory.PARAMETER
        ),
        CorrectionLexeme("minArea", setOf("minArea", "min area", "minimum area", "最小面积"), CorrectionCategory.PARAMETER),
        CorrectionLexeme("template", setOf("template", "模板"), CorrectionCategory.PARAMETER),
        CorrectionLexeme(
            "lv2Strength",
            setOf("lv2Strength", "Lv2 strength", "level 2 strength", "Lv2强度", "Lv2 强度", "二级强度"),
            CorrectionCategory.PARAMETER
        ),
        CorrectionLexeme(
            "lv3Strength",
            setOf("lv3Strength", "Lv3 strength", "level 3 strength", "Lv3强度", "Lv3 强度", "三级强度"),
            CorrectionCategory.PARAMETER
        ),
        CorrectionLexeme(
            "actionDuration",
            setOf("actionDuration", "action duration", "动作持续时间", "动作时长"),
            CorrectionCategory.PARAMETER
        ),
        CorrectionLexeme(
            "rejectDelay",
            setOf(
                "rejectDelay", "reject delay", "剔除延时", "剔除延迟", "去除延时", "去除延迟", "剔除岩石", "拒绝延迟", "拒绝等待"
            ),
            CorrectionCategory.PARAMETER
        )
    )

    fun aliases(parameter: VoiceParameter): Set<String> =
        lexemes.single { it.canonical == parameter.wireName && it.category == CorrectionCategory.PARAMETER }.aliases

    fun aliases(device: MachineDevice): Set<String> =
        lexemes.single { it.canonical == device.wireName && it.category == CorrectionCategory.DEVICE }.aliases
}
