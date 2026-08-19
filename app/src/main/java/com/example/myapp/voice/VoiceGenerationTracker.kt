package com.example.myapp.voice

/**
 * 把控制器内部 generation 映射为单调递增的 UI generation。
 * 新会话或显式失效后，旧录音、推理和网络回调都无法再命中当前操作。
 */
class VoiceGenerationTracker {
    private val lock = Any()
    private var lastUiGeneration = 0L
    private var current: Mapping? = null

    fun begin(controllerGeneration: Long): Long = synchronized(lock) {
        beginLocked(controllerGeneration)
    }

    fun beginUntracked(): Long = synchronized(lock) {
        beginLocked(controllerGeneration = null)
    }

    fun resolve(controllerGeneration: Long): Long? = synchronized(lock) {
        current?.takeIf { it.controllerGeneration == controllerGeneration }?.uiGeneration
    }

    fun invalidate() {
        synchronized(lock) {
            // 清除当前映射后，所有已取得旧编号的异步回调都会被视为过期。
            current = null
        }
    }

    val currentUiGeneration: Long?
        get() = synchronized(lock) { current?.uiGeneration }

    fun isCurrent(uiGeneration: Long): Boolean = synchronized(lock) {
        current?.uiGeneration == uiGeneration
    }

    private fun beginLocked(controllerGeneration: Long?): Long {
        check(lastUiGeneration < Long.MAX_VALUE) { "Voice UI generation exhausted" }
        val uiGeneration = ++lastUiGeneration
        current = Mapping(controllerGeneration, uiGeneration)
        return uiGeneration
    }

    private data class Mapping(
        val controllerGeneration: Long?,
        val uiGeneration: Long
    )
}
