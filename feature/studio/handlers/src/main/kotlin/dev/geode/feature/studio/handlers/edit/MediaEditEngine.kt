package dev.geode.feature.studio.handlers.edit

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.FrameSize
import dev.geode.feature.studio.core.RenderEnd
import dev.geode.feature.studio.core.RenderPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Edit pipeline as the render queue's [EditEngine] (#318): the clip editor's cut, or the Timeline's
 * composition with its transitions from [shaders], made by Media3 Transformer at the queued format and
 * saved, then handed back as an Edit's video. A cut is loop-safe when Settings' Export preferences say
 * so ([loopSafe]). Transformer cannot pause, so an Edit runs to its end once started; cancelling it stops
 * Transformer and leaves no file.
 */
@UnstableApi
class MediaEditEngine(
    private val context: Context,
    private val shaders: TransitionShaders,
    private val loopSafe: () -> Boolean,
) : EditEngine {
    override suspend fun export(
        job: EditJob,
        format: RenderFormat,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd =
        coroutineScope {
            progress(RenderPhase.PREPARING, 0f)
            val exporter = StudioExporter(context)
            val exporting = async { made(exporter, job, format, progress) }
            val watcher =
                launch {
                    while (exporting.isActive) {
                        if (isCancelled()) {
                            exporter.cancel()
                            exporting.cancel()
                        }
                        delay(CANCEL_POLL_MS)
                    }
                }
            val end = awaitOrCancelled(exporting, isCancelled)
            watcher.cancel()
            end ?: RenderEnd.Cancelled
        }

    /** What [exporting] ends with, or null when it was stopped because the Edit was cancelled. */
    @Suppress("SwallowedException") // A cancelled Edit ends as cancelled; any other cancellation goes on up.
    private suspend fun awaitOrCancelled(
        exporting: Deferred<RenderEnd>,
        isCancelled: () -> Boolean,
    ): RenderEnd? =
        try {
            exporting.await()
        } catch (stopped: CancellationException) {
            if (!isCancelled()) throw stopped
            null
        }

    private suspend fun made(
        exporter: StudioExporter,
        job: EditJob,
        format: RenderFormat,
        progress: (RenderPhase, Float) -> Unit,
    ): RenderEnd {
        val name = "geode_edit_${System.currentTimeMillis()}.mp4"
        val rendering: (Float) -> Unit = { progress(RenderPhase.RENDERING, it.coerceIn(0f, 1f) * RENDER_SHARE) }
        return when (val cut = job.cut) {
            is EditCut.OneClip -> {
                val source = cut.source
                val result =
                    exporter.export(
                        Uri.parse(source.file),
                        source.length.value,
                        cut.edit,
                        name,
                        format.codec,
                        sourceSize = FrameSize(source.width, source.height),
                        onProgress = rendering,
                    )
                endOf(result, job.name, frameOf(cut), progress)
            }
            is EditCut.Cut -> {
                val output = EditOutput(format.aspect, format.fps, loopSafe(), format.codec)
                when (val built = ProjectComposition.build(context, cut.project, output, shaders)) {
                    is ProjectComposition.Outcome.Ready -> {
                        val result =
                            exporter.exportComposition(
                                built.composition,
                                built.durationMs,
                                name,
                                format.codec,
                                onProgress = rendering,
                            )
                        endOf(result, job.name, RenderPlan.frameOf(format), progress)
                    }
                    ProjectComposition.Outcome.NoVideo -> RenderEnd.Failed(RenderProblem.TRACK_UNREADABLE)
                }
            }
        }
    }

    /** The clip reframed to its shape at its size, or as shot. */
    private fun frameOf(cut: EditCut.OneClip): FrameSize {
        val shape = cut.edit.ratio ?: return FrameSize(cut.source.width, cut.source.height)
        return RenderPlan.frameOf(RenderFormat(aspect = shape, size = cut.edit.quality))
    }

    private fun endOf(
        result: StudioExporter.Result,
        name: String,
        frame: FrameSize,
        progress: (RenderPhase, Float) -> Unit,
    ): RenderEnd =
        when (result) {
            is StudioExporter.Result.Saved -> {
                progress(RenderPhase.SAVING, 1f)
                val video =
                    NewVideo(VideoKind.EDIT, name, result.uri.toString(), null, Millis(result.durationMs), frame.width, frame.height, "")
                RenderEnd.Saved(video)
            }
            is StudioExporter.Result.Failed -> {
                Log.w(TAG, "the Edit was not made: ${result.message}")
                RenderEnd.Failed(RenderProblem.ENCODER_REFUSED)
            }
            StudioExporter.Result.Cancelled -> RenderEnd.Cancelled
        }

    private companion object {
        const val TAG = "MediaEditEngine"

        /** How often a running Edit looks whether it was cancelled. */
        const val CANCEL_POLL_MS = 200L

        /** How much of an Edit's progress Transformer's own takes; saving takes the rest. */
        const val RENDER_SHARE = 0.95f
    }
}
