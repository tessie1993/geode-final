package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.MacroRole
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave

/**
 * The made-up Customizations the previews and screenshots show: a tunnel-like Style mid-edit. They are
 * sample data for the App UI only, not a Style's real manifest (that lives in Customization's core).
 */
internal object PreviewModels {
    private val tunnel = SceneId("tunnel")
    private val lfo = Modulator(ModulatorId(1), ModulatorShape.Lfo(Wave.SINE, Millis(4_000), beatSync = true))
    private val sweep = Modulator(ModulatorId(2), ModulatorShape.Lfo(Wave.TRIANGLE, Millis(8_000)), StartMode.ByHand)
    private val steps =
        Modulator(
            ModulatorId(3),
            ModulatorShape.StepSequencer(listOf(1f, 0f, 0.5f, 0f, 1f, 0.25f, 0.75f, 0f), Millis(250), beatSync = true),
        )
    private val hits = Modulator(ModulatorId(4), ModulatorShape.HitEnvelope(SoundSignal.KICK, Millis(30), Millis(450), 0.5f))

    private fun link(
        id: Int,
        source: ReactionSource,
        target: Target,
        amount: Float,
    ) = Reaction(ReactionId(id), source, target, amount = amount)

    private val reactions =
        listOf(
            link(1, ReactionSource.Sound(SoundSignal.ENERGY), Target.Extra(ExtraKind.FLIGHT), 0.7f),
            link(2, ReactionSource.Sound(SoundSignal.BASS), Target.Shared(SharedProperty.ZOOM), 0.35f),
            link(3, ReactionSource.Sound(SoundSignal.KICK), Target.Shared(SharedProperty.RIPPLE), 0.5f)
                .copy(fall = Millis(300), curve = ResponseCurve.EXPONENTIAL),
            link(4, ReactionSource.Sound(SoundSignal.BASS), Target.MacroSlot(0), 0.6f),
            link(5, ReactionSource.Generated(ModulatorId(1)), Target.MacroSlot(3), 0.4f),
            link(6, ReactionSource.Generated(ModulatorId(1)), Target.ModulatorField(ModulatorId(2), ModulatorParameter.RATE), 0.3f),
            link(7, ReactionSource.Generated(ModulatorId(2)), Target.ModulatorField(ModulatorId(1), ModulatorParameter.DEPTH), 0.2f),
            link(8, ReactionSource.Sound(SoundSignal.HAT), Target.Shared(SharedProperty.GLOW), 0.25f)
                .copy(rise = Millis(120), invert = true),
        )

    private fun macro(
        role: MacroRole,
        value: Float,
        vararg links: MacroLink,
    ) = Macro(role.title, value, links.toList())

    private val customization =
        Customization(
            style = tunnel,
            shared =
                mapOf(
                    SharedProperty.ZOOM to 1.2f,
                    SharedProperty.KALEIDOSCOPE to 1f,
                    SharedProperty.SYMMETRY to 6f,
                    SharedProperty.TRAILS to 0.6f,
                    SharedProperty.PALETTE to 3f,
                    SharedProperty.GLOW to 0.4f,
                ),
            extras =
                mapOf(
                    ExtraKind.SCALE to 0.5f,
                    ExtraKind.FLIGHT to 0.3f,
                    ExtraKind.SPIN to 0.25f,
                    ExtraKind.EDGE to 0.6f,
                ),
            macros =
                listOf(
                    macro(
                        MacroRole.ENERGY,
                        0.72f,
                        MacroLink(Target.Shared(SharedProperty.SPEED), 0.2f, 0.9f),
                        MacroLink(Target.Extra(ExtraKind.FLIGHT), 0f, 1f),
                    ),
                    macro(MacroRole.DEPTH, 0.5f, MacroLink(Target.Shared(SharedProperty.PUSH_IN), 0f, 0.8f)),
                    macro(MacroRole.WARMTH, 0.35f, MacroLink(Target.Shared(SharedProperty.TEMPERATURE), 0.3f, 0.9f)),
                    macro(MacroRole.FLOW, 0.6f, MacroLink(Target.Shared(SharedProperty.WARP), 0f, 0.7f)),
                    macro(MacroRole.DETAIL, 0.5f, MacroLink(Target.Shared(SharedProperty.DETAIL), 0.2f, 1f)),
                    macro(MacroRole.CALM, 0.2f, MacroLink(Target.Shared(SharedProperty.AUDIO_DRIVE), 1f, 0.2f)),
                ),
            reactions = reactions,
            modulators = listOf(lfo, sweep, steps, hits),
            signals = emptyMap(),
        )

    private val looks =
        listOf(
            LookChip(tunnel, "Tunnel"),
            LookChip(SceneId("warp"), "Star warp"),
            LookChip(SceneId("grid"), "Synth grid"),
            LookChip(SceneId("hexgrid"), "Hexgrid"),
            LookChip(SceneId("starfield"), "Starfield"),
        )

    private val presets = listOf(PresetChip(PresetId("Night drive"), "Night drive"), PresetChip(PresetId("Slow bloom"), "Slow bloom"))

    private val live = LiveMeters(macros = mapOf(0 to 0.86f, 3 to 0.48f), reactions = mapOf(ReactionId(1) to 0.8f, ReactionId(2) to 0.3f))

    /** Mid-edit: history to undo, presets saved, live values coming in. */
    val editing: CustomizeModel =
        CustomizationState(customization, undo = listOf(customization)).toCustomizeModel(looks, presets, live)

    /** A Style just opened: its defaults, nothing linked yet, nothing to undo, no presets. */
    val fresh: CustomizeModel =
        CustomizationState(
            customization.copy(
                shared = emptyMap(),
                macros = MacroRole.entries.map { Macro(it.title) },
                reactions = emptyList(),
                modulators = emptyList(),
            ),
        ).toCustomizeModel(looks)

    /** A new link from Bass to Brightness at its last step, where the No-flash note shows. */
    val draft: Reaction = newReaction(ReactionSource.Sound(SoundSignal.BASS), Target.Shared(SharedProperty.BRIGHTNESS))
}
