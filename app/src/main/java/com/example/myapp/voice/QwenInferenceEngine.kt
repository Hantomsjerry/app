package com.example.myapp.voice

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 本地 Qwen 文本生成抽象。 */
interface QwenInferenceEngine : AutoCloseable {
    suspend fun generate(prompt: String, maxTokens: Int = 128): String

    override fun close()
}

internal interface NativeQwenBridgeApi {
    fun loadModel(path: String, contextSize: Int, threads: Int): Long
    fun generate(handle: Long, prompt: String, maxTokens: Int): String
    fun generateCancellable(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        cancelled: AtomicBoolean
    ): String = generate(handle, prompt, maxTokens)
    fun close(handle: Long)
}

internal object NativeQwenBridge : NativeQwenBridgeApi {
    init {
        System.loadLibrary("qwen_jni")
    }

    override fun loadModel(path: String, contextSize: Int, threads: Int): Long =
        nativeLoadModel(path, contextSize, threads)

    override fun generate(handle: Long, prompt: String, maxTokens: Int): String =
        nativeGenerate(handle, prompt, maxTokens, AtomicBoolean(false))

    override fun generateCancellable(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        cancelled: AtomicBoolean
    ): String = nativeGenerate(handle, prompt, maxTokens, cancelled)

    override fun close(handle: Long) {
        nativeClose(handle)
    }

    @JvmStatic
    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Long

    @JvmStatic
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        cancelled: AtomicBoolean
    ): String

    @JvmStatic
    private external fun nativeClose(handle: Long)
}

/**
 * llama.cpp JNI 的协程封装。
 * 每次生成独立加载并释放 native handle，以便每次请求完成后及时回收大块模型内存。
 */
class NativeQwenInferenceEngine internal constructor(
    private val modelStore: QwenModelStore,
    private val bridge: NativeQwenBridgeApi,
    private val threadCount: Int
) : QwenInferenceEngine {
    constructor(modelStore: QwenModelStore) : this(
        modelStore = modelStore,
        bridge = NativeQwenBridge,
        threadCount = conservativeThreadCount()
    )

    private val mutex = Mutex()
    private val admission = OperationAdmission()

    override suspend fun generate(prompt: String, maxTokens: Int): String {
        // admission 在 close 与新请求之间建立明确先后关系；已获准的调用仍负责清理自己的 handle。
        val permit = admission.tryAcquire()
            ?: throw IllegalStateException("Qwen inference engine is closed")
        try {
            return mutex.withLock {
                LocalInferenceCoordinator.withExclusiveInference {
                    withContext(Dispatchers.Default) {
                        val modelFile = modelStore.prepare()
                        val liveHandle = loadModel(modelFile)
                        try {
                            generateCancellable(liveHandle, prompt, maxTokens)
                        } finally {
                            // 成功、异常或协程取消都必须释放本次调用专属的 native 资源。
                            bridge.close(liveHandle)
                        }
                    }
                }
            }
        } finally {
            permit.release()
        }
    }

    override fun close() {
        admission.close()
    }

    private fun loadModel(modelFile: File): Long {
        val loadedHandle = bridge.loadModel(
            path = modelFile.absolutePath,
            contextSize = CONTEXT_SIZE,
            threads = threadCount.coerceAtLeast(1)
        )
        check(loadedHandle != 0L) { "Native model load returned an invalid handle" }
        return loadedHandle
    }

    private suspend fun generateCancellable(
        handle: Long,
        prompt: String,
        maxTokens: Int
    ): String = suspendCancellableCoroutine { continuation ->
        val cancelled = AtomicBoolean(false)
        // native 每生成一个 token 都会读取该标志，从而尽快响应协程取消。
        continuation.invokeOnCancellation { cancelled.set(true) }
        try {
            val result = bridge.generateCancellable(handle, prompt, maxTokens, cancelled)
            continuation.resumeWith(Result.success(result))
        } catch (error: Throwable) {
            continuation.resumeWith(Result.failure(error))
        }
    }

    companion object {
        private const val CONTEXT_SIZE = 1024
        private const val MAX_THREADS = 4

        private fun conservativeThreadCount(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
    }

    private class OperationAdmission {
        private val state = AtomicLong(0L)

        // CAS 成功即表示调用已取得在途所有权；close 只拒绝之后到达的新调用。
        fun tryAcquire(): Permit? {
            while (true) {
                val current = state.get()
                if (current and CLOSED_BIT != 0L) return null
                if (state.compareAndSet(current, current + 1L)) {
                    return Permit(this)
                }
            }
        }

        fun close() {
            while (true) {
                val current = state.get()
                if (current and CLOSED_BIT != 0L) return
                if (state.compareAndSet(current, current or CLOSED_BIT)) return
            }
        }

        private fun release() {
            state.decrementAndGet()
        }

        class Permit(private val admission: OperationAdmission) {
            fun release() {
                admission.release()
            }
        }

        private companion object {
            const val CLOSED_BIT = Long.MIN_VALUE
        }
    }
}
