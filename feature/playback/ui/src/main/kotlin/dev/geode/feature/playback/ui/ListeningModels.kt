package dev.geode.feature.playback.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.Bookmark
import dev.geode.feature.playback.api.BookmarkId
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.QueueEnd
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.SleepTimer
import dev.geode.feature.playback.api.Track

/** What the queue screen draws (#35): the queue in the order it plays, which track is current, shuffle and repeat. */
@Immutable
data class QueueModel(
    val tracks: List<Track>,
    /** The current track's place in [tracks]; -1 when nothing is queued. */
    val current: Int,
    val shuffle: Boolean,
    val repeat: RepeatMode,
) {
    companion object {
        val EMPTY = QueueModel(emptyList(), current = -1, shuffle = false, repeat = RepeatMode.OFF)
    }
}

/** The one callback the queue screen sends. Indexes are places in [QueueModel.tracks]. */
sealed interface QueueAction {
    data class Play(
        val index: Int,
    ) : QueueAction

    data class Remove(
        val index: Int,
    ) : QueueAction

    data class MoveUp(
        val index: Int,
    ) : QueueAction

    data class MoveDown(
        val index: Int,
    ) : QueueAction

    data object ToggleShuffle : QueueAction

    /** Off, then the whole queue, then this song, then off again. */
    data object CycleRepeat : QueueAction
}

fun PlaybackState.toQueueModel(): QueueModel {
    val queue = player.queue() ?: return QueueModel.EMPTY.copy(shuffle = options.shuffle, repeat = options.repeat)
    return QueueModel(queue.tracks, queue.index, options.shuffle, options.repeat)
}

fun QueueAction.toCommand(model: QueueModel): PlaybackCommand =
    when (this) {
        is QueueAction.Play -> PlaybackCommand.PlayQueueItem(index)
        is QueueAction.Remove -> PlaybackCommand.RemoveFromQueue(index)
        is QueueAction.MoveUp -> PlaybackCommand.MoveInQueue(index, index - 1)
        is QueueAction.MoveDown -> PlaybackCommand.MoveInQueue(index, index + 1)
        QueueAction.ToggleShuffle -> PlaybackCommand.SetShuffle(!model.shuffle)
        QueueAction.CycleRepeat ->
            PlaybackCommand.SetRepeat(
                when (model.repeat) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                },
            )
    }

/**
 * What the listening tools screen draws (#35): the sleep timer, how tracks join, what happens when the
 * queue ends, the A-B loop and the bookmarks. [position] is where the current track is at the moment
 * drawn; [trackOpen] says whether there is a track to loop or bookmark.
 */
@Immutable
data class ListeningModel(
    val sleep: SleepTimer,
    val crossfade: Millis,
    val gapless: Boolean,
    val queueEnd: QueueEnd,
    val loop: AbLoop,
    val trackOpen: Boolean,
    val position: Millis,
    val bookmarks: List<Bookmark>,
)

/** The one callback the listening tools screen sends. */
sealed interface ListeningAction {
    data class StartSleep(
        val after: Millis,
    ) : ListeningAction

    data object SleepAfterTrack : ListeningAction

    data object CancelSleep : ListeningAction

    data class SetCrossfade(
        val length: Millis,
    ) : ListeningAction

    data class SetGapless(
        val on: Boolean,
    ) : ListeningAction

    data class SetQueueEnd(
        val end: QueueEnd,
    ) : ListeningAction

    /** Marks the loop's start, then its end, then clears it, where the track is now. */
    data object MarkLoop : ListeningAction

    data object ClearLoop : ListeningAction

    data object AddBookmark : ListeningAction

    data class GoToBookmark(
        val id: BookmarkId,
    ) : ListeningAction

    data class RemoveBookmark(
        val id: BookmarkId,
    ) : ListeningAction
}

/** The listening tools' model at [now], on the monotonic clock the marks use. */
fun PlaybackState.toListeningModel(now: Millis): ListeningModel {
    val open =
        when (val player = player) {
            is PlayerState.Buffering -> player.queue.current to player.mark.positionAt(now)
            is PlayerState.Playing -> player.queue.current to player.mark.positionAt(now)
            is PlayerState.Paused -> player.queue.current to player.mark.positionAt(now)
            PlayerState.Idle, is PlayerState.Loading, is PlayerState.Ended, is PlayerState.Error -> null
        }
    return ListeningModel(
        sleep = sleep,
        crossfade = options.crossfade,
        gapless = options.gapless,
        queueEnd = options.queueEnd,
        loop = loop,
        trackOpen = open != null,
        position = open?.let { (track, position) -> position.coerceIn(Millis.ZERO, track.duration) } ?: Millis.ZERO,
        bookmarks = bookmarks,
    )
}

fun ListeningAction.toCommand(model: ListeningModel): PlaybackCommand =
    when (this) {
        is ListeningAction.StartSleep -> PlaybackCommand.StartSleepTimer(after)
        ListeningAction.SleepAfterTrack -> PlaybackCommand.SleepAfterTrack
        ListeningAction.CancelSleep -> PlaybackCommand.CancelSleepTimer
        is ListeningAction.SetCrossfade -> PlaybackCommand.SetCrossfade(length)
        is ListeningAction.SetGapless -> PlaybackCommand.SetGapless(on)
        is ListeningAction.SetQueueEnd -> PlaybackCommand.SetQueueEnd(end)
        ListeningAction.MarkLoop -> PlaybackCommand.MarkLoop(model.position)
        ListeningAction.ClearLoop -> PlaybackCommand.ClearLoop
        ListeningAction.AddBookmark -> PlaybackCommand.AddBookmark
        is ListeningAction.GoToBookmark -> PlaybackCommand.GoToBookmark(id)
        is ListeningAction.RemoveBookmark -> PlaybackCommand.RemoveBookmark(id)
    }

/** The sleep timer's choices, in minutes. */
internal val SLEEP_CHOICES = listOf(15, 30, 45, 60)

internal const val MILLIS_PER_MINUTE = 60_000L

internal const val MILLIS_PER_SECOND_LONG = 1_000L

private fun PlayerState.queue() =
    when (this) {
        PlayerState.Idle -> null
        is PlayerState.Loading -> queue
        is PlayerState.Buffering -> queue
        is PlayerState.Playing -> queue
        is PlayerState.Paused -> queue
        is PlayerState.Ended -> queue
        is PlayerState.Error -> queue
    }
