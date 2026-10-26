package dev.geode.feature.customization.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.Target

// The one-line help every Customize control carries (#393): Customize is deep, and one line saves
// guessing. A tap on a control's name shows it. The lines are this module's strings (ADR-0005).

/** [target]'s help, for the Shared core and the Extras; a Macro or a Modulator's field has none of its own. */
@Composable
internal fun targetHelp(target: Target): String? =
    when (target) {
        is Target.Shared -> propertyHelp(target.property)
        is Target.Extra -> extraHelp(target.kind)
        is Target.MacroSlot, is Target.ModulatorField -> null
    }

@Composable
internal fun propertyHelp(property: SharedProperty): String =
    stringResource(
        when (property) {
            SharedProperty.ZOOM -> R.string.customize_help_property_zoom
            SharedProperty.PUSH_IN -> R.string.customize_help_property_push_in
            SharedProperty.ROTATION -> R.string.customize_help_property_rotation
            SharedProperty.ORBIT_YAW -> R.string.customize_help_property_orbit_yaw
            SharedProperty.ORBIT_PITCH -> R.string.customize_help_property_orbit_pitch
            SharedProperty.TRAVEL -> R.string.customize_help_property_travel
            SharedProperty.SWAY -> R.string.customize_help_property_sway
            SharedProperty.SHAKE -> R.string.customize_help_property_shake
            SharedProperty.DRIFT_X -> R.string.customize_help_property_drift_x
            SharedProperty.DRIFT_Y -> R.string.customize_help_property_drift_y
            SharedProperty.ENDLESS_ZOOM -> R.string.customize_help_property_endless_zoom
            SharedProperty.DIVE_SPEED -> R.string.customize_help_property_dive_speed
            SharedProperty.FIELD_OF_VIEW -> R.string.customize_help_property_field_of_view
            SharedProperty.ROLL -> R.string.customize_help_property_roll
            SharedProperty.SPEED_RAMP -> R.string.customize_help_property_speed_ramp
            SharedProperty.PAN_X -> R.string.customize_help_property_pan_x
            SharedProperty.PAN_Y -> R.string.customize_help_property_pan_y
            SharedProperty.TURN -> R.string.customize_help_property_turn
            SharedProperty.PALETTE -> R.string.customize_help_property_palette
            SharedProperty.PALETTE_2 -> R.string.customize_help_property_palette_2
            SharedProperty.PALETTE_MIX -> R.string.customize_help_property_palette_mix
            SharedProperty.DUOTONE -> R.string.customize_help_property_duotone
            SharedProperty.LUT -> R.string.customize_help_property_lut
            SharedProperty.MORPH -> R.string.customize_help_property_morph
            SharedProperty.HUE_SHIFT -> R.string.customize_help_property_hue_shift
            SharedProperty.HUE_RANGE -> R.string.customize_help_property_hue_range
            SharedProperty.COLOUR_CYCLE -> R.string.customize_help_property_colour_cycle
            SharedProperty.CYCLE_SPEED -> R.string.customize_help_property_cycle_speed
            SharedProperty.SPEED -> R.string.customize_help_property_speed
            SharedProperty.AUDIO_DRIVE -> R.string.customize_help_property_audio_drive
            SharedProperty.TURBULENCE -> R.string.customize_help_property_turbulence
            SharedProperty.DETAIL -> R.string.customize_help_property_detail
            SharedProperty.GLOW -> R.string.customize_help_property_glow
            SharedProperty.KALEIDOSCOPE -> R.string.customize_help_property_kaleidoscope
            SharedProperty.SYMMETRY -> R.string.customize_help_property_symmetry
            SharedProperty.MIRROR -> R.string.customize_help_property_mirror
            SharedProperty.TRAILS -> R.string.customize_help_property_trails
            SharedProperty.TRAIL_ZOOM -> R.string.customize_help_property_trail_zoom
            SharedProperty.TRAIL_WARP -> R.string.customize_help_property_trail_warp
            SharedProperty.ECHO_COPY -> R.string.customize_help_property_echo_copy
            SharedProperty.ECHO_COPY_ZOOM -> R.string.customize_help_property_echo_copy_zoom
            SharedProperty.ECHO_COPY_FLIP -> R.string.customize_help_property_echo_copy_flip
            SharedProperty.GRAIN -> R.string.customize_help_property_grain
            SharedProperty.VIGNETTE -> R.string.customize_help_property_vignette
            SharedProperty.CHROMATIC -> R.string.customize_help_property_chromatic
            SharedProperty.SCANLINES -> R.string.customize_help_property_scanlines
            SharedProperty.FISHEYE -> R.string.customize_help_property_fisheye
            SharedProperty.SOFT_FOCUS -> R.string.customize_help_property_soft_focus
            SharedProperty.MOTION_BLUR -> R.string.customize_help_property_motion_blur
            SharedProperty.WARP -> R.string.customize_help_property_warp
            SharedProperty.RIPPLE -> R.string.customize_help_property_ripple
            SharedProperty.TWIST -> R.string.customize_help_property_twist
            SharedProperty.TILE -> R.string.customize_help_property_tile
            SharedProperty.GLITCH -> R.string.customize_help_property_glitch
            SharedProperty.PIXELATE -> R.string.customize_help_property_pixelate
            SharedProperty.POSTERIZE -> R.string.customize_help_property_posterize
            SharedProperty.SOLARIZE -> R.string.customize_help_property_solarize
            SharedProperty.PARTICLES -> R.string.customize_help_property_particles
            SharedProperty.PARTICLE_KIND -> R.string.customize_help_property_particle_kind
            SharedProperty.PARTICLE_SIZE -> R.string.customize_help_property_particle_size
            SharedProperty.SATURATION -> R.string.customize_help_property_saturation
            SharedProperty.BRIGHTNESS -> R.string.customize_help_property_brightness
            SharedProperty.INTENSITY -> R.string.customize_help_property_intensity
            SharedProperty.CONTRAST -> R.string.customize_help_property_contrast
            SharedProperty.GAMMA -> R.string.customize_help_property_gamma
            SharedProperty.TEMPERATURE -> R.string.customize_help_property_temperature
            SharedProperty.INVERT -> R.string.customize_help_property_invert
            SharedProperty.ANAGLYPH -> R.string.customize_help_property_anaglyph
            SharedProperty.LAYER_OPACITY -> R.string.customize_help_property_layer_opacity
            SharedProperty.LAYER_INFLUENCE -> R.string.customize_help_property_layer_influence
            SharedProperty.LAYER_SWAP -> R.string.customize_help_property_layer_swap
        },
    )

@Composable
internal fun extraHelp(kind: ExtraKind): String =
    stringResource(
        when (kind) {
            ExtraKind.SCALE -> R.string.customize_help_extra_scale
            ExtraKind.COUNT -> R.string.customize_help_extra_count
            ExtraKind.SIZE -> R.string.customize_help_extra_size
            ExtraKind.THICKNESS -> R.string.customize_help_extra_thickness
            ExtraKind.FLIGHT -> R.string.customize_help_extra_flight
            ExtraKind.SPIN -> R.string.customize_help_extra_spin
            ExtraKind.ORBIT -> R.string.customize_help_extra_orbit
            ExtraKind.SWIRL -> R.string.customize_help_extra_swirl
            ExtraKind.DRIFT -> R.string.customize_help_extra_drift
            ExtraKind.COMPLEXITY -> R.string.customize_help_extra_complexity
            ExtraKind.EDGE -> R.string.customize_help_extra_edge
            ExtraKind.FOG -> R.string.customize_help_extra_fog
            ExtraKind.DECAY -> R.string.customize_help_extra_decay
            ExtraKind.VISCOSITY -> R.string.customize_help_extra_viscosity
            ExtraKind.GROWTH -> R.string.customize_help_extra_growth
            ExtraKind.SPREAD -> R.string.customize_help_extra_spread
            ExtraKind.FORCE -> R.string.customize_help_extra_force
            ExtraKind.REACH -> R.string.customize_help_extra_reach
            ExtraKind.TURN -> R.string.customize_help_extra_turn
            ExtraKind.HOLD -> R.string.customize_help_extra_hold
            ExtraKind.VARIANT -> R.string.customize_help_extra_variant
            ExtraKind.CENTER_X -> R.string.customize_help_extra_center_x
            ExtraKind.CENTER_Y -> R.string.customize_help_extra_center_y
        },
    )
