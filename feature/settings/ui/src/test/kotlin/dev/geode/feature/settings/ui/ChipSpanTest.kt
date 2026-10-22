package dev.geode.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The chosen chip in a scrolling row is always shown whole, with the row moving as little as it can. */
class ChipSpanTest {
    private val viewport = 400

    @Test
    fun `a chip already in view leaves the row where it is`() {
        assertEquals(50, ChipSpan(start = 100, width = 120).scrollToShow(current = 50, viewport = viewport))
    }

    @Test
    fun `a chip past the right edge scrolls just far enough to show its end`() {
        assertEquals(220, ChipSpan(start = 500, width = 120).scrollToShow(current = 0, viewport = viewport))
    }

    @Test
    fun `a chip before the left edge scrolls back to its start`() {
        assertEquals(40, ChipSpan(start = 40, width = 120).scrollToShow(current = 300, viewport = viewport))
    }

    @Test
    fun `a chip ending exactly at the right edge counts as in view`() {
        assertEquals(0, ChipSpan(start = 280, width = 120).scrollToShow(current = 0, viewport = viewport))
    }
}
