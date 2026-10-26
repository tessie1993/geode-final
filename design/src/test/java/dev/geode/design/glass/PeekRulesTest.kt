package dev.geode.design.glass

import dev.geode.design.glass.PeekValue.EXPANDED
import dev.geode.design.glass.PeekValue.PEEK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A peek panel's drag (#412), as fractions of the way from the whole panel (0) to its peek bar (1). */
class PeekRulesTest {
    private val distance = 400f
    private val threshold = 56f
    private val fling = 400f

    @Test
    fun `a drag moves the fraction by its share of the way and stays on the way`() {
        // fraction, delta -> moved
        val table =
            listOf(
                Triple(0f, 100f, 0.25f),
                Triple(0.5f, -100f, 0.25f),
                Triple(0f, -50f, 0f),
                Triple(0.9f, 100f, 1f),
                Triple(1f, -400f, 0f),
                Triple(0.25f, 0f, 0.25f),
            )
        for ((fraction, delta, moved) in table) {
            assertEquals("$fraction by $delta", moved, PeekRules.dragged(fraction, delta, distance), 1e-6f)
        }
    }

    @Test
    fun `before the panel is measured a drag leaves it where it is`() {
        assertEquals(0.5f, PeekRules.dragged(0.5f, 100f, 0f), 0f)
        assertEquals(0.5f, PeekRules.dragged(0.5f, 100f, -1f), 0f)
    }

    @Test
    fun `the overshoot is what goes past either end of the way`() {
        assertEquals(-30f, PeekRules.overshoot(0f, -30f, distance), 1e-6f)
        assertEquals(-20f, PeekRules.overshoot(0.05f, -40f, distance), 1e-6f)
        assertEquals(50f, PeekRules.overshoot(1f, 50f, distance), 1e-6f)
        assertEquals(10f, PeekRules.overshoot(0.95f, 30f, distance), 1e-6f)
        assertEquals(0f, PeekRules.overshoot(0.5f, 100f, distance), 0f)
        assertEquals(0f, PeekRules.overshoot(0f, 0f, distance), 0f)
        assertEquals(0f, PeekRules.overshoot(1f, -400f, distance), 0f)
    }

    @Test
    fun `a fling goes its way and a slow release switches once it covered a quarter of the way`() {
        // start, fraction, velocity -> settles at
        val table =
            listOf(
                Triple(EXPANDED, 0.1f, 401f) to PEEK,
                Triple(EXPANDED, 0.9f, -401f) to EXPANDED,
                Triple(PEEK, 0.9f, -401f) to EXPANDED,
                Triple(PEEK, 0.1f, 401f) to PEEK,
                Triple(EXPANDED, 0.25f, 0f) to PEEK,
                Triple(EXPANDED, 0.24f, 0f) to EXPANDED,
                Triple(EXPANDED, 0.24f, 400f) to EXPANDED,
                Triple(EXPANDED, 0.25f, -400f) to PEEK,
                Triple(PEEK, 0.75f, 0f) to EXPANDED,
                Triple(PEEK, 0.76f, 0f) to PEEK,
                Triple(PEEK, 0.76f, -400f) to PEEK,
                Triple(PEEK, 0.75f, 400f) to EXPANDED,
            )
        for ((case, settled) in table) {
            val (start, fraction, velocity) = case
            assertEquals(case.toString(), settled, PeekRules.settle(start, fraction, velocity, fling))
        }
    }

    @Test
    fun `pulling past the end it started at reaches past the panel, otherwise the release settles`() {
        fun release(
            start: PeekValue,
            fraction: Float,
            pulled: Float,
            velocity: Float = 0f,
        ) = PeekRules.release(start, fraction, velocity, pulled, threshold, fling)

        assertEquals(PeekOutcome.PULLED_UP, release(EXPANDED, 0f, -56f))
        assertEquals(PeekOutcome.EXPANDED, release(EXPANDED, 0f, -55f))
        assertEquals(PeekOutcome.EXPANDED, release(EXPANDED, 0.01f, -80f))
        assertEquals(PeekOutcome.PULLED_DOWN, release(PEEK, 1f, 56f))
        assertEquals(PeekOutcome.PEEK, release(PEEK, 1f, 55f))
        assertEquals(PeekOutcome.PEEK, release(PEEK, 0.99f, 80f))
        // A pull the other way never counts.
        assertEquals(PeekOutcome.PEEK, release(EXPANDED, 1f, 80f))
        assertEquals(PeekOutcome.EXPANDED, release(PEEK, 0f, -80f))
        assertEquals(PeekOutcome.PEEK, release(EXPANDED, 0.1f, 0f, velocity = 500f))
        assertEquals(PeekOutcome.EXPANDED, release(PEEK, 0.9f, 0f, velocity = -500f))
    }

    @Test
    fun `an upward scroll raises a lowered panel before the list scrolls`() {
        assertEquals(-100f, PeekRules.preScroll(0.5f, -100f, distance), 1e-6f)
        assertEquals(-40f, PeekRules.preScroll(0.1f, -100f, distance), 1e-6f)
        assertEquals(0f, PeekRules.preScroll(0f, -100f, distance), 0f)
        assertEquals(0f, PeekRules.preScroll(0.5f, 100f, distance), 0f)
        assertEquals(0f, PeekRules.preScroll(0.5f, 0f, distance), 0f)
    }

    @Test
    fun `a downward drag left over at the top of the list lowers the panel, a fling never does`() {
        assertEquals(30f, PeekRules.postScroll(0f, 30f, fromDrag = true), 0f)
        assertEquals(30f, PeekRules.postScroll(0.5f, 30f, fromDrag = true), 0f)
        assertEquals(0f, PeekRules.postScroll(1f, 30f, fromDrag = true), 0f)
        assertEquals(0f, PeekRules.postScroll(0f, 30f, fromDrag = false), 0f)
        assertEquals(0f, PeekRules.postScroll(0f, -30f, fromDrag = true), 0f)
        assertEquals(0f, PeekRules.postScroll(0f, 0f, fromDrag = true), 0f)
    }

    @Test
    fun `the panel shows until it rests on its bar, and the bar whenever the panel is lowered`() {
        assertTrue(PeekRules.showsPanel(EXPANDED, 0f))
        assertTrue(PeekRules.showsPanel(EXPANDED, 1f))
        assertTrue(PeekRules.showsPanel(PEEK, 0.99f))
        assertFalse(PeekRules.showsPanel(PEEK, 1f))

        assertFalse(PeekRules.showsBar(EXPANDED, 0f))
        assertTrue(PeekRules.showsBar(EXPANDED, 0.01f))
        assertTrue(PeekRules.showsBar(PEEK, 0f))
        assertTrue(PeekRules.showsBar(PEEK, 1f))
    }

    @Test
    fun `a panel away from where it rests is moved`() {
        assertFalse(PeekRules.isMoved(EXPANDED, 0f))
        assertFalse(PeekRules.isMoved(PEEK, 1f))
        assertTrue(PeekRules.isMoved(EXPANDED, 0.2f))
        assertTrue(PeekRules.isMoved(PEEK, 0.8f))
    }

    @Test
    fun `each resting place has its fraction`() {
        assertEquals(0f, EXPANDED.fraction, 0f)
        assertEquals(1f, PEEK.fraction, 0f)
        assertEquals(0.25f, PeekRules.SETTLE_SHARE, 0f)
    }
}
