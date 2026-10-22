package dev.geode.feature.settings.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Visual, the floating assets and Renders take from the CPU/GPU settings (#28). */
class PerformanceSettingsTest {
    @Test
    fun `out of the box the Visual is drawn at full quality and at the screen's maximum`() {
        val defaults = PerformanceSettings()

        assertEquals(VisualQuality.HIGH, defaults.visual)
        assertEquals(VisualFrameRate.SCREEN_MAX, defaults.frameRate)
        assertFalse(defaults.floatingAssetsHeldStill)
        assertFalse(defaults.rendersSlowed)
    }

    @Test
    fun `without save mode the chosen quality and frame rate are used`() {
        val chosen = PerformanceSettings(visualQuality = VisualQuality.BALANCED, visualFrameRate = VisualFrameRate.FPS60)

        assertEquals(VisualQuality.BALANCED, chosen.visual)
        assertEquals(VisualFrameRate.FPS60, chosen.frameRate)
    }

    @Test
    fun `save mode lowers the quality, caps the frame rate at 30, holds the assets still and slows renders`() {
        val saving = PerformanceSettings(VisualQuality.HIGH, VisualFrameRate.SCREEN_MAX, saveMode = true)

        assertEquals(VisualQuality.SAVER, saving.visual)
        assertEquals(VisualFrameRate.FPS30, saving.frameRate)
        assertTrue(saving.floatingAssetsHeldStill)
        assertTrue(saving.rendersSlowed)
    }

    @Test
    fun `save mode keeps the chosen values, so turning it off brings them back`() {
        val saving = PerformanceSettings(VisualQuality.BALANCED, VisualFrameRate.FPS60, saveMode = true)

        assertEquals(VisualQuality.BALANCED, saving.copy(saveMode = false).visual)
        assertEquals(VisualFrameRate.FPS60, saving.copy(saveMode = false).frameRate)
    }

    @Test
    fun `each quality is a render scale ceiling, and each frame rate a cap or none`() {
        assertEquals(listOf(1f, 0.85f, 0.7f), VisualQuality.entries.map { it.renderScale })
        assertEquals(listOf(30, 60), VisualFrameRate.entries.mapNotNull { it.fps })
        assertNull(VisualFrameRate.SCREEN_MAX.fps)
    }

    @Test
    fun `the background switches start as the owner chose`() {
        assertEquals(BackgroundSettings(music = true, renders = true, recording = false), BackgroundSettings())
        assertTrue(ExportSettings().finishedAlerts)
        assertEquals(ExportQuality.FHD1080, ExportSettings().quality)
    }
}
