package com.example.myapp.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInferenceCoordinatorTest {
    @Test
    fun serializesDifferentLocalModelEngines() = runBlocking {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        val first = async(Dispatchers.Default) {
            LocalInferenceCoordinator.withExclusiveInference {
                firstEntered.countDown()
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        val second = async(start = CoroutineStart.UNDISPATCHED) {
            LocalInferenceCoordinator.withExclusiveInference {
                secondEntered.countDown()
            }
        }
        assertFalse(secondEntered.await(250, TimeUnit.MILLISECONDS))

        releaseFirst.countDown()
        first.await()
        second.await()
        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
    }
}
