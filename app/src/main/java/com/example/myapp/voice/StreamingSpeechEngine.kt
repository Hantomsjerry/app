package com.example.myapp.voice

interface StreamingSpeechEngine : AutoCloseable {
    suspend fun prepare()
    suspend fun startSession(onPartialText: (String) -> Unit)
    suspend fun acceptSamples(samples: ShortArray, sampleCount: Int = samples.size)
    suspend fun finishSession(): String
    suspend fun cancelSession()
    override fun close()
}

fun pcm16ToFloat(samples: ShortArray, sampleCount: Int = samples.size): FloatArray {
    require(sampleCount in 0..samples.size) { "Invalid PCM sample count: $sampleCount" }
    return FloatArray(sampleCount) { index -> samples[index] / 32768.0f }
}
