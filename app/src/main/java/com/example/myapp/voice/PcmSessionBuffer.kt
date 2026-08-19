package com.example.myapp.voice

internal data class BufferedPcm(
    val samples: ShortArray,
    val sampleCount: Int
)

/** One recording session owns one bounded PCM array until submission or cancellation. */
internal class PcmSessionBuffer(capacity: Int = PcmRecordingPolicy.MAX_SAMPLES) {
    private var storage: ShortArray? = ShortArray(capacity)
    private var sampleCount = 0

    fun append(samples: ShortArray): Int {
        val destination = storage ?: return 0
        val copied = minOf(samples.size, destination.size - sampleCount)
        if (copied <= 0) return 0
        samples.copyInto(
            destination = destination,
            destinationOffset = sampleCount,
            startIndex = 0,
            endIndex = copied
        )
        sampleCount += copied
        return copied
    }

    fun take(): BufferedPcm? {
        val samples = storage ?: return null
        val count = sampleCount
        storage = null
        sampleCount = 0
        return BufferedPcm(samples, count)
    }

    fun release() {
        storage = null
        sampleCount = 0
    }
}
