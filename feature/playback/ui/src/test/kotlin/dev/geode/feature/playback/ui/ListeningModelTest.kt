package dev.geode.feature.playback.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.BookmarkId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.QueueEnd
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.SleepTimer
import dev.geode.feature.playback.api.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** The queue and listening tools models follow Playback's state, and their actions become Commands (#35). */
class ListeningModelTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val queue = Queue(listOf(a, b), 1)
    private val moving = PlayMark(Millis(10_000), Millis(1_000), moving = true)

    @Test
    fun `the queue model is the queue as it plays, with shuffle and repeat`() {
        val options = ListeningOptions(shuffle = true, repeat = RepeatMode.ONE)

        assertEquals(
            QueueModel(listOf(a, b), current = 1, shuffle = true, repeat = RepeatMode.ONE),
            PlaybackState(PlayerState.Ended(queue), options = options).toQueueModel(),
        )
        assertEquals(QueueModel.EMPTY.copy(shuffle = true, repeat = RepeatMode.ONE), PlaybackState(options = options).toQueueModel())
    }

    @Test
    fun `queue actions become Commands`() {
        val model = QueueModel(listOf(a, b), current = 0, shuffle = false, repeat = RepeatMode.OFF)

        assertEquals(PlaybackCommand.PlayQueueItem(1), QueueAction.Play(1).toCommand(model))
        assertEquals(PlaybackCommand.RemoveFromQueue(1), QueueAction.Remove(1).toCommand(model))
        assertEquals(PlaybackCommand.MoveInQueue(1, 0), QueueAction.MoveUp(1).toCommand(model))
        assertEquals(PlaybackCommand.MoveInQueue(0, 1), QueueAction.MoveDown(0).toCommand(model))
        assertEquals(PlaybackCommand.SetShuffle(true), QueueAction.ToggleShuffle.toCommand(model))
        assertEquals(PlaybackCommand.SetShuffle(false), QueueAction.ToggleShuffle.toCommand(model.copy(shuffle = true)))
        assertEquals(
            listOf(RepeatMode.ALL, RepeatMode.ONE, RepeatMode.OFF).map { PlaybackCommand.SetRepeat(it) },
            RepeatMode.entries.map { QueueAction.CycleRepeat.toCommand(model.copy(repeat = it)) },
        )
    }

    @Test
    fun `the listening model knows where the open track is`() {
        val state =
            PlaybackState(
                PlayerState.Playing(queue, moving),
                options = ListeningOptions(crossfade = Millis(3_000), gapless = false, queueEnd = QueueEnd.SIMILAR),
                sleep = SleepTimer.AfterTrack,
                loop = AbLoop.Starting(b.id, Millis(5_000)),
            )

        val model = state.toListeningModel(Millis(4_000))

        assertEquals(
            ListeningModel(
                SleepTimer.AfterTrack,
                Millis(3_000),
                false,
                QueueEnd.SIMILAR,
                AbLoop.Starting(b.id, Millis(5_000)),
                true,
                Millis(13_000),
                emptyList(),
            ),
            model,
        )
        assertEquals(Millis(180_000), state.toListeningModel(Millis(900_000)).position)
        assertEquals(false, PlaybackState(PlayerState.Ended(queue)).toListeningModel(Millis.ZERO).trackOpen)
        assertEquals(Millis.ZERO, PlaybackState().toListeningModel(Millis(5)).position)
    }

    @Test
    fun `listening actions become Commands`() {
        val model = PlaybackState(PlayerState.Paused(queue, PlayMark.still(Millis(42_000)))).toListeningModel(Millis.ZERO)

        assertEquals(PlaybackCommand.StartSleepTimer(Millis(900_000)), ListeningAction.StartSleep(Millis(900_000)).toCommand(model))
        assertEquals(PlaybackCommand.SleepAfterTrack, ListeningAction.SleepAfterTrack.toCommand(model))
        assertEquals(PlaybackCommand.CancelSleepTimer, ListeningAction.CancelSleep.toCommand(model))
        assertEquals(PlaybackCommand.SetCrossfade(Millis(2_000)), ListeningAction.SetCrossfade(Millis(2_000)).toCommand(model))
        assertEquals(PlaybackCommand.SetGapless(false), ListeningAction.SetGapless(false).toCommand(model))
        assertEquals(PlaybackCommand.SetQueueEnd(QueueEnd.SIMILAR), ListeningAction.SetQueueEnd(QueueEnd.SIMILAR).toCommand(model))
        assertEquals(PlaybackCommand.MarkLoop(Millis(42_000)), ListeningAction.MarkLoop.toCommand(model))
        assertEquals(PlaybackCommand.ClearLoop, ListeningAction.ClearLoop.toCommand(model))
        assertEquals(PlaybackCommand.AddBookmark, ListeningAction.AddBookmark.toCommand(model))
        assertEquals(PlaybackCommand.GoToBookmark(BookmarkId(3)), ListeningAction.GoToBookmark(BookmarkId(3)).toCommand(model))
        assertEquals(PlaybackCommand.RemoveBookmark(BookmarkId(3)), ListeningAction.RemoveBookmark(BookmarkId(3)).toCommand(model))
    }
}
