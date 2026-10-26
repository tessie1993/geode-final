package dev.geode.feature.customization.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.LayerBlend
import dev.geode.feature.customization.api.LayerInfluence
import dev.geode.feature.customization.api.LoopPreset
import dev.geode.feature.customization.api.LoopRate
import dev.geode.feature.customization.api.LoopShape
import dev.geode.feature.customization.api.LoopTarget
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave

// What each part of a Customization is called on screen. The names are this module's strings
// (ADR-0005); a Macro's name is the person's own.

/** A Modulator by its kind and number, such as "LFO 1". */
@Composable
internal fun modulatorName(
    id: ModulatorId,
    model: CustomizeModel,
): String {
    val modulator = model.modulators.firstOrNull { it.modulator.id == id }?.modulator
    return if (modulator == null) {
        stringResource(R.string.customize_modulator_unknown, id.value)
    } else {
        stringResource(R.string.customize_modulator_label, kindName(modulator.shape.kind), id.value)
    }
}

@Composable
internal fun sourceName(
    source: ReactionSource,
    model: CustomizeModel,
): String =
    when (source) {
        is ReactionSource.Sound -> signalName(source.signal)
        is ReactionSource.Generated -> modulatorName(source.modulator, model)
    }

@Composable
internal fun targetName(
    target: Target,
    model: CustomizeModel,
): String =
    when (target) {
        is Target.Shared -> propertyName(target.property)
        is Target.Extra -> extraName(target.kind)
        is Target.MacroSlot ->
            stringResource(
                R.string.customize_macro_slot,
                model.macros
                    .getOrNull(target.index)
                    ?.name
                    .orEmpty(),
            )
        is Target.ModulatorField ->
            stringResource(
                R.string.customize_modulator_target,
                modulatorName(target.modulator, model),
                parameterName(target.parameter),
            )
    }

@Composable
internal fun propertyName(property: SharedProperty): String =
    stringResource(
        when (property) {
            SharedProperty.ZOOM -> R.string.customize_property_zoom
            SharedProperty.PUSH_IN -> R.string.customize_property_push_in
            SharedProperty.ROTATION -> R.string.customize_property_rotation
            SharedProperty.ORBIT_YAW -> R.string.customize_property_orbit_yaw
            SharedProperty.ORBIT_PITCH -> R.string.customize_property_orbit_pitch
            SharedProperty.TRAVEL -> R.string.customize_property_travel
            SharedProperty.SWAY -> R.string.customize_property_sway
            SharedProperty.SHAKE -> R.string.customize_property_shake
            SharedProperty.DRIFT_X -> R.string.customize_property_drift_x
            SharedProperty.DRIFT_Y -> R.string.customize_property_drift_y
            SharedProperty.ENDLESS_ZOOM -> R.string.customize_property_endless_zoom
            SharedProperty.DIVE_SPEED -> R.string.customize_property_dive_speed
            SharedProperty.PALETTE -> R.string.customize_property_palette
            SharedProperty.PALETTE_2 -> R.string.customize_property_palette_2
            SharedProperty.PALETTE_MIX -> R.string.customize_property_palette_mix
            SharedProperty.DUOTONE -> R.string.customize_property_duotone
            SharedProperty.LUT -> R.string.customize_property_lut
            SharedProperty.MORPH -> R.string.customize_property_morph
            SharedProperty.HUE_SHIFT -> R.string.customize_property_hue_shift
            SharedProperty.HUE_RANGE -> R.string.customize_property_hue_range
            SharedProperty.COLOUR_CYCLE -> R.string.customize_property_colour_cycle
            SharedProperty.CYCLE_SPEED -> R.string.customize_property_cycle_speed
            SharedProperty.SPEED -> R.string.customize_property_speed
            SharedProperty.AUDIO_DRIVE -> R.string.customize_property_audio_drive
            SharedProperty.TURBULENCE -> R.string.customize_property_turbulence
            SharedProperty.DETAIL -> R.string.customize_property_detail
            SharedProperty.GLOW -> R.string.customize_property_glow
            SharedProperty.KALEIDOSCOPE -> R.string.customize_property_kaleidoscope
            SharedProperty.SYMMETRY -> R.string.customize_property_symmetry
            SharedProperty.MIRROR -> R.string.customize_property_mirror
            SharedProperty.TRAILS -> R.string.customize_property_trails
            SharedProperty.TRAIL_ZOOM -> R.string.customize_property_trail_zoom
            SharedProperty.TRAIL_WARP -> R.string.customize_property_trail_warp
            SharedProperty.GRAIN -> R.string.customize_property_grain
            SharedProperty.VIGNETTE -> R.string.customize_property_vignette
            SharedProperty.CHROMATIC -> R.string.customize_property_chromatic
            SharedProperty.SCANLINES -> R.string.customize_property_scanlines
            SharedProperty.FISHEYE -> R.string.customize_property_fisheye
            SharedProperty.SOFT_FOCUS -> R.string.customize_property_soft_focus
            SharedProperty.MOTION_BLUR -> R.string.customize_property_motion_blur
            SharedProperty.WARP -> R.string.customize_property_warp
            SharedProperty.RIPPLE -> R.string.customize_property_ripple
            SharedProperty.TWIST -> R.string.customize_property_twist
            SharedProperty.TILE -> R.string.customize_property_tile
            SharedProperty.GLITCH -> R.string.customize_property_glitch
            SharedProperty.PIXELATE -> R.string.customize_property_pixelate
            SharedProperty.POSTERIZE -> R.string.customize_property_posterize
            SharedProperty.SOLARIZE -> R.string.customize_property_solarize
            SharedProperty.PARTICLES -> R.string.customize_property_particles
            SharedProperty.PARTICLE_KIND -> R.string.customize_property_particle_kind
            SharedProperty.PARTICLE_SIZE -> R.string.customize_property_particle_size
            SharedProperty.SATURATION -> R.string.customize_property_saturation
            SharedProperty.BRIGHTNESS -> R.string.customize_property_brightness
            SharedProperty.INTENSITY -> R.string.customize_property_intensity
            SharedProperty.CONTRAST -> R.string.customize_property_contrast
            SharedProperty.GAMMA -> R.string.customize_property_gamma
            SharedProperty.TEMPERATURE -> R.string.customize_property_temperature
            SharedProperty.INVERT -> R.string.customize_property_invert
            SharedProperty.ANAGLYPH -> R.string.customize_property_anaglyph
            SharedProperty.LAYER_OPACITY -> R.string.customize_property_layer_opacity
            SharedProperty.LAYER_INFLUENCE -> R.string.customize_property_layer_influence
            SharedProperty.LAYER_SWAP -> R.string.customize_property_layer_swap
        },
    )

@Composable
internal fun groupName(group: SharedGroup): String =
    stringResource(
        when (group) {
            SharedGroup.CAMERA -> R.string.customize_group_camera
            SharedGroup.COLOUR -> R.string.customize_group_colour
            SharedGroup.SPEED_AND_ENERGY -> R.string.customize_group_speed_and_energy
            SharedGroup.DETAIL_AND_GLOW -> R.string.customize_group_detail_and_glow
            SharedGroup.KALEIDOSCOPE_AND_MIRROR -> R.string.customize_group_kaleidoscope_and_mirror
            SharedGroup.TRAILS_AND_ECHO -> R.string.customize_group_trails_and_echo
            SharedGroup.LENS -> R.string.customize_group_lens
            SharedGroup.WARP_AND_RIPPLE -> R.string.customize_group_warp_and_ripple
            SharedGroup.GLITCH_AND_PIXELATE -> R.string.customize_group_glitch_and_pixelate
            SharedGroup.PARTICLES -> R.string.customize_group_particles
            SharedGroup.COLOUR_GRADING -> R.string.customize_group_colour_grading
            SharedGroup.LAYERS -> R.string.customize_group_layers
        },
    )

@Composable
internal fun extraName(kind: ExtraKind): String =
    stringResource(
        when (kind) {
            ExtraKind.SCALE -> R.string.customize_extra_scale
            ExtraKind.COUNT -> R.string.customize_extra_count
            ExtraKind.SIZE -> R.string.customize_extra_size
            ExtraKind.THICKNESS -> R.string.customize_extra_thickness
            ExtraKind.FLIGHT -> R.string.customize_extra_flight
            ExtraKind.SPIN -> R.string.customize_extra_spin
            ExtraKind.ORBIT -> R.string.customize_extra_orbit
            ExtraKind.SWIRL -> R.string.customize_extra_swirl
            ExtraKind.DRIFT -> R.string.customize_extra_drift
            ExtraKind.COMPLEXITY -> R.string.customize_extra_complexity
            ExtraKind.EDGE -> R.string.customize_extra_edge
            ExtraKind.FOG -> R.string.customize_extra_fog
            ExtraKind.DECAY -> R.string.customize_extra_decay
            ExtraKind.VISCOSITY -> R.string.customize_extra_viscosity
            ExtraKind.GROWTH -> R.string.customize_extra_growth
            ExtraKind.SPREAD -> R.string.customize_extra_spread
            ExtraKind.FORCE -> R.string.customize_extra_force
            ExtraKind.REACH -> R.string.customize_extra_reach
            ExtraKind.TURN -> R.string.customize_extra_turn
            ExtraKind.HOLD -> R.string.customize_extra_hold
            ExtraKind.VARIANT -> R.string.customize_extra_variant
            ExtraKind.CENTER_X -> R.string.customize_extra_center_x
            ExtraKind.CENTER_Y -> R.string.customize_extra_center_y
        },
    )

@Composable
internal fun signalName(signal: SoundSignal): String =
    stringResource(
        when (signal) {
            SoundSignal.LEVEL -> R.string.customize_signal_level
            SoundSignal.BASS -> R.string.customize_signal_bass
            SoundSignal.MID -> R.string.customize_signal_mid
            SoundSignal.TREBLE -> R.string.customize_signal_treble
            SoundSignal.BRIGHTNESS -> R.string.customize_signal_brightness
            SoundSignal.HIT -> R.string.customize_signal_hit
            SoundSignal.KICK -> R.string.customize_signal_kick
            SoundSignal.MID_HIT -> R.string.customize_signal_mid_hit
            SoundSignal.HAT -> R.string.customize_signal_hat
            SoundSignal.BEAT -> R.string.customize_signal_beat
            SoundSignal.ENERGY -> R.string.customize_signal_energy
            SoundSignal.BUILD_UP -> R.string.customize_signal_build_up
            SoundSignal.SURGE -> R.string.customize_signal_surge
            SoundSignal.CHANGE -> R.string.customize_signal_change
            SoundSignal.STEREO_WIDTH -> R.string.customize_signal_stereo_width
            SoundSignal.STEREO_PAN -> R.string.customize_signal_stereo_pan
        },
    )

@Composable
internal fun waveName(wave: Wave): String =
    stringResource(
        when (wave) {
            Wave.SINE -> R.string.customize_wave_sine
            Wave.TRIANGLE -> R.string.customize_wave_triangle
            Wave.SAW -> R.string.customize_wave_saw
            Wave.SQUARE -> R.string.customize_wave_square
            Wave.RANDOM -> R.string.customize_wave_random
        },
    )

@Composable
internal fun curveName(curve: ResponseCurve): String =
    stringResource(
        when (curve) {
            ResponseCurve.LINEAR -> R.string.customize_curve_linear
            ResponseCurve.EXPONENTIAL -> R.string.customize_curve_exponential
            ResponseCurve.LOGARITHMIC -> R.string.customize_curve_logarithmic
            ResponseCurve.S_CURVE -> R.string.customize_curve_s_curve
        },
    )

@Composable
internal fun parameterName(parameter: ModulatorParameter): String =
    stringResource(
        when (parameter) {
            ModulatorParameter.RATE -> R.string.customize_modulator_parameter_rate
            ModulatorParameter.DEPTH -> R.string.customize_modulator_parameter_depth
            ModulatorParameter.PATTERN -> R.string.customize_modulator_parameter_pattern
        },
    )

@Composable
internal fun kindName(kind: ModulatorKind): String =
    stringResource(
        when (kind) {
            ModulatorKind.LFO -> R.string.customize_modulator_kind_lfo
            ModulatorKind.ENVELOPE -> R.string.customize_modulator_kind_envelope
            ModulatorKind.SEQUENCER -> R.string.customize_modulator_kind_sequencer
            ModulatorKind.DRIFT -> R.string.customize_modulator_kind_drift
        },
    )

@Composable
internal fun partName(part: PresetPart): String =
    stringResource(
        when (part) {
            PresetPart.STYLE -> R.string.customize_part_style
            PresetPart.CAMERA -> R.string.customize_group_camera
            PresetPart.COLOUR -> R.string.customize_group_colour
            PresetPart.SPEED_AND_ENERGY -> R.string.customize_group_speed_and_energy
            PresetPart.DETAIL_AND_GLOW -> R.string.customize_group_detail_and_glow
            PresetPart.KALEIDOSCOPE_AND_MIRROR -> R.string.customize_group_kaleidoscope_and_mirror
            PresetPart.TRAILS_AND_ECHO -> R.string.customize_group_trails_and_echo
            PresetPart.LENS -> R.string.customize_group_lens
            PresetPart.WARP_AND_RIPPLE -> R.string.customize_group_warp_and_ripple
            PresetPart.GLITCH_AND_PIXELATE -> R.string.customize_group_glitch_and_pixelate
            PresetPart.PARTICLES -> R.string.customize_group_particles
            PresetPart.COLOUR_GRADING -> R.string.customize_group_colour_grading
            PresetPart.LAYERS -> R.string.customize_group_layers
            PresetPart.SOUND -> R.string.customize_part_sound
            PresetPart.EXTRAS -> R.string.customize_part_extras
            PresetPart.MACROS -> R.string.customize_part_macros
            PresetPart.REACTIONS -> R.string.customize_part_reactions
            PresetPart.MODULATORS -> R.string.customize_part_modulators
        },
    )

@Composable
internal fun blendName(blend: LayerBlend): String =
    stringResource(
        when (blend) {
            LayerBlend.NORMAL -> R.string.customize_blend_normal
            LayerBlend.ADD -> R.string.customize_blend_add
            LayerBlend.SCREEN -> R.string.customize_blend_screen
            LayerBlend.LIGHTEN -> R.string.customize_blend_lighten
            LayerBlend.MULTIPLY -> R.string.customize_blend_multiply
            LayerBlend.OVERLAY -> R.string.customize_blend_overlay
            LayerBlend.SOFT_LIGHT -> R.string.customize_blend_soft_light
            LayerBlend.DIFFERENCE -> R.string.customize_blend_difference
            LayerBlend.EXCLUSION -> R.string.customize_blend_exclusion
            LayerBlend.LUMINOSITY -> R.string.customize_blend_luminosity
        },
    )

@Composable
internal fun influenceName(influence: LayerInfluence): String =
    stringResource(
        when (influence) {
            LayerInfluence.NONE -> R.string.customize_influence_none
            LayerInfluence.BEND -> R.string.customize_influence_bend
            LayerInfluence.SWIRL -> R.string.customize_influence_swirl
            LayerInfluence.CUT_OUT -> R.string.customize_influence_cut_out
            LayerInfluence.TINT -> R.string.customize_influence_tint
            LayerInfluence.OUTLINE -> R.string.customize_influence_outline
        },
    )

@Composable
internal fun loopShapeName(shape: LoopShape): String =
    stringResource(
        when (shape) {
            LoopShape.SINE -> R.string.customize_loop_shape_sine
            LoopShape.TRIANGLE -> R.string.customize_loop_shape_triangle
            LoopShape.PING_PONG -> R.string.customize_loop_shape_ping_pong
            LoopShape.BEAT_STEPS -> R.string.customize_loop_shape_beat_steps
        },
    )

@Composable
internal fun loopRateName(rate: LoopRate): String =
    stringResource(
        when (rate) {
            LoopRate.TWO_BEATS -> R.string.customize_loop_rate_two_beats
            LoopRate.ONE_BAR -> R.string.customize_loop_rate_one_bar
            LoopRate.FOUR_SECONDS -> R.string.customize_loop_rate_four_seconds
            LoopRate.EIGHT_SECONDS -> R.string.customize_loop_rate_eight_seconds
            LoopRate.SIXTEEN_SECONDS -> R.string.customize_loop_rate_sixteen_seconds
        },
    )

@Composable
internal fun loopTargetName(target: LoopTarget): String =
    stringResource(
        when (target) {
            LoopTarget.OPACITY -> R.string.customize_loop_target_opacity
            LoopTarget.INFLUENCE -> R.string.customize_loop_target_influence
            LoopTarget.SWAP -> R.string.customize_loop_target_swap
        },
    )

@Composable
internal fun loopPresetName(preset: LoopPreset): String =
    stringResource(
        when (preset) {
            LoopPreset.BREATHE -> R.string.customize_loop_preset_breathe
            LoopPreset.TRADE_PLACES -> R.string.customize_loop_preset_trade_places
            LoopPreset.PULSE -> R.string.customize_loop_preset_pulse
            LoopPreset.DRIFT -> R.string.customize_loop_preset_drift
        },
    )
