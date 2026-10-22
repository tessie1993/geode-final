package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.Autopilot
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.Sequenced
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationActionsTest {
    private val trails = Target.Shared(SharedProperty.TRAILS)

    @Test
    fun `Style switching and palette changes turn on from their defaults and keep their settings`() {
        val on = Automations.NONE.switchingStyle(true)
        assertEquals(StyleSwitching(), on.style)
        val tuned = on.copy(style = StyleSwitching(Cadence.EveryBars(4)))
        assertEquals(tuned, tuned.switchingStyle(true))
        assertEquals(null, tuned.switchingStyle(false).style)
        assertEquals(PaletteChanges(), Automations.NONE.changingPalette(true).palette)
        val palette = Automations(palette = PaletteChanges(Cadence.BigMoments))
        assertEquals(palette, palette.changingPalette(true))
        assertEquals(null, palette.changingPalette(false).palette)
    }

    @Test
    fun `the person's own list and the transition`() {
        val warp = SceneId("warp")
        val listed = StyleSwitching().toggled(warp)
        assertEquals(listOf(warp), listed.mine)
        assertEquals(emptyList<SceneId>(), listed.toggled(warp).mine)
        assertEquals(Millis(2_500), StyleSwitching().transitionOf(2.5f).transition)
        assertEquals(3f, StyleSwitching().seconds, 0f)
    }

    @Test
    fun `Camera moves, sequences and autopilots toggle one at a time`() {
        val zoom = Automations.NONE.toggledCamera(SharedProperty.ZOOM)
        assertEquals(listOf(CameraMove(SharedProperty.ZOOM)), zoom.camera)
        assertEquals(emptyList<CameraMove>(), zoom.toggledCamera(SharedProperty.ZOOM).camera)
        assertEquals(2, zoom.toggledCamera(SharedProperty.SHAKE).camera.size)
        val sequenced = Automations.NONE.toggledSequence(trails)
        assertEquals(listOf(Sequenced(trails, NEW_SEQUENCE_STEPS)), sequenced.sequences)
        assertEquals(emptyList<Sequenced>(), sequenced.toggledSequence(trails).sequences)
        val piloted = Automations.NONE.toggledAutopilot(2)
        assertEquals(listOf(Autopilot(2)), piloted.autopilots)
        assertEquals(emptyList<Autopilot>(), piloted.toggledAutopilot(2).autopilots)
    }

    @Test
    fun `only effects that keep brightness steady can be sequenced, and every Modulator's depth`() {
        val targets = PreviewModels.editing.sequenceTargets()
        assertEquals(Target.Shared(SharedProperty.KALEIDOSCOPE), targets.first())
        assertTrue(trails in targets)
        assertFalse(Target.Shared(SharedProperty.GLITCH) in targets)
        assertFalse(Target.Shared(SharedProperty.INVERT) in targets)
        assertFalse(Target.Shared(SharedProperty.SOLARIZE) in targets)
        assertEquals(
            (1..4).map { Target.ModulatorField(ModulatorId(it), ModulatorParameter.DEPTH) },
            targets.filterIsInstance<Target.ModulatorField>(),
        )
    }
}
