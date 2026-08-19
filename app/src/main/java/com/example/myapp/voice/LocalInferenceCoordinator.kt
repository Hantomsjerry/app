package com.example.myapp.voice

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局串行化本地模型推理，避免重叠的本地推理请求造成内存峰值。
 */
internal object LocalInferenceCoordinator {
    private val inferenceMutex = Mutex()

    suspend fun <T> withExclusiveInference(block: suspend () -> T): T =
        inferenceMutex.withLock { block() }
}
