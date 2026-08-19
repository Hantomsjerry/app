package com.example.myapp.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val READ_BUFFER_SAMPLES = 1_024

/** 语音识别所需的 16 kHz 单声道 PCM 边界。 */
object PcmRecordingPolicy {
    const val SAMPLE_RATE = 16_000
    const val MIN_SAMPLES = 4_800
    const val MAX_SAMPLES = 320_000
    const val MAX_DURATION_MILLIS = 20_000L

    fun isLongEnough(sampleCount: Int): Boolean = sampleCount >= MIN_SAMPLES
}

interface PcmRecorder {
    suspend fun start(onSamples: (ShortArray) -> Unit)
    suspend fun stop(): Int
    suspend fun cancel()
}

class PcmRecordingException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal fun interface PcmAudioRecordFactory {
    fun create(): PcmAudioRecord
}

internal interface PcmAudioRecord {
    val isInitialized: Boolean
    val isRecording: Boolean

    fun start()
    fun read(target: ShortArray, offset: Int, size: Int): Int
    fun stop()
    fun release()
}

/**
 * AudioRecord 的协程安全封装。
 *
 * [start] 只负责启动后台读取；[stop] 和 [cancel] 竞争同一份 Recording 所有权，只有成功
 * 摘除它的调用者执行 stop/join/release，避免并发结束时重复释放系统录音对象。
 */
class AndroidPcmRecorder internal constructor(
    private val audioRecordFactory: PcmAudioRecordFactory,
    ioDispatcher: CoroutineDispatcher
) : PcmRecorder {
    constructor(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        audioRecordFactory = FrameworkPcmAudioRecordFactory,
        ioDispatcher = ioDispatcher
    )

    private val stateMutex = Mutex()
    private val recordingScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var recording: Recording? = null
    private var cleanupCompletion: CompletableDeferred<CleanupOutcome>? = null
    private var cleanupStateReleased: CompletableDeferred<Unit>? = null

    override suspend fun start(onSamples: (ShortArray) -> Unit) {
        while (true) {
            val decision = stateMutex.withLock {
                check(recording == null) { "PCM recording is already active" }
                val cleanup = cleanupCompletion
                if (cleanup == null) {
                    startRecordingLocked(onSamples)
                    StartDecision.Started
                } else {
                    StartDecision.WaitForCleanup(
                        completion = cleanup,
                        stateReleased = checkNotNull(cleanupStateReleased)
                    )
                }
            }

            when (decision) {
                StartDecision.Started -> return
                is StartDecision.WaitForCleanup -> {
                    try {
                        decision.completion.await()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        // A finished recorder can start again even when its previous cleanup failed.
                    }
                    decision.stateReleased.await()
                }
            }
        }
    }

    override suspend fun stop(): Int = finish(discardSamples = false) ?: 0

    override suspend fun cancel() {
        finish(discardSamples = true)
    }

    private fun startRecordingLocked(onSamples: (ShortArray) -> Unit) {
        val audioRecord = try {
            audioRecordFactory.create()
        } catch (error: PcmRecordingException) {
            throw error
        } catch (error: RuntimeException) {
            throw PcmRecordingException("AudioRecord failed to initialize", error)
        }
        try {
            if (!audioRecord.isInitialized) {
                throw PcmRecordingException("AudioRecord failed to initialize")
            }
            audioRecord.start()
            if (!audioRecord.isRecording) {
                throw PcmRecordingException("AudioRecord failed to start")
            }

            val nextRecording = Recording(audioRecord, onSamples)
            nextRecording.readJob = recordingScope.launch {
                readUntilStopped(nextRecording)
            }
            recording = nextRecording
        } catch (error: PcmRecordingException) {
            audioRecord.release()
            throw error
        } catch (error: RuntimeException) {
            audioRecord.release()
            throw PcmRecordingException("AudioRecord failed to start", error)
        }
    }

    private suspend fun finish(discardSamples: Boolean): Int? {
        // 在锁内转移生命周期所有权，在锁外执行可能阻塞的 AudioRecord 操作。
        val (detached, existingCleanup) = stateMutex.withLock {
            recording?.let { currentRecording ->
                recording = null
                currentRecording.isCapturing.set(false)
                Pair(
                    DetachedRecording(
                        recording = currentRecording,
                        completion = CompletableDeferred<CleanupOutcome>().also {
                            cleanupCompletion = it
                        },
                        stateReleased = CompletableDeferred<Unit>().also {
                            cleanupStateReleased = it
                        }
                    ),
                    null
                )
            } ?: Pair(null, cleanupCompletion)
        }
        if (detached == null) {
            val outcome = existingCleanup?.await() ?: return null
            return if (discardSamples) null else outcome.sampleCount ?: 0
        }

        try {
            val sampleCount = finishDetachedRecording(detached.recording, discardSamples)
            detached.completion.complete(CleanupOutcome(sampleCount))
            return sampleCount
        } catch (error: Throwable) {
            detached.completion.completeExceptionally(error)
            throw error
        } finally {
            val stateReleased = stateMutex.withLock {
                if (cleanupCompletion === detached.completion) {
                    cleanupCompletion = null
                    cleanupStateReleased = null
                    true
                } else {
                    false
                }
            }
            if (stateReleased) detached.stateReleased.complete(Unit)
        }
    }

    private suspend fun finishDetachedRecording(
        currentRecording: Recording,
        discardSamples: Boolean
    ): Int? {
        var stopFailure: PcmRecordingException? = null
        var releaseFailure: PcmRecordingException? = null

        fun releaseOnce() {
            // stop 失败、read 结束和 finally 都可能走到这里，原子标志保证只释放一次。
            if (!currentRecording.released.compareAndSet(false, true)) return
            try {
                currentRecording.audioRecord.release()
            } catch (error: RuntimeException) {
                releaseFailure = PcmRecordingException("AudioRecord failed to release", error)
            }
        }

        try {
            currentRecording.audioRecord.stop()
        } catch (error: RuntimeException) {
            stopFailure = PcmRecordingException("AudioRecord failed to stop", error)
            releaseOnce()
        }

        try {
            currentRecording.readJob.join()
        } finally {
            releaseOnce()
        }

        stopFailure?.let { throw it }
        currentRecording.failure?.let { throw it }
        releaseFailure?.let { throw it }
        return if (discardSamples) null else currentRecording.sampleCount
    }

    private fun readUntilStopped(currentRecording: Recording) {
        val readBuffer = ShortArray(READ_BUFFER_SAMPLES)
        try {
            // 预分配固定上限缓冲区，达到 20 秒后自然退出，避免录音无限增长。
            while (
                currentRecording.isCapturing.get() &&
                currentRecording.sampleCount < PcmRecordingPolicy.MAX_SAMPLES
            ) {
                val readSize = minOf(
                    readBuffer.size,
                    PcmRecordingPolicy.MAX_SAMPLES - currentRecording.sampleCount
                )
                val samplesRead = currentRecording.audioRecord.read(
                    readBuffer,
                    0,
                    readSize
                )
                when {
                    samplesRead < 0 && currentRecording.isCapturing.get() -> {
                        throw PcmRecordingException("AudioRecord read failed: $samplesRead")
                    }

                    samplesRead < 0 -> break
                    samplesRead > 0 -> {
                        if (samplesRead > readSize) {
                            throw PcmRecordingException(
                                "AudioRecord returned an invalid sample count: $samplesRead"
                            )
                        }
                        if (!currentRecording.isCapturing.get()) break
                        val deliveredSamples = readBuffer.copyOf(samplesRead)
                        try {
                            currentRecording.onSamples(deliveredSamples)
                        } catch (error: Exception) {
                            throw PcmRecordingException("PCM sample callback failed", error)
                        }
                        currentRecording.sampleCount += samplesRead
                    }
                }
            }
        } catch (error: PcmRecordingException) {
            currentRecording.failure = error
        } catch (error: RuntimeException) {
            currentRecording.failure = PcmRecordingException("AudioRecord read failed", error)
        }
    }

    private class Recording(
        val audioRecord: PcmAudioRecord,
        val onSamples: (ShortArray) -> Unit,
        val isCapturing: AtomicBoolean = AtomicBoolean(true),
        var sampleCount: Int = 0,
        var failure: PcmRecordingException? = null,
        val released: AtomicBoolean = AtomicBoolean(false)
    ) {
        lateinit var readJob: Job
    }

    private class DetachedRecording(
        val recording: Recording,
        val completion: CompletableDeferred<CleanupOutcome>,
        val stateReleased: CompletableDeferred<Unit>
    )

    private class CleanupOutcome(val sampleCount: Int?)

    private sealed interface StartDecision {
        data object Started : StartDecision
        data class WaitForCleanup(
            val completion: CompletableDeferred<CleanupOutcome>,
            val stateReleased: CompletableDeferred<Unit>
        ) : StartDecision
    }
}

private object FrameworkPcmAudioRecordFactory : PcmAudioRecordFactory {
    override fun create(): PcmAudioRecord {
        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            PcmRecordingPolicy.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBufferBytes <= 0) {
            throw PcmRecordingException("AudioRecord did not provide a valid buffer size")
        }

        // VOICE_RECOGNITION 音源通常会减少系统音效处理，更适合语音模型输入。
        return FrameworkPcmAudioRecord(
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(PcmRecordingPolicy.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(
                    maxOf(minimumBufferBytes, READ_BUFFER_SAMPLES * Short.SIZE_BYTES)
                )
                .build()
        )
    }
}

private class FrameworkPcmAudioRecord(
    private val audioRecord: AudioRecord
) : PcmAudioRecord {
    override val isInitialized: Boolean
        get() = audioRecord.state == AudioRecord.STATE_INITIALIZED

    override val isRecording: Boolean
        get() = audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override fun start() = audioRecord.startRecording()

    override fun read(target: ShortArray, offset: Int, size: Int): Int =
        audioRecord.read(target, offset, size, AudioRecord.READ_BLOCKING)

    override fun stop() = audioRecord.stop()

    override fun release() = audioRecord.release()
}
