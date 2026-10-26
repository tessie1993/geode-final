package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.core.RecordingEnd
import dev.geode.feature.studio.core.RecordingEvent
import dev.geode.feature.studio.core.RecordingProblem
import dev.geode.feature.studio.core.RecordingWork
import dev.geode.feature.studio.core.StudioEvent
import dev.geode.kernel.Results
import kotlinx.coroutines.CancellationException

/**
 * Records the live Visual with its sound (#317). `:app` fills it: a second surface on the live
 * renderer for the picture (the Visual alone, never the App UI) and a tap on the engine's audio feed
 * for the sound, encoded together and saved where Renders are saved. It calls [started] once the
 * Recording runs, [tick] about once a second with how long it has run and the storage left, records
 * until [shouldStop] turns true, and says how it ended. It may throw; the handlers report that as a
 * failed save.
 */
fun interface LiveRecorder {
    suspend fun record(
        song: String,
        started: () -> Unit,
        tick: (elapsed: Millis, freeBytes: Long) -> Unit,
        shouldStop: () -> Boolean,
    ): RecordingEnd

    companion object {
        /** No recorder: every Recording fails at once, as the Visual cannot be reached. */
        val None: LiveRecorder =
            object : LiveRecorder {
                override suspend fun record(
                    song: String,
                    started: () -> Unit,
                    tick: (elapsed: Millis, freeBytes: Long) -> Unit,
                    shouldStop: () -> Boolean,
                ): RecordingEnd = RecordingEnd.Failed(RecordingProblem.NO_VISUAL)
            }
    }
}

/**
 * Carries out Studio's Recording work: one Recording at a time, as the reducer asks, stopped by
 * [RecordingWork.Stop] from another coroutine. However it ends, the reducer hears.
 */
internal class RecordingHandlers(
    private val recorder: LiveRecorder,
) {
    @Volatile
    private var stopAsked = false

    suspend fun run(
        work: RecordingWork,
        results: Results<StudioEvent>,
    ) {
        when (work) {
            is RecordingWork.Record -> record(work.song, results)
            RecordingWork.Stop -> stopAsked = true
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
    private suspend fun record(
        song: String,
        results: Results<StudioEvent>,
    ) {
        // A Stop may come before the recorder runs (the reducer asks for it while it starts): kept.
        val end =
            try {
                recorder.record(
                    song,
                    started = { results.report(RecordingEvent.Started) },
                    tick = { elapsed, free -> results.report(RecordingEvent.Ticked(elapsed, free)) },
                    shouldStop = { stopAsked },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                RecordingEnd.Failed(RecordingProblem.NOT_SAVED)
            }
        stopAsked = false
        results.report(RecordingEvent.Ended(end))
    }
}
