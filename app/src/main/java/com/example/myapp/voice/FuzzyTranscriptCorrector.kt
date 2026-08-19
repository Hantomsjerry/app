package com.example.myapp.voice

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class CorrectionReplacement(
    val start: Int,
    val endExclusive: Int,
    val source: String,
    val replacement: String,
    val score: Float,
    val reason: CorrectionReason
)

enum class CorrectionReason {
    EXACT_ALIAS,
    PINYIN,
    EDIT_DISTANCE,
    REPETITION,
    CONTEXT
}

data class CorrectionResult(
    val rawText: String,
    val correctedText: String,
    val confidence: Float,
    val ambiguous: Boolean,
    val replacements: List<CorrectionReplacement>
)

class FuzzyTranscriptCorrector(
    private val dictionary: List<CorrectionLexeme>,
    private val pinyinEncoder: PinyinEncoder
) {
    fun correct(rawText: String): CorrectionResult {
        val formattedText = SpeechTranscriptNormalizer.normalizeFormatting(rawText)
        val candidatesByStart = generateCandidates(formattedText).groupBy { it.replacement.start }
        val selection = selectGlobally(formattedText.length, candidatesByStart)
        val replacements = selection.candidates.map(Candidate::replacement)
        val lexicallyCorrected = replacements.asReversed().fold(formattedText) { text, replacement ->
            text.replaceRange(replacement.start, replacement.endExclusive, replacement.replacement)
        }
        val correctedText = SpeechTranscriptNormalizer.normalizeDeterministic(lexicallyCorrected)

        return CorrectionResult(
            rawText = rawText,
            correctedText = correctedText,
            confidence = if (replacements.isEmpty()) {
                1.0f
            } else {
                replacements.map(CorrectionReplacement::score).average().toFloat()
            },
            ambiguous = selection.candidates.any(Candidate::ambiguous),
            replacements = replacements
        )
    }

    private fun generateCandidates(text: String): List<Candidate> {
        if (text.isEmpty()) return emptyList()

        val preparedLexemes = dictionary.map(::prepareLexeme)
        val candidates = mutableListOf<Candidate>()
        for (start in text.indices) {
            for (endExclusive in start + 1..text.length) {
                val source = text.substring(start, endExclusive)
                if (!hasMatchableEdges(source) ||
                    !hasSafeAsciiBoundaries(text, start, endExclusive) ||
                    !hasSafeRepetitionBoundaries(text, start, endExclusive)
                ) {
                    continue
                }
                val normalizedSource = normalizeForMatching(source)
                if (normalizedSource.isEmpty()) continue
                if (preparedLexemes.any { source == it.preferredReplacement }) continue
                if (preparedLexemes.any { lexeme ->
                        source.length < lexeme.preferredReplacement.length &&
                            lexeme.preferredReplacement.startsWith(source) &&
                            text.regionMatches(start, lexeme.preferredReplacement, 0, lexeme.preferredReplacement.length)
                    }
                ) {
                    continue
                }

                val spanCandidates = preparedLexemes.mapNotNull { lexeme ->
                    scoreSpan(text, start, endExclusive, source, normalizedSource, lexeme)
                }.sortedWith(
                    compareByDescending<ScoredReplacement> { it.preciseScore }
                        .thenBy { it.canonical }
                )
                val best = spanCandidates.firstOrNull() ?: continue
                val runnerUp = spanCandidates.firstOrNull { it.canonical != best.canonical }
                candidates += Candidate(
                    replacement = CorrectionReplacement(
                        start = start,
                        endExclusive = endExclusive,
                        source = source,
                        replacement = best.preferredReplacement,
                        score = best.preciseScore.toFloat(),
                        reason = best.reason
                    ),
                    ambiguous = runnerUp != null &&
                        best.preciseScore - runnerUp.preciseScore < AMBIGUITY_DELTA - SCORE_EPSILON,
                    canonical = best.canonical,
                    preciseScore = best.preciseScore
                )
            }
        }
        return candidates
    }

    private fun scoreSpan(
        text: String,
        start: Int,
        endExclusive: Int,
        source: String,
        normalizedSource: String,
        lexeme: PreparedLexeme
    ): ScoredReplacement? {
        if (source == lexeme.preferredReplacement) return null
        if (normalizedSource in lexeme.normalizedAliases) {
            return ScoredReplacement(
                canonical = lexeme.canonical,
                preferredReplacement = lexeme.preferredReplacement,
                preciseScore = 1.0,
                reason = CorrectionReason.EXACT_ALIAS
            )
        }
        val context = contextCompatibility(
            lexeme.category,
            text.substring(max(0, start - CONTEXT_RADIUS), minOf(text.length, endExclusive + CONTEXT_RADIUS))
        )
        val matchInputs = buildList {
            add(MatchInput(normalizedSource, pinyinEncoder.encode(source), repetition = false))
            repetitionReductions(normalizedSource).forEach { reduced ->
                add(MatchInput(reduced, pinyinEncoder.encode(reduced), repetition = true))
            }
        }
        val bestAlias = matchInputs.flatMap { input ->
            if (input.normalized !in lexeme.normalizedAliases &&
                hasUnsafeEmbeddedAlias(input.normalized, lexeme.aliases)
            ) {
                return@flatMap emptyList()
            }
            lexeme.aliases.mapNotNull { alias ->
                if (abs(alias.normalized.length - input.normalized.length) > MAX_LENGTH_DELTA) {
                    return@mapNotNull null
                }
                val pinyinSimilarity = normalizedSimilarity(input.pinyin, alias.pinyin)
                val editSimilarity = normalizedSimilarity(input.normalized, alias.normalized)
                val baseScore = PINYIN_WEIGHT * pinyinSimilarity + EDIT_WEIGHT * editSimilarity
                AliasScore(
                    preciseScore = baseScore + CONTEXT_WEIGHT * context,
                    baseScore = baseScore,
                    pinyinSimilarity = pinyinSimilarity,
                    editSimilarity = editSimilarity,
                    repetition = input.repetition
                )
            }
        }.maxWithOrNull(
            compareBy<AliasScore> { it.preciseScore }
                .thenBy { it.pinyinSimilarity }
                .thenBy { it.editSimilarity }
                .thenBy { it.repetition }
        ) ?: return null
        if (bestAlias.preciseScore < MIN_SCORE) return null

        val reason = when {
            bestAlias.repetition -> CorrectionReason.REPETITION
            bestAlias.baseScore < MIN_SCORE && context > 0.0 -> CorrectionReason.CONTEXT
            bestAlias.pinyinSimilarity > bestAlias.editSimilarity -> CorrectionReason.PINYIN
            else -> CorrectionReason.EDIT_DISTANCE
        }
        return ScoredReplacement(
            canonical = lexeme.canonical,
            preferredReplacement = lexeme.preferredReplacement,
            preciseScore = bestAlias.preciseScore,
            reason = reason
        )
    }

    private fun selectGlobally(
        textLength: Int,
        candidatesByStart: Map<Int, List<Candidate>>
    ): Selection {
        val bestFrom = arrayOfNulls<Selection>(textLength + 1)
        bestFrom[textLength] = Selection.EMPTY
        for (index in textLength - 1 downTo 0) {
            var best = checkNotNull(bestFrom[index + 1])
            candidatesByStart[index].orEmpty().forEach { candidate ->
                val suffix = checkNotNull(bestFrom[candidate.replacement.endExclusive])
                val proposed = Selection(
                    candidates = listOf(candidate) + suffix.candidates,
                    totalScore = candidate.preciseScore + suffix.totalScore,
                    coveredLength = candidate.replacement.endExclusive - candidate.replacement.start +
                        suffix.coveredLength
                )
                if (isPreferred(proposed, best)) best = proposed
            }
            bestFrom[index] = best
        }
        return checkNotNull(bestFrom[0])
    }

    private fun isPreferred(candidate: Selection, incumbent: Selection): Boolean {
        if (abs(candidate.totalScore - incumbent.totalScore) > SCORE_EPSILON) {
            return candidate.totalScore > incumbent.totalScore
        }
        if (candidate.coveredLength != incumbent.coveredLength) {
            return candidate.coveredLength > incumbent.coveredLength
        }
        if (candidate.candidates.size != incumbent.candidates.size) {
            return candidate.candidates.size < incumbent.candidates.size
        }
        return compareCanonicalOrder(candidate.candidates, incumbent.candidates) < 0
    }

    private fun compareCanonicalOrder(left: List<Candidate>, right: List<Candidate>): Int {
        left.indices.forEach { index ->
            val comparison = left[index].canonical.compareTo(right[index].canonical)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun prepareLexeme(lexeme: CorrectionLexeme): PreparedLexeme {
        val aliases = (lexeme.aliases + lexeme.canonical + lexeme.preferredReplacement).map { alias ->
            PreparedAlias(
                normalized = normalizeForMatching(alias),
                pinyin = pinyinEncoder.encode(alias)
            )
        }.distinctBy { it.normalized to it.pinyin }
        return PreparedLexeme(
            canonical = lexeme.canonical,
            preferredReplacement = lexeme.preferredReplacement,
            category = lexeme.category,
            aliases = aliases,
            normalizedAliases = aliases.mapTo(mutableSetOf(), PreparedAlias::normalized)
        )
    }

    private fun contextCompatibility(category: CorrectionCategory, surroundingText: String): Double {
        val normalized = surroundingText.lowercase(Locale.ROOT)
        val compatible = when (category) {
            CorrectionCategory.DEVICE -> normalized.any { it in "的把将请给对在" } ||
                DEVICE_CONTEXT.any(normalized::contains)
            CorrectionCategory.LEVEL -> LEVEL_CONTEXT.any(normalized::contains)
            CorrectionCategory.PARAMETER -> normalized.any(Char::isDigit) ||
                normalized.any { it in CHINESE_NUMBERS } ||
                PARAMETER_CONTEXT.any(normalized::contains)
            CorrectionCategory.ACTION -> ACTION_CONTEXT.any(normalized::contains)
            CorrectionCategory.BOOLEAN -> BOOLEAN_CONTEXT.any(normalized::contains)
            CorrectionCategory.TEMPLATE -> TEMPLATE_CONTEXT.any(normalized::contains)
        }
        return if (compatible) 1.0 else 0.0
    }

    private fun repetitionReductions(text: String): List<String> {
        val reductions = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        pending.add(text)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            for (segmentLength in 1..current.length / 2) {
                for (start in 0..current.length - segmentLength * 2) {
                    val segment = current.substring(start, start + segmentLength)
                    if (current.regionMatches(start + segmentLength, segment, 0, segmentLength)) {
                        val reduced = current.removeRange(start, start + segmentLength)
                        if (reductions.add(reduced)) pending.add(reduced)
                    }
                }
            }
        }
        reductions.remove(text)
        return reductions.toList()
    }

    private fun normalizedSimilarity(left: String, right: String): Double {
        if (left.isEmpty() && right.isEmpty()) return 1.0
        val denominator = max(left.length, right.length)
        if (denominator == 0) return 0.0
        return 1.0 - levenshtein(left, right).toDouble() / denominator
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftCharacter ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightCharacter ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftCharacter == rightCharacter) 0 else 1
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun normalizeForMatching(text: String): String = text
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun hasMatchableEdges(source: String): Boolean =
        source.first().isLetterOrDigit() && source.last().isLetterOrDigit()

    private fun hasSafeAsciiBoundaries(text: String, start: Int, endExclusive: Int): Boolean {
        val first = text[start]
        val last = text[endExclusive - 1]
        val unsafePrefix = first.isAsciiWordCharacter() && text.getOrNull(start - 1).isAsciiWordCharacter()
        val unsafeSuffix = last.isAsciiWordCharacter() && text.getOrNull(endExclusive).isAsciiWordCharacter()
        return !unsafePrefix && !unsafeSuffix
    }

    private fun hasSafeRepetitionBoundaries(text: String, start: Int, endExclusive: Int): Boolean {
        val startsInsideRepeatedRun = text[start].isCjkUnifiedIdeograph() && text.getOrNull(start - 1) == text[start]
        val endsInsideRepeatedRun = text[endExclusive - 1].isCjkUnifiedIdeograph() &&
            text.getOrNull(endExclusive) == text[endExclusive - 1]
        return !startsInsideRepeatedRun && !endsInsideRepeatedRun
    }

    private fun hasUnsafeEmbeddedAlias(source: String, aliases: List<PreparedAlias>): Boolean =
        aliases.any { alias ->
            val aliasStart = source.indexOf(alias.normalized)
            alias.normalized.isNotEmpty() && aliasStart >= 0 &&
                (aliasStart > 0 || !alias.normalized.all { it.isAsciiLetterOrDigit() })
        }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun Char.isCjkUnifiedIdeograph(): Boolean =
        code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF

    private fun Char?.isAsciiWordCharacter(): Boolean =
        this != null && (this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_')

    private data class PreparedLexeme(
        val canonical: String,
        val preferredReplacement: String,
        val category: CorrectionCategory,
        val aliases: List<PreparedAlias>,
        val normalizedAliases: Set<String>
    )

    private data class PreparedAlias(val normalized: String, val pinyin: String)

    private data class MatchInput(
        val normalized: String,
        val pinyin: String,
        val repetition: Boolean
    )

    private data class AliasScore(
        val preciseScore: Double,
        val baseScore: Double,
        val pinyinSimilarity: Double,
        val editSimilarity: Double,
        val repetition: Boolean
    )

    private data class ScoredReplacement(
        val canonical: String,
        val preferredReplacement: String,
        val preciseScore: Double,
        val reason: CorrectionReason
    )

    private data class Candidate(
        val replacement: CorrectionReplacement,
        val ambiguous: Boolean,
        val canonical: String,
        val preciseScore: Double
    )

    private data class Selection(
        val candidates: List<Candidate>,
        val totalScore: Double,
        val coveredLength: Int
    ) {
        companion object {
            val EMPTY = Selection(emptyList(), 0.0, 0)
        }
    }

    private companion object {
        const val PINYIN_WEIGHT = 0.55
        const val EDIT_WEIGHT = 0.35
        const val CONTEXT_WEIGHT = 0.10
        const val MIN_SCORE = 0.82
        const val AMBIGUITY_DELTA = 0.08
        const val SCORE_EPSILON = 1e-9
        const val MAX_LENGTH_DELTA = 2
        const val CONTEXT_RADIUS = 12
        const val CHINESE_NUMBERS = "零〇一二两三四五六七八九十百千万"

        val DEVICE_CONTEXT = listOf("machine", "号")
        val LEVEL_CONTEXT = listOf("强度", "灵敏", "敏感", "浓淡", "密度", "strength", "sensitivity", "density")
        val PARAMETER_CONTEXT = listOf("调", "设", "改", "变", "开启", "打开", "关闭", " on", " off", "true", "false")
        val ACTION_CONTEXT = listOf("设置", "调整", "修改", "change", "set")
        val BOOLEAN_CONTEXT = listOf("开启", "打开", "关闭", " on", " off", "true", "false")
        val TEMPLATE_CONTEXT = listOf("模板", "template", ".engine")
    }
}
