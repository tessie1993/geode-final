package dev.geode.feature.capture.core

import dev.geode.feature.capture.api.CaptureCommand
import dev.geode.feature.capture.api.CaptureFact
import dev.geode.feature.capture.api.CaptureProblem
import dev.geode.feature.capture.api.CaptureSource
import dev.geode.feature.capture.api.CaptureSource.LINE_IN
import dev.geode.feature.capture.api.CaptureSource.MICROPHONE
import dev.geode.feature.capture.api.CaptureState
import dev.geode.feature.capture.api.CaptureState.AskingAboutMusic
import dev.geode.feature.capture.api.CaptureState.AwaitingPermission
import dev.geode.feature.capture.api.CaptureState.Capturing
import dev.geode.feature.capture.api.CaptureState.Failed
import dev.geode.feature.capture.api.CaptureState.Off
import dev.geode.feature.capture.api.CaptureState.Preparing
import dev.geode.feature.capture.api.CaptureState.Starting
import dev.geode.feature.capture.api.MusicChoice
import dev.geode.feature.capture.api.MusicChoice.KEEP_PLAYING
import dev.geode.feature.capture.api.MusicChoice.PAUSE
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Capture's reducer as a transition table (ADR-0003): every state below against every event below.
 * A cell the table does not list must leave the state as it is with no effects; the listed cells are
 * every change.
 */
class CaptureReducerTest {
    private val off = Off
    private val preparing = Preparing(MICROPHONE)
    private val asking = AskingAboutMusic(MICROPHONE)
    private val awaiting = AwaitingPermission(MICROPHONE, PAUSE)
    private val starting = Starting(MICROPHONE, PAUSE)
    private val startingKeep = Starting(MICROPHONE, KEEP_PLAYING)
    private val capturing = Capturing(MICROPHONE, quiet = false)
    private val failed = Failed(LINE_IN, CaptureProblem.SOURCE_LOST)

    private val states: List<CaptureState> = listOf(off, preparing, asking, awaiting, starting, startingKeep, capturing, failed)

    private val startLineIn = command(CaptureCommand.Start(LINE_IN))
    private val startMic = command(CaptureCommand.Start(MICROPHONE))
    private val stop = command(CaptureCommand.Stop)
    private val toggle = command(CaptureCommand.Toggle(LINE_IN))
    private val choosePause = command(CaptureCommand.ChooseMusic(PAUSE))
    private val chooseKeep = command(CaptureCommand.ChooseMusic(KEEP_PLAYING))
    private val granted = command(CaptureCommand.PermissionAnswered(granted = true))
    private val denied = command(CaptureCommand.PermissionAnswered(granted = false))
    private val musicPlaying = CaptureEvent.MusicChecked(playing = true)
    private val musicSilent = CaptureEvent.MusicChecked(playing = false)
    private val runningMic = CaptureEvent.Running(MICROPHONE)
    private val runningLineIn = CaptureEvent.Running(LINE_IN)
    private val refusedPermission = CaptureEvent.Refused(StartRefusal.PERMISSION)
    private val refusedUnavailable = CaptureEvent.Refused(StartRefusal.UNAVAILABLE)
    private val quiet = CaptureEvent.QuietChanged(quiet = true)
    private val lostMic = CaptureEvent.Lost(MICROPHONE)
    private val lostLineIn = CaptureEvent.Lost(LINE_IN)
    private val timedOut = CaptureEvent.StartTimedOut
    private val workFailed = CaptureEvent.WorkFailed(WorkFailure.NOT_STOPPED)

    private val events: List<CaptureEvent> =
        listOf(
            startLineIn,
            startMic,
            stop,
            toggle,
            choosePause,
            chooseKeep,
            granted,
            denied,
            musicPlaying,
            musicSilent,
            runningMic,
            runningLineIn,
            refusedPermission,
            refusedUnavailable,
            quiet,
            lostMic,
            lostLineIn,
            timedOut,
            workFailed,
        )

    private val end = Perform(CaptureWork.End)
    private val cancel = CancelTimer(StartTimeout)
    private val reportWork = ReportFailure(WorkFailure.NOT_STOPPED.message)
    private val timedOutReport = ReportFailure("capture from MICROPHONE did not start")

    private fun begin(
        source: CaptureSource,
        music: MusicChoice,
    ): Next<CaptureState, CaptureEffect> =
        Next(
            Starting(source, music),
            listOf(StartTimer(StartTimeout, START_TIMEOUT, CaptureEvent.StartTimedOut), Perform(CaptureWork.Begin(source))),
        )

    private fun prepare(source: CaptureSource): Next<CaptureState, CaptureEffect> =
        Next(Preparing(source), listOf(Perform(CaptureWork.CheckMusic)))

    /** Every cell that changes something. Everything else stays put. */
    private val changes: Map<Pair<CaptureState, CaptureEvent>, Next<CaptureState, CaptureEffect>> =
        mapOf(
            // Off: a start or a tap on the tile asks Playback first; a start that lands late is undone.
            (off to startLineIn) to prepare(LINE_IN),
            (off to startMic) to prepare(MICROPHONE),
            (off to toggle) to prepare(LINE_IN),
            (off to runningMic) to Next(off, listOf(end)),
            (off to runningLineIn) to Next(off, listOf(end)),
            (off to workFailed) to Next(off, listOf(reportWork)),
            // Preparing: a song playing is asked about; none means start at once.
            (preparing to stop) to Next(Off),
            (preparing to toggle) to Next(Off),
            (preparing to musicPlaying) to Next(asking),
            (preparing to musicSilent) to begin(MICROPHONE, KEEP_PLAYING),
            (preparing to runningMic) to Next(preparing, listOf(end)),
            (preparing to runningLineIn) to Next(preparing, listOf(end)),
            (preparing to workFailed) to Next(preparing, listOf(reportWork)),
            // Asking about the song: the answer starts the capture.
            (asking to stop) to Next(Off),
            (asking to toggle) to Next(Off),
            (asking to choosePause) to begin(MICROPHONE, PAUSE),
            (asking to chooseKeep) to begin(MICROPHONE, KEEP_PLAYING),
            (asking to runningMic) to Next(asking, listOf(end)),
            (asking to runningLineIn) to Next(asking, listOf(end)),
            (asking to workFailed) to Next(asking, listOf(reportWork)),
            // Awaiting the permission: granted tries again, keeping the song choice; refused explains and stops.
            (awaiting to stop) to Next(Off),
            (awaiting to toggle) to Next(Off),
            (awaiting to granted) to begin(MICROPHONE, PAUSE),
            (awaiting to denied) to Next(Failed(MICROPHONE, CaptureProblem.PERMISSION_DENIED)),
            (awaiting to runningMic) to Next(awaiting, listOf(end)),
            (awaiting to runningLineIn) to Next(awaiting, listOf(end)),
            (awaiting to workFailed) to Next(awaiting, listOf(reportWork)),
            // Starting: leaving it, any way, cancels the start timeout. The song pauses once capture runs.
            (starting to stop) to Next(Off, listOf(cancel, end)),
            (starting to toggle) to Next(Off, listOf(cancel, end)),
            (starting to runningMic) to Next(capturing, listOf(cancel, Perform(CaptureWork.PauseMusic))),
            (starting to refusedPermission) to Next(awaiting, listOf(cancel)),
            (starting to refusedUnavailable) to Next(Failed(MICROPHONE, CaptureProblem.SOURCE_UNAVAILABLE), listOf(cancel)),
            (starting to timedOut) to
                Next(Failed(MICROPHONE, CaptureProblem.DID_NOT_START), listOf(timedOutReport, end)),
            (starting to workFailed) to Next(starting, listOf(reportWork)),
            // Starting while keeping the song: the same, without pausing it.
            (startingKeep to stop) to Next(Off, listOf(cancel, end)),
            (startingKeep to toggle) to Next(Off, listOf(cancel, end)),
            (startingKeep to runningMic) to Next(capturing, listOf(cancel)),
            (startingKeep to refusedPermission) to Next(AwaitingPermission(MICROPHONE, KEEP_PLAYING), listOf(cancel)),
            (startingKeep to refusedUnavailable) to Next(Failed(MICROPHONE, CaptureProblem.SOURCE_UNAVAILABLE), listOf(cancel)),
            (startingKeep to timedOut) to
                Next(Failed(MICROPHONE, CaptureProblem.DID_NOT_START), listOf(timedOutReport, end)),
            (startingKeep to workFailed) to Next(startingKeep, listOf(reportWork)),
            // Capturing: follows the source until stopped, switched or lost.
            (capturing to stop) to Next(Off, listOf(end)),
            (capturing to toggle) to Next(Off, listOf(end)),
            (capturing to startLineIn) to begin(LINE_IN, KEEP_PLAYING),
            (capturing to quiet) to Next(Capturing(MICROPHONE, quiet = true)),
            (capturing to lostMic) to Next(Failed(MICROPHONE, CaptureProblem.SOURCE_LOST), listOf(Emit(CaptureFact.Lost(MICROPHONE)))),
            (capturing to workFailed) to Next(capturing, listOf(reportWork)),
            // Failed: shown until dismissed; a start tries again from the beginning.
            (failed to startLineIn) to prepare(LINE_IN),
            (failed to startMic) to prepare(MICROPHONE),
            (failed to toggle) to prepare(LINE_IN),
            (failed to stop) to Next(Off),
            (failed to runningMic) to Next(failed, listOf(end)),
            (failed to runningLineIn) to Next(failed, listOf(end)),
            (failed to workFailed) to Next(failed, listOf(reportWork)),
        )

    @Test
    fun `every state handles every event as the table says`() {
        for (state in states) {
            for (event in events) {
                val expected = changes[state to event] ?: Next(state)
                assertEquals("$state + $event", expected, CaptureReducer.reduce(state, event))
            }
        }
    }

    @Test
    fun `the table lists only cells that exist`() {
        val cells = states.flatMap { state -> events.map { state to it } }.toSet()
        assertEquals(emptySet<Pair<CaptureState, CaptureEvent>>(), changes.keys - cells)
    }

    @Test
    fun `quiet ends when the capture hears something again`() {
        val next = CaptureReducer.reduce(Capturing(LINE_IN, quiet = true), CaptureEvent.QuietChanged(quiet = false))

        assertEquals(Next<CaptureState, CaptureEffect>(Capturing(LINE_IN, quiet = false)), next)
    }

    @Test
    fun `every work failure is reported with its own message`() {
        for (failure in WorkFailure.entries) {
            assertEquals(
                Next<CaptureState, CaptureEffect>(off, listOf(ReportFailure(failure.message))),
                CaptureReducer.reduce(off, CaptureEvent.WorkFailed(failure)),
            )
        }
    }

    private fun command(command: CaptureCommand): CaptureEvent = CaptureEvent.Command(command)
}
