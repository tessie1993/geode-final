package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.VideoKind
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** Studio's render queue as a transition table (ADR-0003): each queue state against each event (#338, #325). */
class RenderQueueTest {
    private val trip = Project(ProjectId(1), "Trip", Millis(10))
    private val gigs = Project(ProjectId(2), "Gigs", Millis(20))
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
    private val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_000))
    private val format = RenderFormat(aspect = RenderAspect.R9_16, size = RenderSize.HD720, fps = 30)
    private val extras = RenderExtras(RenderLoudness.MINUS_14_LUFS)
    private val look = RenderLook.Preset(PresetId("Calm"))
    private val section = RenderLength.Section(Millis(1_000), Millis(30_000))
    private val spec = RenderSpec(listOf(night, tide), look, format, section, trip.id, PlaylistMode.ONE_VIDEO_PER_TRACK, extras)

    private fun job(
        id: Long,
        track: RenderTrack,
        status: RenderStatus = RenderStatus.Queued,
        batch: Long = 1,
    ) = RenderJob(RenderId(id), RenderId(batch), listOf(track), look, format, section, trip.id, extras, status)

    private fun running(
        progress: Float = 0f,
        phase: RenderPhase = RenderPhase.PREPARING,
        cancelling: Boolean = false,
    ) = RenderStatus.Running(phase, progress, null, cancelling)

    private val first = job(1, night)
    private val second = job(2, tide)
    private val firstRunning = first.copy(status = running())
    private val video = NewVideo(VideoKind.RENDER, "Night Drive", "content://media/video/3", null, Millis(30_000), 720, 1280, "Night Drive")

    private val ready = StudioState(loading = false, projects = listOf(trip, gigs), defaultProject = gigs.id)
    private val waiting = ready.copy(renders = listOf(first, second))
    private val busy = ready.copy(renders = listOf(firstRunning, second))

    private fun command(command: StudioCommand) = StudioEvent.Command(command)

    private fun queue(spec: RenderSpec) = command(StudioCommand.QueueRender(spec))

    private fun ended(
        id: Long,
        end: RenderEnd,
    ) = StudioEvent.RenderEnded(RenderId(id), end)

    private data class Row(
        val name: String,
        val from: StudioState,
        val event: StudioEvent,
        val to: Next<StudioState, StudioEffect>,
    )

    private fun row(
        name: String,
        from: StudioState,
        event: StudioEvent,
        to: StudioState,
        vararg effects: StudioEffect,
    ) = Row(name, from, event, Next(to, effects.toList()))

    private val newRenders = RenderPlan.rendersOf(spec, trip.id)

    private val queueRows: List<Row> =
        listOf(
            row(
                "a playlist queues one Render per track into the chosen Project",
                ready,
                queue(spec),
                ready,
                Perform(StudioWork.QueueRenders(newRenders)),
            ),
            row(
                "a Render without a Project goes to the default one",
                ready,
                queue(spec.copy(project = null)),
                ready,
                Perform(StudioWork.QueueRenders(RenderPlan.rendersOf(spec, gigs.id))),
            ),
            row(
                "a Render for a Project that is gone goes to the default one",
                ready,
                queue(spec.copy(project = ProjectId(9))),
                ready,
                Perform(StudioWork.QueueRenders(RenderPlan.rendersOf(spec, gigs.id))),
            ),
            row(
                "with no Project and no default the Render is refused",
                ready.copy(defaultProject = null),
                queue(spec.copy(project = null)),
                ready.copy(defaultProject = null),
                ReportFailure(NO_PROJECT_REFUSAL),
            ),
            row(
                "a default Project that is gone is no Project",
                ready.copy(defaultProject = ProjectId(9)),
                queue(spec.copy(project = null)),
                ready.copy(defaultProject = ProjectId(9)),
                ReportFailure(NO_PROJECT_REFUSAL),
            ),
            row(
                "no Render while Visuals off is on",
                ready.copy(visualsOff = true),
                queue(spec),
                ready.copy(visualsOff = true),
                ReportFailure(VISUALS_OFF_REFUSAL),
            ),
            row(
                "one continuous video of a playlist is not made yet",
                ready,
                queue(spec.copy(playlist = PlaylistMode.ONE_CONTINUOUS_VIDEO)),
                ready,
                ReportFailure(CONTINUOUS_REFUSAL),
            ),
            row("nothing to render queues nothing", ready, queue(spec.copy(tracks = emptyList())), ready),
            row("a frame rate under the range queues nothing", ready, queue(spec.copy(format = format.copy(fps = 11))), ready),
            row("a frame rate over the range queues nothing", ready, queue(spec.copy(format = format.copy(fps = 121))), ready),
            row(
                "the frame rates at the ends of the range are fine",
                ready,
                queue(spec.copy(tracks = listOf(night), format = format.copy(fps = 120))),
                ready,
                Perform(
                    StudioWork.QueueRenders(
                        RenderPlan.rendersOf(spec.copy(tracks = listOf(night), format = format.copy(fps = 120)), trip.id),
                    ),
                ),
            ),
            row(
                "the lowest frame rate is fine",
                ready,
                queue(spec.copy(tracks = listOf(night), format = format.copy(fps = 12))),
                ready,
                Perform(
                    StudioWork.QueueRenders(
                        RenderPlan.rendersOf(spec.copy(tracks = listOf(night), format = format.copy(fps = 12)), trip.id),
                    ),
                ),
            ),
            row(
                "a Render queued before the library is read waits for it",
                StudioState(),
                queue(spec),
                StudioState(pending = listOf(StudioCommand.QueueRender(spec))),
            ),
        )

    private val startRows: List<Row> =
        listOf(
            row(
                "queued Renders start the first when none runs",
                ready,
                StudioEvent.RendersQueued(listOf(first, second)),
                busy,
                Perform(StudioWork.Render(firstRunning)),
            ),
            row(
                "queued Renders wait behind the running one",
                ready.copy(renders = listOf(job(5, tide, running(0.5f)))),
                StudioEvent.RendersQueued(listOf(first)),
                ready.copy(renders = listOf(job(5, tide, running(0.5f)), first)),
            ),
            row(
                "queued Renders wait while Visuals off is on",
                ready.copy(visualsOff = true),
                StudioEvent.RendersQueued(listOf(first)),
                ready.copy(visualsOff = true, renders = listOf(first)),
            ),
            row(
                "the next waiting Render starts after finished ones",
                ready.copy(renders = listOf(job(5, tide, RenderStatus.Done), first)),
                StudioEvent.RendersQueued(emptyList()),
                ready.copy(renders = listOf(job(5, tide, RenderStatus.Done), firstRunning)),
                Perform(StudioWork.Render(firstRunning)),
            ),
            row(
                "Renders that had not finished start again once the library is read",
                StudioState(),
                StudioEvent.Loaded(listOf(trip, gigs), emptyList(), gigs.id, listOf(first, second)),
                busy,
                Perform(StudioWork.Render(firstRunning)),
            ),
            row(
                "a Render waiting before the library is read does not start",
                StudioState(renders = listOf(first)),
                StudioEvent.RenderSettingsChanged(RenderSettings(false, format, extras)),
                StudioState(renders = listOf(first), renderFormat = format, renderExtras = extras),
            ),
            row(
                "Settings give what new Renders start from and hold the queue while Visuals off is on",
                waiting,
                StudioEvent.RenderSettingsChanged(RenderSettings(true, format, extras)),
                waiting.copy(visualsOff = true, renderFormat = format, renderExtras = extras),
            ),
            row(
                "turning Visuals off off starts the queue again",
                waiting.copy(visualsOff = true),
                StudioEvent.RenderSettingsChanged(RenderSettings(false, format, extras)),
                busy.copy(renderFormat = format, renderExtras = extras),
                Perform(StudioWork.Render(firstRunning)),
            ),
        )

    private val progressRows: List<Row> =
        listOf(
            row(
                "the running Render reports its progress and time left",
                busy,
                StudioEvent.RenderProgress(first.id, RenderPhase.RENDERING, 0.4f, 90),
                busy.copy(renders = listOf(first.copy(status = RenderStatus.Running(RenderPhase.RENDERING, 0.4f, 90)), second)),
            ),
            row(
                "progress stays within 0..1",
                busy,
                StudioEvent.RenderProgress(first.id, RenderPhase.SAVING, 1.5f, null),
                busy.copy(renders = listOf(first.copy(status = running(1f, RenderPhase.SAVING)), second)),
            ),
            row(
                "a cancelled Render stays cancelling while it reports",
                ready.copy(renders = listOf(first.copy(status = running(cancelling = true)))),
                StudioEvent.RenderProgress(first.id, RenderPhase.RENDERING, 0.2f, null),
                ready.copy(renders = listOf(first.copy(status = running(0.2f, RenderPhase.RENDERING, cancelling = true)))),
            ),
            row(
                "progress of a Render that is not running is ignored",
                waiting,
                StudioEvent.RenderProgress(first.id, RenderPhase.RENDERING, 0.4f, null),
                waiting,
            ),
            row(
                "progress of an unknown Render is ignored",
                busy,
                StudioEvent.RenderProgress(RenderId(9), RenderPhase.RENDERING, 0.4f, null),
                busy,
            ),
        )

    private val endRows: List<Row> =
        listOf(
            row(
                "a saved Render is done, goes to its Project, and the next starts",
                busy,
                ended(1, RenderEnd.Saved(video)),
                ready.copy(renders = listOf(first.copy(status = RenderStatus.Done), second.copy(status = running()))),
                Perform(StudioWork.AddVideos(trip.id, listOf(video))),
                Perform(StudioWork.Render(second.copy(status = running()))),
            ),
            row(
                "a saved Render whose Project was deleted goes to the default one",
                busy.copy(projects = listOf(gigs), renders = listOf(firstRunning)),
                ended(1, RenderEnd.Saved(video)),
                busy.copy(projects = listOf(gigs), renders = listOf(first.copy(status = RenderStatus.Done))),
                Perform(StudioWork.AddVideos(gigs.id, listOf(video))),
            ),
            row(
                "a saved Render with no Project left is handed back",
                busy.copy(projects = emptyList(), defaultProject = null, renders = listOf(firstRunning)),
                ended(1, RenderEnd.Saved(video)),
                busy.copy(projects = emptyList(), defaultProject = null, renders = listOf(first.copy(status = RenderStatus.Done))),
                Emit(StudioFact.ProjectNeeded(listOf(video))),
            ),
            row(
                "a failed Render is reported and the next starts",
                busy,
                ended(1, RenderEnd.Failed(RenderProblem.ENCODER_REFUSED)),
                ready.copy(
                    renders =
                        listOf(
                            first.copy(status = RenderStatus.Failed(RenderProblem.ENCODER_REFUSED)),
                            second.copy(status = running()),
                        ),
                ),
                ReportFailure(describe(RenderProblem.ENCODER_REFUSED, "Night Drive")),
                Perform(StudioWork.Render(second.copy(status = running()))),
            ),
            row(
                "a cancelled Render ends and the next starts",
                ready.copy(renders = listOf(first.copy(status = running(cancelling = true)), second)),
                ended(1, RenderEnd.Cancelled),
                ready.copy(renders = listOf(first.copy(status = RenderStatus.Cancelled), second.copy(status = running()))),
                Perform(StudioWork.Render(second.copy(status = running()))),
            ),
            row("the end of a Render that is not running is ignored", waiting, ended(1, RenderEnd.Cancelled), waiting),
            row("the end of an unknown Render is ignored", busy, ended(9, RenderEnd.Cancelled), busy),
        )

    private val cancelRows: List<Row> =
        listOf(
            row(
                "a waiting Render is cancelled and forgotten",
                busy,
                command(StudioCommand.CancelRenders(setOf(second.id))),
                busy.copy(renders = listOf(firstRunning, second.copy(status = RenderStatus.Cancelled))),
                Perform(StudioWork.ForgetRender(second.id)),
            ),
            row(
                "the running Render is asked to stop",
                busy,
                command(StudioCommand.CancelRenders(setOf(first.id))),
                busy.copy(renders = listOf(first.copy(status = running(cancelling = true)), second)),
                Perform(StudioWork.StopRender(first.id)),
            ),
            row(
                "cancelling the rest of a playlist stops and forgets each",
                busy,
                command(StudioCommand.CancelRenders(setOf(first.id, second.id))),
                busy.copy(renders = listOf(first.copy(status = running(cancelling = true)), second.copy(status = RenderStatus.Cancelled))),
                Perform(StudioWork.StopRender(first.id)),
                Perform(StudioWork.ForgetRender(second.id)),
            ),
            row(
                "a Render already stopping is not asked again",
                ready.copy(renders = listOf(first.copy(status = running(cancelling = true)))),
                command(StudioCommand.CancelRenders(setOf(first.id))),
                ready.copy(renders = listOf(first.copy(status = running(cancelling = true)))),
            ),
            row(
                "a finished Render cannot be cancelled",
                ready.copy(renders = listOf(job(1, night, RenderStatus.Done))),
                command(StudioCommand.CancelRenders(setOf(first.id))),
                ready.copy(renders = listOf(job(1, night, RenderStatus.Done))),
            ),
            row(
                "clearing the finished keeps what waits and runs",
                ready.copy(
                    renders =
                        listOf(
                            job(3, night, RenderStatus.Done),
                            firstRunning,
                            job(4, tide, RenderStatus.Failed(RenderProblem.NOT_SAVED)),
                            job(6, tide, RenderStatus.Cancelled),
                            second,
                        ),
                ),
                command(StudioCommand.ClearFinishedRenders),
                busy,
            ),
        )

    @Test
    fun `every row of the transition table`() {
        for (row in queueRows + startRows + progressRows + endRows + cancelRows) {
            assertEquals(row.name, row.to, StudioReducer.reduce(row.from, row.event))
        }
    }

    @Test
    fun `a playlist's Renders carry every choice of the setup`() {
        assertEquals(
            listOf(
                NewRender(listOf(night), look, format, section, trip.id, extras),
                NewRender(listOf(tide), look, format, section, trip.id, extras),
            ),
            newRenders,
        )
        assertEquals(first, newRenders[0].queuedAs(RenderId(1), RenderId(1)))
        assertEquals(job(2, tide, batch = 1), newRenders[1].queuedAs(RenderId(2), RenderId(1)))
    }

    @Test
    fun `every Render problem has its own wording`() {
        val wordings = RenderProblem.entries.map { describe(it, "Night Drive") }
        assertEquals(RenderProblem.entries.size, wordings.toSet().size)
        assertEquals(
            "The Render of “Night Drive” failed: the phone would not encode it at this size or frame rate",
            describe(RenderProblem.ENCODER_REFUSED, "Night Drive"),
        )
        assertEquals("The Render of “Low Tide” failed: the video could not be saved", describe(RenderProblem.NOT_SAVED, "Low Tide"))
    }
}
