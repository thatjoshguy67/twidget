package com.tjg.twidget.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationHapticRampTest {
    @Test fun revealSpansTheRemainingAnimationAndEndsInSilence() {
        for (duration in listOf(1L, 19L, 4377L, 8754L)) {
            for (controlled in listOf(true, false)) {
                val envelope = AnimationHapticRamp.reveal(duration, .1f, controlled)
                assertEquals(duration, envelope.timings.sum())
                assertEquals(envelope.timings.size, envelope.amplitudes.size)
                assertTrue(envelope.timings.all { it > 0 })
                assertEquals(0, envelope.amplitudes.last())
            }
        }
    }

    @Test fun revealRisesThenFadesAndLateStartsDoNotRestartTheRise() {
        val full = AnimationHapticRamp.reveal(4500, 0f, true).amplitudes
        assertTrue(full.max() > full.first())
        assertTrue(full[full.size * 3 / 4] > 20)
        assertEquals(0, full.last())
        val tail = AnimationHapticRamp.reveal(900, .8f, true).amplitudes.toList()
        assertTrue(tail.zipWithNext().all { (a, b) -> a >= b })
        assertEquals(0, tail.last())
    }

    @Test fun envelopeMatchesAnimationDurationIncludingRemaindersAndSpeedScaling() {
        for (duration in listOf(1L, 19L, 20L, 117L, 2633L, 5266L, 15001L)) {
            for (amplitudeControl in listOf(true, false)) {
                val ramp = AnimationHapticRamp.create(duration, amplitudeControl)
                assertEquals(duration, ramp.timings.sum())
                assertEquals(ramp.timings.size, ramp.amplitudes.size)
                assertTrue(ramp.timings.all { it > 0 })
            }
        }
    }

    @Test fun controlledAmplitudeStartsMinimalAndRisesWithoutDropping() {
        val ramp = AnimationHapticRamp.create(2633L, true)
        assertEquals(6, ramp.amplitudes.first())
        assertEquals(120, ramp.amplitudes.last())
        assertTrue(ramp.amplitudes.toList().zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun fixedStrengthMotorIncreasesPulseDutyCycle() {
        val ramp = AnimationHapticRamp.create(2600L, false)
        val pulses = ramp.timings.indices.filter { ramp.amplitudes[it] == -1 }.map { ramp.timings[it] }
        assertTrue(pulses.first() < pulses.last())
        assertTrue(pulses.zipWithNext().all { (a, b) -> a <= b })
    }
}
