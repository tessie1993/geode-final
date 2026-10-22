package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.Fixtures.julia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val sound = ReactionSource.Sound(SoundSignal.BASS)

    private fun reaction(
        target: Target,
        rise: Millis = Millis.ZERO,
    ) = Reaction(ReactionId(1), sound, target, rise = rise)

    @Test
    fun `shared values keep to their range, steps and switches`() {
        assertEquals(1f, Rules.shared(SharedProperty.ZOOM, Float.NaN))
        assertEquals(0.3f, Rules.shared(SharedProperty.ZOOM, 0f))
        assertEquals(3f, Rules.shared(SharedProperty.ZOOM, 9f))
        assertEquals(1.5f, Rules.shared(SharedProperty.ZOOM, 1.5f))
        assertEquals(2f, Rules.shared(SharedProperty.PALETTE, 2.4f))
        assertEquals(3f, Rules.shared(SharedProperty.PALETTE, 2.6f))
        assertEquals(0f, Rules.shared(SharedProperty.MIRROR, 0.49f))
        assertEquals(1f, Rules.shared(SharedProperty.MIRROR, 0.5f))
        assertEquals(0.25f, Rules.shared(SharedProperty.GLITCH, 1f))
    }

    @Test
    fun `unit values keep to 0 to 1`() {
        assertEquals(0f, Rules.unit(Float.NaN))
        assertEquals(0f, Rules.unit(-1f))
        assertEquals(1f, Rules.unit(2f))
        assertEquals(0.3f, Rules.unit(0.3f))
    }

    @Test
    fun `a reaction is put in order`() {
        val messy =
            Reaction(
                ReactionId(1),
                sound,
                Target.Shared(SharedProperty.ZOOM),
                amount = 2f,
                rise = Millis(-5),
                fall = Millis(-5),
                low = 0.8f,
                high = 0.2f,
            )
        assertEquals(
            Reaction(
                ReactionId(1),
                sound,
                Target.Shared(SharedProperty.ZOOM),
                amount = 1f,
                rise = Millis.ZERO,
                fall = Millis.ZERO,
                low = 0.2f,
                high = 0.8f,
            ),
            Rules.reaction(messy),
        )
        assertEquals(Millis(40), Rules.reaction(reaction(Target.Extra(ExtraKind.SCALE), Millis(40))).rise)
    }

    @Test
    fun `a reaction on a luminous property always rises`() {
        assertEquals(NO_FLASH_MIN_RISE, Rules.reaction(reaction(Target.Shared(SharedProperty.BRIGHTNESS), Millis(10))).rise)
        assertEquals(Millis(300), Rules.reaction(reaction(Target.Shared(SharedProperty.GLOW), Millis(300))).rise)
        assertEquals(Millis(10), Rules.reaction(reaction(Target.Shared(SharedProperty.ZOOM), Millis(10))).rise)
        assertTrue(Rules.luminous(Target.Shared(SharedProperty.INVERT)))
        assertFalse(Rules.luminous(Target.Shared(SharedProperty.ZOOM)))
        assertFalse(Rules.luminous(Target.Extra(ExtraKind.EDGE)))
    }

    @Test
    fun `a modulator keeps to its ranges`() {
        val lfo = Modulator(ModulatorId(1), ModulatorShape.Lfo(Wave.SINE, Millis(1_000)), rate = Float.NaN, depth = 2f)
        assertEquals(lfo.copy(rate = 1f, depth = 1f), Rules.modulator(lfo))
        assertEquals(Modulator.MIN_RATE, Rules.modulator(lfo.copy(rate = 0f)).rate)
        assertEquals(Modulator.MAX_RATE, Rules.modulator(lfo.copy(rate = 9f)).rate)
        val steps = lfo.copy(shape = ModulatorShape.StepSequencer(listOf(-1f, 0.5f, 2f), Millis(100)))
        assertEquals(listOf(0f, 0.5f, 1f), (Rules.modulator(steps).shape as ModulatorShape.StepSequencer).steps)
    }

    @Test
    fun `a trim keeps to its ranges`() {
        assertEquals(SignalTrim(1f, 0f), Rules.trim(SignalTrim(Float.NaN, Float.NaN)))
        assertEquals(SignalTrim(4f, 0.9f), Rules.trim(SignalTrim(9f, 2f)))
        assertEquals(SignalTrim(0f, 0f), Rules.trim(SignalTrim(-1f, -1f)))
        assertEquals(SignalTrim(2f, 0.3f), Rules.trim(SignalTrim(2f, 0.3f)))
    }

    @Test
    fun `targets and sources exist only when there is something to reach`() {
        val withLfo = Edits.addModulator(julia, Modulator(ModulatorId(0), ModulatorShape.RandomDrift(Millis(1_000))))
        assertTrue(Rules.exists(julia, Target.Shared(SharedProperty.ZOOM)))
        assertTrue(Rules.exists(julia, Target.Extra(ExtraKind.CENTER_X)))
        assertFalse(Rules.exists(julia, Target.Extra(ExtraKind.FLIGHT)))
        assertFalse(Rules.exists(julia, Target.MacroSlot(-1)))
        assertTrue(Rules.exists(julia, Target.MacroSlot(0)))
        assertTrue(Rules.exists(julia, Target.MacroSlot(5)))
        assertFalse(Rules.exists(julia, Target.MacroSlot(6)))
        assertTrue(Rules.exists(withLfo, Target.ModulatorField(ModulatorId(1), ModulatorParameter.RATE)))
        assertFalse(Rules.exists(withLfo, Target.ModulatorField(ModulatorId(2), ModulatorParameter.RATE)))
        assertTrue(Rules.exists(julia, sound))
        assertTrue(Rules.exists(withLfo, ReactionSource.Generated(ModulatorId(1))))
        assertFalse(Rules.exists(julia, ReactionSource.Generated(ModulatorId(1))))
    }

    @Test
    fun `pruning drops links to nothing and keeps the rest`() {
        val ghost = Target.ModulatorField(ModulatorId(7), ModulatorParameter.DEPTH)
        val broken =
            julia.copy(
                reactions =
                    julia.reactions +
                        Reaction(ReactionId(10), sound, ghost) +
                        Reaction(ReactionId(11), ReactionSource.Generated(ModulatorId(7)), Target.Shared(SharedProperty.ZOOM)),
                macros = julia.macros.map { it.copy(links = it.links + MacroLink(ghost, 0f, 1f)) },
            )
        assertEquals(julia, Rules.prune(broken))
    }
}
