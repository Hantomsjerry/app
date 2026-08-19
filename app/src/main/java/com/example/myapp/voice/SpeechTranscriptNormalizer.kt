package com.example.myapp.voice

/** Converts the bounded spoken forms of Lv1/Lv2/Lv3 to parser-friendly tokens. */
object SpeechTranscriptNormalizer {
    private val spacedLv = Regex(
        pattern = """(?i)(?<![A-Za-z0-9_])l\s+v\s+(1|2|3|one|two|three)(?![A-Za-z0-9_])"""
    )
    private val mixedChineseLv = Regex(
        pattern = """(?i)(?<![A-Za-z0-9_])l\s*v\s*([一二三])(?![A-Za-z0-9_])"""
    )
    private val spokenMachine = Regex("""(?i)\bmachine\s+(one|two|three)\b""")
    private val spokenLevel = Regex("""(?i)\blevel\s+(one|two|three)\b""")
    private val chineseNumberRun = Regex("[零〇一二两三四五六七八九十百千万]+")
    private val enhancedInferenceBooleanBoundary = Regex(
        """(开启|打开|关闭)(?=强化推理|增强推理|增强推断)"""
    )
    private val leadingVivoWakePhrase = Regex(
        """(?i)^\s*(?:(?:hi|hey)\s+jovi(?![a-z0-9_])[\s,，。:：;；!?！？-]*)+"""
    )
    private val levels = mapOf(
        "one" to "1", "two" to "2", "three" to "3",
        "一" to "1", "二" to "2", "三" to "3"
    )

    fun normalize(text: String): String {
        val withoutWakePhrase = leadingVivoWakePhrase.replace(text, "")
        val collapsed = insertEnhancedInferenceBooleanBoundary(
            collapseRepeatedChineseNumberPhrases(
                repairObservedDomainDecoderNoise(
                    collapseRepeatedCjkCharacters(withoutWakePhrase)
                )
            )
        )
            .replace("灵敏度杜", "灵敏度")
        val machines = spokenMachine.replace(collapsed) { match ->
            "machine_${levels.getValue(match.groupValues[1].lowercase())}"
        }
        val levelWords = spokenLevel.replace(machines) { match ->
            "level ${levels.getValue(match.groupValues[1].lowercase())}"
        }
        val mixedChineseLevels = mixedChineseLv.replace(levelWords) { match ->
            "Lv${levels.getValue(match.groupValues[1])}"
        }
        return spacedLv.replace(mixedChineseLevels) { match ->
            val rawLevel = match.groupValues[1].lowercase()
            "Lv${levels[rawLevel] ?: rawLevel}"
        }
    }

    private fun collapseRepeatedCjkCharacters(text: String): String = buildString(text.length) {
        text.forEach { character ->
            if (lastOrNull() != character || !isCjkUnifiedIdeograph(character)) append(character)
        }
    }

    private fun repairObservedDomainDecoderNoise(text: String): String = text
        .replace("关闭币避强化推理", "关闭强化推理")
        .replace("强化推理推理", "强化推理")
        .replace("增强推理推理", "增强推理")
        .replace("增强推断推断", "增强推断")

    private fun insertEnhancedInferenceBooleanBoundary(text: String): String =
        enhancedInferenceBooleanBoundary.replace(text, "$1 ")

    private fun collapseRepeatedChineseNumberPhrases(text: String): String =
        chineseNumberRun.replace(text) { match ->
            val value = match.value
            val halfLength = value.length / 2
            val firstHalf = value.take(halfLength)
            if (value.length % 2 == 0 &&
                firstHalf == value.drop(halfLength) &&
                firstHalf.any { it in "十百千万" }
            ) {
                firstHalf
            } else {
                value
            }
        }

    private fun isCjkUnifiedIdeograph(character: Char): Boolean =
        character.code in 0x3400..0x4DBF ||
            character.code in 0x4E00..0x9FFF ||
            character.code in 0xF900..0xFAFF
}
