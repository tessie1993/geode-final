package dev.geode.studio

import android.content.Context
import android.media.MediaCodec
import android.net.Uri
import dev.geode.RingLog
import dev.geode.analysis.AnalysisCache
import dev.geode.analysis.FeatureTimeline
import dev.geode.analysis.OfflineAnalyzer
import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.export.ExportAspect
import dev.geode.export.ExportCodec
import dev.geode.export.ExportRange
import dev.geode.export.VideoExporter
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.RenderEnd
import dev.geode.feature.studio.core.RenderPlan
import dev.geode.feature.studio.handlers.FrozenLook
import dev.geode.feature.studio.handlers.RenderEngine
import dev.geode.playback.toUri
import dev.geode.render.SceneFactory
import dev.geode.render.VisualSafety
import dev.geode.render.scene.SceneIds
import dev.geode.visuals.EngineSnapshots
import dev.geode.visuals.StyleTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException

/**
 * Makes a Render's video on the phone (#316): Studio's [RenderEngine] over the native engine. The whole
 * Track is analysed first (ADR-0001), or its analysis read back from the cache; the frozen look is
 * handed to an offscreen renderer on its own engine instance (ADR-0004), which draws everything the
 * live Visual would for it (#337); the encoder muxes the Track's sound beside it, and the video is
 * saved to `Movies/Geode`, from where Studio adds it to the Render's Project.
 *
 * A Render is not bound to real time: it draws as fast as the encoder takes frames, except while the
 * live Visual is on screen ([liveVisualShown]) or the CPU/GPU save mode is on, when it waits a little
 * after each frame so the live picture stays smooth and the phone cool (ADR-0004, #28).
 */
class NativeRenderEngine(
    private val context: Context,
    private val settings: Settings,
    private val liveVisualShown: () -> Boolean,
    private val work: CoroutineDispatcher,
) : RenderEngine {
    private val exporter = VideoExporter(context)
    private val analyzer = OfflineAnalyzer(context)

    override suspend fun render(
        job: RenderJob,
        look: FrozenLook,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd {
        // One continuous video of a playlist is not made yet (#326); the queue never asks for one.
        val track = job.tracks.singleOrNull() ?: return RenderEnd.Failed(RenderProblem.ENGINE_FAILED)
        val values = (settings.state.value as? SettingsState.Ready)?.values ?: SettingsValues()
        progress(RenderPhase.PREPARING, 0f)
        val customization = lookOf(look) ?: return RenderEnd.Failed(RenderProblem.LOOK_UNAVAILABLE)
        val uri = track.id.toUri()
        val timeline =
            timelineOf(uri, values, isCancelled) { progress(RenderPhase.ANALYSING, it * ANALYSIS_SHARE) }
                ?: return if (isCancelled()) RenderEnd.Cancelled else RenderEnd.Failed(RenderProblem.TRACK_UNREADABLE)
        if (isCancelled()) return RenderEnd.Cancelled
        val format = job.format
        val frame = RenderPlan.frameOf(format)
        val section = RenderPlan.sectionOf(job.length, track.length)
        val made =
            VideoExporter.Job(
                audioUri = uri,
                timeline = timeline,
                sceneFactory = StyleScene.of(customization.style, StyleTargets.milkFolder(context)),
                look = EngineSnapshots.forRender(customization, values),
                aspect = ExportAspect(frame.width, frame.height, RenderPlan.bitRateOf(format)),
                fps = format.fps,
                range = section?.let { ExportRange(it.start.value, it.length.value) },
                codec = if (format.codec == RenderCodec.HEVC) ExportCodec.HEVC else ExportCodec.H264,
            )
        val result = encode(made, track, progress, isCancelled) ?: return RenderEnd.Failed(RenderProblem.ENCODER_REFUSED)
        return endOf(result, track, frame.width, frame.height)
    }

    /** The frozen look as a Customization, or null when it cannot be read. */
    private fun lookOf(look: FrozenLook): Customization? =
        try {
            RenderLookFormat.decode(look.text, SceneIds.DEFAULT)
        } catch (unreadable: JSONException) {
            RingLog.note(TAG, "the Render's look could not be read", unreadable)
            null
        } catch (unreadable: IllegalArgumentException) {
            RingLog.note(TAG, "the Render's look is not one this version reads", unreadable)
            null
        }

    /** The Track's analysis, or null when it cannot be read or the Render was cancelled meanwhile. */
    private suspend fun timelineOf(
        uri: Uri,
        values: SettingsValues,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): FeatureTimeline? =
        try {
            analyse(uri, values, isCancelled, onProgress)
        } catch (stopped: CancellationException) {
            if (!isCancelled()) throw stopped
            null
        } catch (unreadable: IOException) {
            RingLog.note(TAG, "the Track could not be read", unreadable)
            null
        } catch (unreadable: IllegalArgumentException) {
            RingLog.note(TAG, "the Track has no sound to analyse", unreadable)
            null
        }

    /** [made] encoded, or null when the encoder would not take its format. */
    private suspend fun encode(
        made: VideoExporter.Job,
        track: RenderTrack,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): VideoExporter.Result? =
        try {
            exporter.export(
                job = made,
                fileName = fileNameOf(track),
                onProgress = { progress(phaseAt(it), ANALYSIS_SHARE + it * (1f - ANALYSIS_SHARE)) },
                isCancelled = isCancelled,
                pace = ::pace,
            )
        } catch (refused: MediaCodec.CodecException) {
            RingLog.note(TAG, "the encoder refused the Render", refused)
            null
        } catch (refused: IllegalArgumentException) {
            RingLog.note(TAG, "the encoder refused the Render's format", refused)
            null
        } catch (unsaved: IOException) {
            RingLog.note(TAG, "the Render could not be written", unsaved)
            VideoExporter.Result.Failed(unsaved.message.orEmpty())
        }

    private fun endOf(
        result: VideoExporter.Result,
        track: RenderTrack,
        width: Int,
        height: Int,
    ): RenderEnd =
        when (result) {
            is VideoExporter.Result.Saved ->
                RenderEnd.Saved(
                    NewVideo(
                        kind = VideoKind.RENDER,
                        name = track.title,
                        file = result.uri.toString(),
                        thumbnail = null,
                        length = Millis(result.lengthMs),
                        width = width,
                        height = height,
                        song = track.title,
                    ),
                )
            is VideoExporter.Result.Failed -> {
                RingLog.note(TAG, "the Render was not saved: ${result.message}")
                RenderEnd.Failed(RenderProblem.NOT_SAVED)
            }
            VideoExporter.Result.Cancelled -> RenderEnd.Cancelled
        }

    /**
     * The Track's whole-song analysis with Settings' beat tuning, from the cache when it holds one. The
     * beat's shortest gap keeps the live Visual's floor, so a Render never pulses faster than live.
     */
    private suspend fun analyse(
        uri: Uri,
        values: SettingsValues,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): FeatureTimeline {
        val sensitivity = values.analysis.beatSensitivity
        val gap = VisualSafety.beatMinIntervalMs(values.analysis.beatMinIntervalMs)
        val analysed =
            withContext(work) {
                AnalysisCache.load(context, uri, sensitivity, gap)
                    ?: analyzer
                        .analyzeBlocking(uri, sensitivity, gap, onProgress) { !isCancelled() }
                        .also { AnalysisCache.save(context, uri, it) }
            }
        onProgress(1f)
        return analysed.withBeatSensitivity(sensitivity, gap)
    }

    /** Waits a little after a frame while the live Visual shows or the save mode is on. */
    private fun pace() {
        val slowed = (settings.state.value as? SettingsState.Ready)?.values?.performance?.rendersSlowed == true
        if (slowed || liveVisualShown()) Thread.sleep(PACE_MS)
    }

    /**
     * The Style a Render draws: a MilkDrop preset's own Style draws the MilkDrop scene with that preset
     * loaded before the first frame (#305).
     */
    private class StyleScene(
        override val sceneId: SceneId,
        override val milkPresetPath: String?,
    ) : SceneFactory {
        companion object {
            fun of(
                style: SceneId,
                milkFolder: File,
            ): StyleScene {
                val target = StyleTargets.of(style, milkFolder)
                return StyleScene(target.scene, target.milkPreset?.path)
            }
        }
    }

    private companion object {
        const val TAG = "NativeRenderEngine"

        /** How much of a Render's progress the analysis takes; the encoder's own progress shares the rest. */
        const val ANALYSIS_SHARE = 0.1f

        /** Where the encoder's progress moves from its sound to its frames, and on to saving (see VideoExporter). */
        const val FRAMES_FROM = 0.1f
        const val SAVING_FROM = 0.95f

        /** How long a Render waits after each frame while it gives way. */
        const val PACE_MS = 12L

        /** The longest a video's name grows from its Track's title. */
        const val MAX_NAME = 80

        fun phaseAt(encoded: Float): RenderPhase =
            when {
                encoded < FRAMES_FROM -> RenderPhase.PREPARING
                encoded < SAVING_FROM -> RenderPhase.RENDERING
                else -> RenderPhase.SAVING
            }

        /** The video's file name: its Track's title, without characters a file name cannot hold. */
        fun fileNameOf(track: RenderTrack): String {
            val title =
                track.title
                    .replace(Regex("[\\\\/:*?\"<>|]"), " ")
                    .trim()
                    .take(MAX_NAME)
                    .ifBlank { "Render" }
            return "$title (Geode).mp4"
        }
    }
}
