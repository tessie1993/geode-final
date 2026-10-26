package dev.geode.feature.settings.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Visual and Renders take from the CPU/GPU settings (#28). */
class PerformanceSettingsTest {
    @Test
    fun `out of the box the Visual is drawn at full quality and at 60 fps`() {
        val defaults = PerformanceSettings()

        assertEquals(VisualQuality.HIGH, defaults.visual)
        assertEquals(VisualFrameRate.FPS60, defaults.frameRate)
        assertFalse(defaults.rendersSlowed)
    }

    @Test
    fun `without save mode the chosen quality and frame rate are used`() {
        val chosen = PerformanceSettings(visualQuality = VisualQuality.BALANCED, visualFrameRate = VisualFrameRate.SCREEN_MAX)

        assertEquals(VisualQuality.BALANCED, chosen.visual)
        assertEquals(VisualFrameRate.SCREEN_MAX, chosen.frameRate)
    }

    @Test
    fun `save mode lowers the quality, caps the frame rate at 30 and slows renders`() {
        val saving = PerformanceSettings(VisualQuality.HIGH, VisualFrameRate.SCREEN_MAX, saveMode = true)

        assertEquals(VisualQuality.SAVER, saving.visual)
        assertEquals(VisualFrameRate.FPS30, saving.frameRate)
        assertTrue(saving.rendersSlowed)
    }

    @Test
    fun `save mode keeps the chosen values, so turning it off brings them back`() {
        val saving = PerformanceSettings(VisualQuality.BALANCED, VisualFrameRate.FPS60, saveMode = true)

        assertEquals(VisualQuality.BALANCED, saving.copy(saveMode = false).visual)
        assertEquals(VisualFrameRate.FPS60, saving.copy(saveMode = false).frameRate)
    }

    @Test
    fun `every surface follows the shared quality and frame rate until it has its own`() {
        val shared = PerformanceSettings(VisualQuality.BALANCED, VisualFrameRate.FPS60)

        for (surface in VisualSurface.entries) {
            assertEquals(VisualQuality.BALANCED, shared.visualFor(surface))
            assertEquals(VisualFrameRate.FPS60, shared.frameRateFor(surface))
        }
    }

    @Test
    fun `a surface with its own pick draws at it, and the others keep the shared one`() {
        val gentleWallpaper =
            PerformanceSettings(
                visualQuality = VisualQuality.HIGH,
                visualFrameRate = VisualFrameRate.SCREEN_MAX,
                surfaceQuality = mapOf(VisualSurface.WALLPAPER to VisualQuality.SAVER),
                surfaceFrameRate = mapOf(VisualSurface.WALLPAPER to VisualFrameRate.FPS30),
            )

        assertEquals(VisualQuality.SAVER, gentleWallpaper.visualFor(VisualSurface.WALLPAPER))
        assertEquals(VisualFrameRate.FPS30, gentleWallpaper.frameRateFor(VisualSurface.WALLPAPER))
        assertEquals(VisualQuality.HIGH, gentleWallpaper.visualFor(VisualSurface.VISUAL))
        assertEquals(VisualFrameRate.SCREEN_MAX, gentleWallpaper.frameRateFor(VisualSurface.VISUAL))
        assertEquals(VisualQuality.HIGH, gentleWallpaper.visual)
        assertEquals(VisualFrameRate.SCREEN_MAX, gentleWallpaper.frameRate)
    }

    @Test
    fun `save mode holds every surface to Saver and 30 fps, whatever it picked`() {
        val saving =
            PerformanceSettings(
                saveMode = true,
                surfaceQuality = mapOf(VisualSurface.PICTURE_IN_PICTURE to VisualQuality.HIGH),
                surfaceFrameRate = mapOf(VisualSurface.PICTURE_IN_PICTURE to VisualFrameRate.SCREEN_MAX),
            )

        for (surface in VisualSurface.entries) {
            assertEquals(VisualQuality.SAVER, saving.visualFor(surface))
            assertEquals(VisualFrameRate.FPS30, saving.frameRateFor(surface))
        }
        assertEquals(VisualQuality.HIGH, saving.copy(saveMode = false).visualFor(VisualSurface.PICTURE_IN_PICTURE))
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
        // A saved Edit points at its sources unless the person asks for copies (#318).
        assertEquals(EditSources.REFERENCE, ExportSettings().editSources)
    }
}
