package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.MACRO_REST
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
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
        assertEquals(2940, NativePlan.floatCount)
    }

    @Test
    fun `starts with the carried mark and the counts, with no Automation yet`() {
        assertEquals(listOf(1f, 4f, 4f, 2f, 0f), at(0, NativePlan.HEADER_FLOATS))
        assertEquals(List(NativePlan.MAX_AUTOMATIONS * NativePlan.AUTOMATION_FLOATS) { 0f }, plan.subList(automationsAt, plan.size))
    }

    @Test
    fun `carries every value, its default where the Customization has none`() {
        assertEquals(2.5f, plan[sharedAt + SharedProperty.ZOOM.ordinal])
        assertEquals(SharedProperty.VIGNETTE.default, plan[sharedAt + SharedProperty.VIGNETTE.ordinal])
        assertEquals(SharedProperty.INVERT.default, plan[extrasAt - 1])
        assertEquals(0.75f, plan[extrasAt + extra.ordinal])
        assertEquals(0f, plan[extrasAt])
        assertEquals(listOf(MACRO_REST, 0.25f, MACRO_REST, MACRO_REST, MACRO_REST, MACRO_REST), at(macrosAt, MACRO_COUNT))
        assertEquals(listOf(1f, 0f, 2f, 0.3f, 1f, 0f), at(trimsAt, 6))
        assertEquals(listOf(1f, 0f), at(reactionsAt - 2, 2))
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
}
