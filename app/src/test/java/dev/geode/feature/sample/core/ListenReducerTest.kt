package dev.geode.feature.sample.core

import dev.geode.audio.adapter.CaptureFailure.PERMISSION
import dev.geode.audio.adapter.CaptureFailure.UNAVAILABLE
import dev.geode.audio.adapter.CaptureSource.LINE_IN
import dev.geode.audio.adapter.CaptureSource.MICROPHONE
import dev.geode.audio.adapter.CaptureStatus
import dev.geode.engine.types.Millis
import dev.geode.feature.kernel.core.CancelTimer
import dev.geode.feature.kernel.core.Emit
import dev.geode.feature.kernel.core.Next
import dev.geode.feature.kernel.core.Perform
import dev.geode.feature.kernel.core.StartTimer
import dev.geode.feature.sample.core.ListenCommand.Start
import dev.geode.feature.sample.core.ListenCommand.Stop
import dev.geode.feature.sample.core.ListenEvent.CheckDue
import dev.geode.feature.sample.core.ListenEvent.Checked
import dev.geode.feature.sample.core.ListenEvent.Refused
import dev.geode.feature.sample.core.ListenEvent.Started
import dev.geode.feature.sample.core.ListenState.Failed
import dev.geode.feature.sample.core.ListenState.Listening
import dev.geode.feature.sample.core.ListenState.Off
import dev.geode.feature.sample.core.ListenState.Starting
import dev.geode.feature.sample.core.ListenWork.Begin
import dev.geode.feature.sample.core.ListenWork.Check
import dev.geode.feature.sample.core.ListenWork.End
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sample reducer as a transition table: every state against every event (ADR-0003). */
class ListenReducerTest {
    private val listening = Listening(MICROPHONE, quiet = false)
    private val failed = Failed(UNAVAILABLE)
    private val armCheck = StartTimer(CheckTimer, Millis(500), CheckDue)

    private val table: List<Row> =
        listOf(
            // Off: only a start does anything; a start that lands late is undone.
            Row(Off, Start(LINE_IN), Next(Starting(LINE_IN), listOf(Perform(Begin(LINE_IN))))),
            Row(Off, Stop, Next(Off)),
            Row(Off, Started(MICROPHONE), Next(Off, listOf(Perform(End)))),
            Row(Off, Refused(PERMISSION), Next(Off)),
            Row(Off, CheckDue, Next(Off)),
            Row(Off, Checked(CaptureStatus.Running(MICROPHONE, quiet = true)), Next(Off)),
            Row(Off, Checked(CaptureStatus.Idle), Next(Off)),
            // Starting: waits for the adapter's answer.
            Row(Starting(MICROPHONE), Start(LINE_IN), Next(Starting(MICROPHONE))),
            Row(Starting(MICROPHONE), Stop, Next(Off, listOf(Perform(End)))),
            Row(Starting(MICROPHONE), Started(MICROPHONE), Next(listening, listOf(armCheck))),
            Row(Starting(MICROPHONE), Refused(PERMISSION), Next(Failed(PERMISSION))),
            Row(Starting(MICROPHONE), CheckDue, Next(Starting(MICROPHONE))),
            Row(Starting(MICROPHONE), Checked(CaptureStatus.Idle), Next(Starting(MICROPHONE))),
            Row(Starting(MICROPHONE), Checked(CaptureStatus.Running(MICROPHONE, quiet = true)), Next(Starting(MICROPHONE))),
            // Listening: the check timer runs while this state lasts.
            Row(listening, Start(LINE_IN), Next(listening)),
            Row(listening, Stop, Next(Off, listOf(CancelTimer(CheckTimer), Perform(End)))),
            Row(listening, Started(LINE_IN), Next(listening)),
            Row(listening, Refused(PERMISSION), Next(listening)),
            Row(listening, CheckDue, Next(listening, listOf(Perform(Check)))),
            Row(
                listening,
                Checked(CaptureStatus.Running(MICROPHONE, quiet = true)),
                Next(Listening(MICROPHONE, quiet = true), listOf(armCheck)),
            ),
            Row(listening, Checked(CaptureStatus.Idle), Next(Off, listOf(Emit(ListenFact.Lost)))),
            // Failed: like Off, a start tries again.
            Row(failed, Start(MICROPHONE), Next(Starting(MICROPHONE), listOf(Perform(Begin(MICROPHONE))))),
            Row(failed, Stop, Next(failed)),
            Row(failed, Started(MICROPHONE), Next(failed, listOf(Perform(End)))),
            Row(failed, Refused(PERMISSION), Next(failed)),
            Row(failed, CheckDue, Next(failed)),
            Row(failed, Checked(CaptureStatus.Idle), Next(failed)),
            Row(failed, Checked(CaptureStatus.Running(MICROPHONE, quiet = false)), Next(failed)),
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
