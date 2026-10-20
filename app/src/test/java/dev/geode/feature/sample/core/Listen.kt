package dev.geode.feature.sample.core

import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.CaptureFailure
import dev.geode.audio.adapter.CaptureSource
import dev.geode.audio.adapter.CaptureStatus
import dev.geode.engine.types.Millis
import dev.geode.feature.kernel.core.CancelTimer
import dev.geode.feature.kernel.core.Effect
import dev.geode.feature.kernel.core.EffectRunner
import dev.geode.feature.kernel.core.Emit
import dev.geode.feature.kernel.core.FeatureStore
import dev.geode.feature.kernel.core.Next
import dev.geode.feature.kernel.core.Perform
import dev.geode.feature.kernel.core.Reducer
import dev.geode.feature.kernel.core.StartTimer
import dev.geode.feature.kernel.core.TimerScheduler
import kotlinx.coroutines.CoroutineScope

// A sample feature that exists only in tests, to prove the foundation end to end: a sealed state
// machine, a pure reducer, effects carried out against the audio adapter, a timer that runs only
// while one state lasts, and a fact on `events`. It listens to a capture source and notices when
// the capture is lost. It is not Capture (#8), and makes no claim about how Capture behaves.

sealed interface ListenState {
    data object Off : ListenState

    data class Starting(
        val source: CaptureSource,
    ) : ListenState

    data class Listening(
        val source: CaptureSource,
        val quiet: Boolean,
    ) : ListenState

    data class Failed(
        val failure: CaptureFailure,
    ) : ListenState
}

/** Everything the reducer handles: the public commands and the results that come back. */
sealed interface ListenEvent {
    data class Started(
        val source: CaptureSource,
    ) : ListenEvent

    data class Refused(
        val failure: CaptureFailure,
    ) : ListenEvent

    data object CheckDue : ListenEvent

    data class Checked(
        val status: CaptureStatus,
    ) : ListenEvent
}

sealed interface ListenCommand : ListenEvent {
    data class Start(
        val source: CaptureSource,
    ) : ListenCommand

    data object Stop : ListenCommand
}

sealed interface ListenWork {
    data class Begin(
        val source: CaptureSource,
    ) : ListenWork

    data object Check : ListenWork

    data object End : ListenWork
}

sealed interface ListenFact {
    data object Lost : ListenFact
}

typealias ListenEffect = Effect<ListenWork, ListenEvent, ListenFact>

/** The key of the one timer: the capture check, armed only while [ListenState.Listening]. */
object CheckTimer

val CHECK_EVERY = Millis(500)

object ListenReducer : Reducer<ListenState, ListenEvent, ListenEffect> {
    override fun reduce(
        state: ListenState,
        event: ListenEvent,
    ): Next<ListenState, ListenEffect> =
        when (state) {
            ListenState.Off, is ListenState.Failed ->
                when (event) {
                    is ListenCommand.Start -> starting(event.source)
                    // A start that lands after a Stop: undo it.
                    is ListenEvent.Started -> Next(state, listOf(Perform(ListenWork.End)))
                    ListenCommand.Stop, is ListenEvent.Refused, ListenEvent.CheckDue, is ListenEvent.Checked -> Next(state)
                }
            is ListenState.Starting ->
                when (event) {
                    is ListenEvent.Started -> listening(event.source, quiet = false)
                    is ListenEvent.Refused -> Next(ListenState.Failed(event.failure))
                    ListenCommand.Stop -> Next(ListenState.Off, listOf(Perform(ListenWork.End)))
                    is ListenCommand.Start, ListenEvent.CheckDue, is ListenEvent.Checked -> Next(state)
                }
            is ListenState.Listening ->
                when (event) {
                    ListenEvent.CheckDue -> Next(state, listOf(Perform(ListenWork.Check)))
                    is ListenEvent.Checked ->
                        when (val status = event.status) {
                            is CaptureStatus.Running -> listening(state.source, status.quiet)
                            CaptureStatus.Idle -> Next(ListenState.Off, listOf(Emit(ListenFact.Lost)))
                        }
                    ListenCommand.Stop -> Next(ListenState.Off, listOf(CancelTimer(CheckTimer), Perform(ListenWork.End)))
                    is ListenCommand.Start, is ListenEvent.Started, is ListenEvent.Refused -> Next(state)
                }
        }

    private fun starting(source: CaptureSource): Next<ListenState, ListenEffect> =
        Next(ListenState.Starting(source), listOf(Perform(ListenWork.Begin(source))))

    private fun listening(
        source: CaptureSource,
        quiet: Boolean,
    ): Next<ListenState, ListenEffect> =
        Next(ListenState.Listening(source, quiet), listOf(StartTimer(CheckTimer, CHECK_EVERY, ListenEvent.CheckDue)))
}

class ListenRunner(
    private val audio: AudioAdapter,
) : EffectRunner<ListenWork, ListenEvent> {
    override suspend fun run(
        work: ListenWork,
        report: (ListenEvent) -> Unit,
    ) {
        when (work) {
            is ListenWork.Begin -> {
                val failure = audio.startCapture(work.source)
                report(if (failure == null) ListenEvent.Started(work.source) else ListenEvent.Refused(failure))
            }
            ListenWork.Check -> report(ListenEvent.Checked(audio.captureStatus()))
            ListenWork.End -> audio.stopCapture()
        }
    }
}

fun listenStore(
    audio: AudioAdapter,
    timers: TimerScheduler,
    scope: CoroutineScope,
): FeatureStore<ListenState, ListenEvent, ListenCommand, ListenWork, ListenFact> =
    FeatureStore(
        initial = ListenState.Off,
        reducer = ListenReducer,
        runner = ListenRunner(audio),
        timers = timers,
        scope = scope,
    )
