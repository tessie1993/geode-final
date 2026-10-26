package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.CompareSlot
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.ValueKind
import dev.geode.feature.customization.api.Wave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizeModelTest {
    private val tunnel = SceneId("tunnel")
    private val lfo = Modulator(ModulatorId(1), ModulatorShape.Lfo(Wave.SINE, Millis(2_000)))
    private val drift = Modulator(ModulatorId(2), ModulatorShape.RandomDrift(Millis(8_000)))
    private val bassToZoom =
        Reaction(ReactionId(1), ReactionSource.Sound(SoundSignal.BASS), Target.Shared(SharedProperty.ZOOM), amount = 0.4f)
    private val kickToZoom =
        Reaction(ReactionId(2), ReactionSource.Sound(SoundSignal.KICK), Target.Shared(SharedProperty.ZOOM))
    private val lfoToEnergy = Reaction(ReactionId(3), ReactionSource.Generated(ModulatorId(1)), Target.MacroSlot(0))
    private val bassToEnergy = Reaction(ReactionId(4), ReactionSource.Sound(SoundSignal.BASS), Target.MacroSlot(0))

    private val customization =
        Customization(
            style = tunnel,
            shared = mapOf(SharedProperty.ZOOM to 1.5f, SharedProperty.KALEIDOSCOPE to 1f, SharedProperty.TRAILS to 0f),
            extras = mapOf(ExtraKind.FLIGHT to 0.3f, ExtraKind.SCALE to 0.7f),
            macros =
                listOf(
                    Macro("Energy", 0.8f, listOf(MacroLink(Target.Shared(SharedProperty.SPEED), 0f, 1f))),
                    Macro("Depth"),
                    Macro("Warmth"),
                    Macro("Flow"),
                    Macro("Detail"),
                    Macro("Calm", 0.1f),
                ),
            reactions = listOf(bassToZoom, lfoToEnergy, kickToZoom, bassToEnergy),
            modulators = listOf(lfo, drift),
            signals = emptyMap(),
        )

    private val model = CustomizationState(customization).toCustomizeModel()

    @Test
    fun `the model shows the current Style, and undo and redo only when there is history`() {
        assertEquals(tunnel, model.style)
        assertFalse(model.canUndo)
        assertFalse(model.canRedo)

        val history = CustomizationState(customization, undo = listOf(customization), redo = listOf(customization)).toCustomizeModel()
        assertTrue(history.canUndo)
        assertTrue(history.canRedo)
    }

    @Test
    fun `there are six Macro knobs, each with its value, links and the sources that drive it`() {
        assertEquals(MACRO_COUNT, model.macros.size)
        val energy = model.macros[0]
        assertEquals(0, energy.index)
        assertEquals("Energy", energy.name)
        assertEquals(0.8f, energy.value)
        assertEquals(1, energy.links.size)
        assertEquals(listOf(ReactionSource.Generated(ModulatorId(1)), ReactionSource.Sound(SoundSignal.BASS)), energy.drivers)
        assertEquals(5, model.macros[5].index)
        assertEquals(0.1f, model.macros[5].value)
        assertTrue(model.macros[1].drivers.isEmpty())
    }

    @Test
    fun `live values reach the knobs and the links they are measured for`() {
        val live = LiveMeters(macros = mapOf(0 to 0.65f), reactions = mapOf(ReactionId(2) to 0.9f))
        val lively = CustomizationState(customization).toCustomizeModel(live = live)

        assertEquals(0.65f, lively.macros[0].live)
        assertNull(lively.macros[1].live)
        val zoomLinks = lively.reactions.first { it.target == Target.Shared(SharedProperty.ZOOM) }.links
        assertEquals(listOf(null, 0.9f), zoomLinks.map { it.live })
        assertNull(model.macros[0].live)
    }

    @Test
    fun `effect toggles are on when their effect is above its minimum`() {
        val effects = model.effects.associate { it.property to it.on }
        assertEquals(EFFECT_TOGGLES, model.effects.map { it.property })
        assertEquals(true, effects[SharedProperty.KALEIDOSCOPE])
        assertEquals(false, effects[SharedProperty.TRAILS])
        assertEquals(false, effects[SharedProperty.MIRROR])
    }

    @Test
    fun `the palette chip shows the chosen palette, the default when none is set`() {
        assertEquals(SharedProperty.PALETTE.default.toInt(), model.palette)
        val other = customization.copy(shared = customization.shared + (SharedProperty.PALETTE to 7f))
        assertEquals(7, CustomizationState(other).toCustomizeModel().palette)
    }

    @Test
    fun `links are grouped by target, in the order the targets first appear`() {
        assertEquals(listOf(Target.Shared(SharedProperty.ZOOM), Target.MacroSlot(0)), model.reactions.map { it.target })
        assertEquals(listOf(bassToZoom, kickToZoom), model.reactions[0].links.map { it.reaction })
        assertEquals(listOf(lfoToEnergy, bassToEnergy), model.reactions[1].links.map { it.reaction })
    }

    @Test
    fun `a Modulator shows whether anything listens to it, since an unlinked one does not run`() {
        assertEquals(listOf(lfo, drift), model.modulators.map { it.modulator })
        assertEquals(listOf(true, false), model.modulators.map { it.linked })
    }

    @Test
    fun `the Shared core is one list of sliders per group, Camera first, with each property's range and kind`() {
        assertEquals(SharedGroup.entries, model.shared.keys.toList())
        val camera = model.shared.getValue(SharedGroup.CAMERA)
        assertEquals(SharedProperty.entries.filter { it.group == SharedGroup.CAMERA }.map { Target.Shared(it) }, camera.map { it.target })
        val zoom = camera.first()
        assertEquals(1.5f, zoom.value)
        assertEquals(0.3f, zoom.min)
        assertEquals(3f, zoom.max)
        assertEquals(ValueKind.CONTINUOUS, zoom.kind)
        assertTrue("Zoom has Reactions", zoom.linked)
        val rotation = camera.first { it.target == Target.Shared(SharedProperty.ROTATION) }
        assertEquals(SharedProperty.ROTATION.default, rotation.value)
        assertFalse(rotation.linked)
        val kaleidoscope = model.shared.getValue(SharedGroup.KALEIDOSCOPE_AND_MIRROR).first()
        assertEquals(ValueKind.SWITCH, kaleidoscope.kind)
    }

    @Test
    fun `Extras are 0 to 1 sliders, only the ones the Style has, in the catalogue's order`() {
        assertEquals(listOf(Target.Extra(ExtraKind.SCALE), Target.Extra(ExtraKind.FLIGHT)), model.extras.map { it.target })
        assertEquals(listOf(0.7f, 0.3f), model.extras.map { it.value })
        assertTrue(model.extras.all { it.min == 0f && it.max == 1f && it.kind == ValueKind.CONTINUOUS })
    }

    @Test
    fun `a new link can come from every Sound signal and every Modulator`() {
        val expected =
            SoundSignal.entries.map { ReactionSource.Sound(it) } +
                ReactionSource.Generated(ModulatorId(1)) +
                ReactionSource.Generated(ModulatorId(2))
        assertEquals(expected, model.sources)
    }

    @Test
    fun `a new link can go to any slider, a Macro or another Modulator's rate, depth or pattern`() {
        val targets = model.targets
        assertEquals(SharedProperty.entries.size + 2 + MACRO_COUNT + 2 * ModulatorParameter.entries.size, targets.size)
        assertTrue(Target.Shared(SharedProperty.INVERT) in targets)
        assertTrue(Target.Extra(ExtraKind.FLIGHT) in targets)
        assertFalse(Target.Extra(ExtraKind.COUNT) in targets)
        assertTrue(Target.MacroSlot(5) in targets)
        assertTrue(Target.ModulatorField(ModulatorId(2), ModulatorParameter.PATTERN) in targets)
    }

    @Test
    fun `looks and presets are shown as given, the current Style's look marked`() {
        val looks = listOf(LookChip(SceneId("warp"), "Warp"), LookChip(tunnel, "Tunnel"))
        val presets = listOf(PresetChip(PresetId("Night drive"), "Night drive"))
        val shown = CustomizationState(customization).toCustomizeModel(looks = looks, presets = presets)

        assertEquals(looks, shown.looks)
        assertEquals(presets, shown.presets)
        assertFalse(shown.isCurrent(looks[0]))
        assertTrue(shown.isCurrent(looks[1]))
    }

    @Test
    fun `the A and B compare slots that keep something are offered`() {
        val state = CustomizationState(customization, compare = mapOf(CompareSlot.B to customization))
        assertEquals(setOf(CompareSlot.B), state.toCustomizeModel().kept)
        assertEquals(emptySet<CompareSlot>(), CustomizationState(customization).toCustomizeModel().kept)
    }

    @Test
    fun `the controls the Style ignores are greyed out, and only those`() {
        val ignored = setOf(Target.Shared(SharedProperty.SPEED), Target.Shared(SharedProperty.KALEIDOSCOPE), Target.MacroSlot(0))
        val shown = CustomizationState(customization).toCustomizeModel(ignored = ignored)

        val speed = shown.shared.getValue(SharedGroup.SPEED_AND_ENERGY).single { it.target == Target.Shared(SharedProperty.SPEED) }
        assertTrue(speed.ignored)
        assertFalse(shown.shared.values.flatten().single { it.target == Target.Shared(SharedProperty.ZOOM) }.ignored)
        assertTrue(shown.effects.single { it.property == SharedProperty.KALEIDOSCOPE }.ignored)
        assertFalse(shown.effects.single { it.property == SharedProperty.MIRROR }.ignored)
        assertEquals(listOf(true, false, false, false, false, false), shown.macros.map { it.ignored })
        assertFalse(model.shared.values.flatten().any { it.ignored } || model.effects.any { it.ignored } || model.macros.any { it.ignored })
    }
}
