package com.example.myapp.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object WavPcmParser {
    fun parse16kMonoPcm(bytes: ByteArray): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.readFourCc() == "RIFF") { "WAV fixture is not RIFF" }
        val riffSize = buffer.int
        require(riffSize >= 4 && riffSize <= buffer.remaining()) { "Invalid RIFF payload size" }
        require(buffer.readFourCc() == "WAVE") { "RIFF fixture is not WAVE" }

        var pcm: ByteArray? = null
        while (buffer.remaining() >= 8) {
            val chunkId = buffer.readFourCc()
            val chunkSize = buffer.int
            require(chunkSize >= 0 && chunkSize <= buffer.remaining()) { "Invalid $chunkId chunk length" }
            val chunkEnd = buffer.position() + chunkSize
            if (chunkId == "fmt ") {
                require(chunkSize >= 16) { "WAV fmt chunk is truncated" }
                require(buffer.short.toInt() == 1) { "WAV is not PCM" }
                require(buffer.short.toInt() == 1) { "WAV is not mono" }
                require(buffer.int == 16_000) { "WAV is not 16 kHz" }
                buffer.int
                buffer.short
                require(buffer.short.toInt() == 16) { "WAV is not 16-bit PCM" }
            } else if (chunkId == "data") {
                pcm = ByteArray(chunkSize).also(buffer::get)
            }
            val nextChunk = chunkEnd + chunkSize % 2
            require(nextChunk <= buffer.limit()) { "WAV chunk padding is truncated" }
            buffer.position(nextChunk)
        }

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
