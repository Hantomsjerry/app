package com.example.myapp.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WavPcmParserTest {
    @Test
    fun parsesRiffSizeBeforeWaveAndFmt18Chunk() {
        val pcm = shortArrayOf(7, -9)

        assertArrayEquals(pcm, WavPcmParser.parse16kMonoPcm(wav(pcm, fmtSize = 18)))
    }

    private fun wav(pcm: ShortArray, fmtSize: Int): ByteArray {
        val dataSize = pcm.size * 2
        val riffSize = 4 + 8 + fmtSize + 8 + dataSize
        return ByteBuffer.allocate(8 + riffSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            putInt(riffSize)
            put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            putInt(fmtSize)
            putShort(1)
            putShort(1)
            putInt(16_000)
            putInt(32_000)
            putShort(2)
            putShort(16)
            repeat(fmtSize - 16) { put(0) }
            put("data".toByteArray(StandardCharsets.US_ASCII))
            putInt(dataSize)
            pcm.forEach(::putShort)
        }.array()
    }
}
