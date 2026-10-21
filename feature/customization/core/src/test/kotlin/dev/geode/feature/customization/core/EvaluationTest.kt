package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
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
import dev.geode.feature.customization.core.Fixtures.julia
import org.junit.Assert.assertEquals
import org.junit.Test

class EvaluationTest {
    private val delta = 1e-5f

    @Test
    fun `the normaliser brings every signal to 0 to 1`() {
        assertEquals(0.5f, Evaluation.normalise(0.5f, SignalTrim.NEUTRAL), delta)
        assertEquals(1f, Evaluation.normalise(0.8f, SignalTrim(gain = 2f)), delta)
        assertEquals(0.4f, Evaluation.normalise(0.2f, SignalTrim(gain = 2f)), delta)
        assertEquals(0f, Evaluation.normalise(-3f, SignalTrim.NEUTRAL), delta)
        assertEquals(0f, Evaluation.normalise(Float.NaN, SignalTrim.NEUTRAL), delta)
        assertEquals(0f, Evaluation.normalise(0.5f, SignalTrim(floor = 0.5f)), delta)
        assertEquals(0.5f, Evaluation.normalise(0.75f, SignalTrim(floor = 0.5f)), delta)
        assertEquals(1f, Evaluation.normalise(1f, SignalTrim(floor = 0.5f)), delta)
    }

    @Test
    fun `curves bend the input`() {
        assertEquals(0.25f, Evaluation.curve(0.25f, ResponseCurve.LINEAR), delta)
        assertEquals(0.0625f, Evaluation.curve(0.25f, ResponseCurve.EXPONENTIAL), delta)
        assertEquals(0.5f, Evaluation.curve(0.25f, ResponseCurve.LOGARITHMIC), delta)
        assertEquals(0.15625f, Evaluation.curve(0.25f, ResponseCurve.S_CURVE), delta)
        assertEquals(1f, Evaluation.curve(2f, ResponseCurve.S_CURVE), delta)
    }

    @Test
    fun `a reaction pushes from the target's own value and stays in its range`() {
        val reaction = Reaction(ReactionId(1), ReactionSource.Sound(SoundSignal.BASS), Target.Shared(SharedProperty.ZOOM), amount = 0.5f)
        assertEquals(0.45f, Evaluation.react(0.2f, 0.5f, reaction), delta)
        assertEquals(0f, Evaluation.react(0.2f, 1f, reaction.copy(invert = true)), delta)
        assertEquals(0.3f, Evaluation.react(0.2f, 1f, reaction.copy(invert = true, low = 0.3f)), delta)
        assertEquals(0.6f, Evaluation.react(0.2f, 1f, reaction.copy(high = 0.6f, amount = 1f)), delta)
        assertEquals(0.225f, Evaluation.react(0.2f, 0.5f, reaction.copy(amount = 0.1f, curve = ResponseCurve.EXPONENTIAL)), delta)
    }

    @Test
    fun `a macro at rest leaves its targets where they are`() {
        val link = MacroLink(Target.Shared(SharedProperty.SPEED), 0.1f, 0.9f)
        assertEquals(0.4f, Evaluation.macro(0.4f, link, 0.5f), delta)
        assertEquals(0.1f, Evaluation.macro(0.4f, link, 0f), delta)
        assertEquals(0.25f, Evaluation.macro(0.4f, link, 0.25f), delta)
        assertEquals(0.65f, Evaluation.macro(0.4f, link, 0.75f), delta)
        assertEquals(0.9f, Evaluation.macro(0.4f, link, 1f), delta)
        assertEquals(0.9f, Evaluation.macro(0.4f, link, 3f), delta)
        assertEquals(0.7f, Evaluation.macro(0.4f, MacroLink(link.target, 0.7f, 0f), 0f), delta)
    }

    @Test
    fun `only linked modulators run, and those by hand only while playing`() {
        val drift = Modulator(ModulatorId(0), ModulatorShape.RandomDrift(Millis(1_000)))
        val three = Edits.addModulator(Edits.addModulator(Edits.addModulator(julia, drift), drift), drift.copy(start = StartMode.ByHand))
        val linked =
            listOf(1, 3).fold(three) { customization, id ->
                Edits.addReaction(
                    customization,
                    Reaction(ReactionId(0), ReactionSource.Generated(ModulatorId(id)), Target.Shared(SharedProperty.TWIST)),
                )
            }
        assertEquals(emptySet<ModulatorId>(), Evaluation.running(three))
        assertEquals(setOf(ModulatorId(1)), Evaluation.running(linked))
        assertEquals(setOf(ModulatorId(1), ModulatorId(3)), Evaluation.running(Edits.playModulator(linked, ModulatorId(3), true)))
    }
}
