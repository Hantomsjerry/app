package com.example.myapp

import org.junit.Assert.assertEquals
import org.junit.Test

class ParameterAdjusterTest {
    @Test
    fun adjustParameter_incrementsByStepWithinBounds() {
        val result = adjustParameterValue(
            value = 30,
            direction = 1,
            step = 5,
            range = 0..100
        )

        assertEquals(35, result)
    }

    @Test
    fun adjustParameter_clampsAtRangeLimits() {
        val upper = adjustParameterValue(
            value = 100,
            direction = 1,
            step = 5,
            range = 0..100
        )
        val lower = adjustParameterValue(
            value = 0,
            direction = -1,
            step = 5,
            range = 0..100
        )

        assertEquals(100, upper)
        assertEquals(0, lower)
    }

    @Test
    fun increment_preserves_off_step_value() {
        assertEquals(37, adjustParameterValue(32, 1, 5, 0..100))
    }

    @Test
    fun commitInput_preservesExactInRangeInteger() {
        assertEquals(32, commitParameterInput("32", 30, 0..100))
    }

    @Test
    fun commitInput_clampsValuesOutsideRange() {
        assertEquals(100, commitParameterInput("150", 30, 0..100))
        assertEquals(0, commitParameterInput("-5", 30, 0..100))
    }

    @Test
    fun commitInput_restoresPreviousValueForInvalidText() {
        assertEquals(30, commitParameterInput("", 30, 0..100))
        assertEquals(30, commitParameterInput("abc", 30, 0..100))
        assertEquals(30, commitParameterInput("2147483648", 30, 0..100))
    }
}
