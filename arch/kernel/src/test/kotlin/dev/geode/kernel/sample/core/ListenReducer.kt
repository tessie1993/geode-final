package dev.geode.kernel.sample.core

import dev.geode.engine.types.Millis
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Effect
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer
import dev.geode.kernel.sample.api.ListenCommand
import dev.geode.kernel.sample.api.ListenFact
import dev.geode.kernel.sample.api.ListenProblem
import dev.geode.kernel.sample.api.ListenSource
import dev.geode.kernel.sample.api.ListenState

/** Everything the reducer handles: the commands, and what the handlers report back. */
sealed interface ListenEvent {
    data class Command(
        val command: ListenCommand,
    ) : ListenEvent

    data class Running(
        val source: ListenSource,
    ) : ListenEvent

    data class Refused(
        val problem: ListenProblem,
    ) : ListenEvent

    data class QuietChanged(
        val quiet: Boolean,
    ) : ListenEvent

    data object Lost : ListenEvent

    data object StartTimedOut : ListenEvent
}

sealed interface ListenWork {
    /** Listen to the capture's announcements for the life of the store. */
    data object Subscribe : ListenWork

    data class Begin(
        val source: ListenSource,
    ) : ListenWork

    data object End : ListenWork
}

typealias ListenEffect = Effect<ListenWork, ListenEvent, ListenFact>

/** The one timer: how long a start may take, armed only while [ListenState.Starting]. */
object StartTimeout

val START_TIMEOUT = Millis(2_000)

object ListenReducer : Reducer<ListenState, ListenEvent, ListenEffect> {
    override fun reduce(
        state: ListenState,
        event: ListenEvent,
    ): Next<ListenState, ListenEffect> =
        when (state) {
            ListenState.Off, is ListenState.Failed -> idle(state, event)
            is ListenState.Starting -> starting(state, event)
            is ListenState.Listening -> listening(state, event)
        }

    private fun idle(
        state: ListenState,
        event: ListenEvent,
    ): Next<ListenState, ListenEffect> =
        when (event) {
            is ListenEvent.Command ->
                when (val command = event.command) {
                    is ListenCommand.Start -> start(command.source)
                    ListenCommand.Stop -> Next(state)
                }
            // A start that lands after a Stop or a timeout: undo it.
            is ListenEvent.Running -> Next(state, listOf(Perform(ListenWork.End)))
            is ListenEvent.Refused, is ListenEvent.QuietChanged, ListenEvent.Lost, ListenEvent.StartTimedOut -> Next(state)
        }

    private fun starting(
        state: ListenState.Starting,
        event: ListenEvent,
    ): Next<ListenState, ListenEffect> =
        when (event) {
            is ListenEvent.Command ->
                when (event.command) {
                    is ListenCommand.Start -> Next(state)
                    ListenCommand.Stop -> Next(ListenState.Off, listOf(CancelTimer(StartTimeout), Perform(ListenWork.End)))
                }
            is ListenEvent.Running -> Next(ListenState.Listening(event.source, quiet = false), listOf(CancelTimer(StartTimeout)))
            is ListenEvent.Refused -> Next(ListenState.Failed(event.problem), listOf(CancelTimer(StartTimeout)))
            ListenEvent.StartTimedOut ->
                Next(
                    ListenState.Failed(ListenProblem.DID_NOT_START),
                    listOf(ReportFailure("capture from ${state.source} did not start"), Perform(ListenWork.End)),
                )
            is ListenEvent.QuietChanged, ListenEvent.Lost -> Next(state)
        }

    private fun listening(
        state: ListenState.Listening,
        event: ListenEvent,
    ): Next<ListenState, ListenEffect> =
        when (event) {
            is ListenEvent.Command ->
                when (event.command) {
                    is ListenCommand.Start -> Next(state)
                    ListenCommand.Stop -> Next(ListenState.Off, listOf(Perform(ListenWork.End)))
                }
            is ListenEvent.QuietChanged -> Next(state.copy(quiet = event.quiet))
            ListenEvent.Lost -> Next(ListenState.Off, listOf(Emit(ListenFact.Lost)))
            is ListenEvent.Running, is ListenEvent.Refused, ListenEvent.StartTimedOut -> Next(state)
        }

    private fun start(source: ListenSource): Next<ListenState, ListenEffect> =
        Next(
            ListenState.Starting(source),
            listOf(StartTimer(StartTimeout, START_TIMEOUT, ListenEvent.StartTimedOut), Perform(ListenWork.Begin(source))),
        )
}
