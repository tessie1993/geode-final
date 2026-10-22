package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.Wave
import org.junit.Assert.assertEquals
import org.junit.Test

class ModulatorPicturesTest {
    private val tolerance = 1e-4f

    @Test
    fun `a sine starts at the middle, peaks a quarter in and dips three quarters in`() {
        assertEquals(0.5f, waveAt(Wave.SINE, 0f), tolerance)
        assertEquals(1f, waveAt(Wave.SINE, 0.25f), tolerance)
        assertEquals(0f, waveAt(Wave.SINE, 0.75f), tolerance)
    }

    @Test
    fun `a triangle climbs to the top halfway and back down`() {
        assertEquals(0f, waveAt(Wave.TRIANGLE, 0f), tolerance)
        assertEquals(0.5f, waveAt(Wave.TRIANGLE, 0.25f), tolerance)
        assertEquals(1f, waveAt(Wave.TRIANGLE, 0.5f), tolerance)
        assertEquals(0.5f, waveAt(Wave.TRIANGLE, 0.75f), tolerance)
    }

    @Test
    fun `a saw ramps up and drops each cycle, a square holds high then low`() {
        assertEquals(0.25f, waveAt(Wave.SAW, 0.25f), tolerance)
        assertEquals(0.25f, waveAt(Wave.SAW, 1.25f), tolerance)
        assertEquals(1f, waveAt(Wave.SQUARE, 0.2f), tolerance)
        assertEquals(0f, waveAt(Wave.SQUARE, 0.7f), tolerance)
    }

    @Test
    fun `random holds each step's value for a step, and repeats every cycle`() {
        assertEquals(waveAt(Wave.RANDOM, 0.01f), waveAt(Wave.RANDOM, 0.1f), tolerance)
        assertEquals(waveAt(Wave.RANDOM, 0.3f), waveAt(Wave.RANDOM, 1.3f), tolerance)
        assertEquals(RANDOM_STEPS[1], waveAt(Wave.RANDOM, 0.3f), tolerance)
    }

    @Test
    fun `an envelope rises over its attack, then falls away over its release`() {
        val attack = Millis(100)
        val release = Millis(300)
        assertEquals(0f, envelopeAt(attack, release, 0f), tolerance)
        assertEquals(0.5f, envelopeAt(attack, release, 0.125f), tolerance)
        assertEquals(1f, envelopeAt(attack, release, 0.25f), tolerance)
        assertEquals(0.5f, envelopeAt(attack, release, 0.625f), tolerance)
        assertEquals(0f, envelopeAt(attack, release, 1f), tolerance)
    }

    @Test
    fun `an envelope with no time at all is a single spike at the start`() {
        assertEquals(1f, envelopeAt(Millis.ZERO, Millis.ZERO, 0f), tolerance)
        assertEquals(0f, envelopeAt(Millis.ZERO, Millis.ZERO, 0.5f), tolerance)
    }

    @Test
    fun `a drift stays within 0 and 1`() {
        (0..100).map { driftAt(it / 100f) }.forEach { assertEquals(true, it in 0f..1f) }
    }
}
