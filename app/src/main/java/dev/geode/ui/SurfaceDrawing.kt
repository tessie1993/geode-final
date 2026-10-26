package dev.geode.ui

import android.view.Surface
import dev.geode.feature.settings.api.PerformanceSettings
import dev.geode.feature.settings.api.VisualFrameRate
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
    }
}

/** A frame rate as the pacer takes it: a cap, or the screen's own rate when there is none. */
internal fun VisualFrameRate.policy(): FrameRatePolicy = fps?.let { FrameRatePolicy.Capped(it.toFloat()) } ?: FrameRatePolicy.Native
