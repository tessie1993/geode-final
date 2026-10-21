package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.PlayerState.Buffering
import dev.geode.feature.playback.api.PlayerState.Ended
import dev.geode.feature.playback.api.PlayerState.Error
import dev.geode.feature.playback.api.PlayerState.Idle
import dev.geode.feature.playback.api.PlayerState.Loading
import dev.geode.feature.playback.api.PlayerState.Paused
import dev.geode.feature.playback.api.PlayerState.Playing
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.core.EngineStep.Load
import dev.geode.feature.playback.core.PlaybackWork.Drive
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Play next" and "Add to queue" (#37), as a transition table: every state of the player against
 * both Commands. The player stays where it is; only its queue grows.
 */
class PlaybackEnqueueTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val c = Track(TrackId("content://c"), "Gamma", "Cy", Millis(240_000))
    private val x = Track(TrackId("content://x"), "Xi", "Xu", Millis(100_000))
    private val y = Track(TrackId("content://y"), "Ypsilon", "Yan", Millis(90_000))
    private val atB = Queue(listOf(a, b, c), 1)
    private val mark = PlayMark(Millis(30_000), Millis(1_000_000), moving = true)
    private val start = PlayMark.still(Millis.ZERO)

    private val playNext = PlaybackEvent.Command(PlaybackCommand.PlayNext(listOf(x, y)))
    private val addToQueue = PlaybackEvent.Command(PlaybackCommand.AddToQueue(listOf(x, y)))

    /** atB with x and y after the current track. */
    private val nextQueue = Queue(listOf(a, b, x, y, c), 1)

    /** atB with x and y at the end. */
    private val endQueue = Queue(listOf(a, b, c, x, y), 1)

    private fun states(queue: Queue): List<PlayerState> =
        listOf(
            Loading(queue, start, playWhenReady = true, LoadReason.PLAY),
            Buffering(queue, mark),
            Playing(queue, mark),
            Paused(queue, mark),
            Ended(queue),
            Error(PlaybackFailure.ENGINE_NOT_RESPONDING, queue),
        )

    @Test
    fun `play next puts the tracks right after the current one, wherever the player is`() {
        states(atB).zip(states(nextQueue)).forEach { (before, after) ->
            assertEquals("$before", Next<PlaybackState, PlaybackEffect>(PlaybackState(after)), reduce(before, playNext))
        }
    }

    @Test
    fun `add to queue puts the tracks at the end, wherever the player is`() {
        states(atB).zip(states(endQueue)).forEach { (before, after) ->
            assertEquals("$before", Next<PlaybackState, PlaybackEffect>(PlaybackState(after)), reduce(before, addToQueue))
        }
    }

    @Test
    fun `play next at the last track appends`() {
        val atC = Queue(listOf(a, b, c), 2)

        assertEquals(PlaybackState(Paused(Queue(listOf(a, b, c, x, y), 2), mark)), reduce(Paused(atC, mark), playNext).state)
    }

    @Test
    fun `with nothing queued, both Commands play the tracks`() {
        val playing =
            Next<PlaybackState, PlaybackEffect>(
                PlaybackState(Loading(Queue(listOf(x, y), 0), start, playWhenReady = true, LoadReason.PLAY)),
                listOf(
                    StartTimer(LoadTimeout, LOAD_TIMEOUT, PlaybackEvent.LoadTimedOut),
                    Perform(Drive(listOf(Load(x.id, start), EngineStep.Play))),
                ),
            )

        assertEquals(playing, reduce(Idle, playNext))
        assertEquals(playing, reduce(Idle, addToQueue))
    }

    @Test
    fun `nothing to add changes nothing`() {
        val playing = Playing(atB, mark)

        val nothingNext = PlaybackEvent.Command(PlaybackCommand.PlayNext(emptyList()))
        val nothingAdded = PlaybackEvent.Command(PlaybackCommand.AddToQueue(emptyList()))

        assertEquals(Next<PlaybackState, PlaybackEffect>(PlaybackState(playing)), reduce(playing, nothingNext))
        assertEquals(Next<PlaybackState, PlaybackEffect>(PlaybackState(Idle)), reduce(Idle, nothingAdded))
    }

    private fun reduce(
        player: PlayerState,
        event: PlaybackEvent,
    ) = PlaybackReducer.reduce(PlaybackState(player), event)
}
