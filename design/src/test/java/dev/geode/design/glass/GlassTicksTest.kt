package dev.geode.design.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A dragged value ticks gently: at each stop, or every twentieth of a range without stops (#398). */
class GlassTicksTest {
    @Test
    fun `a range without stops ticks every twentieth`() {
        assertEquals(0, GlassTicks.index(0f, 0f..1f, steps = 0))
        assertEquals(0, GlassTicks.index(0.049f, 0f..1f, steps = 0))
        assertEquals(1, GlassTicks.index(0.05f, 0f..1f, steps = 0))
        assertEquals(10, GlassTicks.index(0.5f, 0f..1f, steps = 0))
        assertEquals(GlassTicks.CONTINUOUS_TICKS, GlassTicks.index(1f, 0f..1f, steps = 0))
    }

    @Test
    fun `a range with stops ticks at each stop`() {
        // Three stops between the ends: five places, a quarter apart.
        assertEquals(0, GlassTicks.index(0f, 0f..1f, steps = 3))
        assertEquals(0, GlassTicks.index(0.12f, 0f..1f, steps = 3))
        assertEquals(1, GlassTicks.index(0.13f, 0f..1f, steps = 3))
        assertEquals(2, GlassTicks.index(0.5f, 0f..1f, steps = 3))
        assertEquals(4, GlassTicks.index(1f, 0f..1f, steps = 3))
    }

    @Test
    fun `the index follows the range's own start and span`() {
        assertEquals(0, GlassTicks.index(0.5f, 0.5f..1.5f, steps = 0))
        assertEquals(10, GlassTicks.index(1f, 0.5f..1.5f, steps = 0))
        assertEquals(GlassTicks.CONTINUOUS_TICKS, GlassTicks.index(1.5f, 0.5f..1.5f, steps = 0))
    }

    @Test
    fun `values outside the range tick as its ends`() {
        assertEquals(0, GlassTicks.index(-3f, 0f..1f, steps = 0))
        assertEquals(GlassTicks.CONTINUOUS_TICKS, GlassTicks.index(3f, 0f..1f, steps = 0))
    }

    @Test
    fun `an empty range never ticks`() {
        assertEquals(0, GlassTicks.index(5f, 1f..1f, steps = 0))
        assertFalse(GlassTicks.crossed(0f, 5f, 1f..1f, steps = 0))
    }

    @Test
    fun `only a move past a tick plays one`() {
        assertFalse(GlassTicks.crossed(0.51f, 0.54f, 0f..1f, steps = 0))
        assertTrue(GlassTicks.crossed(0.54f, 0.56f, 0f..1f, steps = 0))
        assertTrue(GlassTicks.crossed(0.56f, 0.54f, 0f..1f, steps = 0))
        assertFalse(GlassTicks.crossed(0.3f, 0.3f, 0f..1f, steps = 0))
    }
}
