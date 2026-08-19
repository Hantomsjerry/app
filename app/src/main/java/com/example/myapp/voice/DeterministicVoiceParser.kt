package com.example.myapp.voice

import java.util.Locale

sealed interface DirectParseResult {
    data class Parsed(val command: SetParameterCommand) : DirectParseResult

    data object NeedsModel : DirectParseResult

    data class Rejected(val message: String) : DirectParseResult
}

/**
 * 不依赖模型的中英文参数指令解析器。
 * 只有“一个明确参数 + 一个明确值 + 至多一个合法设备”才直接生成命令；有歧义时拒绝，
 * 信息不足时交给 Qwen，而不是猜测用户意图。
 */
object DeterministicVoiceParser {
    private val numericPattern = Regex("""(?<![a-z])[-+]?\d+(?:\.\d+)?(?![a-z])""")
    private val chineseIntegerPattern = Regex("[零〇一二两三四五六七八九十百千万]+")
    private val englishIntegerPattern = Regex(
        """(?i)(?<![a-z])(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand)(?:[\s-]+(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand))*(?![a-z])"""
    )
    private val decimalPattern = Regex("""(?<![a-z0-9_])[-+]?(?:\d+\.\d*|\.\d+)(?![a-z0-9_])""")
    private val trueBooleanPattern =
        Regex("(?<![A-Za-z0-9_\\u4e00-\\u9fff])(on|true)(?![A-Za-z0-9_\\u4e00-\\u9fff])")
    private val falseBooleanPattern =
        Regex("(?<![A-Za-z0-9_\\u4e00-\\u9fff])(off|false)(?![A-Za-z0-9_\\u4e00-\\u9fff])")
    private val trueChineseBooleanPattern =
        Regex("(?<![\\p{L}\\p{N}_])(\\u5f00\\u542f|\\u6253\\u5f00)(?![\\p{L}\\p{N}_])")
    private val falseChineseBooleanPattern =
        Regex("(?<![\\p{L}\\p{N}_])\\u5173\\u95ed(?![\\p{L}\\p{N}_])")
    private val englishMachineMarkerPattern =
        Regex("""machine[^\s]*""")
    private val chineseDeviceMarkerPattern = Regex("\u53f7\u673a")
    private val safeChineseDeviceContext =
        setOf('\u7684', '\u628a', '\u5728', '\u8bf7', '\u5c06', '\u7ed9', '\u5bf9', '\u548c', '\u4e0e')
    private val chineseDeviceGrammarContinuation = setOf('\u7684', '\u628a', '\u5c06')
    private val safeChineseAliasPrefix = setOf('的', '把', '将', '请', '对', '给', '在')
    private val safeChineseAliasContinuation = setOf('调', '设', '改', '变')
    private val nonNumericChinesePrefixes = setOf('第')
    private val nonNumericChineseSuffixes = setOf('点', '些', '下', '号', '级')
    private val sentencePunctuation =
        setOf(',', '\uFF0C', '\u3002', '!', '?', '\uFF01', '\uFF1F')
    private val exactTemplatePattern = Regex("""(?<![a-zA-Z0-9_.])400mmBase\.engine(?![a-zA-Z0-9_.])""")
    private val lvPattern = Regex("""\blv\s*([123])\b""")
    private val punctuation = setOf(
        ',', ';', ':', '!', '?', '=', '(', ')', '[', ']', '{', '}', '<', '>', '"', '\'',
        '\uFF0C', '\u3002', '\uFF01', '\uFF1F', '\uFF1A', '\uFF1B', '\u3001',
        '\uFF08', '\uFF09', '\u3010', '\u3011', '\u300A', '\u300B', '\u201C', '\u201D',
        '\u2018', '\u2019'
    )

    fun parse(transcript: String): DirectParseResult {
        val normalized = normalizePunctuation(transcript)
        if (normalized.isBlank()) return DirectParseResult.NeedsModel

        val matchingText = matchingText(normalized)
        val deviceText = matchingText(normalizeDevicePunctuation(transcript))
        // 统计出现次数而不只是参数种类，重复提到同一参数也视为可能存在多次修改。
        val parameterOccurrences = findParameterOccurrences(matchingText)
        if (parameterOccurrences.isEmpty()) return DirectParseResult.NeedsModel
        if (parameterOccurrences.size > 1) {
            return DirectParseResult.Rejected("multiple parameter changes are not deterministic")
        }
        val parameter = parameterOccurrences.single().parameter

        val device = parseDevice(deviceText)
            ?: return DirectParseResult.Rejected("unknown device")
        return when (val value = parseValue(normalized, matchingText, parameter)) {
            is DetectedValue.Present -> validateAndBuild(device, parameter, value.value)
            DetectedValue.Missing -> DirectParseResult.NeedsModel
            is DetectedValue.Invalid -> DirectParseResult.Rejected(value.message)
        }
    }

    internal fun explicitParameter(transcript: String): VoiceParameter? {
        val matchingText = matchingText(normalizePunctuation(transcript))
        val occurrences = findParameterOccurrences(matchingText)
        return occurrences.singleOrNull()?.parameter
    }

    private fun parseDevice(matchingText: String): MachineDevice? {
        val englishMarkers = englishMachineMarkerPattern.findAll(matchingText).toList()
        val chineseMarkers = chineseDeviceMarkerPattern.findAll(matchingText).toList()
        // 完全没有设备标记时才使用默认设备；形似设备但非法的文本不能悄悄降级。
        if (englishMarkers.isEmpty() && chineseMarkers.isEmpty()) {
            return MachineDevice.machine_1
        }
        if (englishMarkers.size + chineseMarkers.size != 1) return null

        englishMarkers.singleOrNull()?.let { marker ->
            if (!isSafeEnglishMachinePrefix(matchingText, marker.range.first)) return null
            return parseEnglishDeviceToken(marker.value)
        }
        return parseChineseDevice(matchingText, chineseMarkers.single().range)
    }

    private fun isSafeEnglishMachinePrefix(text: String, start: Int): Boolean {
        val character = text.getOrNull(start - 1) ?: return true
        if (character.isWhitespace() || isCjkUnifiedIdeograph(character)) return true
        return false
    }

    private fun isCjkUnifiedIdeograph(character: Char): Boolean {
        val codePoint = character.code
        return codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF
    }

    private fun parseEnglishDeviceToken(token: String): MachineDevice? {
        MachineDevice.fromWireName(token)?.let { return it }
        val device = MachineDevice.values().firstOrNull { token.startsWith(it.wireName) }
            ?: return null
        val continuation = token.removePrefix(device.wireName)
        return if (continuation.isEmpty() ||
            (continuation.length == 1 && continuation.single() in setOf(':', '.')) ||
            continuation.all { it in safeChineseDeviceContext }
        ) {
            device
        } else {
            null
        }
    }

    private fun parseChineseDevice(matchingText: String, marker: IntRange): MachineDevice? {
        val prefixIndex = marker.first - 1
        if (prefixIndex < 0) return null

        val device = when (matchingText[prefixIndex]) {
            '1', '\u4e00' -> MachineDevice.machine_1
            '2', '\u4e8c' -> MachineDevice.machine_2
            '3', '\u4e09' -> MachineDevice.machine_3
            else -> null
        } ?: return null

        if (!isSafeChineseDeviceBoundary(matchingText, prefixIndex - 1) ||
            !isSafeChineseDeviceBoundary(matchingText, marker.last + 1, afterDeviceAlias = true)
        ) {
            return null
        }
        return device
    }

    private fun isSafeChineseDeviceBoundary(
        text: String,
        index: Int,
        afterDeviceAlias: Boolean = false
    ): Boolean {
        val character = text.getOrNull(index)
        if (character == null) return true
        if (character == ':' || character == '.') {
            val next = text.getOrNull(index + 1)
            return next == null || next.isWhitespace()
        }
        if (character.isWhitespace() || character in sentencePunctuation) return true
        if (afterDeviceAlias) {
            return character in chineseDeviceGrammarContinuation &&
                startsWithControlledParameterAlias(text, index + 1)
        }
        return character in safeChineseDeviceContext
    }

    private fun startsWithControlledParameterAlias(text: String, index: Int): Boolean {
        val remainder = text.substring(index).trimStart()
        return VoiceParameter.values().any { parameter ->
            normalizedAliases(parameter).any { alias ->
                remainder.startsWith(alias) &&
                    isBoundedAlias(remainder, 0, alias.length, alias)
            }
        }
    }

    private fun parseValue(
        normalized: String,
        matchingText: String,
        parameter: VoiceParameter
    ): DetectedValue {
        // 值类型由参数白名单决定，不进行数字、布尔或模板之间的隐式转换。
        return when {
            parameter.minimum != null -> parseInteger(matchingText, parameter)
            parameter == VoiceParameter.enhancedInference || parameter == VoiceParameter.lv1AreaMask ->
                parseBoolean(matchingText, parameter)
            parameter == VoiceParameter.template -> {
                val templateAliases = findAliasRanges(matchingText, normalizedAliases(parameter))
                val templateText = normalized.removeSuffix(".")
                val approvedTemplates = exactTemplatePattern.findAll(templateText).toList()
                val isAssociated = approvedTemplates.any { approved ->
                    templateAliases.any { alias -> alias.last < approved.range.first }
                }
                if (approvedTemplates.size == 1 && isAssociated) {
                    DetectedValue.Present(ParameterValue.StringValue("400mmBase.engine"))
                } else if (removeKnownWords(matchingText, parameter).isBlank()) {
                    DetectedValue.Missing
                } else {
                    DetectedValue.Invalid("template must be 400mmBase.engine")
                }
            }
            else -> DetectedValue.Invalid("unsupported parameter")
        }
    }

    private fun parseInteger(matchingText: String, parameter: VoiceParameter): DetectedValue {
        val source = removeKnownWords(matchingText, parameter)
        if (decimalPattern.containsMatchIn(source)) {
            return DetectedValue.Invalid("value must be an integer")
        }
        val arabicValues = numericPattern.findAll(source).map { it.value.toIntOrNull() }.toList()
        val chineseValues = chineseIntegerPattern.findAll(source)
            .filter { match ->
                source.getOrNull(match.range.first - 1) !in nonNumericChinesePrefixes &&
                    source.getOrNull(match.range.last + 1) !in nonNumericChineseSuffixes
            }
            .map { parseChineseInteger(it.value) }
            .toList()
        val englishValues = englishIntegerPattern.findAll(source)
            .map { parseEnglishInteger(it.value) }
            .toList()
        val values = arabicValues + chineseValues + englishValues
        if (values.isEmpty()) return DetectedValue.Missing
        if (values.size > 1) return DetectedValue.Invalid("integer value is ambiguous")
        val value = values.single()
            ?: return DetectedValue.Invalid("value must be an integer")
        return DetectedValue.Present(ParameterValue.IntValue(value))
    }

    private fun parseChineseInteger(text: String): Int? {
        val digits = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (text.none { it in setOf('十', '百', '千', '万') }) {
            return text.map { digits[it] ?: return null }
                .joinToString("")
                .toIntOrNull()
        }

        var total = 0
        var section = 0
        var digit = 0
        text.forEach { character ->
            when (character) {
                in digits -> digit = digits.getValue(character)
                '十', '百', '千' -> {
                    val unit = when (character) {
                        '十' -> 10
                        '百' -> 100
                        else -> 1_000
                    }
                    section += (if (digit == 0) 1 else digit) * unit
                    digit = 0
                }
                '万' -> {
                    total += (section + digit) * 10_000
                    section = 0
                    digit = 0
                }
                else -> return null
            }
        }
        return total + section + digit
    }

    private fun parseEnglishInteger(text: String): Int? {
        val values = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
            "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
            "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30,
            "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
            "eighty" to 80, "ninety" to 90
        )
        var total = 0
        var current = 0
        text.lowercase(Locale.ROOT).split(Regex("[\\s-]+"))
            .forEach { token ->
                when (token) {
                    "hundred" -> current = (if (current == 0) 1 else current) * 100
                    "thousand" -> {
                        total += (if (current == 0) 1 else current) * 1_000
                        current = 0
                    }
                    else -> current += values[token] ?: return null
                }
            }
        return total + current
    }

    private fun parseBoolean(matchingText: String, parameter: VoiceParameter): DetectedValue {
        val source = removeKnownWords(matchingText, parameter)
        val trueTokens = trueBooleanPattern.findAll(source).toList() +
            trueChineseBooleanPattern.findAll(source).toList()
        val falseTokens = falseBooleanPattern.findAll(source).toList() +
            falseChineseBooleanPattern.findAll(source).toList()
        if (trueTokens.isNotEmpty() && falseTokens.isNotEmpty()) {
            return DetectedValue.Invalid("boolean value is ambiguous")
        }
        if (trueTokens.isEmpty() && falseTokens.isEmpty()) {
            return if (source.isBlank()) DetectedValue.Missing
            else DetectedValue.Invalid("value must be boolean")
        }
        return DetectedValue.Present(ParameterValue.BooleanValue(trueTokens.isNotEmpty()))
    }

    private fun validateAndBuild(
        device: MachineDevice,
        parameter: VoiceParameter,
        value: ParameterValue
    ): DirectParseResult {
        val command = SetParameterCommand(
            action = "SET_PARAMETER",
            device = device,
            parameter = parameter,
            value = value
        )
        return try {
            VoiceCommandCodec.compact(command)
            DirectParseResult.Parsed(command)
        } catch (error: IllegalArgumentException) {
            DirectParseResult.Rejected("value rejected by Task 2 validation")
        }
    }

    private fun findAliasOccurrences(
        matchingText: String,
        parameter: VoiceParameter
    ): List<AliasOccurrence> =
        findAliasRanges(matchingText, normalizedAliases(parameter))
            .map { range -> AliasOccurrence(parameter, range) }

    private fun findParameterOccurrences(matchingText: String): List<AliasOccurrence> =
        VoiceParameter.values().flatMap { parameter ->
            findAliasOccurrences(matchingText, parameter)
        }

    private fun findAliasRanges(matchingText: String, aliases: List<String>): List<IntRange> =
        aliases.flatMap { alias -> findAliasRanges(matchingText, alias) }.distinct()

    private fun findAliasRanges(matchingText: String, alias: String): List<IntRange> {
        if (alias.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var searchFrom = 0
        while (searchFrom <= matchingText.length - alias.length) {
            val start = matchingText.indexOf(alias, searchFrom)
            if (start < 0) break
            val endExclusive = start + alias.length
            if (isBoundedAlias(matchingText, start, endExclusive, alias)) {
                ranges += start until endExclusive
            }
            searchFrom = start + 1
        }
        return ranges
    }

    private fun isBoundedAlias(
        matchingText: String,
        start: Int,
        endExclusive: Int,
        alias: String
    ): Boolean {
        val before = matchingText.getOrNull(start - 1)
        val after = matchingText.getOrNull(endExclusive)
        if (alias.any(::isCjkUnifiedIdeograph)) {
            val safeBefore = !isAliasWordCharacter(before) || before in safeChineseAliasPrefix
            val safeAfter = !isAliasWordCharacter(after) || after in safeChineseAliasContinuation
            return safeBefore && safeAfter
        }
        // 单词边界阻止别名嵌入更长标识符，从而误识别未知参数。
        return !isAliasWordCharacter(before) && !isAliasWordCharacter(after)
    }

    private fun isAliasWordCharacter(character: Char?): Boolean =
        character != null && (character.isLetterOrDigit() || character == '_')

    private fun removeKnownWords(matchingText: String, parameter: VoiceParameter): String {
        var source = matchingText
        normalizedAliases(parameter)
            .sortedByDescending { it.length }
            .forEach { source = source.replace(it, " ") }
        MachineDevice.values().flatMap(SpeechCorrectionDictionary::aliases)
            .flatMap { alias -> normalizedAliasForms(alias) }
            .sortedByDescending { it.length }
            .forEach { source = source.replace(it, " ") }
        return source.trim()
    }

    private fun normalizedAliases(parameter: VoiceParameter): List<String> =
        SpeechCorrectionDictionary.aliases(parameter).flatMap(::normalizedAliasForms).distinct()

    private fun normalizedAliases(device: MachineDevice): List<String> =
        SpeechCorrectionDictionary.aliases(device).flatMap(::normalizedAliasForms).distinct()

    private fun normalizedAliasForms(alias: String): List<String> {
        val normalized = matchingText(normalizePunctuation(alias))
        return listOf(normalized, normalized.replace(" ", ""))
    }

    private fun matchingText(normalized: String): String =
        normalized.lowercase(Locale.ROOT).replace(lvPattern, "lv$1")

    private fun normalizePunctuation(value: String): String =
        value.map { character -> if (character in punctuation) ' ' else character }
            .joinToString("")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun normalizeDevicePunctuation(value: String): String =
        value.map { character -> if (character in sentencePunctuation) ' ' else character }
            .joinToString("")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private data class AliasOccurrence(
        val parameter: VoiceParameter,
        val range: IntRange
    )

    private sealed interface DetectedValue {
        data class Present(val value: ParameterValue) : DetectedValue

        data object Missing : DetectedValue

        data class Invalid(val message: String) : DetectedValue
    }
}
