package com.example.myapp.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object WavPcmParser {
    fun parse16kMonoPcm(bytes: ByteArray): ShortArray {
        require(bytes.size >= 12) { "WAV fixture is missing the RIFF/WAVE header" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.readFourCc() == "RIFF") { "WAV fixture is not RIFF" }
        val riffSize = buffer.int
        require(riffSize >= 4 && riffSize <= buffer.remaining()) { "Invalid RIFF payload size" }
        val riffEnd = buffer.position() + riffSize
        require(buffer.readFourCc() == "WAVE") { "RIFF fixture is not WAVE" }

        var pcm: ByteArray? = null
        var hasFmtChunk = false
        while (buffer.position() + 8 <= riffEnd) {
            val chunkId = buffer.readFourCc()
            val chunkSize = buffer.int
            require(chunkSize >= 0 && buffer.position() + chunkSize <= riffEnd) { "Invalid $chunkId chunk length" }
            val chunkEnd = buffer.position() + chunkSize
            if (chunkId == "fmt ") {
                hasFmtChunk = true
                require(chunkSize >= 16) { "WAV fmt chunk is truncated" }
                require(buffer.short.toInt() == 1) { "WAV is not PCM" }
                require(buffer.short.toInt() == 1) { "WAV is not mono" }
                require(buffer.int == 16_000) { "WAV is not 16 kHz" }
                buffer.int
                buffer.short
                require(buffer.short.toInt() == 16) { "WAV is not 16-bit PCM" }
            } else if (chunkId == "data") {
                require(hasFmtChunk) { "WAV data precedes fmt chunk" }
                pcm = ByteArray(chunkSize).also(buffer::get)
            }
            val nextChunk = chunkEnd + chunkSize % 2
            require(nextChunk <= riffEnd) { "WAV chunk padding is truncated" }
            buffer.position(nextChunk)
        }

        require(hasFmtChunk) { "WAV fixture has no fmt chunk" }
        require(buffer.position() == riffEnd) { "WAV RIFF payload has trailing bytes" }
        val pcmBytes = requireNotNull(pcm) { "WAV fixture has no data chunk" }
        require(pcmBytes.size % 2 == 0) { "WAV PCM data must contain 16-bit samples" }
        return ShortArray(pcmBytes.size / 2) { index ->
            val offset = index * 2
            ((pcmBytes[offset].toInt() and 0xff) or (pcmBytes[offset + 1].toInt() shl 8)).toShort()
        }
    }

    private fun ByteBuffer.readFourCc(): String =
        ByteArray(4).also(::get).toString(StandardCharsets.US_ASCII)
}
