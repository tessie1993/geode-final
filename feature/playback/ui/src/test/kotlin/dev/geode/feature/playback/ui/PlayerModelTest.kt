package dev.geode.feature.playback.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The screens' model follows Playback's state, and their actions become Commands. */
class PlayerModelTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val queue = Queue(listOf(a, b), 0)
    private val moving = PlayMark(Millis(10_000), Millis(1_000), moving = true)

    @Test
    fun `the position moves with the clock while playing`() {
        val model = PlaybackState(PlayerState.Playing(queue, moving)).toPlayerModel(Millis(4_000))

        assertEquals(
            PlayerModel(PlayerStatus.PLAYING, a, Millis(13_000), meantToPlay = true, canGoPrevious = true, canGoNext = true),
            model,
        )
    }

    @Test
    fun `the position never runs past the track`() {
        val model = PlaybackState(PlayerState.Buffering(queue, moving)).toPlayerModel(Millis(900_000))

        assertEquals(a.duration, model.position)
        assertEquals(PlayerStatus.BUFFERING, model.status)
    }

    @Test
    fun `every state has its status`() {
        val states =
            listOf(
                PlayerState.Idle,
                PlayerState.Loading(queue, PlayMark.still(Millis(5_000)), playWhenReady = false, LoadReason.RESTORE),
                PlayerState.Paused(queue, PlayMark.still(Millis(7_000))),
                PlayerState.Ended(queue.next()),
                PlayerState.Error(PlaybackFailure.ENGINE_NOT_RESPONDING, queue),
            )

        val models = states.map { PlaybackState(it).toPlayerModel(Millis.ZERO) }

        assertEquals(
            listOf(PlayerStatus.IDLE, PlayerStatus.LOADING, PlayerStatus.PAUSED, PlayerStatus.ENDED, PlayerStatus.ERROR),
            models.map { it.status },
        )
        assertEquals(listOf(Millis.ZERO, Millis(5_000), Millis(7_000), b.duration, Millis.ZERO), models.map { it.position })
        assertEquals(PlaybackFailure.ENGINE_NOT_RESPONDING, models.last().failure)
        assertEquals(false, models[3].canGoNext)
    }

    @Test
    fun `actions become Commands`() {
        val playing = PlaybackState(PlayerState.Playing(queue, moving)).toPlayerModel(Millis.ZERO)
        val paused = playing.copy(meantToPlay = false)

        assertEquals(PlaybackCommand.Pause, PlayerAction.PlayPause.toCommand(playing))
        assertEquals(PlaybackCommand.Resume, PlayerAction.PlayPause.toCommand(paused))
        assertEquals(PlaybackCommand.Next, PlayerAction.Next.toCommand(playing))
        assertEquals(PlaybackCommand.Previous, PlayerAction.Previous.toCommand(playing))
        assertEquals(PlaybackCommand.SeekTo(Millis(3_000)), PlayerAction.SeekTo(Millis(3_000)).toCommand(playing))
        assertNull(PlayerAction.Expand.toCommand(playing))
    }

    @Test
    fun `the Player controls show Playback's shuffle and repeat`() {
        val options = ListeningOptions(shuffle = true, repeat = RepeatMode.ONE)

        val model = PlaybackState(PlayerState.Playing(queue, moving), options = options).toPlayerModel(Millis.ZERO)

        assertEquals(true, model.shuffle)
        assertEquals(RepeatMode.ONE, model.repeat)
        assertEquals(false, PlaybackState(PlayerState.Idle).toPlayerModel(Millis.ZERO).shuffle)
    }

    @Test
    fun `shuffle flips and repeat goes off, the queue, this song, off`() {
        val model = PlaybackState(PlayerState.Playing(queue, moving)).toPlayerModel(Millis.ZERO)

        assertEquals(PlaybackCommand.SetShuffle(true), PlayerAction.ToggleShuffle.toCommand(model))
        assertEquals(PlaybackCommand.SetShuffle(false), PlayerAction.ToggleShuffle.toCommand(model.copy(shuffle = true)))
        assertEquals(
            listOf(RepeatMode.ALL, RepeatMode.ONE, RepeatMode.OFF).map(PlaybackCommand::SetRepeat),
            listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE).map { PlayerAction.CycleRepeat.toCommand(model.copy(repeat = it)) },
        )
    }

    @Test
    fun `opening the queue or the tools, and the heart, are not Playback's`() {
        val model = PlaybackState(PlayerState.Playing(queue, moving)).toPlayerModel(Millis.ZERO)

        assertEquals(
            listOf(null, null, null),
            listOf(PlayerAction.OpenQueue, PlayerAction.OpenTools, PlayerAction.ToggleFavourite).map { it.toCommand(model) },
        )
    }

    @Test
    fun `the A-B loop is shaded only on its own track`() {
        val playing = PlayerState.Playing(queue, moving)

        fun loopOf(loop: AbLoop) = PlaybackState(playing, loop = loop).toPlayerModel(Millis.ZERO).loop

        assertNull(loopOf(AbLoop.Off))
        assertEquals(LoopSpan(Millis(1_000), null), loopOf(AbLoop.Starting(a.id, Millis(1_000))))
        assertEquals(LoopSpan(Millis(1_000), Millis(9_000)), loopOf(AbLoop.On(a.id, Millis(1_000), Millis(9_000))))
        assertNull(loopOf(AbLoop.Starting(b.id, Millis(1_000))))
        assertNull(loopOf(AbLoop.On(b.id, Millis(1_000), Millis(9_000))))
    }

    @Test
    fun `with nothing playing no A-B loop is shaded`() {
        val state = PlaybackState(PlayerState.Idle, loop = AbLoop.On(a.id, Millis(1_000), Millis(9_000)))

        assertEquals(PlayerModel.IDLE, state.toPlayerModel(Millis.ZERO))
    }

    @Test
    fun `while the seek bar is dragged the time shown is where it would seek to`() {
        val model = PlayerModel(PlayerStatus.PLAYING, a, Millis(13_000), meantToPlay = true, canGoPrevious = true, canGoNext = true)

        assertEquals(Millis(13_000), model.timeShown(null))
        assertEquals(Millis.ZERO, model.timeShown(0f))
        assertEquals(Millis(50_000), model.timeShown(0.25f))
        assertEquals(Millis(200_000), model.timeShown(1f))
        assertEquals(Millis.ZERO, model.timeShown(-0.5f))
        assertEquals(Millis(200_000), model.timeShown(1.5f))
        assertEquals(Millis.ZERO, PlayerModel.IDLE.timeShown(0.5f))
    }

    @Test
    fun `times print as the player shows them`() {
        assertEquals("0:00", formatTime(Millis.ZERO))
        assertEquals("3:07", formatTime(Millis(187_400)))
        assertEquals("1:02:09", formatTime(Millis(3_729_000)))
        assertEquals("0:00", formatTime(Millis(-5)))
    }
}
