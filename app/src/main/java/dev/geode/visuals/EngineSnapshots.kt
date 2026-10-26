package dev.geode.visuals

import dev.geode.analysis.AnalysisEngine
import dev.geode.analysis.BeatTuning
import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.core.NativePlan
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.render.VisualSafety
import dev.geode.render.bridge.SceneParamsCodec
import dev.geode.render.scene.SceneParams

/**
 * The snapshot the engine is handed for a Customization (ADR-0002, ADR-0004): its `plan` is the
 * Customization itself, encoded by [NativePlan], and the engine's Normaliser maps each value into the
 * Style's own range. What the snapshot carries beside the plan comes from Settings: the reduce-motion
 * switch and the beat detection's tuning. The drawing path the plan does not drive yet gets the
 * Style's defaults, and no old app-wide LFO or envelope slot is used any more.
 */
object EngineSnapshots {
    private val defaultParams: List<Float> = packed(SceneParams.DEFAULT)

    // A Render's Fluid quality never adapts downward: it renders as fast as the encoder takes frames,
    // so its frame time says nothing about the phone (the thermal half is the offscreen pin).
    private val renderParams: List<Float> = packed(SceneParams.DEFAULT.copy(fluidAutoQuality = false))

    private val noLfo: List<Float> = List(CustomizationSnapshot.LFO_COUNT) { 0f }

    private val noEnvelope: List<Float> = List(CustomizationSnapshot.ADSR_COUNT) { 0f }

    /** [customization] as the engine reads it, with [settings] when they have loaded, otherwise their defaults. */
    fun of(
        customization: Customization,
        settings: SettingsValues?,
    ): CustomizationSnapshot = snapshot(customization, settings, defaultParams)

    /**
     * [customization] as a Render hands it to its own engine (#337): the same plan as live, so the
     * Render draws everything the live Visual would, with only the quality pinned for offline work.
     */
    fun forRender(
        customization: Customization,
        settings: SettingsValues?,
    ): CustomizationSnapshot = snapshot(customization, settings, renderParams)

    private fun snapshot(
        customization: Customization,
        settings: SettingsValues?,
        params: List<Float>,
    ): CustomizationSnapshot {
        val values = settings ?: SettingsValues()
        return CustomizationSnapshot(
            revision = 0,
            reducedMotion = values.behavior.reducedMotion,
            params = params,
            lfo = noLfo,
            adsr = noEnvelope,
            plan = NativePlan.encode(customization),
            soundSensitivity = BeatTuning.clampSensitivity(values.analysis.beatSensitivity),
            soundRefractoryMs = VisualSafety.beatMinIntervalMs(values.analysis.beatMinIntervalMs),
            soundAttackSeconds = BeatTuning.envelopeSeconds(AnalysisEngine.DEFAULT_ATTACK),
            soundReleaseSeconds = BeatTuning.envelopeSeconds(AnalysisEngine.DEFAULT_DECAY),
        )
    }

    private fun packed(params: SceneParams): List<Float> =
        FloatArray(CustomizationSnapshot.PARAMS_COUNT).also { SceneParamsCodec.pack(params, it) }.toList()
}
