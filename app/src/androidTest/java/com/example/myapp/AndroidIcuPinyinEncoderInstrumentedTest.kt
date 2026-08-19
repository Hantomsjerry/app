package com.example.myapp

import com.example.myapp.voice.AndroidIcuPinyinEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidIcuPinyinEncoderInstrumentedTest {
    private val encoder = AndroidIcuPinyinEncoder()

    @Test
    fun normalizesMixedLatinAndChineseText() {
        assertEquals("lv2qiangdu", encoder.encode("Lv2强度"))
    }

    @Test
    fun normalizesEquivalentRejectDelayTranscripts() {
        assertEquals(encoder.encode("剔除延时"), encoder.encode("提出岩石"))
    }
}
