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
    fun correctsAboveThresholdAsciiSuffixInsertion() {
        val result = corrector.correct("action durationx 700")

        assertEquals("动作持续 700", result.correctedText)
        assertEquals(0.94f, result.replacements.single().score, 0.0001f)
        assertEquals(CorrectionReason.EDIT_DISTANCE, result.replacements.single().reason)
    }

    @Test
    fun correctsObservedSensitivitySuffixOutsideNormalizer() {
        val result = corrector.correct("把一级灵敏度杜调到97")

        assertEquals("把Lv1灵敏度调到97", result.correctedText)
        assertEquals("一级灵敏度杜", result.replacements.single().source)
        assertTrue(result.replacements.single().score in 0.82f..<1.0f)
        assertTrue(result.replacements.single().reason != CorrectionReason.EXACT_ALIAS)
    }

    @Test
    fun correctsGenericCjkSuffixInsertionAtTheAcceptanceThreshold() {
        val result = correctorWithEntries(
            lexeme("target", "天地玄黄")
        ).correct("天地玄黄宇 7")

        assertEquals("target 7", result.correctedText)
        assertEquals("天地玄黄宇", result.replacements.single().source)
        assertEquals(0.82f, result.replacements.single().score, 0.0001f)
    }

    @Test
    fun cjkSuffixCorrectionDoesNotConsumeCommandContextNegationOrAliasPrefixes() {
        val stableLexeme = CorrectionLexeme(
            canonical = "wire",
            aliases = setOf("甲乙丙丁"),
            category = CorrectionCategory.PARAMETER,
            preferredReplacement = "甲乙丙丁"
        )
        val stableCorrector = correctorWithEntries(stableLexeme)

        listOf("甲乙丙丁调到7", "不甲乙丙丁 7", "戊甲乙丙丁 7").forEach { source ->
            val result = stableCorrector.correct(source)

            assertEquals(source, result.correctedText)
            assertTrue(result.replacements.toString(), result.replacements.isEmpty())
        }
    }

    @Test
    fun unrelatedLongerFuzzyMatchDoesNotSuppressAnExactAliasPrefix() {
        val source = "甲乙丙"
        val unrelatedAlias = "甲戊丙"
        val encoder = PinyinEncoder { text ->
            when (text) {
                source, unrelatedAlias -> "same"
                else -> TestPinyinEncoder.encode(text)
            }
        }
        val result = correctorWithEntries(
            lexeme("short", "甲乙"),
            lexeme("unrelated", unrelatedAlias),
            pinyinEncoder = encoder
        ).correct("$source 7")

        assertEquals("short丙 7", result.correctedText)
        assertEquals(listOf("甲乙"), result.replacements.map { it.source })
    }

    @Test
    fun repairsRepeatedPhraseSuffixesLexically() {
        val result = corrector.correct("关闭强化推理推理")

        assertEquals("关闭 强化推理", result.correctedText)
        assertEquals(CorrectionReason.REPETITION, result.replacements.single().reason)
    }

    @Test
    fun repetitionUsesTheWeightedFormulaInsteadOfPerfectScore() {
        val result = corrector.correct("强化推理推理")

        assertEquals("强化推理", result.correctedText)
        assertEquals(0.9f, result.replacements.single().score, 0.0001f)
        assertEquals(0.9f, result.confidence, 0.0001f)
        assertEquals(CorrectionReason.REPETITION, result.replacements.single().reason)
    }

    @Test
    fun correctsTripleDeviceRepetitionAsOneAtomicSpan() {
        val result = corrector.correct("二二二号机")

        assertEquals("machine_2", result.correctedText)
        assertEquals(1, result.replacements.size)
        assertEquals("二二二号机", result.replacements.single().source)
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
    fun representedDifferenceJustBelowPointZeroEightIsAmbiguous() {
        val source = "aaaaaaaaaaaaaa"
        val nearAlias = "aaaaaaaaaaaaab"
        val representedDifference = 1.0 - (0.55 * 0.9 + 0.35 * (13.0 / 14.0) + 0.10)
        val encoder = PinyinEncoder { text ->
            when (text) {
                source -> "aaaaaaaaaa"
                nearAlias -> "aaaaaaaaab"
                else -> TestPinyinEncoder.encode(text)
            }
        }
        val result = correctorWithEntries(
            lexeme("alpha", source),
            lexeme("beta", nearAlias),
            pinyinEncoder = encoder
        ).correct("$source 7")

        assertEquals(0.07999999999999996, representedDifference, 0.0)
        assertEquals("alpha 7", result.correctedText)
        assertTrue(result.ambiguous)
        assertEquals(1, result.replacements.size)
    }

    @Test
    fun representedDifferenceAbovePointZeroEightIsNotAmbiguous() {
        val source = "aaaaaaaaaaaaa"
        val nearAlias = "aaaaaaaaaaaab"
        val representedDifference = 1.0 - (0.55 * 0.9 + 0.35 * (12.0 / 13.0) + 0.10)
        val encoder = PinyinEncoder { text ->
            when (text) {
                source -> "aaaaaaaaaa"
                nearAlias -> "aaaaaaaaab"
                else -> TestPinyinEncoder.encode(text)
            }
        }
        val result = correctorWithEntries(
            lexeme("alpha", source),
            lexeme("beta", nearAlias),
            pinyinEncoder = encoder
        ).correct("$source 7")

        assertEquals(0.08192307692307688, representedDifference, 0.0)
        assertEquals("alpha 7", result.correctedText)
        assertFalse(result.ambiguous)
        assertEquals(1, result.replacements.size)
    }

    @Test
    fun rejectsExactAsciiAliasInsideALargerToken() {
        val result = corrector.correct("xtemplate 400")

        assertEquals("xtemplate 400", result.correctedText)
        assertTrue(result.replacements.isEmpty())
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
    fun globalSelectionPrefersFewerReplacementsAfterScoreAndCoverageTie() {
        val source = CharArray(112) { offset -> (0x7000 + offset).toChar() }.also { characters ->
            (1 until 7).forEach { boundary -> characters[boundary * 16] = '的' }
        }.concatToString()
        val sourceChunks = (0 until 8).map { index -> source.substring(index * 14, (index + 1) * 14) }
        val exactBoundaries = (0..7).map { it * 16 }
        val exactLexemes = exactBoundaries.zipWithNext().mapIndexed { index, (start, end) ->
            lexeme("A${index + 1}", source.substring(start, end))
        }
        val fuzzyAliases = sourceChunks.mapIndexed { index, chunk ->
            chunk.toCharArray().also { characters ->
                characters[1] = (0x8000 + index).toChar()
            }.concatToString()
        }
        val pinyinByText = buildMap {
            sourceChunks.zip(fuzzyAliases).forEach { (chunk, alias) ->
                put(chunk, "aaaaaa")
                put(alias, "aaaaaa")
            }
        }
        val encoder = PinyinEncoder { text -> pinyinByText[text] ?: TestPinyinEncoder.encode(text) }
        val fuzzyLexemes = fuzzyAliases.mapIndexed { index, alias -> lexeme("B${index + 1}", alias) }
        val result = FuzzyTranscriptCorrector(
            dictionary = exactLexemes + fuzzyLexemes,
            pinyinEncoder = encoder
        ).correct(source)

        assertEquals(result.replacements.toString(), "A1A2A3A4A5A6A7", result.correctedText)
        assertEquals(7, result.replacements.size)
    }

    @Test
    fun globalSelectionUsesTinyHigherDoubleTotalBeforeTieBreakers() {
        val sourceChunks = (0 until 6).map { chunkIndex ->
            buildString {
                append('一')
                repeat(13) { offset -> append((0x4E10 + chunkIndex * 13 + offset).toChar()) }
            }
        }
        val source = sourceChunks.joinToString("")
        val exactBoundaries = listOf(0, 17, 34, 51, 68, source.length)
        val exactLexemes = exactBoundaries.zipWithNext().mapIndexed { index, (start, end) ->
            lexeme("A${index + 1}", source.substring(start, end))
        }
        val fuzzyAliases = sourceChunks.mapIndexed { index, chunk ->
            chunk.toCharArray().also { characters ->
                repeat(3) { offset -> characters[offset + 1] = (0x6000 + index * 3 + offset).toChar() }
            }.concatToString()
        }
        val pinyinByText = buildMap {
            sourceChunks.zip(fuzzyAliases).forEach { (chunk, alias) ->
                put(chunk, "aaaaaa")
                put(alias, "aaaaab")
            }
        }
        val encoder = PinyinEncoder { text -> pinyinByText[text] ?: TestPinyinEncoder.encode(text) }
        val fuzzyLexemes = fuzzyAliases.mapIndexed { index, alias -> lexeme("B${index + 1}", alias) }
        val result = FuzzyTranscriptCorrector(
            dictionary = exactLexemes + fuzzyLexemes,
            pinyinEncoder = encoder
        ).correct(source)

        assertEquals("B1B2B3B4B5B6", result.correctedText)
        assertEquals(6, result.replacements.size)
    }

    @Test
    fun globalSelectionUsesCanonicalOrderAsFinalTieBreaker() {
        val result = correctorWithEntries(
            lexeme("zeta", "甲乙"),
            lexeme("alpha", "乙丙")
        ).correct("甲乙丙")

        assertEquals("甲alpha", result.correctedText)
        assertEquals("alpha", result.replacements.single().replacement)
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

    private fun correctorWithEntries(
        vararg entries: CorrectionLexeme,
        pinyinEncoder: PinyinEncoder = TestPinyinEncoder
    ) = FuzzyTranscriptCorrector(
        dictionary = entries.toList(),
        pinyinEncoder = pinyinEncoder
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
