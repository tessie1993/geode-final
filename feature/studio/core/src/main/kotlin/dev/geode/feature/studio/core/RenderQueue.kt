package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.StudioState
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure

/** What the banner says when a Render is refused. */
const val VISUALS_OFF_REFUSAL = "No Render can be made while Visuals off is on"
const val NO_PROJECT_REFUSAL = "Choose a Project for the Render first"
const val CONTINUOUS_REFUSAL = "One continuous video of a playlist is not available yet: render one video per track"

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
        val refusal =
            when {
                state.visualsOff -> VISUALS_OFF_REFUSAL
                spec.playlist == PlaylistMode.ONE_CONTINUOUS_VIDEO -> CONTINUOUS_REFUSAL
                else -> null
            }
        val project = state.projectFor(spec.project)
        if (refusal != null || project == null) return Next(state, listOf(ReportFailure(refusal ?: NO_PROJECT_REFUSAL)))
        return Next(state, listOf(Perform(StudioWork.QueueRenders(RenderPlan.rendersOf(spec, project)))))
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
                rendersKeepGoing = settings.keepGoing,
            ),
        )

    /**
     * Starts the oldest waiting Render, unless one runs, the library is not read yet, Visuals off is on,
     * or the queue is held while the app is away.
     */
    fun next(state: StudioState): Next<StudioState, StudioEffect> {
        if (state.loading || state.visualsOff || state.rendersHeld || state.runningRender != null) return Next(state)
        val waiting = state.renders.firstOrNull { it.status == RenderStatus.Queued } ?: return Next(state)
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
                    addVideos(done, listOf(end.video), job.project)
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
}
