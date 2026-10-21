package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.core.Fixtures.JULIA
import dev.geode.feature.customization.core.Fixtures.TUNNEL
import dev.geode.feature.customization.core.Fixtures.julia
import dev.geode.feature.customization.core.Fixtures.styles
import dev.geode.feature.customization.core.Fixtures.tunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetRulesTest {
    /** julia with something changed in every part. */
    private val edited =
        Edits.addReaction(
            Edits.addModulator(
                Edits.trimSignal(
                    Edits.setMacro(
                        Edits.setExtra(
                            Edits.setShared(Edits.setShared(julia, SharedProperty.ZOOM, 2f), SharedProperty.SATURATION, 0.5f),
                            ExtraKind.CENTER_X,
                            0.9f,
                        ),
                        0,
                        0.9f,
                    ),
                    SoundSignal.BASS,
                    SignalTrim(2f),
                ),
                Modulator(ModulatorId(0), ModulatorShape.RandomDrift(Millis(3_000))),
            ),
            Reaction(ReactionId(0), ReactionSource.Generated(ModulatorId(1)), Target.Shared(SharedProperty.TWIST)),
        )

    @Test
    fun `the shared parts are the Shared core groups and the sound trims`() {
        assertTrue(PresetRules.shared(PresetPart.CAMERA))
        assertTrue(PresetRules.shared(PresetPart.SOUND))
        assertFalse(PresetRules.shared(PresetPart.STYLE))
        assertFalse(PresetRules.shared(PresetPart.EXTRAS))
        assertFalse(PresetRules.shared(PresetPart.REACTIONS))
        assertEquals(SharedGroup.entries.toSet(), PresetPart.entries.mapNotNull { it.group }.toSet())
    }

    @Test
    fun `a preset holds only the ticked parts`() {
        val camera = PresetRules.capture("  Mine ", edited, setOf(PresetPart.CAMERA))
        assertEquals(PRESET_VERSION, camera.version)
        assertEquals("Mine", camera.name)
        assertEquals(JULIA, camera.style)
        assertEquals(setOf(PresetPart.CAMERA), camera.parts)
        assertEquals(edited.shared.filterKeys { it.group == SharedGroup.CAMERA }, camera.shared)
        assertTrue(camera.extras.isEmpty() && camera.macros.isEmpty() && camera.reactions.isEmpty())
        assertTrue(camera.modulators.isEmpty() && camera.signals.isEmpty())
        val all = PresetRules.capture("All", edited, PresetRules.ALL_PARTS)
        assertEquals(edited.shared, all.shared)
        assertEquals(edited.extras, all.extras)
        assertEquals(edited.macros, all.macros)
        assertEquals(edited.reactions, all.reactions)
        assertEquals(edited.modulators, all.modulators)
        assertEquals(edited.signals, all.signals)
    }

    @Test
    fun `a whole preset on its own Style brings everything back`() {
        assertEquals(edited, PresetRules.apply(julia, PresetRules.capture("All", edited, PresetRules.ALL_PARTS), styles))
    }

    @Test
    fun `a partial preset applies only what it kept`() {
        val camera = PresetRules.capture("Camera", edited, setOf(PresetPart.CAMERA)).copy(shared = edited.shared)
        val applied = PresetRules.apply(julia, camera, styles)
        assertEquals(Edits.setShared(julia, SharedProperty.ZOOM, 2f), applied)
    }

    @Test
    fun `on another Style only the shared parts apply`() {
        val all = PresetRules.capture("All", edited, PresetRules.ALL_PARTS - PresetPart.STYLE)
        val applied = PresetRules.apply(tunnel, all, styles)
        assertEquals(TUNNEL, applied.style)
        assertEquals(edited.shared, applied.shared)
        assertEquals(edited.signals, applied.signals)
        assertEquals(tunnel.extras, applied.extras)
        assertEquals(tunnel.macros, applied.macros)
        assertEquals(tunnel.reactions, applied.reactions)
        assertEquals(tunnel.modulators, applied.modulators)
    }

    @Test
    fun `a kept Style is switched to first`() {
        assertEquals(edited, PresetRules.apply(tunnel, PresetRules.capture("All", edited, PresetRules.ALL_PARTS), styles))
        val gone = PresetRules.capture("Gone", edited, PresetRules.ALL_PARTS).copy(style = SceneId("silk_web"))
        val applied = PresetRules.apply(tunnel, gone, styles)
        assertEquals(TUNNEL, applied.style)
        assertEquals(tunnel.extras, applied.extras)
        assertEquals(edited.shared, applied.shared)
    }

    @Test
    fun `values from a preset are kept in range and links to nothing are dropped`() {
        val wild =
            PresetRules.capture("Wild", edited, PresetRules.ALL_PARTS).copy(
                shared = mapOf(SharedProperty.ZOOM to 99f),
                extras = mapOf(ExtraKind.CENTER_X to 2f, ExtraKind.FLIGHT to 0.4f),
                signals = mapOf(SoundSignal.MID to SignalTrim(9f)),
                modulators = emptyList(),
                macros = edited.macros.take(2),
            )
        val applied = PresetRules.apply(julia, wild, styles)
        assertEquals(3f, applied.shared[SharedProperty.ZOOM])
        assertEquals(1f, applied.extras[ExtraKind.CENTER_X])
        assertFalse(ExtraKind.FLIGHT in applied.extras)
        assertEquals(SignalTrim(4f), applied.signals[SoundSignal.MID])
        assertEquals(julia.macros, applied.macros)
        assertEquals(julia.reactions, applied.reactions)
    }
}
