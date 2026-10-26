package dev.geode.ui

import android.view.Surface
import dev.geode.feature.settings.api.PerformanceSettings
import dev.geode.feature.settings.api.VisualFrameRate
import dev.geode.feature.settings.api.VisualQuality
import dev.geode.feature.settings.api.VisualSurface
import dev.geode.render.FramePacer
import dev.geode.render.FrameRatePolicy
import dev.geode.render.VisualizerRenderer

/**
 * How the engine draws the Visual on one surface (#394): the render scale ceiling Settings' quality
 * asks for, and the pacer policy its frame rate asks for. The thermal tiers and frame cap still only
 * lower both.
 */
internal data class SurfaceDrawing(
    val qualityCeiling: Float,
    val policy: FrameRatePolicy,
) {
    /** Hands both to a renderer and its pacer; call it on the pacer's Looper thread. */
    fun applyTo(
        renderer: VisualizerRenderer,
        pacer: FramePacer,
        surface: Surface?,
    ) {
        renderer.qualityCeiling = qualityCeiling
        if (pacer.policy != policy) {
            pacer.policy = policy
            surface?.let(pacer::applyTo)
        }
    }

    companion object {
        /** What [surface] draws at under [performance], save mode included. */
        fun of(
            performance: PerformanceSettings,
            surface: VisualSurface,
        ): SurfaceDrawing = SurfaceDrawing(performance.visualFor(surface).renderScale, performance.frameRateFor(surface).policy())

        /**
         * What the screen saver draws at (#406): its own quality and frame rate once the person picks
         * them, and until then the live wallpaper's, a quality tier lower. Save mode holds it to Saver
         * and 30 fps as it does every surface.
         */
        fun forScreenSaver(performance: PerformanceSettings): SurfaceDrawing {
            val quality =
                if (VisualSurface.SCREEN_SAVER in performance.surfaceQuality) {
                    performance.visualFor(VisualSurface.SCREEN_SAVER)
                } else {
                    performance.visualFor(VisualSurface.WALLPAPER).tierLower()
                }
            val frameRate =
                if (VisualSurface.SCREEN_SAVER in performance.surfaceFrameRate) {
                    performance.frameRateFor(VisualSurface.SCREEN_SAVER)
                } else {
                    performance.frameRateFor(VisualSurface.WALLPAPER)
                }
            return SurfaceDrawing(quality.renderScale, frameRate.policy())
        }
    }
}

/** The next quality down; Saver is already the lowest. */
internal fun VisualQuality.tierLower(): VisualQuality = VisualQuality.entries.getOrElse(ordinal + 1) { this }

/** A frame rate as the pacer takes it: a cap, or the screen's own rate when there is none. */
internal fun VisualFrameRate.policy(): FrameRatePolicy = fps?.let { FrameRatePolicy.Capped(it.toFloat()) } ?: FrameRatePolicy.Native
