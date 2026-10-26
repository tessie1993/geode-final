package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RecordingCommand
import dev.geode.feature.studio.api.RecordingState
import dev.geode.feature.studio.api.StudioState
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure

/** What the banner says when a Recording is refused or cannot be put away. */
const val RECORDING_VISUALS_OFF_REFUSAL = "No Recording can be made while Visuals off is on"
const val RECORDING_NO_PROJECT = "Choose a Project for the Recording: there is no default Project"

/** What Studio's handlers find out about a Recording (#317). */
sealed interface RecordingEvent : StudioEvent {
    /** The encoder and the Visual's second surface are ready: the Recording runs. */
    data object Started : RecordingEvent

    /** The running Recording has lasted [elapsed], with [freeBytes] of storage left on the phone. */
    data class Ticked(
        val elapsed: Millis,
        val freeBytes: Long,
    ) : RecordingEvent

    data class Ended(
        val end: RecordingEnd,
    ) : RecordingEvent
}

/** How a Recording ended. */
sealed interface RecordingEnd {
    /** Finished and saved as [video], which still has to be put into a Project. */
    data class Saved(
        val video: NewVideo,
    ) : RecordingEnd

    data class Failed(
        val problem: RecordingProblem,
    ) : RecordingEnd
}

/** Why a Recording could not be made. */
enum class RecordingProblem {
    /** The live Visual was not on screen to be recorded. */
    NO_VISUAL,

    /** The phone's encoder would not record at the Visual's size. */
    ENCODER_REFUSED,

    /** The Visual could not be drawn into the encoder. */
    DRAWING_FAILED,

    /** It stopped before anything was recorded. */
    NOTHING_RECORDED,

    /** The file could not be written or saved. */
    NOT_SAVED,
}

/** Work for Studio's handlers on a Recording. */
sealed interface RecordingWork : StudioWork {
    /**
     * Record the live Visual with its sound until told to [Stop], reporting [RecordingEvent.Started],
     * then [RecordingEvent.Ticked] about once a second, then how it ended. [song] names it.
     */
    data class Record(
        val song: String,
    ) : RecordingWork

    /** Stop the Recording: it is finished and saved. */
    data object Stop : RecordingWork
}

/** How the reducer describes a Recording that failed with [problem], on the app-wide banner. */
fun describe(problem: RecordingProblem): String {
    val why =
        when (problem) {
            RecordingProblem.NO_VISUAL -> "the Visual was not on screen"
            RecordingProblem.ENCODER_REFUSED -> "the phone would not record at this size"
            RecordingProblem.DRAWING_FAILED -> "the Visual could not be drawn into the video"
            RecordingProblem.NOTHING_RECORDED -> "it stopped before anything was recorded"
            RecordingProblem.NOT_SAVED -> "the video could not be saved"
        }
    return "The Recording failed: $why"
}

/**
 * Studio's Recordings of the live Visual (#317): idle → starting → recording → choose a Project →
 * idle. One at a time. It stops by itself at [RecordingState.LIMIT] or when the phone's storage runs
 * out, and warns while it runs low. Once stopped it is saved, then waits for the person to choose its
 * Project; dismissing the question puts it into the default Project, and with none it keeps waiting,
 * so nothing is discarded silently. It joins the library the way a finished Render does.
 */
internal object Recordings {
    /** Below this much free storage the Recording warns. */
    const val LOW_STORAGE_BYTES: Long = 500L * 1024L * 1024L

    /** Below this much free storage the Recording stops by itself, so the file can still be finished. */
    const val STORAGE_FLOOR_BYTES: Long = 100L * 1024L * 1024L

    fun command(
        state: StudioState,
        command: RecordingCommand,
    ): Next<StudioState, StudioEffect> =
        when (command) {
            is RecordingCommand.Start -> start(state, command.song)
            RecordingCommand.Stop -> stop(state)
            is RecordingCommand.Save -> save(state, command)
        }

    fun event(
        state: StudioState,
        event: RecordingEvent,
    ): Next<StudioState, StudioEffect> =
        when (event) {
            RecordingEvent.Started -> started(state)
            is RecordingEvent.Ticked -> ticked(state, event)
            is RecordingEvent.Ended -> ended(state, event.end)
        }

    private fun started(state: StudioState): Next<StudioState, StudioEffect> =
        if (state.recording == RecordingState.Starting) Next(state.copy(recording = RecordingState.Recording())) else Next(state)

    private fun start(
        state: StudioState,
        song: String,
    ): Next<StudioState, StudioEffect> {
        if (state.recording != RecordingState.Idle) return Next(state)
        if (state.visualsOff) return Next(state, listOf(ReportFailure(RECORDING_VISUALS_OFF_REFUSAL)))
        return Next(state.copy(recording = RecordingState.Starting), listOf(Perform(RecordingWork.Record(song))))
    }

    private fun stop(state: StudioState): Next<StudioState, StudioEffect> =
        when (val now = state.recording) {
            // Not running yet: the handlers stop it as soon as it is.
            RecordingState.Starting -> Next(state, listOf(Perform(RecordingWork.Stop)))
            is RecordingState.Recording ->
                if (now.stopping) {
                    Next(state)
                } else {
                    Next(state.copy(recording = now.copy(stopping = true)), listOf(Perform(RecordingWork.Stop)))
                }
            else -> Next(state)
        }

    private fun ticked(
        state: StudioState,
        event: RecordingEvent.Ticked,
    ): Next<StudioState, StudioEffect> {
        val now = state.recording as? RecordingState.Recording ?: return Next(state)
        val moved = now.copy(elapsed = event.elapsed, lowStorage = event.freeBytes < LOW_STORAGE_BYTES)
        val limited = event.elapsed >= RecordingState.LIMIT || event.freeBytes < STORAGE_FLOOR_BYTES
        if (moved.stopping || !limited) return Next(state.copy(recording = moved))
        return Next(state.copy(recording = moved.copy(stopping = true, atLimit = true)), listOf(Perform(RecordingWork.Stop)))
    }

    private fun ended(
        state: StudioState,
        end: RecordingEnd,
    ): Next<StudioState, StudioEffect> {
        val now = state.recording
        if (now != RecordingState.Starting && now !is RecordingState.Recording) return Next(state)
        return when (end) {
            is RecordingEnd.Saved -> {
                val atLimit = (now as? RecordingState.Recording)?.atLimit == true
                Next(state.copy(recording = RecordingState.ChooseProject(end.video, atLimit)))
            }
            is RecordingEnd.Failed ->
                Next(state.copy(recording = RecordingState.Idle), listOf(ReportFailure(describe(end.problem))))
        }
    }

    private fun save(
        state: StudioState,
        command: RecordingCommand.Save,
    ): Next<StudioState, StudioEffect> {
        val waiting = state.recording as? RecordingState.ChooseProject ?: return Next(state)
        val into = state.projectFor(command.project) ?: return Next(state, listOf(ReportFailure(RECORDING_NO_PROJECT)))
        // Into the library the way a finished Render goes.
        val add = Perform(StudioWork.AddVideos(into, listOf(waiting.video)))
        return Next(state.copy(recording = RecordingState.Idle), listOf(add))
    }
}
