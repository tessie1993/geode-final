package dev.geode.visuals

import dev.geode.analysis.BeatTuning
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.Defaults
import dev.geode.feature.customization.core.Edits
import dev.geode.feature.customization.core.NativePlan
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.render.bridge.SceneParamsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snapshot `:app` hands the engine for a Customization (#17): the plan, and what Settings add to it. */
class EngineSnapshotsTest {
    private val customization = Defaults.customization(BuiltInStyles.catalogue.manifestOf(BuiltInStyles.all[0].id))

    @Test
    fun `the plan is the Customization itself`() {
        val zoomed = Edits.setShared(customization, SharedProperty.ZOOM, SharedProperty.ZOOM.max)
        val snapshot = EngineSnapshots.of(zoomed, null)
        assertEquals(NativePlan.encode(zoomed), snapshot.plan)
        assertNotEquals(EngineSnapshots.of(customization, null).plan, snapshot.plan)
    }

    @Test
    fun `a held Style switching is held in the plan`() {
        val held = EngineSnapshots.of(customization, null, switchingHeld = true)
        assertEquals(NativePlan.encode(customization, switchingHeld = true), held.plan)
    }

    @Test
    fun `reduce motion and the beat detection come from Settings`() {
        val settings =
            SettingsValues(
                behavior = BehaviorSettings(reducedMotion = true),
                analysis = AnalysisSettings(beatSensitivity = 2f, beatMinIntervalMs = 400f),
            )
        val snapshot = EngineSnapshots.of(customization, settings)
        assertTrue(snapshot.reducedMotion)
        assertEquals(BeatTuning.clampSensitivity(2f), snapshot.soundSensitivity)
        assertEquals(400f, snapshot.soundRefractoryMs)
    }

    @Test
    fun `a beat gap shorter than the flash limit reaches the detection as chosen, within Settings' range`() {
        fun gapFor(ms: Float): Float {
            val settings = SettingsValues(analysis = AnalysisSettings(beatMinIntervalMs = ms))
            return EngineSnapshots.of(customization, settings).soundRefractoryMs
        }
        assertEquals(BeatTuning.INTERVAL_MS_MIN, gapFor(BeatTuning.INTERVAL_MS_MIN))
        assertEquals(250f, gapFor(250f))
        assertEquals(BeatTuning.INTERVAL_MS_MIN, gapFor(50f))
        assertEquals(BeatTuning.INTERVAL_MS_MAX, gapFor(5_000f))
    }

    @Test
    fun `before Settings load their defaults apply`() {
        val snapshot = EngineSnapshots.of(customization, null)
        assertFalse(snapshot.reducedMotion)
        assertEquals(AnalysisSettings().beatMinIntervalMs, snapshot.soundRefractoryMs)
        assertEquals(BeatTuning.clampSensitivity(AnalysisSettings().beatSensitivity), snapshot.soundSensitivity)
        assertEquals(0, snapshot.revision)
    }

    @Test
    fun `a Render's engine gets the same plan and Settings as live, with the Fluid quality pinned`() {
        val zoomed = Edits.setShared(customization, SharedProperty.ZOOM, SharedProperty.ZOOM.max)
        val settings = SettingsValues(behavior = BehaviorSettings(reducedMotion = true))
        val live = EngineSnapshots.of(zoomed, settings)
        val render = EngineSnapshots.forRender(zoomed, settings)
        assertEquals(live.plan, render.plan)
        assertEquals(live.reducedMotion, render.reducedMotion)
        assertEquals(live.soundSensitivity, render.soundSensitivity)
        assertEquals(live.soundRefractoryMs, render.soundRefractoryMs)
        val autoQuality = SceneParamsCodec.NAMES.indexOf("fluidAutoQuality")
        assertEquals(1f, live.params[autoQuality])
        assertEquals(0f, render.params[autoQuality])
        // Live keeps the Medium tier it adapts from; a Render runs at Ultra, the finest (#345).
        val fluidQuality = SceneParamsCodec.NAMES.indexOf("fluidQuality")
        assertEquals(2f, live.params[fluidQuality])
        assertEquals(0f, render.params[fluidQuality])
    }
}
