package dev.geode.feature.liveinput.handlers

import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.CaptureAnnouncement
import dev.geode.audio.adapter.CaptureFailure
import dev.geode.feature.liveinput.api.Capture
import dev.geode.feature.liveinput.api.CaptureCommand
import dev.geode.feature.liveinput.api.CaptureFact
import dev.geode.feature.liveinput.api.CaptureSource
import dev.geode.feature.liveinput.api.CaptureState
import dev.geode.feature.liveinput.core.CAPTURE_FEATURE
import dev.geode.feature.liveinput.core.CaptureEvent
import dev.geode.feature.liveinput.core.CaptureReducer
import dev.geode.feature.liveinput.core.CaptureWork
import dev.geode.feature.liveinput.core.MusicControl
import dev.geode.feature.liveinput.core.StartRefusal
import dev.geode.feature.liveinput.core.WorkFailure
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Perform
import dev.geode.kernel.Results
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.coroutines.cancellation.CancellationException
import dev.geode.audio.adapter.CaptureSource as AdapterSource

/**
 * Carries out Capture's work against the audio adapter (the microphone only) and
 * Playback, and turns what the adapter announces into events. Everything that can fail is caught here,
 * at the edge, and reported as a named event for the reducer to decide on (ADR-0005).
 */
class CaptureHandlers(
    private val audio: AudioAdapter,
    private val music: MusicControl,
) : EffectRunner<CaptureWork, CaptureEvent> {
    override suspend fun run(
        work: CaptureWork,
        results: Results<CaptureEvent>,
    ) {
        when (work) {
            CaptureWork.Listen ->
                audio.announcements
                    .filterIsInstance<CaptureAnnouncement>()
                    .collect { results.report(it.toEvent()) }
            CaptureWork.CheckMusic -> {
                val playing = attempt(results, WorkFailure.MUSIC_UNREACHABLE) { music.isPlaying() } ?: false
                results.report(CaptureEvent.MusicChecked(playing))
            }
            is CaptureWork.Begin -> {
                val failure = attempt(results, null) { audio.startCapture(work.source.toAdapter()) } ?: return
                results.report(CaptureEvent.Refused(failure.toRefusal()))
            }
            CaptureWork.End -> attempt(results, WorkFailure.NOT_STOPPED) { audio.stopCapture() }
            CaptureWork.PauseMusic -> attempt(results, WorkFailure.MUSIC_NOT_PAUSED) { music.pause() }
        }
    }

    /**
     * Runs [block], turning anything it throws into a report of [failure] (or, for a start, a refusal),
     * so no exception leaves the handlers. Cancellation is passed on.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> attempt(
        results: Results<CaptureEvent>,
        failure: WorkFailure?,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            results.report(failure?.let(CaptureEvent::WorkFailed) ?: CaptureEvent.Refused(StartRefusal.UNAVAILABLE))
            null
        }

    private fun CaptureAnnouncement.toEvent(): CaptureEvent =
        when (this) {
            is CaptureAnnouncement.Running -> CaptureEvent.Running(source.toCapture())
            is CaptureAnnouncement.QuietChanged -> CaptureEvent.QuietChanged(quiet)
            is CaptureAnnouncement.Lost -> CaptureEvent.Lost(source.toCapture())
        }

    private fun CaptureSource.toAdapter(): AdapterSource =
        when (this) {
            CaptureSource.MICROPHONE -> AdapterSource.MICROPHONE
        }

    private fun AdapterSource.toCapture(): CaptureSource =
        when (this) {
            AdapterSource.MICROPHONE -> CaptureSource.MICROPHONE
        }

    private fun CaptureFailure.toRefusal(): StartRefusal =
        when (this) {
            CaptureFailure.PERMISSION -> StartRefusal.PERMISSION
            CaptureFailure.UNAVAILABLE -> StartRefusal.UNAVAILABLE
        }
}

/**
 * Capture's one app-scoped store, as `:app` builds it when it wires the feature in (#17). Following
 * the adapter's announcements is its start effect.
 */
fun captureStore(
    audio: AudioAdapter,
    music: MusicControl,
    runtime: FeatureRuntime,
): Capture =
    FeatureStore<CaptureState, CaptureCommand, CaptureEvent, CaptureWork, CaptureFact>(
        name = CAPTURE_FEATURE,
        initial = CaptureState.Off,
        reducer = CaptureReducer,
        toEvent = CaptureEvent::Command,
        runner = CaptureHandlers(audio, music),
        runtime = runtime,
        start = listOf(Perform(CaptureWork.Listen)),
    )
