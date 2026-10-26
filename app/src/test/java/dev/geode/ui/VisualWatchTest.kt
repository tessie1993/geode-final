package dev.geode.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualWatchTest {
    @Test
    fun `the Visual is shown full screen or on the Visuals tab, and nowhere else`() {
        assertTrue(VisualWatch.shown(onVisualsTab = true, fullScreen = false))
        assertTrue(VisualWatch.shown(onVisualsTab = false, fullScreen = true))
        assertTrue(VisualWatch.shown(onVisualsTab = true, fullScreen = true))
        assertFalse(VisualWatch.shown(onVisualsTab = false, fullScreen = false))
    }

    @Test
    fun `the analysis runs only while the Visual is shown and the app is started`() {
        assertTrue(VisualWatch.watching(shown = true, started = true))
        assertFalse(VisualWatch.watching(shown = true, started = false))
        assertFalse(VisualWatch.watching(shown = false, started = true))
        assertFalse(VisualWatch.watching(shown = false, started = false))
    }
}
