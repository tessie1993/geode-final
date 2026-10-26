package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.BeatSpan
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.CameraMoveKind
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Lane
import dev.geode.feature.customization.api.Layer
import dev.geode.feature.customization.api.LayerBlend
import dev.geode.feature.customization.api.LayerInfluence
import dev.geode.feature.customization.api.LayerLoop
import dev.geode.feature.customization.api.LoopPreset
import dev.geode.feature.customization.api.LoopRate
import dev.geode.feature.customization.api.LoopShape
import dev.geode.feature.customization.api.LoopTarget
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.MACRO_REST
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.StepLength
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Trigger
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.NativePlan.AUTOMATION_FLOATS
import dev.geode.feature.customization.core.NativePlan.MACRO_LINK_FLOATS
import dev.geode.feature.customization.core.NativePlan.MAX_MACRO_LINKS
import dev.geode.feature.customization.core.NativePlan.MAX_MODULATORS
import dev.geode.feature.customization.core.NativePlan.MAX_REACTIONS
import dev.geode.feature.customization.core.NativePlan.MAX_STEPS
import dev.geode.feature.customization.core.NativePlan.MODULATOR_FLOATS
import dev.geode.feature.customization.core.NativePlan.NO_INDEX
import dev.geode.feature.customization.core.NativePlan.REACTION_FLOATS
import org.junit.Assert.assertEquals
import org.junit.Test

/** The plan's floats in the places PlanCodec.hpp reads them (core/tests/plan_codec_test.cpp reads the same places). */
class NativePlanTest {
    private val sharedAt = NativePlan.HEADER_FLOATS
    private val extrasAt = sharedAt + SharedProperty.entries.size
    private val macrosAt = extrasAt + ExtraKind.entries.size
    private val trimsAt = macrosAt + MACRO_COUNT
    private val reactionsAt = trimsAt + 2 * SoundSignal.entries.size
    private val modulatorsAt = reactionsAt + MAX_REACTIONS * REACTION_FLOATS
    private val linksAt = modulatorsAt + MAX_MODULATORS * MODULATOR_FLOATS
    private val automationsAt = linksAt + MAX_MACRO_LINKS * MACRO_LINK_FLOATS
    private val layerAt = automationsAt + NativePlan.MAX_AUTOMATIONS * NativePlan.AUTOMATION_FLOATS

    /** An Automation slot's floats before its steps. */
    private val automationHead = AUTOMATION_FLOATS - MAX_STEPS

    private val extra = ExtraKind.entries[2]
    private val steps = ModulatorId(7)
    private val lfo = ModulatorId(3)
    private val hit = ModulatorId(4)
    private val drift = ModulatorId(0)

    private val modulators =
        listOf(
            Modulator(
                steps,
                ModulatorShape.StepSequencer(List(MAX_STEPS + 4) { it / 32f }, Millis(125), beatSync = true),
                start = StartMode.OnSound(SoundSignal.BEAT),
                rate = 2f,
                depth = 0.5f,
                playing = true,
            ),
            Modulator(lfo, ModulatorShape.Lfo(Wave.SQUARE, Millis(1500), beatSync = true), start = StartMode.ByHand),
            Modulator(hit, ModulatorShape.HitEnvelope(SoundSignal.KICK, Millis(20), Millis(400), 0.6f)),
            Modulator(drift, ModulatorShape.RandomDrift(Millis(3000))),
        )

    private val reactions =
        listOf(
            Reaction(
                ReactionId(1),
                ReactionSource.Sound(SoundSignal.BASS),
                Target.Shared(SharedProperty.VIGNETTE),
                amount = 0.5f,
                invert = true,
                rise = Millis(150),
                fall = Millis(400),
                low = 0.1f,
                high = 0.9f,
                curve = ResponseCurve.S_CURVE,
            ),
            Reaction(ReactionId(2), ReactionSource.Generated(steps), Target.ModulatorField(lfo, ModulatorParameter.DEPTH)),
            Reaction(ReactionId(3), ReactionSource.Generated(ModulatorId(99)), Target.Extra(extra)),
            Reaction(ReactionId(4), ReactionSource.Generated(hit), Target.MacroSlot(2)),
        )

    private val customization =
        Customization(
            style = Fixtures.JULIA,
            shared = mapOf(SharedProperty.ZOOM to 2.5f),
            extras = mapOf(extra to 0.75f),
            macros =
                List(MACRO_COUNT - 1) { Macro("macro $it") }.mapIndexed { i, macro ->
                    when (i) {
                        1 -> macro.copy(value = 0.25f, links = listOf(MacroLink(Target.Shared(SharedProperty.GLOW), 0.8f, 0.2f)))
                        3 -> macro.copy(links = listOf(MacroLink(Target.ModulatorField(drift, ModulatorParameter.RATE), 0f, 1f)))
                        else -> macro
                    }
                },
            reactions = reactions,
            modulators = modulators,
            signals = mapOf(SoundSignal.BASS to SignalTrim(gain = 2f, floor = 0.3f)),
        )

    private val plan = NativePlan.encode(customization)

    private fun at(
        start: Int,
        count: Int,
    ): List<Float> = plan.subList(start, start + count)

    @Test
    fun `is exactly the engine's size`() {
        assertEquals(NativePlan.floatCount, plan.size)
        assertEquals(3956, NativePlan.floatCount)
    }

    @Test
    fun `starts with the carried mark and the counts, and no Automation when there is none`() {
        assertEquals(listOf(1f, 4f, 4f, 2f, 0f), at(0, NativePlan.HEADER_FLOATS))
        assertEquals(List(NativePlan.MAX_AUTOMATIONS * NativePlan.AUTOMATION_FLOATS) { 0f }, plan.subList(automationsAt, layerAt))
    }

    @Test
    fun `carries every value, its default where the Customization has none`() {
        assertEquals((2.5f - 0.3f) / (3f - 0.3f), plan[sharedAt + SharedProperty.ZOOM.ordinal], 1e-6f)
        assertEquals(SharedProperty.VIGNETTE.default, plan[sharedAt + SharedProperty.VIGNETTE.ordinal])
        assertEquals(0f, plan[extrasAt - 1])
        assertEquals(0.75f, plan[extrasAt + extra.ordinal])
        assertEquals(0f, plan[extrasAt])
        assertEquals(listOf(MACRO_REST, 0.25f, MACRO_REST, MACRO_REST, MACRO_REST, MACRO_REST), at(macrosAt, MACRO_COUNT))
        assertEquals(listOf(1f, 0f, 2f, 0.3f, 1f, 0f), at(trimsAt, 6))
        assertEquals(listOf(1f, 0f), at(reactionsAt - 2, 2))
    }

    @Test
    fun `carries every Shared core value as a fraction of its range`() {
        assertEquals(0.5f, NativePlan.fraction(SharedProperty.ROTATION, 0f))
        assertEquals(1f, NativePlan.fraction(SharedProperty.ROTATION, 9f))
        assertEquals(0f, NativePlan.fraction(SharedProperty.ROTATION, -9f))
        assertEquals((1f - 0.3f) / 2.7f, NativePlan.fraction(SharedProperty.ZOOM, Float.NaN), 1e-6f)
        assertEquals(1f, NativePlan.fraction(SharedProperty.PALETTE, 20f))
        (SharedProperty.entries - SharedProperty.ZOOM).forEach {
            assertEquals(it.name, NativePlan.fraction(it, it.default), plan[sharedAt + it.ordinal], 1e-6f)
        }
    }

    @Test
    fun `carries a Reaction from a Sound signal`() {
        assertEquals(
            listOf(0f, 1f, NativePlan.TARGET_SHARED, SharedProperty.VIGNETTE.ordinal.toFloat(), 0f, 0.5f, 1f, 0.1f, 0.9f, 3f, 0.15f, 0.4f),
            at(reactionsAt, REACTION_FLOATS),
        )
    }

    @Test
    fun `carries Reactions from Modulators by their place, and none it cannot find`() {
        assertEquals(
            listOf(NativePlan.SOURCE_MODULATOR, 0f, NativePlan.TARGET_MODULATOR, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f),
            at(reactionsAt + REACTION_FLOATS, REACTION_FLOATS),
        )
        assertEquals(
            listOf(NativePlan.SOURCE_MODULATOR, NO_INDEX, NativePlan.TARGET_EXTRA, extra.ordinal.toFloat(), 0f),
            at(reactionsAt + 2 * REACTION_FLOATS, 5),
        )
        assertEquals(listOf(NativePlan.SOURCE_MODULATOR, 2f, NativePlan.TARGET_MACRO, 2f, 0f), at(reactionsAt + 3 * REACTION_FLOATS, 5))
        assertEquals(List(REACTION_FLOATS) { 0f }, at(reactionsAt + 4 * REACTION_FLOATS, REACTION_FLOATS))
    }

    @Test
    fun `carries a step sequencer started on a sound`() {
        val m = at(modulatorsAt, MODULATOR_FLOATS)
        assertEquals(listOf(NativePlan.KIND_STEP_SEQUENCER, 0f, 0f, 1f, 0f, 0f, 0f, 0f), m.subList(0, 8))
        assertEquals(List(MAX_STEPS) { it / 32f }, m.subList(8, 8 + MAX_STEPS))
        assertEquals(
            listOf(MAX_STEPS.toFloat(), 0.125f, NativePlan.START_ON_SOUND, SoundSignal.BEAT.ordinal.toFloat(), 1f, 2f, 0.5f, 7f),
            m.subList(8 + MAX_STEPS, MODULATOR_FLOATS),
        )
    }

    @Test
    fun `carries an LFO, a hit envelope and a drift`() {
        val lfoFloats = at(modulatorsAt + MODULATOR_FLOATS, MODULATOR_FLOATS)
        assertEquals(listOf(0f, Wave.SQUARE.ordinal.toFloat(), 1.5f, 1f, 0f), lfoFloats.subList(0, 5))
        assertEquals(listOf(0f, 0f, NativePlan.START_BY_HAND, 0f, 0f, 1f, 1f, 3f), lfoFloats.subList(8 + MAX_STEPS, MODULATOR_FLOATS))
        val hitFloats = at(modulatorsAt + 2 * MODULATOR_FLOATS, 8)
        assertEquals(listOf(NativePlan.KIND_HIT_ENVELOPE, 0f, 0f, 0f, SoundSignal.KICK.ordinal.toFloat(), 0.02f, 0.4f, 0.6f), hitFloats)
        assertEquals(0f, plan[modulatorsAt + 3 * MODULATOR_FLOATS - 6])
        val driftFloats = at(modulatorsAt + 3 * MODULATOR_FLOATS, MODULATOR_FLOATS)
        assertEquals(listOf(NativePlan.KIND_RANDOM_DRIFT, 0f, 3f, 0f), driftFloats.subList(0, 4))
        assertEquals(0f, driftFloats.last())
    }

    @Test
    fun `carries every Macro link with its Macro`() {
        assertEquals(
            listOf(1f, NativePlan.TARGET_SHARED, SharedProperty.GLOW.ordinal.toFloat(), 0f, 0.8f, 0.2f),
            at(linksAt, MACRO_LINK_FLOATS),
        )
        assertEquals(listOf(3f, NativePlan.TARGET_MODULATOR, 3f, 0f, 0f, 1f), at(linksAt + MACRO_LINK_FLOATS, MACRO_LINK_FLOATS))
        assertEquals(List(MACRO_LINK_FLOATS) { 0f }, at(linksAt + 2 * MACRO_LINK_FLOATS, MACRO_LINK_FLOATS))
    }

    private val laneTargets =
        listOf(Target.Shared(SharedProperty.GLOW), Target.ModulatorField(lfo, ModulatorParameter.DEPTH), Target.Extra(extra))

    private val automated =
        customization.copy(
            automations =
                Automations(
                    style = StyleSwitching(Cadence(Trigger.BigMoments(0.7f), Millis(8_000)), transition = Millis(4_000)),
                    palette = PaletteChanges(Cadence(Trigger.EveryBars(8)), fade = Millis(2_000)),
                    camera = listOf(CameraMove(CameraMoveKind.TRAVEL_CURVE, BeatSpan.FOUR, 0.5f), CameraMove(CameraMoveKind.PULL_OUT)),
                    lanes = listOf(Lane(laneTargets, listOf(listOf(0.1f, 0.2f, 0.3f), listOf(0.4f, 0.5f)), StepLength.BEAT)),
                ),
        )
    private val automatedPlan = NativePlan.encode(automated)

    /** Where the Automation slot [index] starts. */
    private fun slotAt(index: Int): Int = automationsAt + index * AUTOMATION_FLOATS

    /** The Automation slot [index] of [automatedPlan], less its steps. */
    private fun slot(index: Int): List<Float> = automatedPlan.subList(slotAt(index), slotAt(index) + automationHead)

    private fun steps(index: Int): List<Float> = automatedPlan.subList(slotAt(index) + automationHead, slotAt(index + 1))

    /**
     * A slot as written: its kind, its target's three floats, then amount, every, trigger, threshold,
     * spacing, fade, alternate, held, count.
     */
    private fun expected(
        kind: Float,
        target: List<Float>,
        vararg rest: Float,
    ): List<Float> = listOf(kind) + target + rest.toList()

    private fun shared(property: SharedProperty): List<Float> = listOf(NativePlan.TARGET_SHARED, property.ordinal.toFloat(), 0f)

    private val none = listOf(0f, 0f, 0f)

    @Test
    fun `counts every Automation it carries`() {
        assertEquals(listOf(1f, 4f, 4f, 2f, 8f), automatedPlan.subList(0, NativePlan.HEADER_FLOATS))
        assertEquals(List(AUTOMATION_FLOATS) { 0f }, slot(8) + steps(8))
    }

    @Test
    fun `carries Style switching on big moments, so the engine counts its due moves`() {
        assertEquals(
            expected(NativePlan.AUTOMATION_STYLE_SWITCH, none, 0f, 1f, NativePlan.TRIGGER_BIG_MOMENTS, 0.7f, 8f, 4f, 0f, 0f, 0f),
            slot(0),
        )
        assertEquals(List(MAX_STEPS) { 0f }, steps(0))
    }

    @Test
    fun `marks Style switching held, and only Style switching`() {
        val held = NativePlan.encode(automated, switchingHeld = true)
        val heldAt = automationHead - 2
        assertEquals(1f, held[slotAt(0) + heldAt])
        assertEquals(0f, held[slotAt(1) + heldAt])
        assertEquals(automatedPlan.subList(0, slotAt(0)), held.subList(0, slotAt(0)))
        assertEquals(automatedPlan.subList(slotAt(1), automatedPlan.size), held.subList(slotAt(1), held.size))
    }

    @Test
    fun `carries palette changes every few bars, in beats`() {
        assertEquals(
            expected(NativePlan.AUTOMATION_PALETTE, none, 0f, 32f, NativePlan.TRIGGER_EVERY_BEATS, 0f, 10f, 2f, 0f, 0f, 0f),
            slot(1),
        )
    }

    @Test
    fun `carries each Camera move as one slot per property, the alternating ones marked`() {
        val camera = NativePlan.AUTOMATION_CAMERA
        assertEquals(expected(camera, shared(SharedProperty.DRIFT_X), 0.5f, 4f, 0f, 0f, 0f, 0f, 1f, 0f, 0f), slot(2))
        assertEquals(expected(camera, shared(SharedProperty.TRAVEL), 0.5f, 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), slot(3))
        assertEquals(expected(camera, shared(SharedProperty.ZOOM), -0.25f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), slot(4))
    }

    @Test
    fun `carries a lane as one slot per target, each with its own steps`() {
        val lane = NativePlan.AUTOMATION_LANE
        assertEquals(expected(lane, shared(SharedProperty.GLOW), 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 2f), slot(5))
        assertEquals(listOf(0.1f, 0.4f) + List(MAX_STEPS - 2) { 0f }, steps(5))
        assertEquals(listOf(lane, NativePlan.TARGET_MODULATOR, 1f, ModulatorParameter.DEPTH.ordinal.toFloat()), slot(6).subList(0, 4))
        assertEquals(listOf(0.2f, 0.5f), steps(6).subList(0, 2))
        assertEquals(listOf(lane, NativePlan.TARGET_EXTRA, extra.ordinal.toFloat(), 0f), slot(7).subList(0, 4))
        assertEquals(listOf(0.3f, 0f), steps(7).subList(0, 2))
    }

    @Test
    fun `carries at most the engine's room for Automations and steps`() {
        val glow = Target.Shared(SharedProperty.GLOW)
        val lane = Lane(List(4) { glow }, List(MAX_STEPS + 2) { listOf(1f, 1f, 1f, 1f) })
        val crowded = customization.copy(automations = Automations(lanes = List(NativePlan.MAX_AUTOMATIONS / 4 + 1) { lane }))
        val full = NativePlan.encode(crowded)
        assertEquals(NativePlan.floatCount, full.size)
        assertEquals(NativePlan.MAX_AUTOMATIONS.toFloat(), full[4])
        assertEquals(MAX_STEPS.toFloat(), full[automationsAt + automationHead - 1])
        assertEquals(1f, full[automationsAt + AUTOMATION_FLOATS - 1])
        assertEquals(NativePlan.AUTOMATION_LANE, full[automationsAt + (NativePlan.MAX_AUTOMATIONS - 1) * AUTOMATION_FLOATS])
    }

    @Test
    fun `leaves out what is past the engine's limits`() {
        val link = MacroLink(Target.Shared(SharedProperty.GLOW), 0f, 1f)
        val crowded =
            customization.copy(
                reactions = List(MAX_REACTIONS + 1) { reactions[0].copy(id = ReactionId(it)) },
                modulators = List(MAX_MODULATORS + 1) { modulators[3].copy(id = ModulatorId(it)) },
                macros = List(MACRO_COUNT) { Macro("macro $it", links = List(MAX_MACRO_LINKS / 2 + 1) { link }) },
            )
        val full = NativePlan.encode(crowded)
        assertEquals(NativePlan.floatCount, full.size)
        assertEquals(listOf(1f, MAX_REACTIONS.toFloat(), MAX_MODULATORS.toFloat(), MAX_MACRO_LINKS.toFloat(), 0f), full.subList(0, 5))
        assertEquals(1f, full[linksAt + (MAX_MACRO_LINKS - 1) * MACRO_LINK_FLOATS])
        assertEquals(15f, full[linksAt - 1])
    }

    @Test
    fun `has Layer 2's Style, blend and influence before the Style`() {
        val styleAt = layerAt + NativePlan.LAYER_FLOATS
        assertEquals(listOf(0f, LayerBlend.SCREEN.ordinal.toFloat(), 0f), plan.subList(layerAt, styleAt))
        val layered =
            NativePlan.encode(customization.copy(layer = Layer(Fixtures.TUNNEL, LayerBlend.DIFFERENCE, LayerInfluence.TINT)))
        assertEquals(listOf(StyleHash.of(Fixtures.TUNNEL).toFloat(), 7f, 4f), layered.subList(layerAt, styleAt))
    }

    @Test
    fun `ends with the Style the Customization is for`() {
        assertEquals(NativePlan.floatCount, layerAt + NativePlan.LAYER_FLOATS + NativePlan.STYLE_FLOATS)
        assertEquals(StyleHash.of(Fixtures.JULIA).toFloat(), plan.last())
        val tunnel = NativePlan.encode(customization.copy(style = Fixtures.TUNNEL))
        assertEquals(StyleHash.of(Fixtures.TUNNEL).toFloat(), tunnel.last())
    }

    @Test
    fun `names a Style the way the engine does`() {
        assertEquals(3997830, StyleHash.of(SceneId("julia")))
        assertEquals(15643514, StyleHash.of(SceneId("plasma")))
        assertEquals(1875268, StyleHash.of(SceneId("")))
    }

    @Test
    fun `runs Layer 2's loop as one more Modulator and its Reactions`() {
        val looped =
            NativePlan.encode(customization.copy(layer = Layer(Fixtures.TUNNEL, loop = LoopPreset.BREATHE.loop)))
        assertEquals(listOf(1f, 5f, 5f, 2f, 0f), looped.subList(0, NativePlan.HEADER_FLOATS))
        val m = modulatorsAt + 4 * MODULATOR_FLOATS
        assertEquals(listOf(0f, Wave.SINE.ordinal.toFloat(), 0.5f, 1f), looped.subList(m, m + 4))
        assertEquals(listOf(0f, 0f, 0f, 0.25f, 1f, 8f), looped.subList(m + 10 + MAX_STEPS, m + MODULATOR_FLOATS))
        val r = reactionsAt + 4 * REACTION_FLOATS
        assertEquals(
            listOf(
                NativePlan.SOURCE_MODULATOR,
                4f,
                NativePlan.TARGET_SHARED,
                SharedProperty.LAYER_OPACITY.ordinal.toFloat(),
                0f,
                0.6f,
                1f,
                0f,
                1f,
                ResponseCurve.LINEAR.ordinal.toFloat(),
                0.15f,
                0f,
            ),
            looped.subList(r, r + REACTION_FLOATS),
        )
    }

    @Test
    fun `a loop with Layer 2 off runs nothing`() {
        val off = NativePlan.encode(customization.copy(layer = Layer(loop = LoopPreset.BREATHE.loop)))
        assertEquals(plan, off)
    }

    @Test
    fun `the loop's shapes and rates reach the Modulator`() {
        val id = ModulatorId(9)
        val steps = LayerLoops.modulator(LayerLoop(LoopShape.BEAT_STEPS, LoopRate.TWO_BEATS), id)
        assertEquals(ModulatorShape.StepSequencer(LayerLoops.STEPS, LayerLoops.BEAT_FALLBACK, beatSync = true), steps.shape)
        assertEquals(0.5f, steps.rate)
        val slow = LayerLoops.modulator(LayerLoop(LoopShape.PING_PONG, LoopRate.EIGHT_SECONDS), id)
        assertEquals(ModulatorShape.Lfo(Wave.TRIANGLE, Millis(8000), beatSync = false), slow.shape)
        assertEquals(Modulator.DEFAULT_RATE, slow.rate)
        val triangle = LayerLoops.modulator(LayerLoop(LoopShape.TRIANGLE, LoopRate.FOUR_SECONDS), id)
        assertEquals(ModulatorShape.Lfo(Wave.TRIANGLE, Millis(4000), beatSync = false), triangle.shape)
        val all = setOf(LoopTarget.SWAP, LoopTarget.OPACITY, LoopTarget.INFLUENCE)
        val eased = LayerLoops.reactions(LayerLoop(LoopShape.PING_PONG, LoopRate.ONE_BAR, 0.4f, all), id)
        assertEquals(LoopTarget.entries.map { Target.Shared(it.property) }, eased.map { it.target })
        assertEquals(listOf(true, true, false), eased.map { it.invert })
        assertEquals(setOf(ResponseCurve.S_CURVE), eased.map { it.curve }.toSet())
        assertEquals(setOf(0.4f), eased.map { it.amount }.toSet())
        assertEquals(setOf(Millis.ZERO), eased.map { it.fall }.toSet())
        val glided = LayerLoops.reactions(LayerLoop(LoopShape.BEAT_STEPS, LoopRate.ONE_BAR, 1f, setOf(LoopTarget.SWAP)), id)
        assertEquals(LayerLoops.GLIDE, glided.single().rise)
        assertEquals(LayerLoops.GLIDE, glided.single().fall)
        assertEquals(ReactionSource.Generated(id), glided.single().source)
    }

    @Test
    fun `a full plan makes room for the loop`() {
        val crowded =
            customization.copy(
                reactions = List(MAX_REACTIONS) { reactions[0].copy(id = ReactionId(it)) },
                modulators = List(MAX_MODULATORS) { modulators[3].copy(id = ModulatorId(it)) },
                layer = Layer(Fixtures.TUNNEL, loop = LayerLoop(targets = setOf(LoopTarget.OPACITY, LoopTarget.SWAP))),
            )
        val full = NativePlan.encode(crowded)
        assertEquals(listOf(MAX_REACTIONS.toFloat(), MAX_MODULATORS.toFloat()), full.subList(1, 3))
        val lastReaction = reactionsAt + (MAX_REACTIONS - 1) * REACTION_FLOATS
        assertEquals(SharedProperty.LAYER_SWAP.ordinal.toFloat(), full[lastReaction + 3])
        assertEquals((MAX_MODULATORS - 1).toFloat(), full[lastReaction + 1])
    }
}
