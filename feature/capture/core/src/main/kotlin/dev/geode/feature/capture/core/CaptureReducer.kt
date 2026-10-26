package dev.geode.feature.capture.core

import dev.geode.feature.capture.api.CaptureCommand
import dev.geode.feature.capture.api.CaptureFact
import dev.geode.feature.capture.api.CaptureProblem
import dev.geode.feature.capture.api.CaptureSource
import dev.geode.feature.capture.api.CaptureState
import dev.geode.feature.capture.api.MusicChoice
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer

/**
 * Capture's state machine (ADR-0003, owner decisions on #8):
 *
 * - Starting asks Playback whether a song plays. If one does, the person is asked each time whether
 *   to pause it or keep both (ADR-0004); the song is paused only once the capture really runs.
 * - A missing microphone permission is asked for by the route, whose answer comes back as a command.
 * - A source that is unavailable or refused is explained and capture stops. No fallback.
 * - A source that is lost (another app took the microphone, the app went to the background) is
 *   explained, and the adapter waits for it: when it runs again, so does the capture (#297).
 *   Dismissing the explanation stops the wait.
 * - A start must be announced within [START_TIMEOUT], a timer that lives only while starting.
 * - A start that lands when Capture no longer wants it is undone.
 */
object CaptureReducer : Reducer<CaptureState, CaptureEvent, CaptureEffect> {
    override fun reduce(
        state: CaptureState,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when (state) {
            CaptureState.Off -> idle(state, event)
            is CaptureState.Failed -> if (state.problem == CaptureProblem.SOURCE_LOST) lost(state, event) else idle(state, event)
            is CaptureState.Preparing -> preparing(state, event)
            is CaptureState.AskingAboutMusic -> asking(state, event)
            is CaptureState.AwaitingPermission -> awaitingPermission(state, event)
            is CaptureState.Starting -> starting(state, event)
            is CaptureState.Capturing -> capturing(state, event)
        }

    private fun idle(
        state: CaptureState,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when (event) {
            is CaptureEvent.Command ->
                when (val command = event.command) {
                    is CaptureCommand.Start -> prepare(command.source)
                    is CaptureCommand.Toggle -> prepare(command.source)
                    CaptureCommand.Stop -> Next(CaptureState.Off)
                    is CaptureCommand.ChooseMusic, is CaptureCommand.PermissionAnswered -> Next(state)
                }
            is CaptureEvent.MusicChecked,
            is CaptureEvent.Running,
            is CaptureEvent.Refused,
            is CaptureEvent.QuietChanged,
            is CaptureEvent.Lost,
            CaptureEvent.StartTimedOut,
            is CaptureEvent.WorkFailed,
            -> unwanted(state, event)
        }

    /** The source was lost and the adapter waits for it to come back; anything else is as when idle. */
    private fun lost(
        state: CaptureState.Failed,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when {
            event is CaptureEvent.Running -> Next(CaptureState.Capturing(state.source, quiet = false))
            event is CaptureEvent.Command && event.command == CaptureCommand.Stop ->
                Next(CaptureState.Off, listOf(Perform(CaptureWork.End)))
            else -> idle(state, event)
        }

    private fun preparing(
        state: CaptureState.Preparing,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when (event) {
            is CaptureEvent.Command -> stoppable(state, event.command)
            is CaptureEvent.MusicChecked ->
                if (event.playing) {
                    Next(CaptureState.AskingAboutMusic(state.source))
                } else {
                    begin(state.source, MusicChoice.KEEP_PLAYING)
                }
            is CaptureEvent.Running,
            is CaptureEvent.Refused,
            is CaptureEvent.QuietChanged,
            is CaptureEvent.Lost,
            CaptureEvent.StartTimedOut,
            is CaptureEvent.WorkFailed,
            -> unwanted(state, event)
        }

    private fun asking(
        state: CaptureState.AskingAboutMusic,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when (event) {
            is CaptureEvent.Command ->
                when (val command = event.command) {
                    is CaptureCommand.ChooseMusic -> begin(state.source, command.choice)
                    CaptureCommand.Stop, is CaptureCommand.Toggle, is CaptureCommand.Start, is CaptureCommand.PermissionAnswered ->
                        stoppable(state, command)
                }
            is CaptureEvent.MusicChecked,
            is CaptureEvent.Running,
            is CaptureEvent.Refused,
            is CaptureEvent.QuietChanged,
            is CaptureEvent.Lost,
            CaptureEvent.StartTimedOut,
            is CaptureEvent.WorkFailed,
            -> unwanted(state, event)
        }

    private fun awaitingPermission(
        state: CaptureState.AwaitingPermission,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when (event) {
            is CaptureEvent.Command ->
                when (val command = event.command) {
                    is CaptureCommand.PermissionAnswered ->
                        if (command.granted) {
                            begin(state.source, state.music)
                        } else {
                            Next(CaptureState.Failed(state.source, CaptureProblem.PERMISSION_DENIED))
                        }
                    CaptureCommand.Stop, is CaptureCommand.Toggle, is CaptureCommand.Start, is CaptureCommand.ChooseMusic ->
                        stoppable(state, command)
                }
            is CaptureEvent.MusicChecked,
            is CaptureEvent.Running,
            is CaptureEvent.Refused,
            is CaptureEvent.QuietChanged,
            is CaptureEvent.Lost,
            CaptureEvent.StartTimedOut,
            is CaptureEvent.WorkFailed,
            -> unwanted(state, event)
        }

    private fun starting(
        state: CaptureState.Starting,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when (event) {
            is CaptureEvent.Command ->
                when (event.command) {
                    CaptureCommand.Stop, is CaptureCommand.Toggle ->
                        Next(CaptureState.Off, listOf(CancelTimer(StartTimeout), Perform(CaptureWork.End)))
                    is CaptureCommand.Start, is CaptureCommand.ChooseMusic, is CaptureCommand.PermissionAnswered -> Next(state)
                }
            is CaptureEvent.Running -> running(state)
            is CaptureEvent.Refused ->
                when (event.refusal) {
                    StartRefusal.PERMISSION ->
                        Next(CaptureState.AwaitingPermission(state.source, state.music), listOf(CancelTimer(StartTimeout)))
                    StartRefusal.UNAVAILABLE ->
                        Next(CaptureState.Failed(state.source, CaptureProblem.SOURCE_UNAVAILABLE), listOf(CancelTimer(StartTimeout)))
                }
            CaptureEvent.StartTimedOut ->
                Next(
                    CaptureState.Failed(state.source, CaptureProblem.DID_NOT_START),
                    listOf(ReportFailure("capture from ${state.source} did not start"), Perform(CaptureWork.End)),
                )
            is CaptureEvent.WorkFailed -> report(state, event.failure)
            is CaptureEvent.MusicChecked, is CaptureEvent.QuietChanged, is CaptureEvent.Lost -> Next(state)
        }

    private fun capturing(
        state: CaptureState.Capturing,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when (event) {
            is CaptureEvent.Command ->
                when (event.command) {
                    CaptureCommand.Stop, is CaptureCommand.Toggle -> Next(CaptureState.Off, listOf(Perform(CaptureWork.End)))
                    // The microphone is the only source, so a start while capturing changes nothing.
                    is CaptureCommand.Start, is CaptureCommand.ChooseMusic, is CaptureCommand.PermissionAnswered -> Next(state)
                }
            is CaptureEvent.QuietChanged -> Next(state.copy(quiet = event.quiet))
            is CaptureEvent.Lost ->
                Next(CaptureState.Failed(state.source, CaptureProblem.SOURCE_LOST), listOf(Emit(CaptureFact.Lost(state.source))))
            is CaptureEvent.WorkFailed -> report(state, event.failure)
            is CaptureEvent.Running, is CaptureEvent.Refused, is CaptureEvent.MusicChecked, CaptureEvent.StartTimedOut -> Next(state)
        }

    /** The commands of a state that is waiting on something before the capture starts: only stopping counts. */
    private fun stoppable(
        state: CaptureState,
        command: CaptureCommand,
    ): Next<CaptureState, CaptureEffect> =
        when (command) {
            CaptureCommand.Stop, is CaptureCommand.Toggle -> Next(CaptureState.Off)
            is CaptureCommand.Start, is CaptureCommand.ChooseMusic, is CaptureCommand.PermissionAnswered -> Next(state)
        }

    /**
     * An event the state did not ask for: a capture that runs is undone, a failed piece of work is
     * reported, and anything else is late news.
     */
    private fun unwanted(
        state: CaptureState,
        event: CaptureEvent,
    ): Next<CaptureState, CaptureEffect> =
        when (event) {
            is CaptureEvent.Running -> Next(state, listOf(Perform(CaptureWork.End)))
            is CaptureEvent.WorkFailed -> report(state, event.failure)
            is CaptureEvent.Command,
            is CaptureEvent.MusicChecked,
            is CaptureEvent.Refused,
            is CaptureEvent.QuietChanged,
            is CaptureEvent.Lost,
            CaptureEvent.StartTimedOut,
            -> Next(state)
        }

    private fun report(
        state: CaptureState,
        failure: WorkFailure,
    ): Next<CaptureState, CaptureEffect> = Next(state, listOf(ReportFailure(failure.message)))

    private fun prepare(source: CaptureSource): Next<CaptureState, CaptureEffect> =
        Next(CaptureState.Preparing(source), listOf(Perform(CaptureWork.CheckMusic)))

    private fun begin(
        source: CaptureSource,
        music: MusicChoice,
    ): Next<CaptureState, CaptureEffect> =
        Next(
            CaptureState.Starting(source, music),
            listOf(StartTimer(StartTimeout, START_TIMEOUT, CaptureEvent.StartTimedOut), Perform(CaptureWork.Begin(source))),
        )

    private fun running(state: CaptureState.Starting): Next<CaptureState, CaptureEffect> {
        val pause = if (state.music == MusicChoice.PAUSE) listOf(Perform(CaptureWork.PauseMusic)) else emptyList()
        return Next(CaptureState.Capturing(state.source, quiet = false), listOf(CancelTimer(StartTimeout)) + pause)
    }
}
