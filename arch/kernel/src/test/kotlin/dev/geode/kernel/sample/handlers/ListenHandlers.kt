package dev.geode.kernel.sample.handlers

import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.CaptureAnnouncement
import dev.geode.audio.adapter.CaptureFailure
import dev.geode.audio.adapter.CaptureSource
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Perform
import dev.geode.kernel.Results
import dev.geode.kernel.sample.api.Listen
import dev.geode.kernel.sample.api.ListenCommand
import dev.geode.kernel.sample.api.ListenFact
import dev.geode.kernel.sample.api.ListenProblem
import dev.geode.kernel.sample.api.ListenSource
import dev.geode.kernel.sample.api.ListenState
import dev.geode.kernel.sample.core.ListenEvent
import dev.geode.kernel.sample.core.ListenReducer
import dev.geode.kernel.sample.core.ListenWork
import kotlinx.coroutines.flow.filterIsInstance

/** Carries out the sample's work against the audio adapter and turns what it announces into events. */
class ListenHandlers(
    private val audio: AudioAdapter,
) : EffectRunner<ListenWork, ListenEvent> {
    override suspend fun run(
        work: ListenWork,
        results: Results<ListenEvent>,
    ) {
        when (work) {
            ListenWork.Subscribe ->
                audio.announcements
                    .filterIsInstance<CaptureAnnouncement>()
                    .collect { results.report(it.toEvent()) }
            is ListenWork.Begin -> audio.startCapture(work.source.toCapture())?.let { results.report(ListenEvent.Refused(it.toProblem())) }
            ListenWork.End -> audio.stopCapture()
        }
    }

    private fun CaptureAnnouncement.toEvent(): ListenEvent =
        when (this) {
            is CaptureAnnouncement.Running -> ListenEvent.Running(source.toListen())
            is CaptureAnnouncement.QuietChanged -> ListenEvent.QuietChanged(quiet)
            is CaptureAnnouncement.Lost -> ListenEvent.Lost
        }

    private fun ListenSource.toCapture(): CaptureSource =
        when (this) {
            ListenSource.MICROPHONE -> CaptureSource.MICROPHONE
        }

    private fun CaptureSource.toListen(): ListenSource =
        when (this) {
            CaptureSource.MICROPHONE -> ListenSource.MICROPHONE
        }

    private fun CaptureFailure.toProblem(): ListenProblem =
        when (this) {
            CaptureFailure.PERMISSION -> ListenProblem.PERMISSION
            CaptureFailure.UNAVAILABLE -> ListenProblem.UNAVAILABLE
        }
}

/** How `:app` would build the sample's one store; subscribing to the adapter is its start effect. */
fun listenStore(
    audio: AudioAdapter,
    runtime: FeatureRuntime,
): Listen =
    FeatureStore<ListenState, ListenCommand, ListenEvent, ListenWork, ListenFact>(
        name = "listen",
        initial = ListenState.Off,
        reducer = ListenReducer,
        toEvent = ListenEvent::Command,
        runner = ListenHandlers(audio),
        runtime = runtime,
        start = listOf(Perform(ListenWork.Subscribe)),
    )
