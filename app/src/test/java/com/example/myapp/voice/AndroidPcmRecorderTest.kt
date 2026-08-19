package com.example.myapp.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPcmRecorderTest {
    @Test
    fun startReturnsWhileCaptureContinuesOnTheIoDispatcher() = runBlocking {
        val audioRecord = FakePcmAudioRecord()
        val recorder = recorder(audioRecord)

        recorder.start { }

        assertTrue(audioRecord.readEntered.await(1, TimeUnit.SECONDS))
        assertEquals(1, audioRecord.startCalls)
        assertFalse(audioRecord.released.get())

        recorder.cancel()

        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun maximumSampleCountStillStopsAt320000() = runBlocking {
        val source = ShortArray(PcmRecordingPolicy.MAX_SAMPLES + 1) { it.toShort() }
        val audioRecord = FakePcmAudioRecord(source = source)
        val recorder = recorder(audioRecord)
        val maximumDelivered = CountDownLatch(1)

        var deliveredSamples = 0
        recorder.start {
            deliveredSamples += it.size
            if (deliveredSamples == PcmRecordingPolicy.MAX_SAMPLES) {
                maximumDelivered.countDown()
            }
        }
        assertTrue(maximumDelivered.await(2, TimeUnit.SECONDS))

        val result = recorder.stop()

        assertEquals(PcmRecordingPolicy.MAX_SAMPLES, result)
        assertEquals(PcmRecordingPolicy.MAX_SAMPLES, deliveredSamples)
        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun readChunksAreCopiedAndDeliveredInOrder() = runBlocking {
        val source = ShortArray(1_025) { index -> (index + 1).toShort() }
        val audioRecord = FakePcmAudioRecord(source = source)
        val recorder = recorder(audioRecord)
        val delivered = mutableListOf<ShortArray>()

        recorder.start { delivered += it }
        assertTrue(audioRecord.sourceExhausted.await(1, TimeUnit.SECONDS))

        val result = recorder.stop()

        assertEquals(source.size, result)
        assertEquals(2, delivered.size)
        assertEquals(1_024, delivered[0].size)
        assertEquals(1, delivered[1].size)
        assertEquals(1, delivered[0][0].toInt())
        assertEquals(1_024, delivered[0].last().toInt())
        assertEquals(1_025, delivered[1].single().toInt())
        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun stopReturnsTotalDeliveredSampleCount() = runBlocking {
        val source = ShortArray(1_724) { index -> index.toShort() }
        val audioRecord = FakePcmAudioRecord(source = source)
        val recorder = recorder(audioRecord)
        val delivered = mutableListOf<ShortArray>()

        recorder.start { delivered += it }
        assertTrue(audioRecord.sourceExhausted.await(1, TimeUnit.SECONDS))

        assertEquals(1_724, recorder.stop())
        assertEquals(listOf(1_024, 700), delivered.map { it.size })
        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun cancelStopsWithoutReturningOrDeliveringAfterCancellation() = runBlocking {
        val audioRecord = FakePcmAudioRecord(source = shortArrayOf(1, 2, 3))
        val recorder = recorder(audioRecord)
        var deliveredChunks = 0

        recorder.start { deliveredChunks++ }
        assertTrue(audioRecord.sourceExhausted.await(1, TimeUnit.SECONDS))
        recorder.cancel()
        val chunksAtCancellation = deliveredChunks
        Thread.sleep(50)

        assertEquals(1, audioRecord.stopCalls)
        assertEquals(1, audioRecord.releaseCalls)
        assertEquals(0, recorder.stop())
        assertEquals(chunksAtCancellation, deliveredChunks)
    }

    @Test
    fun negativeReadIsReportedAsARecordingException() = runBlocking {
        val audioRecord = FakePcmAudioRecord(readError = -6)
        val recorder = recorder(audioRecord)

        recorder.start { }
        assertTrue(audioRecord.readEntered.await(1, TimeUnit.SECONDS))

        val failure = assertRecordingFailure { recorder.stop() }

        assertEquals("AudioRecord read failed: -6", failure.message)
        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun initializationFailureIsReportedAndReleasesTheAudioRecord() = runBlocking {
        val audioRecord = FakePcmAudioRecord(isInitialized = false)

        val failure = assertRecordingFailure { recorder(audioRecord).start { } }

        assertEquals("AudioRecord failed to initialize", failure.message)
        assertEquals(1, audioRecord.releaseCalls)
        assertEquals(0, audioRecord.startCalls)
    }

    @Test
    fun startFailureIsReportedAndReleasesTheAudioRecord() = runBlocking {
        val audioRecord = FakePcmAudioRecord(startFailure = IllegalStateException("start"))

        val failure = assertRecordingFailure { recorder(audioRecord).start { } }

        assertEquals("AudioRecord failed to start", failure.message)
        assertEquals(1, audioRecord.releaseCalls)
        assertEquals(1, audioRecord.startCalls)
    }

    @Test
    fun repeatedStopAndCancelAreIdempotent() = runBlocking {
        val stoppedAudioRecord = FakePcmAudioRecord()
        val stoppedRecorder = recorder(stoppedAudioRecord)
        stoppedRecorder.start { }
        assertTrue(stoppedAudioRecord.readEntered.await(1, TimeUnit.SECONDS))

        assertEquals(0, stoppedRecorder.stop())
        assertEquals(0, stoppedRecorder.stop())
        stoppedRecorder.cancel()

        assertEquals(1, stoppedAudioRecord.stopCalls)
        assertEquals(1, stoppedAudioRecord.releaseCalls)

        val cancelledAudioRecord = FakePcmAudioRecord()
        val cancelledRecorder = recorder(cancelledAudioRecord)
        cancelledRecorder.start { }
        assertTrue(cancelledAudioRecord.readEntered.await(1, TimeUnit.SECONDS))
        cancelledRecorder.cancel()
        cancelledRecorder.cancel()

        assertEquals(1, cancelledAudioRecord.stopCalls)
        assertEquals(1, cancelledAudioRecord.releaseCalls)
    }

    @Test
    fun concurrentStopsWaitForBlockedCallbackAndShareTheRetainedSampleCount() = runBlocking {
        val source = shortArrayOf(4, 8, 15, 16, 23, 42)
        val audioRecord = FakePcmAudioRecord(source = source)
        val recorder = recorder(audioRecord)
        val callbackEntered = CountDownLatch(1)
        val allowCallbackToReturn = CountDownLatch(1)
        var callbackCalls = 0
        recorder.start {
            callbackEntered.countDown()
            assertTrue(allowCallbackToReturn.await(1, TimeUnit.SECONDS))
            callbackCalls++
        }
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))

        val firstStop = async(Dispatchers.Default) { recorder.stop() }
        assertTrue(audioRecord.stopEntered.await(1, TimeUnit.SECONDS))
        val secondStop = async(Dispatchers.Default) { recorder.stop() }
        Thread.sleep(50)

        assertFalse(firstStop.isCompleted)
        assertFalse(secondStop.isCompleted)
        allowCallbackToReturn.countDown()
        val results = withTimeout(1_000) { listOf(firstStop.await(), secondStop.await()) }

        assertEquals(listOf(source.size, source.size), results)
        assertEquals(1, callbackCalls)
        assertEquals(1, audioRecord.stopCalls)
        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun stopWaitsForDiscardingCancelOwnerAndReturnsZero() = runBlocking {
        val source = shortArrayOf(3, 1, 4, 1, 5)
        val audioRecord = FakePcmAudioRecord(source = source)
        val recorder = recorder(audioRecord)
        val callbackEntered = CountDownLatch(1)
        val allowCallbackToReturn = CountDownLatch(1)
        var callbackCalls = 0
        recorder.start {
            callbackEntered.countDown()
            assertTrue(allowCallbackToReturn.await(1, TimeUnit.SECONDS))
            callbackCalls++
        }
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))

        val cancelResult = async(Dispatchers.Default) { recorder.cancel() }
        assertTrue(audioRecord.stopEntered.await(1, TimeUnit.SECONDS))
        val stopResult = async(Dispatchers.Default) { recorder.stop() }
        Thread.sleep(50)

        assertFalse(cancelResult.isCompleted)
        assertFalse(stopResult.isCompleted)
        allowCallbackToReturn.countDown()
        withTimeout(1_000) { cancelResult.await() }
        assertEquals(0, withTimeout(1_000) { stopResult.await() })

        assertEquals(1, callbackCalls)
        assertEquals(1, audioRecord.stopCalls)
        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun waitingStopReceivesTheOwnerCleanupFailure() = runBlocking {
        val audioRecord = FakePcmAudioRecord(
            source = shortArrayOf(1),
            stopFailure = IllegalStateException("stop")
        )
        val recorder = recorder(audioRecord)
        val callbackEntered = CountDownLatch(1)
        val allowCallbackToReturn = CountDownLatch(1)
        recorder.start {
            callbackEntered.countDown()
            assertTrue(allowCallbackToReturn.await(1, TimeUnit.SECONDS))
        }
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))

        val owner = async(Dispatchers.Default) { runCatching { recorder.stop() } }
        assertTrue(audioRecord.stopEntered.await(1, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.Default) { runCatching { recorder.stop() } }
        Thread.sleep(50)
        assertFalse(owner.isCompleted)
        assertFalse(waiter.isCompleted)
        allowCallbackToReturn.countDown()

        val ownerFailure = withTimeout(1_000) { owner.await().exceptionOrNull() }
        val waiterFailure = withTimeout(1_000) { waiter.await().exceptionOrNull() }

        assertTrue(ownerFailure is PcmRecordingException)
        assertTrue(waiterFailure is PcmRecordingException)
        assertEquals("AudioRecord failed to stop", ownerFailure?.message)
        assertEquals(ownerFailure?.message, waiterFailure?.message)
        assertEquals(1, audioRecord.stopCalls)
        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun startWaitsForDetachedCleanupBeforeCreatingTheNextAudioRecord() = runBlocking {
        val allowFirstStopToFinish = CountDownLatch(1)
        val firstAudioRecord = FakePcmAudioRecord(stopGate = allowFirstStopToFinish)
        val secondAudioRecord = FakePcmAudioRecord()
        val recorder = recorder(firstAudioRecord, secondAudioRecord)
        recorder.start { }
        assertTrue(firstAudioRecord.readEntered.await(1, TimeUnit.SECONDS))

        val stopping = async(Dispatchers.Default) { recorder.stop() }
        assertTrue(firstAudioRecord.stopEntered.await(1, TimeUnit.SECONDS))

        val starting = async(Dispatchers.Unconfined) { recorder.start { } }
        assertFalse(starting.isCompleted)
        assertEquals(0, secondAudioRecord.startCalls)

        allowFirstStopToFinish.countDown()
        withTimeout(1_000) {
            stopping.await()
            starting.await()
        }
        assertTrue(secondAudioRecord.readEntered.await(1, TimeUnit.SECONDS))

        recorder.cancel()
    }

    @Test
    fun stopFailureReleasesBeforeWaitingForTheBlockedRead() = runBlocking {
        val audioRecord = FakePcmAudioRecord(stopFailure = IllegalStateException("stop"))
        val recorder = recorder(audioRecord)
        recorder.start { }
        assertTrue(audioRecord.readEntered.await(1, TimeUnit.SECONDS))

        val stopResult = async(Dispatchers.Default) { runCatching { recorder.stop() } }

        assertTrue(audioRecord.releaseObserved.await(1, TimeUnit.SECONDS))
        val failure = withTimeout(1_000) { stopResult.await().exceptionOrNull() }

        assertTrue(failure is PcmRecordingException)
        assertEquals("AudioRecord failed to stop", failure?.message)
        assertEquals(1, audioRecord.releaseCalls)
    }

    @Test
    fun callbackFailureStopsDeliveryAndIsReportedFromStop() = runBlocking {
        val source = ShortArray(1_025) { index -> index.toShort() }
        val audioRecord = FakePcmAudioRecord(source = source)
        val recorder = recorder(audioRecord)
        var callbackCalls = 0

        recorder.start {
            callbackCalls++
            throw IllegalArgumentException("consumer")
        }
        assertTrue(audioRecord.readEntered.await(1, TimeUnit.SECONDS))

        val failure = assertRecordingFailure { recorder.stop() }

        assertEquals("PCM sample callback failed", failure.message)
        assertEquals(1, callbackCalls)
        assertEquals(1, audioRecord.releaseCalls)
    }

    private fun recorder(vararg audioRecords: FakePcmAudioRecord): AndroidPcmRecorder {
        val remainingAudioRecords = ArrayDeque(audioRecords.asList())
        return AndroidPcmRecorder(
            audioRecordFactory = PcmAudioRecordFactory { remainingAudioRecords.removeFirst() },
            ioDispatcher = Dispatchers.Default
        )
    }

    private suspend fun assertRecordingFailure(
        block: suspend () -> Any?
    ): PcmRecordingException = try {
        block()
        throw AssertionError("Expected PcmRecordingException")
    } catch (failure: PcmRecordingException) {
        failure
    }

    private class FakePcmAudioRecord(
        private val source: ShortArray = ShortArray(0),
        private val readError: Int? = null,
        override val isInitialized: Boolean = true,
        private val startFailure: RuntimeException? = null,
        private val stopFailure: RuntimeException? = null,
        private val stopGate: CountDownLatch? = null
    ) : PcmAudioRecord {
        override var isRecording: Boolean = false
            private set

        val readEntered = CountDownLatch(1)
        val sourceExhausted = CountDownLatch(1)
        val releaseObserved = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val released = AtomicBoolean(false)
        var startCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        private var sourceOffset = 0
        private val readMayFinish = CountDownLatch(1)

        override fun start() {
            startCalls++
            startFailure?.let { throw it }
            isRecording = true
        }

        override fun read(target: ShortArray, offset: Int, size: Int): Int {
            readEntered.countDown()
            readError?.let { return it }
            if (sourceOffset < source.size) {
                val copied = minOf(size, source.size - sourceOffset)
                source.copyInto(target, offset, sourceOffset, sourceOffset + copied)
                sourceOffset += copied
                return copied
            }

            sourceExhausted.countDown()
            check(readMayFinish.await(5, TimeUnit.SECONDS)) { "Read was not unblocked" }
            return if (released.get()) -3 else 0
        }

        override fun stop() {
            stopCalls++
            stopEntered.countDown()
            stopFailure?.let { throw it }
            stopGate?.let {
                check(it.await(5, TimeUnit.SECONDS)) { "Stop was not allowed to finish" }
            }
            isRecording = false
            readMayFinish.countDown()
        }

        override fun release() {
            releaseCalls++
            released.set(true)
            releaseObserved.countDown()
            readMayFinish.countDown()
        }
    }
}
