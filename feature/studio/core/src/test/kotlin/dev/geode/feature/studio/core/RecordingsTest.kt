package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RecordingCommand
import dev.geode.feature.studio.api.RecordingState
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.VideoKind
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** Studio's Recordings of the live Visual as a transition table (ADR-0003, #317). */
class RecordingsTest {
    private val trip = Project(ProjectId(1), "Trip", Millis(10))
    private val gigs = Project(ProjectId(2), "Gigs", Millis(20))
    private val video =
        NewVideo(VideoKind.RECORDING, "Night Drive", "content://media/video/9", null, Millis(61_000), 1080, 2400, "Night Drive")

    private val ready = StudioState(loading = false, projects = listOf(trip, gigs), defaultProject = gigs.id)
    private val starting = ready.copy(recording = RecordingState.Starting)
    private val recording = ready.copy(recording = RecordingState.Recording(Millis(5_000)))
    private val stopping = ready.copy(recording = RecordingState.Recording(Millis(5_000), stopping = true))
    private val atLimit = ready.copy(recording = RecordingState.Recording(RecordingState.LIMIT, stopping = true, atLimit = true))
    private val choosing = ready.copy(recording = RecordingState.ChooseProject(video))

    private val plenty = 4L * 1024L * 1024L * 1024L

    private fun command(command: RecordingCommand) = StudioEvent.Command(command)

    private fun ticked(
        elapsed: Long,
        free: Long = plenty,
    ) = RecordingEvent.Ticked(Millis(elapsed), free)

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

    private val startRows =
        listOf(
            row(
                "Record starts setting up the Recording",
                ready,
                command(RecordingCommand.Start("Night Drive")),
                starting,
                Perform(RecordingWork.Record("Night Drive")),
            ),
            row(
                "no Recording while Visuals off is on",
                ready.copy(visualsOff = true),
                command(RecordingCommand.Start()),
                ready.copy(visualsOff = true),
                ReportFailure(RECORDING_VISUALS_OFF_REFUSAL),
            ),
            row("one Recording at a time: not while one starts", starting, command(RecordingCommand.Start()), starting),
            row("one Recording at a time: not while one runs", recording, command(RecordingCommand.Start()), recording),
            row("one Recording at a time: not while one waits", choosing, command(RecordingCommand.Start()), choosing),
            row(
                "a ready recorder starts the Recording",
                starting,
                RecordingEvent.Started,
                ready.copy(recording = RecordingState.Recording()),
            ),
            row("a recorder ready with no Recording asked for changes nothing", ready, RecordingEvent.Started, ready),
            row("a recorder ready again while recording changes nothing", recording, RecordingEvent.Started, recording),
            row(
                "a Record that arrives before the library is read waits for it",
                StudioState(),
                command(RecordingCommand.Start("Low Tide")),
                StudioState(pending = listOf(RecordingCommand.Start("Low Tide"))),
            ),
        )

    private val stopRows =
        listOf(
            row("Stop finishes the Recording", recording, command(RecordingCommand.Stop), stopping, Perform(RecordingWork.Stop)),
            row("Stop twice asks once", stopping, command(RecordingCommand.Stop), stopping),
            row(
                "Stop while it starts stops it once it runs",
                starting,
                command(RecordingCommand.Stop),
                starting,
                Perform(RecordingWork.Stop),
            ),
            row("Stop with no Recording changes nothing", ready, command(RecordingCommand.Stop), ready),
            row("Stop while it waits for its Project changes nothing", choosing, command(RecordingCommand.Stop), choosing),
        )

    private val tickRows =
        listOf(
            row(
                "the time recorded moves on",
                recording,
                ticked(6_000),
                ready.copy(recording = RecordingState.Recording(Millis(6_000))),
            ),
            row(
                "low storage warns and goes on recording",
                recording,
                ticked(6_000, Recordings.LOW_STORAGE_BYTES - 1),
                ready.copy(recording = RecordingState.Recording(Millis(6_000), lowStorage = true)),
            ),
            row(
                "just enough storage does not warn",
                recording,
                ticked(6_000, Recordings.LOW_STORAGE_BYTES),
                ready.copy(recording = RecordingState.Recording(Millis(6_000))),
            ),
            row(
                "the Recording stops by itself at 30 minutes",
                recording,
                ticked(RecordingState.LIMIT.value),
                ready.copy(recording = RecordingState.Recording(RecordingState.LIMIT, stopping = true, atLimit = true)),
                Perform(RecordingWork.Stop),
            ),
            row(
                "just under 30 minutes it goes on",
                recording,
                ticked(RecordingState.LIMIT.value - 1),
                ready.copy(recording = RecordingState.Recording(Millis(RecordingState.LIMIT.value - 1))),
            ),
            row(
                "the Recording stops by itself when storage runs out",
                recording,
                ticked(7_000, Recordings.STORAGE_FLOOR_BYTES - 1),
                ready.copy(recording = RecordingState.Recording(Millis(7_000), lowStorage = true, stopping = true, atLimit = true)),
                Perform(RecordingWork.Stop),
            ),
            row(
                "just enough storage left goes on, warning",
                recording,
                ticked(7_000, Recordings.STORAGE_FLOOR_BYTES),
                ready.copy(recording = RecordingState.Recording(Millis(7_000), lowStorage = true)),
            ),
            row(
                "a Recording already stopping is not stopped again",
                stopping,
                ticked(RecordingState.LIMIT.value),
                ready.copy(recording = RecordingState.Recording(RecordingState.LIMIT, stopping = true)),
            ),
            row("a tick with no Recording running changes nothing", ready, ticked(1_000), ready),
            row("a tick while it starts changes nothing", starting, ticked(1_000), starting),
        )

    private val endRows =
        listOf(
            row(
                "a finished Recording waits for its Project",
                stopping,
                RecordingEvent.Ended(RecordingEnd.Saved(video)),
                choosing,
            ),
            row(
                "a Recording that stopped by itself says so when it asks",
                atLimit,
                RecordingEvent.Ended(RecordingEnd.Saved(video)),
                ready.copy(recording = RecordingState.ChooseProject(video, stoppedAtLimit = true)),
            ),
            row(
                "a Recording stopped while it started still waits for its Project",
                starting,
                RecordingEvent.Ended(RecordingEnd.Saved(video)),
                choosing,
            ),
            row(
                "a failed Recording is reported",
                recording,
                RecordingEvent.Ended(RecordingEnd.Failed(RecordingProblem.ENCODER_REFUSED)),
                ready,
                ReportFailure(describe(RecordingProblem.ENCODER_REFUSED)),
            ),
            row(
                "a Recording that failed to start is reported",
                starting,
                RecordingEvent.Ended(RecordingEnd.Failed(RecordingProblem.NO_VISUAL)),
                ready,
                ReportFailure(describe(RecordingProblem.NO_VISUAL)),
            ),
            row("an end with no Recording changes nothing", ready, RecordingEvent.Ended(RecordingEnd.Saved(video)), ready),
            row(
                "an end while one waits for its Project changes nothing",
                choosing,
                RecordingEvent.Ended(RecordingEnd.Failed(RecordingProblem.NOT_SAVED)),
                choosing,
            ),
        )

    private val saveRows =
        listOf(
            row(
                "the Recording goes into the Project chosen",
                choosing,
                command(RecordingCommand.Save(trip.id)),
                ready,
                Perform(StudioWork.AddVideos(trip.id, listOf(video))),
            ),
            row(
                "dismissed, it goes into the default Project",
                choosing,
                command(RecordingCommand.Save(null)),
                ready,
                Perform(StudioWork.AddVideos(gigs.id, listOf(video))),
            ),
            row(
                "a Project deleted meanwhile is the default one",
                choosing,
                command(RecordingCommand.Save(ProjectId(9))),
                ready,
                Perform(StudioWork.AddVideos(gigs.id, listOf(video))),
            ),
            row(
                "dismissed with no default Project, it keeps waiting",
                choosing.copy(defaultProject = null),
                command(RecordingCommand.Save(null)),
                choosing.copy(defaultProject = null),
                ReportFailure(RECORDING_NO_PROJECT),
            ),
            row("nothing to put away changes nothing", ready, command(RecordingCommand.Save(trip.id)), ready),
            row("a running Recording is not put away", recording, command(RecordingCommand.Save(trip.id)), recording),
        )

    @Test
    fun `every row of the transition table`() {
        for (row in startRows + stopRows + tickRows + endRows + saveRows) {
            assertEquals(row.name, row.to, StudioReducer.reduce(row.from, row.event))
        }
    }

    @Test
    fun `a Recording waiting for the library starts once it is read`() {
        val waiting = StudioReducer.reduce(StudioState(), command(RecordingCommand.Start("Low Tide"))).state
        val read = StudioReducer.reduce(waiting, StudioEvent.Loaded(listOf(trip), emptyList(), trip.id))
        assertEquals(RecordingState.Starting, read.state.recording)
        assertEquals(listOf(Perform(RecordingWork.Record("Low Tide"))), read.effects)
    }

    @Test
    fun `the limits are 30 minutes and the storage thresholds`() {
        assertEquals(Millis(1_800_000), RecordingState.LIMIT)
        assertEquals(524_288_000L, Recordings.LOW_STORAGE_BYTES)
        assertEquals(104_857_600L, Recordings.STORAGE_FLOOR_BYTES)
    }

    @Test
    fun `every Recording problem has its own wording`() {
        val wordings = RecordingProblem.entries.map { describe(it) }
        assertEquals(RecordingProblem.entries.size, wordings.toSet().size)
        assertEquals("The Recording failed: the Visual was not on screen", describe(RecordingProblem.NO_VISUAL))
        assertEquals("The Recording failed: the phone would not record at this size", describe(RecordingProblem.ENCODER_REFUSED))
        assertEquals("The Recording failed: the Visual could not be drawn into the video", describe(RecordingProblem.DRAWING_FAILED))
        assertEquals("The Recording failed: it stopped before anything was recorded", describe(RecordingProblem.NOTHING_RECORDED))
        assertEquals("The Recording failed: the video could not be saved", describe(RecordingProblem.NOT_SAVED))
    }
}
