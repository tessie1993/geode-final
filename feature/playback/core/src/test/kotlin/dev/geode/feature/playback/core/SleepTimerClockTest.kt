package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.SleepTimer
import dev.geode.feature.playback.api.Track
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sleep timer and the A-B loop on the fake clock (ADR-0003: timers are effects): Playback's reducer
 * in a real store, its work recorded instead of done, and the player's announcements sent by hand.
 */
class SleepTimerClockTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val clock = FakeClock()
    private val work = mutableListOf<PlaybackWork>()
    private lateinit var announce: Results<PlaybackEvent>

    private val runner =
        object : EffectRunner<PlaybackWork, PlaybackEvent> {
            override suspend fun run(
                work: PlaybackWork,
                results: Results<PlaybackEvent>,
            ) {
                this@SleepTimerClockTest.work += work
                announce = results
            }
        }

    private val playback =
        FeatureStore<PlaybackState, PlaybackCommand, PlaybackEvent, PlaybackWork, PlaybackFact>(
            name = "playback",
            initial = PlaybackState(),
            reducer = PlaybackReducer,
            toEvent = PlaybackEvent::Command,
            runner = runner,
            runtime = inPlaceRuntime(clock),
        )

    /** Plays a then b, and the player announces that a is open and playing. */
    private fun playing() {
        playback.send(PlaybackCommand.Play(listOf(a, b)))
        announce.report(PlaybackEvent.TrackChanged(a.id, PlayMark.still(Millis.ZERO)))
        announce.report(PlaybackEvent.Started(PlayMark(Millis.ZERO, clock.now, moving = true)))
        work.clear()
    }

    @Test
    fun `the sleep timer counts down a minute at a time and then pauses`() {
        playing()

        playback.send(PlaybackCommand.StartSleepTimer(Millis(150_000)))
        clock.advanceBy(Millis(60_000))
        assertEquals(SleepTimer.Counting(Millis(90_000), finishTrack = false), playback.state.value.sleep)
        clock.advanceBy(Millis(60_000))
        assertEquals(SleepTimer.Counting(Millis(30_000), finishTrack = false), playback.state.value.sleep)
        clock.advanceBy(Millis(29_999))
        assertEquals(SleepTimer.Counting(Millis(30_000), finishTrack = false), playback.state.value.sleep)
        assertEquals(emptyList<PlaybackWork>(), work)

        clock.advanceBy(Millis(1))

        assertEquals(SleepTimer.Off, playback.state.value.sleep)
        assertEquals(PlaybackWork.Drive(listOf(EngineStep.Pause)), work.first())
        assertEquals(PlayerState.Paused::class, playback.state.value.player::class)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `a cancelled sleep timer never pauses`() {
        playing()

        playback.send(PlaybackCommand.StartSleepTimer(Millis(120_000)))
        clock.advanceBy(Millis(60_000))
        playback.send(PlaybackCommand.CancelSleepTimer)
        clock.advanceBy(Millis(600_000))

        assertEquals(SleepTimer.Off, playback.state.value.sleep)
        assertEquals(PlayerState.Playing::class, playback.state.value.player::class)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `starting the sleep timer again starts it over`() {
        playing()

        playback.send(PlaybackCommand.StartSleepTimer(Millis(60_000)))
        clock.advanceBy(Millis(30_000))
        playback.send(PlaybackCommand.StartSleepTimer(Millis(60_000)))
        clock.advanceBy(Millis(59_999))

        assertEquals(PlayerState.Playing::class, playback.state.value.player::class)
        clock.advanceBy(Millis(1))
        assertEquals(PlayerState.Paused::class, playback.state.value.player::class)
    }

    @Test
    fun `finishing the track lets it end, then the next one opens paused`() {
        playing()

        playback.send(PlaybackCommand.StartSleepTimer(Millis(60_000), finishTrack = true))
        clock.advanceBy(Millis(60_000))
        assertEquals(SleepTimer.AfterTrack, playback.state.value.sleep)
        assertEquals(PlayerState.Playing::class, playback.state.value.player::class)

        announce.report(PlaybackEvent.Ended)

        assertEquals(SleepTimer.Off, playback.state.value.sleep)
        assertEquals(
            PlayerState.Loading(Queue(listOf(a, b), 1), PlayMark.still(Millis.ZERO), playWhenReady = false, LoadReason.PLAY),
            playback.state.value.player,
        )
    }

    @Test
    fun `the A-B loop goes back to its start each time playing reaches its end`() {
        playing()

        playback.send(PlaybackCommand.MarkLoop(Millis(10_000)))
        playback.send(PlaybackCommand.MarkLoop(Millis(25_000)))
        clock.advanceBy(Millis(24_999))
        assertEquals(emptyList<PlaybackWork>(), work)

        clock.advanceBy(Millis(1))
        assertEquals(listOf(PlaybackWork.Drive(listOf(EngineStep.Seek(Millis(10_000))))), work)

        // The player seeks; the loop's end is 15 seconds away again.
        announce.report(PlaybackEvent.Seeked(PlayMark(Millis(10_000), clock.now, moving = true)))
        work.clear()
        clock.advanceBy(Millis(15_000))
        assertEquals(listOf(PlaybackWork.Drive(listOf(EngineStep.Seek(Millis(10_000))))), work)

        playback.send(PlaybackCommand.ClearLoop)
        assertEquals(0, clock.pendingCount)
    }
}
