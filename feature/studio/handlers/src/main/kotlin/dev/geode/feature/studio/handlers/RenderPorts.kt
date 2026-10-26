package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.ExportCodec
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportQuality
import dev.geode.feature.settings.api.ExportRatio
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.FadeCurve
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.TrackJoin
import dev.geode.feature.studio.api.edit.SubtitleCue
import dev.geode.feature.studio.core.RenderEnd
import dev.geode.feature.studio.core.RenderSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import dev.geode.feature.settings.api.EditSources as SettingsEditSources

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
 * draws with the frozen [look]; one continuous video of a playlist (#326) draws each track with its own,
 * [trackLooks], in the tracks' order (empty for any other Render). It reports [progress] (0..1 of the
 * whole Render) as it goes, stops soon after [isCancelled] turns true, and says how it ended. It may
 * throw; the handlers report that as an engine failure.
 */
fun interface RenderEngine {
    suspend fun render(
        job: RenderJob,
        look: FrozenLook,
        trackLooks: List<FrozenLook>,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd
}

/**
 * A Track's synced lyrics as captions in the song's own time (#329), for a Render's captions. `:app`
 * reads them from an `.lrc` file beside the Track or its own tags, never from anywhere online. Empty
 * when it has none; it may throw.
 */
fun interface RenderLyrics {
    suspend fun cues(track: RenderTrack): List<SubtitleCue>

    companion object {
        /** No lyrics anywhere: a Render's captions add nothing. */
        val None: RenderLyrics = RenderLyrics { emptyList() }
    }
}

/**
 * What Studio's Renders need beyond its storage: Settings' choices for them, the look, the engine,
 * whether the app is in front of the person ([inFront], #28), which `:app` follows, and the songs'
 * [lyrics] for captions (#329).
 */
class RenderSources(
    val settings: Flow<RenderSettings>,
    val looks: RenderLooks,
    val engine: RenderEngine,
    val inFront: Flow<Boolean> = flowOf(true),
    val lyrics: RenderLyrics = RenderLyrics.None,
)

/** Settings' Visuals off and Export preferences as Studio's Renders read them, through Settings' interface. */
fun renderSettingsOf(settings: Settings): Flow<RenderSettings> =
    settings.state
        .filterIsInstance<SettingsState.Ready>()
        .map { renderSettingsOf(it.values) }
        .distinctUntilChanged()

/**
 * What a new Render starts from in [values] (#22, #28), whether Visuals off is on (#196), Renders keep
 * going (#28), what a saved Edit does with its videos (#318), and how a continuous video joins its tracks:
 * the Playback settings' crossfade, its length and curve (#326).
 */
fun renderSettingsOf(values: SettingsValues): RenderSettings {
    val export = values.export
    val format =
        RenderFormat(
            aspect = aspectOf(export.ratio),
            size = sizeOf(export.quality),
            fps = export.frameRate.fps,
            codec = if (export.codec == ExportCodec.HEVC) RenderCodec.HEVC else RenderCodec.H264,
        )
    return RenderSettings(
        values.performance.visualsOff,
        format,
        RenderExtras(loudnessOf(export.loudness)),
        values.background.renders,
        sourcesOf(export.editSources),
        TrackJoin(values.playback.crossfade.coerceIn(Millis.ZERO, MAX_JOIN_CROSSFADE), curveOf(values.playback.crossfadeCurve)),
    )
}

/** The longest crossfade a continuous video joins with: the longest Playback plays (`MAX_CROSSFADE`). */
private val MAX_JOIN_CROSSFADE = Millis(12_000)

/** The Playback settings' crossfade curve, as a continuous video's join reads it (#326). */
private fun curveOf(curve: CrossfadeCurve): FadeCurve =
    when (curve) {
        CrossfadeCurve.LINEAR -> FadeCurve.LINEAR
        CrossfadeCurve.EQUAL_POWER -> FadeCurve.EQUAL_POWER
        CrossfadeCurve.SMOOTH -> FadeCurve.SMOOTH
    }

/** Settings' choice of what a saved Edit does with its videos, as Studio reads it (#318). */
private fun sourcesOf(sources: SettingsEditSources): EditSources =
    when (sources) {
        SettingsEditSources.REFERENCE -> EditSources.REFERENCE
        SettingsEditSources.COPY -> EditSources.COPY
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
