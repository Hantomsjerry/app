package com.example.myapp.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGenerationTrackerTest {
    @Test
    fun beginMapsControllerGenerationToFreshUiGeneration() {
        val tracker = VoiceGenerationTracker()

        val uiGeneration = tracker.begin(controllerGeneration = 41)

        assertEquals(uiGeneration, tracker.resolve(controllerGeneration = 41))
        assertEquals(uiGeneration, tracker.currentUiGeneration)
        assertTrue(tracker.isCurrent(uiGeneration))
    }

    @Test
    fun mismatchedControllerGenerationCannotResolveCurrentOperation() {
        val tracker = VoiceGenerationTracker()
        tracker.begin(controllerGeneration = 41)

        assertNull(tracker.resolve(controllerGeneration = 40))
        assertNull(tracker.resolve(controllerGeneration = 42))
    }

    @Test
    fun replacementMakesPreviousControllerGenerationStale() {
        val tracker = VoiceGenerationTracker()
        val firstUiGeneration = tracker.begin(controllerGeneration = 41)

        val replacementUiGeneration = tracker.begin(controllerGeneration = 42)

        assertNull(tracker.resolve(controllerGeneration = 41))
        assertEquals(replacementUiGeneration, tracker.resolve(controllerGeneration = 42))
        assertFalse(tracker.isCurrent(firstUiGeneration))
        assertTrue(tracker.isCurrent(replacementUiGeneration))
    }

    @Test
    fun invalidateClearsControllerMappingAndCurrentOperation() {
        val tracker = VoiceGenerationTracker()
        val uiGeneration = tracker.begin(controllerGeneration = 41)

        tracker.invalidate()

        assertNull(tracker.resolve(controllerGeneration = 41))
        assertNull(tracker.currentUiGeneration)
        assertFalse(tracker.isCurrent(uiGeneration))
    }

    @Test
    fun uiGenerationsIncreaseAcrossReplacementAndInvalidation() {
        val tracker = VoiceGenerationTracker()
        val first = tracker.begin(controllerGeneration = 41)
        val second = tracker.begin(controllerGeneration = 42)
        tracker.invalidate()
        val third = tracker.begin(controllerGeneration = 43)

        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test
    fun untrackedOperationGetsMonotonicUiGenerationWithoutControllerResolution() {
        val tracker = VoiceGenerationTracker()
        val controllerUiGeneration = tracker.begin(controllerGeneration = 41)

        val untrackedUiGeneration = tracker.beginUntracked()

        assertTrue(untrackedUiGeneration > controllerUiGeneration)
        assertEquals(untrackedUiGeneration, tracker.currentUiGeneration)
        assertNull(tracker.resolve(controllerGeneration = 41))
        assertTrue(tracker.isCurrent(untrackedUiGeneration))
    }

    @Test
    fun backgroundThreadCanCheckCurrentOperation() {
        val tracker = VoiceGenerationTracker()
        val uiGeneration = tracker.begin(controllerGeneration = 41)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val isCurrent = executor.submit<Boolean> {
                tracker.resolve(controllerGeneration = 41) == uiGeneration &&
                    tracker.currentUiGeneration == uiGeneration &&
                    tracker.isCurrent(uiGeneration)
            }.get()

            assertTrue(isCurrent)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentBeginsCannotDecreaseCurrentUiGeneration() {
        val tracker = VoiceGenerationTracker()
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        try {
            val generations = (1L..100L).map { controllerGeneration ->
                executor.submit<Long> {
                    start.await()
                    tracker.begin(controllerGeneration)
                }
            }
            start.countDown()
            val allocated = generations.map { it.get() }

            assertEquals((1L..100L).toList(), allocated.sorted())
            assertEquals(allocated.maxOrNull(), tracker.currentUiGeneration)
        } finally {
            executor.shutdownNow()
        }
    }
}
