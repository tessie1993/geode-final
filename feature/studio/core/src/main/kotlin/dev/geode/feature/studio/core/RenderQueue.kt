package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.MadeFrom
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure

/** What the banner says when a Render is refused. */
const val VISUALS_OFF_REFUSAL = "No Render can be made while Visuals off is on"
const val NO_PROJECT_REFUSAL = "Choose a Project for the Render first"

/**
 * Studio's render queue (#338, ADR-0004): Renders run one at a time, in the order they were queued,
 * each on its own engine instance in the handlers. A Render is queued once its look is frozen and it
 * is saved, so it survives the app being killed; it then waits until none runs and the Visual is on.
 * With Renders keep going off (#28) nothing new starts while the app is not in front, and the running
 * Render waits in the engine until it is back. Cancelling a waiting Render forgets it; cancelling the
 * running one asks the engine to stop, and it ends as cancelled. A saved Render's video goes to its
 * Project, or the default one if its Project was deleted meanwhile.
 */
internal object RenderQueue {
    fun queue(
        state: StudioState,
        spec: RenderSpec,
    ): Next<StudioState, StudioEffect> {
        if (spec.tracks.isEmpty() || spec.format.fps !in RenderFormat.FRAME_RATES) return Next(state)
        val refusal = if (state.visualsOff) VISUALS_OFF_REFUSAL else null
        val project = state.projectFor(spec.project)
        if (refusal != null || project == null) return Next(state, listOf(ReportFailure(refusal ?: NO_PROJECT_REFUSAL)))
        return Next(state, listOf(Perform(StudioWork.QueueRenders(RenderPlan.rendersOf(spec, project)))))
    }

    /**
     * Makes the Render [video] again with [spec]'s choices (#327): the same Track and the look frozen for
     * it, so it reproduces the Render at another shape or quality. A continuous video (#326) keeps every
     * track, each whole, the look of each and how they join. A video Studio did not render does nothing;
     * the refusals are [queue]'s.
     */
    fun again(
        state: StudioState,
        video: VideoId,
        spec: RenderSpec,
    ): Next<StudioState, StudioEffect> {
        val made = state.videos.firstOrNull { it.id == video } ?: return Next(state)
        val from = made.madeWith ?: return Next(state)
        if (spec.format.fps !in RenderFormat.FRAME_RATES) return Next(state)
        val project = state.projectFor(spec.project)
        val refusal = if (state.visualsOff) VISUALS_OFF_REFUSAL else null
        if (refusal != null || project == null) return Next(state, listOf(ReportFailure(refusal ?: NO_PROJECT_REFUSAL)))
        val length = if (from.continuous) RenderLength.WholeTrack else spec.length
        val render =
            NewRender(
                from.tracks,
                from.look,
                spec.format,
                length,
                project,
                spec.extras,
                spec.milkdrop,
                trackLooks = from.trackLooks,
                join = from.join,
            )
        return Next(state, listOf(Perform(StudioWork.QueueRenderAgain(made.file, render))))
    }

    fun queued(
        state: StudioState,
        renders: List<RenderJob>,
    ): Next<StudioState, StudioEffect> = next(state.copy(renders = state.renders + renders))

    fun settings(
        state: StudioState,
        settings: RenderSettings,
    ): Next<StudioState, StudioEffect> =
        next(
            state.copy(
                visualsOff = settings.visualsOff,
                renderFormat = settings.format,
                renderExtras = settings.extras,
                renderJoin = settings.join,
                rendersKeepGoing = settings.keepGoing,
                editSources = settings.editSources,
            ),
        )

    /**
     * Whether anything may start: nothing while one runs, the library is not read yet, or the queue is
     * held while the app is away. The oldest waiting Render then starts, unless Visuals off is on; an
     * Edit's export (#318) starts either way.
     */
    private fun canStart(state: StudioState): Boolean = !state.loading && !state.rendersHeld && state.runningRender == null

    /** Whether [job] may start now: an Edit's export cuts videos and draws nothing, so Visuals off holds only Renders. */
    private fun mayStart(
        state: StudioState,
        job: RenderJob,
    ): Boolean = job.status == RenderStatus.Queued && (job.edit != null || !state.visualsOff)

    fun next(state: StudioState): Next<StudioState, StudioEffect> {
        if (!canStart(state)) return Next(state)
        val waiting = state.renders.firstOrNull { mayStart(state, it) } ?: return Next(state)
        val started = waiting.copy(status = RenderStatus.Running(RenderPhase.PREPARING, 0f))
        return Next(state.replacing(started), listOf(Perform(StudioWork.Render(started))))
    }

    fun progress(
        state: StudioState,
        event: StudioEvent.RenderProgress,
    ): Next<StudioState, StudioEffect> {
        val job = state.renders.firstOrNull { it.id == event.render }
        val running = job?.status as? RenderStatus.Running ?: return Next(state)
        val moved = running.copy(phase = event.phase, progress = event.progress.coerceIn(0f, 1f), secondsLeft = event.secondsLeft)
        return Next(state.replacing(job.copy(status = moved)))
    }

    fun ended(
        state: StudioState,
        event: StudioEvent.RenderEnded,
    ): Next<StudioState, StudioEffect> {
        val job = state.renders.firstOrNull { it.id == event.render }
        if (job == null || job.status !is RenderStatus.Running) return Next(state)
        val outcome =
            when (val end = event.end) {
                is RenderEnd.Saved -> {
                    val done = state.replacing(job.copy(status = RenderStatus.Done))
                    // A Render's video keeps the setup it was made from, so it can be made again (#327);
                    // an Edit's is cut from videos, and has none.
                    val madeWith = if (job.edit == null) job.toNewRender().toSpec() else null
                    val video = linked(state, job, end.video).copy(madeWith = madeWith)
                    val added = addVideos(done, listOf(video), job.project)
                    Next(added.state, added.effects + finishing(job, video))
                }
                is RenderEnd.Failed ->
                    Next(
                        state.replacing(job.copy(status = RenderStatus.Failed(end.problem))),
                        listOf(ReportFailure(describe(end.problem, job.title))),
                    )
                RenderEnd.Cancelled -> Next(state.replacing(job.copy(status = RenderStatus.Cancelled)))
            }
        val after = next(outcome.state)
        return Next(after.state, outcome.effects + after.effects)
    }

    fun cancel(
        state: StudioState,
        renders: Set<RenderId>,
    ): Next<StudioState, StudioEffect> {
        val decided = state.renders.map { if (it.id in renders) cancelled(it) else it to null }
        return Next(state.copy(renders = decided.map { it.first }), decided.mapNotNull { it.second })
    }

    fun clearFinished(state: StudioState): Next<StudioState, StudioEffect> = Next(state.copy(renders = state.renders.filter { it.active }))

    /** [job] cancelled, and what that asks of the handlers: nothing when it already ended or is stopping. */
    private fun cancelled(job: RenderJob): Pair<RenderJob, StudioEffect?> {
        val status = job.status
        return when {
            status == RenderStatus.Queued -> job.copy(status = RenderStatus.Cancelled) to Perform(StudioWork.ForgetRender(job.id))
            status is RenderStatus.Running && !status.cancelling ->
                job.copy(status = status.copy(cancelling = true)) to Perform(StudioWork.StopRender(job.id))
            else -> job to null
        }
    }

    private fun StudioState.replacing(job: RenderJob): StudioState = copy(renders = renders.map { if (it.id == job.id) job else it })

    /**
     * Its extras for a finished Render that has any (#329), of one Track or one continuous video of a
     * playlist (#326): the lyrics are read, then the captions and cards are made as an Edit of its video.
     * An Edit's export has none of its own.
     */
    private fun finishing(
        job: RenderJob,
        video: NewVideo,
    ): List<StudioEffect> =
        if (job.edit == null && job.tracks.isNotEmpty() && job.extras.finishing) {
            listOf(Perform(StudioWork.FinishRender(job, video)))
        } else {
            emptyList()
        }

    /**
     * Queues the Edit that adds a finished Render's captions and cards (#329), into the Render's Project,
     * at its format, linked to its video; nothing when there is nothing to add.
     */
    fun finish(
        state: StudioState,
        event: StudioEvent.FinishReady,
    ): Next<StudioState, StudioEffect> {
        val render = event.render
        val cut = finishCutOf(render, event) ?: return Next(state)
        val into = state.projectFor(render.project) ?: return Next(state, listOf(ReportFailure(NO_PROJECT_REFUSAL)))
        val name = RenderFinish.nameOf(event.video.name, render.extras, RenderFinish.hasCaptions(cut))
        val job = EditJob(name, EditCut.Cut(cut), EditSources.REFERENCE)
        // The Render's sound was levelled already; the Edit keeps it as it is.
        return Edits.queueEdit(state, job, render.format, RenderExtras(), into, MadeFrom(event.video.file, VideoKind.EDIT))
    }

    /**
     * The Edit with [render]'s captions and cards: of its one Track, or of every track of a continuous video
     * inside the one video (#326); null when there is nothing to add, or nothing was rendered.
     */
    private fun finishCutOf(
        render: RenderJob,
        event: StudioEvent.FinishReady,
    ): EditorProject? {
        val track = render.tracks.singleOrNull()
        if (track != null) return RenderFinish.cutOf(event.video, track, render.length, render.extras, event.lyrics.firstOrNull().orEmpty())
        return RenderFinish.playlistCutOf(event.video, render.tracks, render.join, render.extras, event.lyrics)
    }

    /**
     * [video] as what [job] was made as (#328, #329): a Short, sibling or finishing Edit takes its kind
     * and links to the video it was made from, while that is still in the library.
     */
    private fun linked(
        state: StudioState,
        job: RenderJob,
        video: NewVideo,
    ): NewVideo {
        val from = job.madeFrom ?: return video
        val source = state.videos.firstOrNull { it.file == from.file }?.id
        return video.copy(kind = from.kind, source = source)
    }
}
