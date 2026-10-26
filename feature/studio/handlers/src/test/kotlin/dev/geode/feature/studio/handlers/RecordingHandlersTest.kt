package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.RecordingEnd
import dev.geode.feature.studio.core.RecordingEvent
import dev.geode.feature.studio.core.RecordingProblem
import dev.geode.feature.studio.core.RecordingWork
import dev.geode.feature.studio.core.StudioEvent
import dev.geode.kernel.Results
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** Studio's Recording work over a fake recorder (#317). */
class RecordingHandlersTest {
    private val events = mutableListOf<StudioEvent>()
    private val results = Results<StudioEvent> { events += it }

    /** A recorder that does [body] with the song, and its started, tick and should-stop callbacks. */
    private fun fakeRecorder(body: (String, () -> Unit, (Millis, Long) -> Unit, () -> Boolean) -> RecordingEnd): LiveRecorder =
        object : LiveRecorder {
            override suspend fun record(
                song: String,
                started: () -> Unit,
                tick: (elapsed: Millis, freeBytes: Long) -> Unit,
                shouldStop: () -> Boolean,
            ): RecordingEnd = body(song, started, tick, shouldStop)
        }

    private fun video(song: String) =
        NewVideo(VideoKind.RECORDING, song, "content://media/video/4", null, Millis(2_000), 1080, 2400, song)

    @Test
    fun `a Recording reports that it runs, how long it has run, and how it ended`() =
        runTest {
            val recorder =
                fakeRecorder { song, started, tick, _ ->
                    started()
                    tick(Millis(1_000), 9_000L)
                    RecordingEnd.Saved(video(song))
                }

            RecordingHandlers(recorder).run(RecordingWork.Record("Night Drive"), results)

            assertEquals(
                listOf(
                    RecordingEvent.Started,
                    RecordingEvent.Ticked(Millis(1_000), 9_000L),
                    RecordingEvent.Ended(RecordingEnd.Saved(video("Night Drive"))),
                ),
                events,
            )
        }

    @Test
    fun `Stop reaches the recorder, even when it comes first, and only for that Recording`() =
        runTest {
            val recorder =
                fakeRecorder { song, _, _, shouldStop ->
                    if (shouldStop()) RecordingEnd.Saved(video(song)) else RecordingEnd.Failed(RecordingProblem.NOTHING_RECORDED)
                }
            val handlers = RecordingHandlers(recorder)

            handlers.run(RecordingWork.Stop, results)
            handlers.run(RecordingWork.Record("Low Tide"), results)
            handlers.run(RecordingWork.Record("Low Tide"), results)

            assertEquals(
                listOf(
                    RecordingEvent.Ended(RecordingEnd.Saved(video("Low Tide"))),
                    RecordingEvent.Ended(RecordingEnd.Failed(RecordingProblem.NOTHING_RECORDED)),
                ),
                events,
            )
        }

    @Test
    fun `a recorder that throws ends the Recording as not saved`() =
        runTest {
            val recorder = fakeRecorder { _, _, _, _ -> throw IOException("disk full") }

            RecordingHandlers(recorder).run(RecordingWork.Record(""), results)

            assertEquals(listOf(RecordingEvent.Ended(RecordingEnd.Failed(RecordingProblem.NOT_SAVED))), events)
        }

    @Test
    fun `without a recorder a Recording fails at once`() =
        runTest {
            RecordingHandlers(LiveRecorder.None).run(RecordingWork.Record(""), results)

            assertEquals(listOf(RecordingEvent.Ended(RecordingEnd.Failed(RecordingProblem.NO_VISUAL))), events)
        }
}
