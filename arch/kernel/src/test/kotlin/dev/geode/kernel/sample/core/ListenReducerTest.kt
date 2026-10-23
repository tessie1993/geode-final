package dev.geode.kernel.sample.core

import dev.geode.engine.types.Millis
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer
import dev.geode.kernel.sample.api.ListenCommand
import dev.geode.kernel.sample.api.ListenFact
import dev.geode.kernel.sample.api.ListenProblem.DID_NOT_START
import dev.geode.kernel.sample.api.ListenProblem.PERMISSION
import dev.geode.kernel.sample.api.ListenProblem.UNAVAILABLE
import dev.geode.kernel.sample.api.ListenSource.MICROPHONE
import dev.geode.kernel.sample.api.ListenState
import dev.geode.kernel.sample.api.ListenState.Failed
import dev.geode.kernel.sample.api.ListenState.Listening
import dev.geode.kernel.sample.api.ListenState.Off
import dev.geode.kernel.sample.api.ListenState.Starting
import dev.geode.kernel.sample.core.ListenEvent.Lost
import dev.geode.kernel.sample.core.ListenEvent.QuietChanged
import dev.geode.kernel.sample.core.ListenEvent.Refused
import dev.geode.kernel.sample.core.ListenEvent.Running
import dev.geode.kernel.sample.core.ListenEvent.StartTimedOut
import dev.geode.kernel.sample.core.ListenWork.Begin
import dev.geode.kernel.sample.core.ListenWork.End
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sample reducer as a transition table: every state against every event (ADR-0003). */
class ListenReducerTest {
    private val start = ListenEvent.Command(ListenCommand.Start(MICROPHONE))
    private val stop = ListenEvent.Command(ListenCommand.Stop)
    private val listening = Listening(MICROPHONE, quiet = false)
    private val failed = Failed(UNAVAILABLE)
    private val starting = Starting(MICROPHONE)
    private val beginMicrophone =
        listOf(StartTimer(StartTimeout, Millis(2_000), StartTimedOut), Perform(Begin(MICROPHONE)))

    private val table: List<Row> =
        listOf(
            // Off: a start arms the start timeout; a start that lands late is undone.
            Row(Off, start, Next(Starting(MICROPHONE), beginMicrophone)),
            Row(Off, stop, Next(Off)),
            Row(Off, Running(MICROPHONE), Next(Off, listOf(Perform(End)))),
            Row(Off, Refused(PERMISSION), Next(Off)),
            Row(Off, QuietChanged(true), Next(Off)),
            Row(Off, Lost, Next(Off)),
            Row(Off, StartTimedOut, Next(Off)),
            // Starting: leaving it, any way, cancels the start timeout.
            Row(starting, start, Next(starting)),
            Row(starting, stop, Next(Off, listOf(CancelTimer(StartTimeout), Perform(End)))),
            Row(starting, Running(MICROPHONE), Next(listening, listOf(CancelTimer(StartTimeout)))),
            Row(starting, Refused(PERMISSION), Next(Failed(PERMISSION), listOf(CancelTimer(StartTimeout)))),
            Row(starting, QuietChanged(true), Next(starting)),
            Row(starting, Lost, Next(starting)),
            Row(
                starting,
                StartTimedOut,
                Next(Failed(DID_NOT_START), listOf(ReportFailure("capture from MICROPHONE did not start"), Perform(End))),
            ),
            // Listening: follows the capture until it is stopped or lost.
            Row(listening, start, Next(listening)),
            Row(listening, stop, Next(Off, listOf(Perform(End)))),
            Row(listening, Running(MICROPHONE), Next(listening)),
            Row(listening, Refused(PERMISSION), Next(listening)),
            Row(listening, QuietChanged(true), Next(Listening(MICROPHONE, quiet = true))),
            Row(listening, Lost, Next(Off, listOf(Emit(ListenFact.Lost)))),
            Row(listening, StartTimedOut, Next(listening)),
            // Failed: like Off, a start tries again.
            Row(failed, start, Next(Starting(MICROPHONE), beginMicrophone)),
            Row(failed, stop, Next(failed)),
            Row(failed, Running(MICROPHONE), Next(failed, listOf(Perform(End)))),
            Row(failed, Refused(PERMISSION), Next(failed)),
            Row(failed, QuietChanged(false), Next(failed)),
            Row(failed, Lost, Next(failed)),
            Row(failed, StartTimedOut, Next(failed)),
        )

    @Test
    fun `every state handles every event as the table says`() {
        for (row in table) {
            assertEquals("${row.state} + ${row.event}", row.next, ListenReducer.reduce(row.state, row.event))
        }
    }

    private class Row(
        val state: ListenState,
        val event: ListenEvent,
        val next: Next<ListenState, ListenEffect>,
    )
}
