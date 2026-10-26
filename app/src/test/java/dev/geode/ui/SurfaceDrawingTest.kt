package dev.geode.ui

import dev.geode.feature.settings.api.PerformanceSettings
import dev.geode.feature.settings.api.VisualFrameRate
import dev.geode.feature.settings.api.VisualQuality
import dev.geode.feature.settings.api.VisualSurface
import dev.geode.render.FrameRatePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each surface hands the engine from Settings' quality and frame rate (#394). */
class SurfaceDrawingTest {
    @Test
    fun `a frame rate is a cap, and the screen's maximum lets the pacer run at the panel's rate`() {
        assertEquals(FrameRatePolicy.Capped(30f), VisualFrameRate.FPS30.policy())
        assertEquals(FrameRatePolicy.Capped(60f), VisualFrameRate.FPS60.policy())
        assertEquals(FrameRatePolicy.Native, VisualFrameRate.SCREEN_MAX.policy())
    }

    @Test
    fun `a surface with its own pick draws at it while the app keeps the shared one`() {
        val performance =
            PerformanceSettings(
                visualQuality = VisualQuality.HIGH,
                visualFrameRate = VisualFrameRate.FPS60,
                surfaceQuality = mapOf(VisualSurface.WALLPAPER to VisualQuality.SAVER),
                surfaceFrameRate = mapOf(VisualSurface.WALLPAPER to VisualFrameRate.FPS30),
            )

        assertEquals(SurfaceDrawing(0.7f, FrameRatePolicy.Capped(30f)), SurfaceDrawing.of(performance, VisualSurface.WALLPAPER))
        assertEquals(SurfaceDrawing(1f, FrameRatePolicy.Capped(60f)), SurfaceDrawing.of(performance, VisualSurface.VISUAL))
    }

    @Test
    fun `out of the box every surface paces at 60, and a chosen Screen max runs at the panel's rate`() {
        assertEquals(SurfaceDrawing(1f, FrameRatePolicy.Capped(60f)), SurfaceDrawing.of(PerformanceSettings(), VisualSurface.WALLPAPER))
        val screenMax = PerformanceSettings(visualFrameRate = VisualFrameRate.SCREEN_MAX)
        assertEquals(SurfaceDrawing(1f, FrameRatePolicy.Native), SurfaceDrawing.of(screenMax, VisualSurface.VISUAL))
    }

    @Test
    fun `save mode draws every surface at Saver quality and 30 fps`() {
        val saving = PerformanceSettings(surfaceQuality = mapOf(VisualSurface.PICTURE_IN_PICTURE to VisualQuality.HIGH), saveMode = true)

        assertEquals(SurfaceDrawing(0.7f, FrameRatePolicy.Capped(30f)), SurfaceDrawing.of(saving, VisualSurface.PICTURE_IN_PICTURE))
    }

    @Test
    fun `each quality's tier below is the next one down, and Saver stays Saver`() {
        assertEquals(VisualQuality.BALANCED, VisualQuality.HIGH.tierLower())
        assertEquals(VisualQuality.SAVER, VisualQuality.BALANCED.tierLower())
        assertEquals(VisualQuality.SAVER, VisualQuality.SAVER.tierLower())
    }

    @Test
    fun `out of the box the screen saver draws a tier below the live wallpaper at its frame rate`() {
        assertEquals(SurfaceDrawing(0.85f, FrameRatePolicy.Capped(60f)), SurfaceDrawing.forScreenSaver(PerformanceSettings()))
        val gentleWallpaper =
            PerformanceSettings(
                visualQuality = VisualQuality.HIGH,
                visualFrameRate = VisualFrameRate.SCREEN_MAX,
                surfaceQuality = mapOf(VisualSurface.WALLPAPER to VisualQuality.BALANCED),
                surfaceFrameRate = mapOf(VisualSurface.WALLPAPER to VisualFrameRate.FPS30),
            )

        assertEquals(SurfaceDrawing(0.7f, FrameRatePolicy.Capped(30f)), SurfaceDrawing.forScreenSaver(gentleWallpaper))
    }

    @Test
    fun `the screen saver's own picks win over the wallpaper's`() {
        val picked =
            PerformanceSettings(
                visualQuality = VisualQuality.SAVER,
                visualFrameRate = VisualFrameRate.FPS30,
                surfaceQuality = mapOf(VisualSurface.SCREEN_SAVER to VisualQuality.HIGH),
                surfaceFrameRate = mapOf(VisualSurface.SCREEN_SAVER to VisualFrameRate.SCREEN_MAX),
            )

        assertEquals(SurfaceDrawing(1f, FrameRatePolicy.Native), SurfaceDrawing.forScreenSaver(picked))
    }

    @Test
    fun `save mode holds the screen saver to Saver and 30 fps, whatever it picked`() {
        val saving =
            PerformanceSettings(
                saveMode = true,
                surfaceQuality = mapOf(VisualSurface.SCREEN_SAVER to VisualQuality.HIGH),
                surfaceFrameRate = mapOf(VisualSurface.SCREEN_SAVER to VisualFrameRate.SCREEN_MAX),
            )

        assertEquals(SurfaceDrawing(0.7f, FrameRatePolicy.Capped(30f)), SurfaceDrawing.forScreenSaver(saving))
        assertEquals(SurfaceDrawing(0.7f, FrameRatePolicy.Capped(30f)), SurfaceDrawing.forScreenSaver(PerformanceSettings(saveMode = true)))
    }
}
