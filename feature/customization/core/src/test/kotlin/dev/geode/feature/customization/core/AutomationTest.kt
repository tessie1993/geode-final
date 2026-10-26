package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.BeatSpan
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.CameraMoveKind
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Lane
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StepLength
import dev.geode.feature.customization.api.StyleMark
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Trigger
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.Fixtures.JULIA
import dev.geode.feature.customization.core.Fixtures.TUNNEL
import dev.geode.feature.customization.core.Fixtures.julia
import dev.geode.feature.customization.core.Fixtures.styles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutomationTest {
    private val delta = 1e-5f
    private val a = SceneId("a")
    private val b = SceneId("b")
    private val c = SceneId("c")
    private val d = SceneId("d")
    private val builtIns = listOf(a, b, c, d)
    private val zoom = Target.Shared(SharedProperty.ZOOM)
    private val trails = Target.Shared(SharedProperty.TRAILS)
    private val orbit = Target.Extra(ExtraKind.ORBIT)

    @Test
    fun `a big moment fires once as it rises past its threshold`() {
        val big = Trigger.BigMoments(threshold = 0.7f)
        assertTrue(Automation.triggered(big, null, 0.7f, armed = true))
        assertTrue(Automation.triggered(big, 3, 1f, armed = true))
        assertFalse(Automation.triggered(big, null, 0.69f, armed = true))
        assertFalse(Automation.triggered(big, null, 1f, armed = false))
    }

    @Test
    fun `every so many bars fires on the reported beats only, never at the very start`() {
        val everyTwo = Trigger.EveryBars(2)
        assertFalse("no tempo reported yet", Automation.triggered(everyTwo, null, 1f, armed = true))
        assertFalse(Automation.triggered(everyTwo, 0, 0f, armed = true))
        assertFalse(Automation.triggered(everyTwo, 4, 0f, armed = true))
        assertFalse(Automation.triggered(everyTwo, 12, 1f, armed = true))
        assertTrue(Automation.triggered(everyTwo, 8, 0f, armed = false))
        assertTrue(Automation.triggered(everyTwo, 16, 0f, armed = true))
        assertFalse(Automation.triggered(Trigger.EveryBars(1), -4, 0f, armed = true))
    }

    @Test
    fun `the spacing holds the next move back`() {
        val cadence = Cadence(Trigger.BigMoments(0.5f), spacing = Millis(4_000))
        assertTrue(Automation.spaced(cadence, null))
        assertFalse(Automation.spaced(cadence, Millis(3_999)))
        assertTrue(Automation.spaced(cadence, Millis(4_000)))
        assertTrue(Automation.due(cadence, null, 0.9f, armed = true, sinceLast = Millis(5_000)))
        assertFalse(Automation.due(cadence, null, 0.9f, armed = true, sinceLast = Millis(1_000)))
        assertFalse(Automation.due(cadence, null, 0.1f, armed = true, sinceLast = null))
    }

    @Test
    fun `Style switching goes through the Visualizer styles in their order, round after round`() {
        val visualizer = StyleSwitching(pool = StylePool.VISUALIZER)
        val listed = listOf(c, SceneId("gone"), a, c)
        assertEquals(Automation.Pick(c, 1), Automation.nextStyle(visualizer, b, listed, builtIns, 0))
        assertEquals(Automation.Pick(a, 2), Automation.nextStyle(visualizer, c, listed, builtIns, 1))
        assertEquals(Automation.Pick(c, 3), Automation.nextStyle(visualizer, a, listed, builtIns, 2))
        assertEquals("the Style showing is passed over", Automation.Pick(a, 2), Automation.nextStyle(visualizer, c, listed, builtIns, 0))
        assertEquals(Automation.Pick(c, 1), Automation.nextStyle(visualizer, b, listed, builtIns, -3))
    }

    @Test
    fun `Style switching stays when there is nowhere to go`() {
        val visualizer = StyleSwitching(pool = StylePool.VISUALIZER)
        assertNull(Automation.nextStyle(visualizer, a, emptyList(), builtIns, 0))
        assertNull(Automation.nextStyle(visualizer, a, listOf(a), builtIns, 5))
        assertNull(Automation.nextStyle(StyleSwitching(pool = StylePool.PICKS), a, emptyList(), listOf(a), 0))
        assertNull(Automation.nextStyle(StyleSwitching(pool = StylePool.PICKS), a, emptyList(), emptyList(), 0))
    }

    @Test
    fun `the app's picks play every built-in Style once before any comes again`() {
        val picks = StyleSwitching(pool = StylePool.PICKS, seed = 7L)
        val many = (1..12).map { SceneId("s$it") }
        val first = (0 until 12).map { checkNotNull(Automation.nextStyle(picks, SceneId("none"), emptyList(), many, it)).style }
        val second = (12 until 24).map { checkNotNull(Automation.nextStyle(picks, SceneId("none"), emptyList(), many, it)).style }
        assertEquals(many.toSet(), first.toSet())
        assertEquals(many.toSet(), second.toSet())
        assertEquals(Automation.shuffled(many, 7L, 0), first)
        assertEquals(Automation.shuffled(many, 7L, 1), second)
    }

    @Test
    fun `the app's picks repeat exactly for the same seed and differ for another`() {
        val many = (1..12).map { SceneId("s$it") }
        assertEquals(Automation.shuffled(many, 3L, 2), Automation.shuffled(many, 3L, 2))
        assertNotEquals(Automation.shuffled(many, 3L, 0), Automation.shuffled(many, 4L, 0))
        assertNotEquals(Automation.shuffled(many, 3L, 0), Automation.shuffled(many, 3L, 1))
        assertEquals(many.toSet(), Automation.shuffled(many, 3L, 0).toSet())
    }

    @Test
    fun `both plays the Visualizer styles first, then the app's picks of the rest`() {
        val both = StyleSwitching(pool = StylePool.BOTH, seed = 11L)
        val played = (0 until 8).map { checkNotNull(Automation.nextStyle(both, SceneId("none"), listOf(d, b), builtIns, it)).style }
        assertEquals(listOf(d, b), played.take(2))
        assertEquals(setOf(a, c), played.subList(2, 4).toSet())
        assertEquals(listOf(d, b), played.subList(4, 6))
        assertEquals(Automation.shuffled(listOf(a, c), 11L, 0), played.subList(2, 4))
    }

    @Test
    fun `a crossfade is a smooth curve from the old to the new`() {
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
    fun `the shortest Style transition and palette fade keep the No-flash rule`() {
        // A full swing of brightness may take no less than the No-flash rise: per millisecond, at most 1 / 150.
        val limit = 1f / NO_FLASH_MIN_RISE.value
        listOf(StyleSwitching.MIN_TRANSITION, PaletteChanges.MIN_FADE).forEach { length ->
            val steepest =
                (1..length.value).maxOf { ms ->
                    abs(Automation.crossfade(Millis(ms), length) - Automation.crossfade(Millis(ms - 1), length))
                }
            assertTrue("steepest step $steepest", steepest <= limit)
            assertTrue(steepest > 0f)
        }
    }

    @Test
    fun `palette changes count on from the person's palette and wrap round`() {
        assertEquals(3, Automation.palette(3, 0))
        assertEquals(5, Automation.palette(3, 2))
        assertEquals(0, Automation.palette(SharedProperty.PALETTE.max.toInt(), 1))
        assertEquals(SharedProperty.PALETTE.max.toInt(), Automation.palette(0, -1))
    }

    @Test
    fun `a palette change crossfades into the new palette and never cuts`() {
        val fade = Millis(2_000)
        assertEquals(Automation.PaletteFrame(4, 4, 0f), Automation.paletteFrame(4, 0, Millis(100), fade))
        assertEquals(Automation.PaletteFrame(6, 6, 0f), Automation.paletteFrame(4, 2, null, fade))
        assertEquals(Automation.PaletteFrame(5, 6, 0f), Automation.paletteFrame(4, 2, Millis.ZERO, fade))
        assertEquals(Automation.PaletteFrame(5, 6, 0.5f), Automation.paletteFrame(4, 2, Millis(1_000), fade))
        assertEquals(Automation.PaletteFrame(6, 6, 0f), Automation.paletteFrame(4, 2, fade, fade))
        val nearlyDone = Automation.paletteFrame(4, 2, Millis(1_999), fade)
        assertEquals(6, nearlyDone.next)
        assertTrue(nearlyDone.mix > 0.99f)
    }

    @Test
    fun `a Camera move swells over its span and is still at both ends`() {
        val push = CameraMove(CameraMoveKind.PUSH_IN, BeatSpan.TWO, strength = 0.4f)
        val pushIn = SharedProperty.PUSH_IN
        assertEquals(0f, Automation.camera(push, 0.0).getValue(pushIn), delta)
        assertEquals(0.2f, Automation.camera(push, 0.5).getValue(pushIn), delta)
        assertEquals(0.4f, Automation.camera(push, 1.0).getValue(pushIn), delta)
        assertEquals(0f, Automation.camera(push, 2.0).getValue(pushIn), delta)
        assertEquals("not alternating", 0.4f, Automation.camera(push, 3.0).getValue(pushIn), delta)
        assertEquals(0f, Automation.camera(push, -1.0).getValue(pushIn), delta)
        assertEquals(0f, Automation.camera(push, Double.NaN).getValue(pushIn), delta)
    }

    @Test
    fun `an alternating Camera move swings the other way every other span`() {
        val orbitMove = CameraMove(CameraMoveKind.ORBIT, BeatSpan.ONE, strength = 0.5f)
        val yaw = SharedProperty.ORBIT_YAW
        assertEquals(0.5f, Automation.camera(orbitMove, 0.5).getValue(yaw), delta)
        assertEquals(-0.5f, Automation.camera(orbitMove, 1.5).getValue(yaw), delta)
        assertEquals(0.5f, Automation.camera(orbitMove, 2.5).getValue(yaw), delta)
        val pull = Automation.camera(CameraMove(CameraMoveKind.PULL_OUT, BeatSpan.FOUR, 1f), 2.0)
        assertEquals(mapOf(SharedProperty.ZOOM to -1f), pull)
        val curve = Automation.camera(CameraMove(CameraMoveKind.TRAVEL_CURVE, BeatSpan.FOUR, 0.2f), 6.0)
        assertEquals(-0.2f, curve.getValue(SharedProperty.DRIFT_X), delta)
        assertEquals("it keeps flying forward", 0.2f, curve.getValue(SharedProperty.TRAVEL), delta)
    }

    @Test
    fun `every Camera move eases over at least one beat`() {
        // From still to its full strength takes at least half a beat each way: sampled finely, no jump.
        val moves = CameraMoveKind.entries.flatMap { kind -> BeatSpan.entries.map { span -> CameraMove(kind, span, 1f) } }
        moves.forEach { move -> assertTrue(move.toString(), steepestStep(move) < 0.04f) }
        assertTrue(CameraMoveKind.entries.all { kind -> kind.moves.keys.all { it.group == SharedGroup.CAMERA } })
    }

    /** The largest change of any of [move]'s properties between samples a hundredth of a beat apart. */
    private fun steepestStep(move: CameraMove): Float =
        (0..800)
            .map { Automation.camera(move, it / 100.0) }
            .zipWithNext()
            .maxOf { (before, after) -> before.keys.maxOf { abs(after.getValue(it) - before.getValue(it)) } }

    @Test
    fun `a Camera move is added on top of the Camera setting and kept in range`() {
        assertEquals(0.7f, Automation.moved(0.5f, 0.2f), delta)
        assertEquals(1f, Automation.moved(0.9f, 0.4f), delta)
        assertEquals(0f, Automation.moved(0.1f, -0.4f), delta)
    }

    @Test
    fun `a lane holds every target at its step, stepped by beat or bar, and loops`() {
        val lane = Lane(listOf(zoom, trails), listOf(listOf(0f, 1f), listOf(0.5f, 0f), listOf(1f, 0.5f)), StepLength.BEAT)
        assertEquals(listOf(0f, 1f), Automation.step(lane, 0))
        assertEquals(listOf(0.5f, 0f), Automation.step(lane, 1))
        assertEquals(listOf(1f, 0.5f), Automation.step(lane, 2))
        assertEquals(listOf(0f, 1f), Automation.step(lane, 3))
        val barred = lane.copy(step = StepLength.BAR)
        assertEquals((0..12).map { lane.steps[it / 4 % 3] }, (0..12).map { Automation.step(barred, it) })
    }

    @Test
    fun `a Modulator linked to a Macro moves it around the value the person set`() {
        val drift = Reaction(ReactionId(1), ReactionSource.Generated(ModulatorId(2)), Target.MacroSlot(3), amount = 0.4f)
        assertEquals(0.5f, Evaluation.drift(0.5f, 0.5f, drift), delta)
        assertEquals(0.7f, Evaluation.drift(0.5f, 1f, drift), delta)
        assertEquals(0.3f, Evaluation.drift(0.5f, 0f, drift), delta)
        assertEquals(0.7f, Evaluation.drift(0.5f, 0f, drift.copy(invert = true)), delta)
        assertEquals(0.6f, Evaluation.drift(0.5f, 1f, drift.copy(high = 0.6f)), delta)
        assertEquals(0.45f, Evaluation.drift(0.5f, 0f, drift.copy(low = 0.45f)), delta)
        assertEquals(0.5f + (0.25f - 0.5f) * 0.4f, Evaluation.drift(0.5f, 0.5f, drift.copy(curve = ResponseCurve.EXPONENTIAL)), delta)
        assertTrue(Evaluation.drifts(drift))
        assertFalse(Evaluation.drifts(drift.copy(target = zoom)))
        assertFalse(Evaluation.drifts(drift.copy(source = ReactionSource.Sound(SoundSignal.BASS))))
    }

    @Test
    fun `the rules bring every Automation into range`() {
        val wild =
            Automations(
                style = StyleSwitching(Cadence(Trigger.EveryBars(0), Millis(-5)), StylePool.VISUALIZER, 4L, Millis(10)),
                palette = PaletteChanges(Cadence(Trigger.BigMoments(3f), Millis(900_000)), fade = Millis(1)),
                camera =
                    listOf(
                        CameraMove(CameraMoveKind.ORBIT, BeatSpan.FOUR, 3f),
                        CameraMove(CameraMoveKind.SHAKE, BeatSpan.ONE, -1f),
                        CameraMove(CameraMoveKind.ORBIT, BeatSpan.ONE, 0.5f),
                    ),
                lanes = listOf(Lane(listOf(zoom), listOf(listOf(-1f), listOf(0.5f), listOf(2f, 9f), emptyList()))),
            )
        val expected =
            Automations(
                style = StyleSwitching(Cadence(Trigger.EveryBars(1), Millis.ZERO), StylePool.VISUALIZER, 4L, StyleSwitching.MIN_TRANSITION),
                palette = PaletteChanges(Cadence(Trigger.BigMoments(1f), Cadence.MAX_SPACING), PaletteChanges.MIN_FADE),
                camera = listOf(CameraMove(CameraMoveKind.ORBIT, BeatSpan.FOUR, 1f), CameraMove(CameraMoveKind.SHAKE, BeatSpan.ONE, 0f)),
                lanes = listOf(Lane(listOf(zoom), listOf(listOf(0f), listOf(0.5f), listOf(1f), listOf(0f)))),
            )
        assertEquals(expected, Rules.automations(julia, wild))
        val longer = Automations(style = StyleSwitching(Cadence(Trigger.EveryBars(500))), palette = PaletteChanges(fade = Millis(60_000)))
        val tamed = Rules.automations(julia, longer.copy(style = longer.style?.copy(transition = Millis(60_000))))
        assertEquals(StyleSwitching.MAX_TRANSITION, tamed.style?.transition)
        assertEquals(Trigger.EveryBars(MAX_AUTOMATION_BARS), tamed.style?.cadence?.trigger)
        assertEquals(PaletteChanges.MAX_FADE, tamed.palette?.fade)
        val low = Rules.automations(julia, Automations(palette = PaletteChanges(Cadence(Trigger.BigMoments(0f)))))
        assertEquals(Trigger.BigMoments(Trigger.BigMoments.MIN_THRESHOLD), low.palette?.cadence?.trigger)
        val broken = Rules.automations(julia, Automations(palette = PaletteChanges(Cadence(Trigger.BigMoments(Float.NaN)))))
        assertEquals(Trigger.BigMoments(Trigger.BigMoments.MIN_THRESHOLD), broken.palette?.cadence?.trigger)
        val nan = Rules.automations(julia, Automations(camera = listOf(CameraMove(CameraMoveKind.ROLL, strength = Float.NaN))))
        assertEquals(0f, nan.camera.single().strength)
    }

    @Test
    fun `the rules keep sensible Automations as they are`() {
        val fine =
            Automations(
                style = StyleSwitching(),
                palette = PaletteChanges(),
                camera = listOf(CameraMove(CameraMoveKind.PUSH_IN), CameraMove(CameraMoveKind.TILT, BeatSpan.FOUR, 0.6f)),
                lanes = listOf(Lane(listOf(zoom, orbit), listOf(listOf(1f, 0f), listOf(0f, 1f)), StepLength.BEAT)),
            )
        assertEquals(fine, Rules.automations(julia, fine))
        assertEquals(Automations.NONE, Rules.automations(julia, Automations.NONE))
    }

    @Test
    fun `the rules drop lane targets that cannot run, with their values`() {
        val flight = Target.Extra(ExtraKind.FLIGHT)
        val gone = Target.ModulatorField(ModulatorId(7), ModulatorParameter.DEPTH)
        val lanes =
            listOf(
                Lane(listOf(flight, zoom, gone, trails), listOf(listOf(0.1f, 0.2f, 0.3f, 0.4f), listOf(0.5f))),
                Lane(listOf(zoom, orbit), listOf(listOf(1f, 0.9f))),
                Lane(listOf(flight), listOf(listOf(1f))),
                Lane(listOf(orbit), emptyList()),
                Lane(listOf(orbit), listOf(listOf(0.25f))),
            )
        val kept = Rules.automations(julia, Automations(lanes = lanes)).lanes
        assertEquals(
            listOf(
                Lane(listOf(zoom, trails), listOf(listOf(0.2f, 0.4f), listOf(0f, 0f))),
                Lane(listOf(orbit), listOf(listOf(0.9f))),
            ),
            kept,
        )
    }

    @Test
    fun `the rules keep lanes within the engine's room`() {
        val targets = SharedProperty.entries.take(Automations.MAX_LANE_TARGETS + 2).map { Target.Shared(it) }
        val wide = Lane(targets, List(Automations.MAX_LANE_STEPS + 3) { step -> targets.map { step / 100f } })
        val kept = Rules.automations(julia, Automations(lanes = listOf(wide))).lanes.single()
        assertEquals(targets.take(Automations.MAX_LANE_TARGETS), kept.targets)
        assertEquals(Automations.MAX_LANE_STEPS, kept.steps.size)
        assertTrue(kept.steps.all { it.size == Automations.MAX_LANE_TARGETS })
        val many = SharedProperty.entries.take(Automations.MAX_LANES + 2).map { Lane(listOf(Target.Shared(it)), listOf(listOf(1f))) }
        assertEquals(many.take(Automations.MAX_LANES), Rules.automations(julia, Automations(lanes = many)).lanes)
    }

    @Test
    fun `a lane target on a Modulator goes with the Modulator`() {
        val lfo = Modulator(ModulatorId(0), ModulatorShape.Lfo(Wave.SINE, Millis(2_000)))
        val withLfo = Edits.addModulator(julia, lfo)
        val depth = Target.ModulatorField(withLfo.modulators.single().id, ModulatorParameter.DEPTH)
        val automations = Automations(lanes = listOf(Lane(listOf(depth, zoom), listOf(listOf(1f, 0f), listOf(0f, 1f)))))
        val automated = Edits.setAutomations(withLfo, automations)
        assertEquals(automations, automated.automations)
        val zoomOnly = Automations(lanes = listOf(Lane(listOf(zoom), listOf(listOf(0f), listOf(1f)))))
        assertEquals(zoomOnly, Edits.removeModulator(automated, withLfo.modulators.single().id).automations)
    }

    @Test
    fun `Automations are kept across a change of Style and a reset, less what the Style cannot run`() {
        val automations =
            Automations(
                style = StyleSwitching(),
                palette = PaletteChanges(),
                camera = listOf(CameraMove(CameraMoveKind.DOLLY)),
                lanes = listOf(Lane(listOf(orbit, zoom), listOf(listOf(1f, 0.5f)))),
            )
        val automated = Edits.setAutomations(julia, automations)
        val onTunnel = Edits.switchStyle(automated, styles.manifestOf(JULIA), styles.manifestOf(TUNNEL))
        assertEquals(automations.copy(lanes = listOf(Lane(listOf(zoom), listOf(listOf(0.5f))))), onTunnel.automations)
        val reset = Edits.resetToStyle(Edits.setShared(automated, SharedProperty.ZOOM, 2f), styles.manifestOf(JULIA))
        assertEquals(automations, reset.automations)
        assertEquals(julia, reset.copy(automations = Automations.NONE))
    }

    @Test
    fun `Style switching takes a Level under the silence level for silence`() {
        assertTrue(Automation.silent(0f))
        assertTrue(Automation.silent(Automation.SILENCE_LEVEL - 0.001f))
        assertFalse(Automation.silent(Automation.SILENCE_LEVEL))
        assertFalse(Automation.silent(0.5f))
    }

    @Test
    fun `Style switching's threshold rises after a switch and relaxes with a half-life`() {
        val halfLife = Automation.THRESHOLD_HALF_LIFE
        assertEquals(0.6f, Automation.raisedThreshold(0.6f, null), 1e-6f)
        assertEquals(0.8f, Automation.raisedThreshold(0.6f, Millis.ZERO), 1e-6f)
        assertEquals(0.7f, Automation.raisedThreshold(0.6f, halfLife), 1e-6f)
        assertEquals(0.65f, Automation.raisedThreshold(0.6f, halfLife + halfLife), 1e-6f)
        assertEquals(0.8f, Automation.raisedThreshold(0.6f, Millis(-5_000)), 1e-6f)
        assertEquals(1f, Automation.raisedThreshold(1f, Millis.ZERO), 0f)
        assertEquals(1.5f, Automation.raisedThreshold(1.5f, Millis.ZERO), 0f)
    }

    @Test
    fun `a Style marked Never is never picked, from any pool`() {
        val never = mapOf(c to StyleMark.NEVER)
        val pools = StylePool.entries.map { StyleSwitching(pool = it, seed = 9L, chosen = listOf(c, a, b)) }
        for (switching in pools) {
            val played =
                (0 until 24).mapNotNull {
                    Automation.nextStyle(switching, SceneId("none"), listOf(c, a), builtIns, it, never, listOf(c, b))?.style
                }
            assertTrue("${switching.pool} plays something", played.isNotEmpty())
            assertFalse("${switching.pool} never plays a Never", c in played)
        }
        assertNull(Automation.nextStyle(StyleSwitching(pool = StylePool.CHOSEN, chosen = listOf(c)), a, emptyList(), builtIns, 0, never))
    }

    @Test
    fun `a loved Style comes round as often as its weight, and a round repeats for its seed`() {
        val loved = mapOf(a to StyleMark.LOVE, d to StyleMark.NORMAL)
        val round = Automation.weighted(builtIns, loved, 5L, 0)
        assertEquals(StyleMark.LOVE.weight, round.count { it == a })
        assertEquals(1, round.count { it == b })
        assertEquals(1, round.count { it == d })
        assertEquals(builtIns.size - 1 + StyleMark.LOVE.weight, round.size)
        assertEquals(round, Automation.weighted(builtIns, loved, 5L, 0))
        assertEquals(Automation.shuffled(builtIns, 5L, 2), Automation.weighted(builtIns, emptyMap(), 5L, 2))
        val picks = StyleSwitching(pool = StylePool.PICKS, seed = 5L)
        val played =
            (0 until round.size).map { checkNotNull(Automation.nextStyle(picks, SceneId("none"), emptyList(), builtIns, it, loved)).style }
        assertEquals(round, played)
    }

    @Test
    fun `Favourites and chosen Styles switch within themselves`() {
        val favourites = StyleSwitching(pool = StylePool.FAVOURITES, seed = 2L)
        val liked = listOf(b, d, SceneId("gone"))
        val fromFavourites =
            (0 until 6).map {
                checkNotNull(Automation.nextStyle(favourites, SceneId("none"), emptyList(), builtIns, it, favourites = liked)).style
            }
        assertEquals(setOf(b, d), fromFavourites.toSet())
        val chosen = StyleSwitching(pool = StylePool.CHOSEN, seed = 2L, chosen = listOf(c, a))
        val fromChosen = (0 until 6).map { checkNotNull(Automation.nextStyle(chosen, SceneId("none"), emptyList(), builtIns, it)).style }
        assertEquals(setOf(a, c), fromChosen.toSet())
        assertNull(Automation.nextStyle(favourites, a, emptyList(), builtIns, 0))
    }

    @Test
    fun `a Style with no mark is Normal`() {
        assertEquals(StyleMark.NORMAL, Automation.markOf(a, emptyMap()))
        assertEquals(StyleMark.LOVE, Automation.markOf(a, mapOf(a to StyleMark.LOVE)))
    }

    @Test
    fun `the rules keep each chosen Style once`() {
        val chosen = Automations(style = StyleSwitching(pool = StylePool.CHOSEN, chosen = listOf(a, b, a)))
        assertEquals(listOf(a, b), Rules.automations(julia, chosen).style?.chosen)
    }
}
