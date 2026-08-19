package com.example.myapp.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTranscriptNormalizerTest {
    @Test
    fun normalizesSpacedLvForms() {
        assertEquals(
            "把二号机的 Lv1 灵敏度调整到97",
            SpeechTranscriptNormalizer.normalize("把二号机的 l v 1 灵敏度调整到97")
        )
        assertEquals(
            "machine_3 Lv2 strength 80",
            SpeechTranscriptNormalizer.normalize("machine three L V TWO strength 80")
        )
        assertEquals(
            "Lv3 强度",
            SpeechTranscriptNormalizer.normalize("L   V   3 强度")
        )
    }

    @Test
    fun leavesLettersInsideWordsUntouched() {
        assertEquals(
            "level vivid love 1",
            SpeechTranscriptNormalizer.normalize("level vivid love 1")
        )
    }

    @Test
    fun blocksAsciiIdentifierAdjacencyIncludingUnderscores() {
        assertEquals(
            "part_l v 1_value",
            SpeechTranscriptNormalizer.normalize("part_l v 1_value")
        )
    }

    @Test
    fun acceptsCjkAdjacencyForMixedChineseEnglishSpeech() {
        assertEquals(
            "把二号机的Lv1灵敏度调整到32",
            SpeechTranscriptNormalizer.normalize("把二号机的l v 1灵敏度调整到32")
        )
    }

    @Test
    fun respectsPunctuationBoundaries() {
        assertEquals(
            "(Lv1), Lv2. Lv3!",
            SpeechTranscriptNormalizer.normalize("(l v 1), L V TWO. L   V   3!")
        )
    }

    @Test
    fun normalizesMultipleOccurrences() {
        assertEquals(
            "Lv1 and Lv2 then Lv3",
            SpeechTranscriptNormalizer.normalize("l v 1 and L V 2 then l v three")
        )
    }

    @Test
    fun collapsesStreamingDecoderCharacterRepetitions() {
        assertEquals(
            "把二号机的一级灵敏度杜调整到九十七",
            SpeechTranscriptNormalizer.normalize("把二二号机的一级灵敏敏敏敏度杜调整整到九十七")
        )
        assertEquals(
            "把一号机的一级强度调整到八十",
            SpeechTranscriptNormalizer.normalize("把一一号机的一级强强度调整到八十")
        )
    }

    @Test
    fun normalizesControlledSpokenEnglishDeviceAndLevelForms() {
        assertEquals(
            "machine_2 level 1 sensitivity thirty two",
            SpeechTranscriptNormalizer.normalize("machine two level one sensitivity thirty two")
        )
    }

    @Test
    fun stripsOnlyLeadingVivoWakePhraseNoise() {
        assertEquals(
            "Lv2强度改为80",
            SpeechTranscriptNormalizer.normalize("HI JOVI，l v 2强度改为80")
        )
        assertEquals("", SpeechTranscriptNormalizer.normalize("  hey jovi  "))
        assertEquals(
            "set HI JOVI to 1",
            SpeechTranscriptNormalizer.normalize("set HI JOVI to 1")
        )
    }

    @Test
    fun normalizesMixedChineseLvNumberFromOfflineAsr() {
        assertEquals(
            "Lv2强度改为八十",
            SpeechTranscriptNormalizer.normalize("LV二强度改为八十")
        )
    }

    @Test
    fun insertsBooleanBoundaryWithoutLexicalRepair() {
        assertEquals(
            "关闭 强化推理",
            SpeechTranscriptNormalizer.normalize("关闭强化推理")
        )
    }

    @Test
    fun collapsesAnExactlyRepeatedChineseNumberPhrase() {
        assertEquals(
            "将Lv2强度调到六十",
            SpeechTranscriptNormalizer.normalize("将Lv2强度调到六十六十")
        )
        assertEquals(
            "最小面积调到一二一二",
            SpeechTranscriptNormalizer.normalize("最小面积调到一二一二")
        )
    }
}
