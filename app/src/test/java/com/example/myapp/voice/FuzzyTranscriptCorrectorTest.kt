package com.example.myapp.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTranscriptCorrectorTest {
    private val corrector = correctorWithEntries(*SpeechCorrectionDictionary.lexemes.toTypedArray())

    @Test
    fun correctsObservedLv2AndRejectDelayErrorsAcrossSentence() {
        val result = corrector.correct("把二号几的绿二强度调到60并把剔除岩石改成700")

        assertEquals("把machine_2的Lv2强度调到60并把剔除延时改成700", result.correctedText)
        assertFalse(result.ambiguous)
        assertTrue(result.replacements.size >= 3)
        assertTrue(result.replacements.any { it.reason == CorrectionReason.EXACT_ALIAS })
        assertTrue(result.replacements.any { it.reason == CorrectionReason.CONTEXT })
    }

    @Test
    fun usesPinyinForAConfusableParameterCharacter() {
        val result = corrector.correct("动作持序时间调到60")

        assertEquals("动作持续调到60", result.correctedText)
        assertEquals(1, result.replacements.size)
        assertEquals("动作持序时间", result.replacements.single().source)
        assertEquals(CorrectionReason.PINYIN, result.replacements.single().reason)
    }

    @Test
    fun usesNormalizedEditDistanceForMixedEnglishErrors() {
        val result = corrector.correct("action duratiox 700")

        assertEquals("动作持续 700", result.correctedText)
        assertEquals(CorrectionReason.EDIT_DISTANCE, result.replacements.single().reason)
    }

    @Test
    fun repairsRepeatedPhraseSuffixesLexically() {
        val result = corrector.correct("关闭强化推理推理")

        assertEquals("关闭 强化推理", result.correctedText)
        assertEquals(CorrectionReason.REPETITION, result.replacements.single().reason)
    }

    @Test
    fun repairsMigratedEnhancedInferenceDecoderNoise() {
        val result = corrector.correct("关闭币避强化推理推理")

        assertEquals("关闭 强化推理", result.correctedText)
        assertEquals("币避强化推理推理", result.replacements.single().source)
        assertEquals(CorrectionReason.REPETITION, result.replacements.single().reason)
    }

    @Test
    fun correctsMixedChineseAndEnglishForms() {
        val result = corrector.correct("machine too的绿二强度改成80")

        assertEquals("machine_2的Lv2强度改成80", result.correctedText)
        assertFalse(result.ambiguous)
        assertEquals(2, result.replacements.size)
    }

    @Test
    fun noOpTextReturnsNormalizedTextAtFullConfidence() {
        val result = corrector.correct("HI JOVI，请保留 ordinary text")

        assertEquals("请保留 ordinary text", result.correctedText)
        assertEquals(1.0f, result.confidence)
        assertFalse(result.ambiguous)
        assertTrue(result.replacements.toString(), result.replacements.isEmpty())
    }

    @Test
    fun preferredParameterSurfaceDoesNotCreateANoOpReplacement() {
        val result = corrector.correct("把Lv1强度调到60")

        assertEquals("把Lv1强度调到60", result.correctedText)
        assertTrue(result.replacements.toString(), result.replacements.isEmpty())
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun correctionCountDoesNotCauseRejection() {
        val result = corrector.correct("一号几绿二剔除岩石增强推断动作持序")

        assertFalse(result.ambiguous)
        assertTrue(result.replacements.toString(), result.replacements.size > 3)
    }

    @Test
    fun equalPlausibleCandidatesAreAmbiguous() {
        val result = correctorWithEntries(
            lexeme("甲", "架"),
            lexeme("价", "架")
        ).correct("架")

        assertEquals("价", result.correctedText)
        assertTrue(result.ambiguous)
    }

    @Test
    fun globalSelectionPrefersLongerCoverageWhenScoresTie() {
        val result = correctorWithEntries(
            lexeme("short", "甲乙"),
            lexeme("whole", "甲乙丙丁")
        ).correct("甲乙丙丁")

        assertEquals("whole", result.correctedText)
        assertEquals(listOf("甲乙丙丁"), result.replacements.map { it.source })
    }

    @Test
    fun lexicalCorrectionRunsBeforeRepeatedNumberNormalization() {
        val result = corrector.correct("将绿二强度调到六十六十")

        assertEquals("将Lv2强度调到六十", result.correctedText)
        assertEquals("绿二", result.replacements.single().source)
    }

    @Test
    fun correctionDoesNotEraseChineseNegationContext() {
        val result = corrector.correct("不关闭强化推理")

        assertEquals("不关闭 强化推理", result.correctedText)
        assertTrue(DeterministicVoiceParser.parse(result.correctedText) is DirectParseResult.Rejected)
    }

    private fun correctorWithEntries(vararg entries: CorrectionLexeme) = FuzzyTranscriptCorrector(
        dictionary = entries.toList(),
        pinyinEncoder = TestPinyinEncoder
    )

    private fun lexeme(canonical: String, vararg aliases: String) = CorrectionLexeme(
        canonical = canonical,
        aliases = aliases.toSet(),
        category = CorrectionCategory.PARAMETER
    )

    private object TestPinyinEncoder : PinyinEncoder {
        private val syllables = mapOf(
            '一' to "yi", '二' to "er", '三' to "san", '号' to "hao",
            '机' to "ji", '几' to "ji", '绿' to "lv", '吕' to "lv",
            '剔' to "ti", '除' to "chu", '岩' to "yan", '石' to "shi",
            '延' to "yan", '时' to "shi", '强' to "qiang", '化' to "hua",
            '推' to "tui", '理' to "li", '增' to "zeng", '断' to "duan",
            '动' to "dong", '作' to "zuo", '持' to "chi", '序' to "xu",
            '续' to "xu", '间' to "jian", '甲' to "jia", '价' to "jia",
            '架' to "jia"
        )

        override fun encode(text: String): String = buildString {
            text.lowercase(Locale.ROOT).forEach { character ->
                val syllable = syllables[character]
                if (syllable != null) {
                    append(syllable)
                } else if (character.isLetterOrDigit()) {
                    append(character)
                }
            }
        }
    }
}
