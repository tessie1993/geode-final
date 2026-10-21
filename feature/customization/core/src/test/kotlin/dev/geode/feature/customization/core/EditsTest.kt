package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.MACRO_COUNT
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
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave
import dev.geode.feature.customization.core.Fixtures.JULIA
import dev.geode.feature.customization.core.Fixtures.KALEIDO
import dev.geode.feature.customization.core.Fixtures.TUNNEL
import dev.geode.feature.customization.core.Fixtures.julia
import dev.geode.feature.customization.core.Fixtures.styles
import dev.geode.feature.customization.core.Fixtures.tunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EditsTest {
    private val zoom = Target.Shared(SharedProperty.ZOOM)
    private val bassToZoom = Reaction(ReactionId(0), ReactionSource.Sound(SoundSignal.BASS), zoom, amount = 2f)
    private val lfo = Modulator(ModulatorId(0), ModulatorShape.Lfo(Wave.SINE, Millis(1_000)))

    @Test
    fun `shared and extra values are kept in range`() {
        assertEquals(3f, Edits.setShared(julia, SharedProperty.ZOOM, 10f).shared[SharedProperty.ZOOM])
        assertEquals(1f, Edits.setExtra(julia, ExtraKind.CENTER_X, 2f).extras[ExtraKind.CENTER_X])
        assertEquals(0f, Edits.setExtra(julia, ExtraKind.CENTER_X, -1f).extras[ExtraKind.CENTER_X])
        assertSame(julia, Edits.setExtra(julia, ExtraKind.FLIGHT, 0.5f))
    }

    @Test
    fun `macros are set, renamed and cleared by place`() {
        assertEquals(1f, Edits.setMacro(julia, 0, 2f).macros[0].value)
        assertEquals(julia, Edits.setMacro(julia, MACRO_COUNT, 1f))
        assertEquals(julia, Edits.setMacro(julia, -1, 1f))
        assertEquals("Punch", Edits.renameMacro(julia, 2, "  Punch  ").macros[2].name)
        assertEquals("Energy", Edits.renameMacro(julia, 2, "  Punch  ").macros[0].name)
        assertSame(julia, Edits.renameMacro(julia, 2, "   "))
        assertEquals(MAX_MACRO_NAME, Edits.renameMacro(julia, 0, "x".repeat(40)).macros[0].name.length)
        assertEquals(emptyList<MacroLink>(), Edits.clearMacro(julia, 1).macros[1].links)
        assertEquals(julia.macros[0], Edits.clearMacro(julia, 1).macros[0])
    }

    @Test
    fun `a macro link replaces the one to the same target`() {
        val speed = Target.Shared(SharedProperty.SPEED)
        val relinked = Edits.linkMacro(julia, 0, MacroLink(speed, -1f, 2f)).macros[0].links
        assertEquals(julia.macros[0].links.size, relinked.size)
        assertEquals(MacroLink(speed, 0f, 1f), relinked.last())
        val added = Edits.linkMacro(julia, 0, MacroLink(Target.MacroSlot(3), 0f, 1f)).macros[0].links
        assertEquals(julia.macros[0].links + MacroLink(Target.MacroSlot(3), 0f, 1f), added)
        assertSame(julia, Edits.linkMacro(julia, 0, MacroLink(Target.MacroSlot(0), 0f, 1f)))
        assertSame(julia, Edits.linkMacro(julia, 0, MacroLink(Target.Extra(ExtraKind.FLIGHT), 0f, 1f)))
        assertEquals(julia.macros[0].links.drop(1), Edits.unlinkMacro(julia, 0, speed).macros[0].links)
    }

    @Test
    fun `a new reaction gets the next id and is put in order`() {
        val added = Edits.addReaction(julia, bassToZoom)
        assertEquals(bassToZoom.copy(id = ReactionId(julia.reactions.size + 1), amount = 1f), added.reactions.last())
        val first = Edits.addReaction(julia.copy(reactions = emptyList()), bassToZoom)
        assertEquals(listOf(ReactionId(1)), first.reactions.map { it.id })
        assertSame(julia, Edits.addReaction(julia, bassToZoom.copy(target = Target.Extra(ExtraKind.FLIGHT))))
        assertSame(julia, Edits.addReaction(julia, bassToZoom.copy(source = ReactionSource.Generated(ModulatorId(1)))))
    }

    @Test
    fun `a reaction is updated only when it exists and still links`() {
        val first = julia.reactions.first()
        val updated = Edits.updateReaction(julia, first.copy(amount = 3f)).reactions
        assertEquals(first.copy(amount = 1f), updated.first())
        assertEquals(julia.reactions.drop(1), updated.drop(1))
        assertSame(julia, Edits.updateReaction(julia, first.copy(id = ReactionId(99))))
        assertSame(julia, Edits.updateReaction(julia, first.copy(target = Target.Extra(ExtraKind.FLIGHT))))
        assertEquals(julia.reactions.drop(1), Edits.removeReaction(julia, first.id).reactions)
    }

    @Test
    fun `modulators get fresh ids and stay in range`() {
        val one = Edits.addModulator(julia, lfo.copy(rate = 10f))
        val two = Edits.addModulator(one, lfo)
        assertEquals(listOf(ModulatorId(1), ModulatorId(2)), two.modulators.map { it.id })
        assertEquals(Modulator.MAX_RATE, one.modulators.single().rate)
        val updated = Edits.updateModulator(two, two.modulators[1].copy(depth = 3f))
        assertEquals(1f, updated.modulators[1].depth)
        assertEquals(two.modulators[0], updated.modulators[0])
        assertSame(two, Edits.updateModulator(two, lfo.copy(id = ModulatorId(9))))
    }

    @Test
    fun `removing a modulator removes what used it`() {
        val withLfo = Edits.addModulator(julia, lfo)
        val id = withLfo.modulators.single().id
        val rate = Target.ModulatorField(id, ModulatorParameter.RATE)
        val linked =
            Edits.linkMacro(
                Edits.addReaction(
                    Edits.addReaction(withLfo, bassToZoom.copy(target = rate)),
                    bassToZoom.copy(source = ReactionSource.Generated(id)),
                ),
                1,
                MacroLink(rate, 0f, 1f),
            )
        assertEquals(julia, Edits.removeModulator(linked, id))
        assertEquals(linked, Edits.removeModulator(linked, ModulatorId(9)))
    }

    @Test
    fun `only a modulator started by hand is played`() {
        val two = Edits.addModulator(Edits.addModulator(julia, lfo.copy(start = StartMode.ByHand)), lfo)
        val played = Edits.playModulator(two, ModulatorId(1), true)
        assertEquals(true, played.modulators[0].playing)
        assertEquals(false, Edits.playModulator(two, ModulatorId(2), true).modulators[1].playing)
        assertEquals(two, Edits.playModulator(two, ModulatorId(3), true))
        assertEquals(false, Edits.playModulator(played, ModulatorId(1), false).modulators[0].playing)
    }

    @Test
    fun `a signal trim is kept in range`() {
        assertEquals(SignalTrim(4f, 0f), Edits.trimSignal(julia, SoundSignal.BASS, SignalTrim(9f)).signals[SoundSignal.BASS])
    }

    @Test
    fun `switching Style keeps the shared changes and starts the Style's own parts fresh`() {
        val zoomed = Edits.setShared(julia, SharedProperty.ZOOM, 2f)
        val edited = Edits.trimSignal(Edits.addModulator(zoomed, lfo), SoundSignal.MID, SignalTrim(2f))
        val switched = Edits.switchStyle(edited, styles.manifestOf(JULIA), styles.manifestOf(TUNNEL))
        assertEquals(TUNNEL, switched.style)
        assertEquals(2f, switched.shared[SharedProperty.ZOOM])
        assertEquals(tunnel.extras, switched.extras)
        assertEquals(tunnel.reactions, switched.reactions)
        assertEquals(tunnel.macros, switched.macros)
        assertEquals(emptyList<Modulator>(), switched.modulators)
        assertEquals(edited.signals, switched.signals)
        // An untouched shared value takes the new Style's own start: kaleido starts with its folds on.
        assertEquals(1f, Edits.switchStyle(julia, styles.manifestOf(JULIA), styles.manifestOf(KALEIDO)).shared[SharedProperty.KALEIDOSCOPE])
        val kaleido = Fixtures.defaults(KALEIDO)
        assertEquals(
            0f,
            Edits.switchStyle(kaleido, styles.manifestOf(KALEIDO), styles.manifestOf(JULIA)).shared[SharedProperty.KALEIDOSCOPE],
        )
    }
}
