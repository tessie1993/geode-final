package dev.geode.feature.studio.handlers

import dev.geode.feature.settings.api.ExportCodec
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportQuality
import dev.geode.feature.settings.api.ExportRatio
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.core.RenderEnd
import dev.geode.feature.studio.core.RenderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A Render's look, frozen when it was queued (#338): the full Customization, Automations included, as
 * `:app` encodes it. Studio only keeps it with the Render and hands it back to the [RenderEngine].
 */
@JvmInline
value class FrozenLook(
    val text: String,
)

/**
 * Freezes a Render's look as it is queued (#338). `:app` fills it: it reads the current Customization
 * through Customization's interface, applies a preset's parts for [RenderLook.Preset], and encodes the
 * result. It may throw; the handlers turn that into a named failure.
 */
fun interface RenderLooks {
    suspend fun freeze(look: RenderLook): FrozenLook
}

/**
 * Makes one Render's video (#316). `:app` fills it with the whole-track analysis, the offscreen
 * renderer on its own engine instance (ADR-0004), the encoder and the sound, and saves the video. It
 * reports [progress] (0..1 of the whole Render) as it goes, stops soon after [isCancelled] turns true,
 * and says how it ended. It may throw; the handlers report that as an engine failure.
 */
fun interface RenderEngine {
    suspend fun render(
        job: RenderJob,
        look: FrozenLook,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd
}

/**
 * What Studio's Renders need beyond its storage: Settings' choices for them, the look, the engine, and
 * whether the app is in front of the person ([inFront], #28), which `:app` follows.
 */
class RenderSources(
    val settings: Flow<RenderSettings>,
    val looks: RenderLooks,
    val engine: RenderEngine,
    val inFront: Flow<Boolean> = flowOf(true),
)

/** Settings' Visuals off and Export preferences as Studio's Renders read them, through Settings' interface. */
fun renderSettingsOf(settings: Settings): Flow<RenderSettings> =
    settings.state
        .filterIsInstance<SettingsState.Ready>()
        .map { renderSettingsOf(it.values) }
        .distinctUntilChanged()

/** What a new Render starts from in [values] (#22, #28), whether Visuals off is on (#196), and Renders keep going (#28). */
fun renderSettingsOf(values: SettingsValues): RenderSettings {
    val export = values.export
    val format =
        RenderFormat(
            aspect = aspectOf(export.ratio),
            size = sizeOf(export.quality),
            fps = export.frameRate.fps,
            codec = if (export.codec == ExportCodec.HEVC) RenderCodec.HEVC else RenderCodec.H264,
        )
    return RenderSettings(values.performance.visualsOff, format, RenderExtras(loudnessOf(export.loudness)), values.background.renders)
}

private fun aspectOf(ratio: ExportRatio): RenderAspect =
    when (ratio) {
        ExportRatio.R16_9 -> RenderAspect.R16_9
        ExportRatio.R9_16 -> RenderAspect.R9_16
        ExportRatio.R1_1 -> RenderAspect.R1_1
        ExportRatio.R4_5 -> RenderAspect.R4_5
        ExportRatio.R4_3 -> RenderAspect.R4_3
        ExportRatio.R21_9 -> RenderAspect.R21_9
    }

private fun sizeOf(quality: ExportQuality): RenderSize =
    when (quality) {
        ExportQuality.SD480 -> RenderSize.SD480
        ExportQuality.HD720 -> RenderSize.HD720
        ExportQuality.FHD1080 -> RenderSize.FHD1080
        ExportQuality.QHD1440 -> RenderSize.QHD1440
        ExportQuality.UHD4K -> RenderSize.UHD4K
    }

private fun loudnessOf(loudness: ExportLoudness): RenderLoudness =
    when (loudness) {
        ExportLoudness.LEAVE_AS_IS -> RenderLoudness.LEAVE_AS_IS
        ExportLoudness.YOUTUBE -> RenderLoudness.MINUS_14_LUFS
        ExportLoudness.SHORTS_AND_TIKTOK -> RenderLoudness.MINUS_11_LUFS
    }
