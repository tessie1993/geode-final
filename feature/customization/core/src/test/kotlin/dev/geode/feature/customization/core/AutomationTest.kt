package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.Autopilot
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.Sequenced
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.Fixtures.JULIA
import dev.geode.feature.customization.core.Fixtures.TUNNEL
import dev.geode.feature.customization.core.Fixtures.julia
import dev.geode.feature.customization.core.Fixtures.styles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutomationTest {
    private val delta = 1e-5f
    private val a = SceneId("a")
    private val b = SceneId("b")
    private val c = SceneId("c")
    private val picks = listOf(a, b, c)
    private val zoom = Target.Shared(SharedProperty.ZOOM)

    @Test
    fun `a cadence acts on big moments or every so many bars, never at the very start`() {
        assertTrue(Automation.due(Cadence.BigMoments, 3, bigMoment = true))
        assertFalse(Automation.due(Cadence.BigMoments, 8, bigMoment = false))
        val everyTwo = Cadence.EveryBars(2)
        assertFalse(Automation.due(everyTwo, 0, bigMoment = true))
        assertFalse(Automation.due(everyTwo, 4, bigMoment = false))
        assertTrue(Automation.due(everyTwo, 8, bigMoment = false))
        assertTrue(Automation.due(everyTwo, 16, bigMoment = false))
        assertFalse(Automation.due(everyTwo, 12, bigMoment = true))
    }

    @Test
    fun `Style switching takes the next Style of its pool, wrapping round`() {
        val mine = StyleSwitching(pool = StylePool.MINE, mine = listOf(c, a))
        assertEquals(a, Automation.nextStyle(mine, c, picks))
        assertEquals(c, Automation.nextStyle(mine, a, picks))
        assertEquals(c, Automation.nextStyle(mine, b, picks))
        assertEquals(b, Automation.nextStyle(mine.copy(pool = StylePool.PICKS), a, picks))
        assertEquals(a, Automation.nextStyle(mine.copy(pool = StylePool.PICKS), c, picks))
        val both = mine.copy(pool = StylePool.BOTH)
        assertEquals(a, Automation.nextStyle(both, c, picks))
        assertEquals(b, Automation.nextStyle(both, a, picks))
        assertEquals(c, Automation.nextStyle(both, b, picks))
    }

    @Test
    fun `Style switching skips Styles this version does not know, and stays when there is nowhere to go`() {
        val mine = StyleSwitching(pool = StylePool.MINE, mine = listOf(SceneId("gone"), b))
        assertEquals(b, Automation.nextStyle(mine, a, picks))
        assertNull(Automation.nextStyle(mine, b, picks))
        assertNull(Automation.nextStyle(mine.copy(mine = emptyList()), a, picks))
        assertNull(Automation.nextStyle(StyleSwitching(pool = StylePool.PICKS), a, listOf(a)))
    }

    @Test
    fun `the crossfade is a smooth curve from the old Style to the new`() {
        val length = Millis(1_000)
        assertEquals(0f, Automation.crossfade(Millis.ZERO, length), delta)
        assertEquals(0.5f, Automation.crossfade(Millis(500), length), delta)
        assertEquals(0.15625f, Automation.crossfade(Millis(250), length), delta)
        assertEquals(1f, Automation.crossfade(length, length), delta)
        assertEquals(1f, Automation.crossfade(Millis(5_000), length), delta)
        assertEquals(0f, Automation.crossfade(Millis(-5), length), delta)
        assertEquals(1f, Automation.crossfade(Millis(10), Millis.ZERO), delta)
    }

    @Test
    fun `the shortest Style transition keeps the No-flash rule`() {
        // A full swing of brightness may take no less than the No-flash rise: per millisecond, at most 1 / 150.
        val limit = 1f / NO_FLASH_MIN_RISE.value
        val length = StyleSwitching.MIN_TRANSITION
        val steepest =
            (1..length.value).maxOf { ms ->
                abs(Automation.crossfade(Millis(ms), length) - Automation.crossfade(Millis(ms - 1), length))
            }
        assertTrue("steepest step $steepest", steepest <= limit)
        assertTrue(steepest > 0f)
    }

    @Test
    fun `palette changes move to the next palette and wrap round`() {
        assertEquals(4f, Automation.nextPalette(3f), delta)
        assertEquals(0f, Automation.nextPalette(SharedProperty.PALETTE.max), delta)
    }

    @Test
    fun `a Camera move pushes on its beat and eases back`() {
        val move = CameraMove(SharedProperty.ZOOM, amount = 0.4f, beats = 2)
        assertEquals(0.7f, Automation.camera(0.3f, move, 4, 0f), delta)
        assertEquals(0.4f, Automation.camera(0.3f, move, 4, 0.5f), delta)
        assertEquals(0.3f, Automation.camera(0.3f, move, 4, 1f), delta)
        assertEquals(0.3f, Automation.camera(0.3f, move, 3, 0f), delta)
        assertEquals(1f, Automation.camera(0.9f, move, 0, 0f), delta)
        assertEquals(0.7f, Automation.camera(0.3f, move, 2, -1f), delta)
    }

    @Test
    fun `a sequence holds each step for its beats and loops`() {
        val sequenced = Sequenced(zoom, listOf(0f, 0.5f, 1f), beats = 2)
        assertEquals(listOf(0f, 0f, 0.5f, 0.5f, 1f, 1f, 0f), (0..6).map { Automation.step(sequenced, it) })
    }

    @Test
    fun `an autopilot swings around the value the person set`() {
        val pilot = Autopilot(0, width = 0.2f, bars = 8)
        assertEquals(0.5f, Automation.autopilot(0.5f, pilot, 0f), delta)
        assertEquals(0.7f, Automation.autopilot(0.5f, pilot, 2f), delta)
        assertEquals(0.5f, Automation.autopilot(0.5f, pilot, 4f), delta)
        assertEquals(0.3f, Automation.autopilot(0.5f, pilot, 6f), delta)
        assertEquals(1f, Automation.autopilot(0.9f, pilot, 2f), delta)
        assertEquals(0f, Automation.autopilot(0.1f, pilot, 6f), delta)
    }

    @Test
    fun `the rules bring every Automation into range`() {
        val wild =
            Automations(
                style = StyleSwitching(Cadence.EveryBars(0), StylePool.MINE, listOf(a, a, b), Millis(10)),
                palette = PaletteChanges(Cadence.EveryBars(500)),
                camera = listOf(CameraMove(SharedProperty.ORBIT_YAW, amount = 3f, beats = 0), CameraMove(SharedProperty.SHAKE, -1f, 99)),
                sequences = listOf(Sequenced(zoom, listOf(-1f, 0.5f, 2f), beats = 40)),
                autopilots = listOf(Autopilot(1, width = 2f, bars = 0), Autopilot(2, width = -1f, bars = 100)),
            )
        val expected =
            Automations(
                style = StyleSwitching(Cadence.EveryBars(1), StylePool.MINE, listOf(a, b), StyleSwitching.MIN_TRANSITION),
                palette = PaletteChanges(Cadence.EveryBars(MAX_AUTOMATION_BARS)),
                camera = listOf(CameraMove(SharedProperty.ORBIT_YAW, 1f, 1), CameraMove(SharedProperty.SHAKE, 0f, MAX_AUTOMATION_BEATS)),
                sequences = listOf(Sequenced(zoom, listOf(0f, 0.5f, 1f), MAX_AUTOMATION_BEATS)),
                autopilots = listOf(Autopilot(1, Autopilot.MAX_WIDTH, 1), Autopilot(2, 0f, MAX_AUTOMATION_BARS)),
            )
        assertEquals(expected, Rules.automations(julia, wild))
        assertEquals(
            StyleSwitching.MAX_TRANSITION,
            Rules.automations(julia, Automations(style = StyleSwitching(transition = Millis(60_000)))).style?.transition,
        )
        assertEquals(0f, Rules.automations(julia, Automations(autopilots = listOf(Autopilot(0, Float.NaN)))).autopilots.single().width)
    }

    @Test
    fun `the rules keep sensible Automations as they are`() {
        val fine =
            Automations(
                style = StyleSwitching(),
                palette = PaletteChanges(),
                camera = listOf(CameraMove(SharedProperty.ZOOM)),
                sequences = listOf(Sequenced(zoom, listOf(1f, 0f))),
                autopilots = listOf(Autopilot(5)),
            )
        assertEquals(fine, Rules.automations(julia, fine))
        assertEquals(Automations.NONE, Rules.automations(julia, Automations.NONE))
    }

    @Test
    fun `the rules drop what cannot run`() {
        val nowhere =
            Automations(
                camera = listOf(CameraMove(SharedProperty.GLOW), CameraMove(SharedProperty.ZOOM), CameraMove(SharedProperty.ZOOM, 0.9f)),
                sequences =
                    listOf(
                        Sequenced(zoom, emptyList()),
                        Sequenced(Target.Extra(ExtraKind.FLIGHT), listOf(1f)),
                        Sequenced(Target.ModulatorField(ModulatorId(7), ModulatorParameter.DEPTH), listOf(1f)),
                        Sequenced(zoom, listOf(0.5f)),
                        Sequenced(zoom, listOf(0.25f)),
                    ),
                autopilots = listOf(Autopilot(-1), Autopilot(6), Autopilot(3), Autopilot(3, 0.1f)),
            )
        val kept = Rules.automations(julia, nowhere)
        assertEquals(listOf(CameraMove(SharedProperty.ZOOM)), kept.camera)
        assertEquals(listOf(Sequenced(zoom, listOf(0.5f))), kept.sequences)
        assertEquals(listOf(Autopilot(3)), kept.autopilots)
    }

    @Test
    fun `a sequence on a Modulator goes with the Modulator, and Automations carry over to the next Style`() {
        val lfo = Modulator(ModulatorId(0), ModulatorShape.Lfo(Wave.SINE, Millis(2_000)))
        val withLfo = Edits.addModulator(julia, lfo)
        val depth = Target.ModulatorField(withLfo.modulators.single().id, ModulatorParameter.DEPTH)
        val automations =
            Automations(
                style = StyleSwitching(),
                sequences = listOf(Sequenced(depth, listOf(1f, 0f)), Sequenced(zoom, listOf(1f, 0f))),
            )
        val automated = Edits.setAutomations(withLfo, automations)
        assertEquals(automations, automated.automations)
        val zoomOnly = automations.copy(sequences = listOf(Sequenced(zoom, listOf(1f, 0f))))
        assertEquals(zoomOnly, Edits.removeModulator(automated, withLfo.modulators.single().id).automations)
        assertEquals(zoomOnly, Edits.switchStyle(automated, styles.manifestOf(JULIA), styles.manifestOf(TUNNEL)).automations)
    }
}
