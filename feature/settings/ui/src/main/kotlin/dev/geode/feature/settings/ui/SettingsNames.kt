package dev.geode.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.geode.feature.settings.api.CornerStyle
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.EditSources
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.ExportCodec
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportQuality
import dev.geode.feature.settings.api.ExportRatio
import dev.geode.feature.settings.api.PlayerPosition
import dev.geode.feature.settings.api.ReplayGainMode
import dev.geode.feature.settings.api.ThemeMode
import dev.geode.feature.settings.api.TiltAmount
import dev.geode.feature.settings.api.VisualFrameRate
import dev.geode.feature.settings.api.VisualQuality
import dev.geode.feature.settings.api.VisualSurface

// What each choice is called on screen. The names are this module's strings (ADR-0005).

@Composable
internal fun themeModeName(mode: ThemeMode): String =
    stringResource(
        when (mode) {
            ThemeMode.SYSTEM -> R.string.look_theme_system
            ThemeMode.DARK -> R.string.look_theme_dark
            ThemeMode.LIGHT -> R.string.look_theme_light
        },
    )

@Composable
internal fun positionName(position: PlayerPosition): String =
    stringResource(
        when (position) {
            PlayerPosition.TOP -> R.string.look_position_top
            PlayerPosition.BOTTOM -> R.string.look_position_bottom
        },
    )

@Composable
internal fun tiltName(amount: TiltAmount): String =
    stringResource(
        when (amount) {
            TiltAmount.OFF -> R.string.behavior_tilt_off
            TiltAmount.SUBTLE -> R.string.behavior_tilt_subtle
            TiltAmount.FULL -> R.string.behavior_tilt_full
        },
    )

@Composable
internal fun cornerName(style: CornerStyle): String =
    stringResource(
        when (style) {
            CornerStyle.SHARP -> R.string.look_corner_sharp
            CornerStyle.ROUNDED -> R.string.look_corner_rounded
            CornerStyle.PILL -> R.string.look_corner_pill
        },
    )

@Composable
internal fun replayGainName(mode: ReplayGainMode): String =
    stringResource(
        when (mode) {
            ReplayGainMode.OFF -> R.string.playback_replaygain_off
            ReplayGainMode.TRACK -> R.string.playback_replaygain_track
            ReplayGainMode.ALBUM -> R.string.playback_replaygain_album
        },
    )

@Composable
internal fun curveName(curve: CrossfadeCurve): String =
    stringResource(
        when (curve) {
            CrossfadeCurve.LINEAR -> R.string.playback_crossfade_linear
            CrossfadeCurve.EQUAL_POWER -> R.string.playback_crossfade_equal_power
            CrossfadeCurve.SMOOTH -> R.string.playback_crossfade_smooth
        },
    )

/** A null preset is the bands as set by hand. */
@Composable
internal fun presetName(preset: EqualizerPreset?): String =
    stringResource(
        when (preset) {
            null -> R.string.eq_preset_custom
            EqualizerPreset.FLAT -> R.string.eq_preset_flat
            EqualizerPreset.BASS -> R.string.eq_preset_bass
            EqualizerPreset.TREBLE -> R.string.eq_preset_treble
            EqualizerPreset.VOCAL -> R.string.eq_preset_vocal
            EqualizerPreset.ROCK -> R.string.eq_preset_rock
            EqualizerPreset.ELECTRONIC -> R.string.eq_preset_electronic
        },
    )

@Composable
internal fun qualityName(quality: ExportQuality): String =
    stringResource(
        when (quality) {
            ExportQuality.SD480 -> R.string.export_quality_480
            ExportQuality.HD720 -> R.string.export_quality_720
            ExportQuality.FHD1080 -> R.string.export_quality_1080
            ExportQuality.QHD1440 -> R.string.export_quality_1440
            ExportQuality.UHD4K -> R.string.export_quality_4k
        },
    )

@Composable
internal fun ratioName(ratio: ExportRatio): String =
    stringResource(
        when (ratio) {
            ExportRatio.R16_9 -> R.string.export_ratio_16_9
            ExportRatio.R9_16 -> R.string.export_ratio_9_16
            ExportRatio.R1_1 -> R.string.export_ratio_1_1
            ExportRatio.R4_5 -> R.string.export_ratio_4_5
            ExportRatio.R4_3 -> R.string.export_ratio_4_3
            ExportRatio.R21_9 -> R.string.export_ratio_21_9
        },
    )

@Composable
internal fun codecName(codec: ExportCodec): String =
    stringResource(
        when (codec) {
            ExportCodec.H264 -> R.string.export_codec_h264
            ExportCodec.HEVC -> R.string.export_codec_hevc
        },
    )

@Composable
internal fun editSourcesName(sources: EditSources): String =
    stringResource(
        when (sources) {
            EditSources.REFERENCE -> R.string.export_edit_sources_reference
            EditSources.COPY -> R.string.export_edit_sources_copy
        },
    )

@Composable
internal fun loudnessName(loudness: ExportLoudness): String =
    stringResource(
        when (loudness) {
            ExportLoudness.YOUTUBE -> R.string.export_loudness_youtube
            ExportLoudness.SHORTS_AND_TIKTOK -> R.string.export_loudness_shorts
            ExportLoudness.LEAVE_AS_IS -> R.string.export_loudness_as_is
        },
    )

@Composable
internal fun visualQualityName(quality: VisualQuality): String =
    stringResource(
        when (quality) {
            VisualQuality.HIGH -> R.string.cpu_gpu_quality_high
            VisualQuality.BALANCED -> R.string.cpu_gpu_quality_balanced
            VisualQuality.SAVER -> R.string.cpu_gpu_quality_saver
        },
    )

/** A frame rate with no cap is the screen's maximum. */
@Composable
internal fun visualFrameRateName(frameRate: VisualFrameRate): String =
    frameRate.fps?.let { stringResource(R.string.cpu_gpu_fps, it) } ?: stringResource(R.string.cpu_gpu_screen_max)

@Composable
internal fun visualSurfaceName(surface: VisualSurface): String =
    stringResource(
        when (surface) {
            VisualSurface.VISUAL -> R.string.cpu_gpu_surface_visual
            VisualSurface.WALLPAPER -> R.string.cpu_gpu_surface_wallpaper
            VisualSurface.PICTURE_IN_PICTURE -> R.string.cpu_gpu_surface_pip
            VisualSurface.SCREEN_SAVER -> R.string.cpu_gpu_surface_screen_saver
        },
    )
