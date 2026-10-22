package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CustomizeActionsTest {
    private val link = MacroLink(Target.Shared(SharedProperty.SPEED), 0.1f, 0.9f)
    private val customization =
        Customization(
            style = SceneId("tunnel"),
            shared = emptyMap(),
            extras = mapOf(ExtraKind.FLIGHT to 0.3f),
            macros = List(6) { Macro("M$it", 0.5f, if (it == 0) listOf(link) else emptyList()) },
            reactions = emptyList(),
            modulators = emptyList(),
            signals = emptyMap(),
        )

    @Test
    fun `Surprise me moves the Macros only, within the safe range, as one undoable step`() {
        val command = surprise(customization, Random(42))

        val preset = command.preset
        assertEquals(setOf(PresetPart.MACROS), preset.parts)
        assertEquals(customization.style, preset.style)
        assertEquals(PRESET_VERSION, preset.version)
        assertEquals(customization.macros.map { it.name }, preset.macros.map { it.name })
        assertEquals(customization.macros.map { it.links }, preset.macros.map { it.links })
        assertTrue(preset.macros.all { it.value in SURPRISE_LOW..SURPRISE_HIGH })
        assertTrue(preset.shared.isEmpty() && preset.extras.isEmpty() && preset.reactions.isEmpty())
    }

    @Test
    fun `Surprise me lands somewhere new each time`() {
        val values = { seed: Int -> surprise(customization, Random(seed)).preset.macros.map { it.value } }
        assertTrue(values(1) != values(2))
        assertTrue(values(1).toSet().size > 1)
    }

    @Test
    fun `the safe range keeps clear of both ends`() {
        assertEquals(0.2f, SURPRISE_LOW)
        assertEquals(0.8f, SURPRISE_HIGH)
    }

    @Test
    fun `a slider sets its Shared property or Extra, and nothing else is a slider`() {
        assertEquals(
            CustomizationCommand.SetShared(SharedProperty.ZOOM, 2f),
            sliderCommand(Target.Shared(SharedProperty.ZOOM), 2f),
        )
        assertEquals(CustomizationCommand.SetExtra(ExtraKind.FLIGHT, 0.4f), sliderCommand(Target.Extra(ExtraKind.FLIGHT), 0.4f))
        assertEquals(CustomizationCommand.SetMacro(3, 0.7f), sliderCommand(Target.MacroSlot(3), 0.7f))
        assertNull(sliderCommand(Target.ModulatorField(ModulatorId(1), ModulatorParameter.RATE), 0.5f))
    }

    @Test
    fun `an effect toggle turns a switch fully on, and a level effect to its on value`() {
        assertEquals(
            CustomizationCommand.SetShared(SharedProperty.KALEIDOSCOPE, 1f),
            EffectToggle(SharedProperty.KALEIDOSCOPE, on = false).toggled(),
        )
        assertEquals(
            CustomizationCommand.SetShared(SharedProperty.TRAILS, 0.6f),
            EffectToggle(SharedProperty.TRAILS, on = false).toggled(),
        )
        assertEquals(
            CustomizationCommand.SetShared(SharedProperty.GLITCH, 0.1f),
            EffectToggle(SharedProperty.GLITCH, on = false).toggled(),
        )
        assertEquals(
            CustomizationCommand.SetShared(SharedProperty.PARTICLES, 0.5f),
            EffectToggle(SharedProperty.PARTICLES, on = false).toggled(),
        )
    }

    @Test
    fun `turning an effect off puts it back to its minimum`() {
        assertEquals(
            CustomizationCommand.SetShared(SharedProperty.TRAILS, 0f),
            EffectToggle(SharedProperty.TRAILS, on = true).toggled(),
        )
        assertEquals(
            CustomizationCommand.SetShared(SharedProperty.INVERT, 0f),
            EffectToggle(SharedProperty.INVERT, on = true).toggled(),
        )
    }

    @Test
    fun `a new link starts at half strength, with the rest of the Reaction's defaults`() {
        val reaction = newReaction(ReactionSource.Sound(SoundSignal.BASS), Target.Shared(SharedProperty.ZOOM))

        assertEquals(ReactionSource.Sound(SoundSignal.BASS), reaction.source)
        assertEquals(Target.Shared(SharedProperty.ZOOM), reaction.target)
        assertEquals(0.5f, reaction.amount)
        assertEquals(Reaction(reaction.id, reaction.source, reaction.target, amount = 0.5f), reaction)
    }

    @Test
    fun `Learn mode links its source to the first slider moved, and only then`() {
        val source = ReactionSource.Sound(SoundSignal.KICK)
        val target = Target.Shared(SharedProperty.RIPPLE)

        assertEquals(CustomizationCommand.AddReaction(newReaction(source, target)), learnOrSet(source, target, 0.8f))
        assertEquals(CustomizationCommand.SetShared(SharedProperty.RIPPLE, 0.8f), learnOrSet(null, target, 0.8f))
    }

    @Test
    fun `a new Modulator of each kind starts from a calm shape, run while playing`() {
        val lfo = newModulator(ModulatorKind.LFO)
        assertEquals(ModulatorShape.Lfo(Wave.SINE, Millis(4_000)), lfo.shape)
        assertEquals(StartMode.WhilePlaying, lfo.start)
        assertEquals(
            ModulatorShape.HitEnvelope(SoundSignal.KICK, Millis(30), Millis(400), 0.5f),
            newModulator(ModulatorKind.ENVELOPE).shape,
        )
        assertEquals(
            ModulatorShape.StepSequencer(listOf(1f, 0f, 0.5f, 0f, 1f, 0f, 0.75f, 0.25f), Millis(250), beatSync = true),
            newModulator(ModulatorKind.SEQUENCER).shape,
        )
        assertEquals(ModulatorShape.RandomDrift(Millis(8_000)), newModulator(ModulatorKind.DRIFT).shape)
    }

    @Test
    fun `every Modulator shape has its kind`() {
        assertEquals(ModulatorKind.LFO, ModulatorShape.Lfo(Wave.SAW, Millis(1)).kind)
        assertEquals(ModulatorKind.ENVELOPE, ModulatorShape.HitEnvelope(SoundSignal.HIT, Millis(1), Millis(1), 0f).kind)
        assertEquals(ModulatorKind.SEQUENCER, ModulatorShape.StepSequencer(emptyList(), Millis(1)).kind)
        assertEquals(ModulatorKind.DRIFT, ModulatorShape.RandomDrift(Millis(1)).kind)
        ModulatorKind.entries.forEach { assertEquals(it, newModulator(it).shape.kind) }
    }

    @Test
    fun `a hand-started Modulator plays and stops live`() {
        val manual = Modulator(ModulatorId(4), ModulatorShape.RandomDrift(Millis(1_000)), StartMode.ByHand, playing = false)
        assertEquals(CustomizationCommand.PlayModulator(ModulatorId(4), true), togglePlay(manual))
        assertEquals(CustomizationCommand.PlayModulator(ModulatorId(4), false), togglePlay(manual.copy(playing = true)))
    }

    @Test
    fun `a Macro link starts across the whole range of its target`() {
        assertEquals(
            CustomizationCommand.LinkMacro(2, MacroLink(Target.Shared(SharedProperty.WARP), 0f, 1f)),
            linkMacro(2, Target.Shared(SharedProperty.WARP)),
        )
    }

    @Test
    fun `saving keeps every part unless the person unticks some`() {
        assertEquals(PresetPart.entries.toSet(), DEFAULT_PRESET_PARTS)
        assertEquals(
            CustomizationCommand.SavePreset("Night", setOf(PresetPart.MACROS)),
            savePreset(" Night ", setOf(PresetPart.MACROS)),
        )
        assertNull(savePreset("  ", DEFAULT_PRESET_PARTS))
        assertNull(savePreset("Night", emptySet()))
    }

    @Test
    fun `an edited Reaction replaces the one with its id`() {
        val reaction = Reaction(ReactionId(9), ReactionSource.Sound(SoundSignal.MID), Target.Extra(ExtraKind.FLIGHT))
        assertEquals(CustomizationCommand.UpdateReaction(reaction), saveReaction(reaction, isNew = false))
        assertEquals(CustomizationCommand.AddReaction(reaction), saveReaction(reaction, isNew = true))
    }
}
