package dev.geode.feature.playback.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.Track
import java.util.Locale

/** What the player shows, one per state of Playback's state machine. */
enum class PlayerStatus {
    IDLE,
    LOADING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR,
}

/**
 * Everything the player and the mini-player draw (ADR-0003: an immutable screen model). [position]
 * is already worked out for the moment drawn; [meantToPlay] decides whether the main button pauses.
 * [shuffle] and [repeat] are Playback's options, shown on the Player controls.
 */
@Immutable
data class PlayerModel(
    val status: PlayerStatus,
    val track: Track?,
    val position: Millis,
    val meantToPlay: Boolean,
    val canGoPrevious: Boolean,
    val canGoNext: Boolean,
    val failure: PlaybackFailure? = null,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
) {
    val duration: Millis get() = track?.duration ?: Millis.ZERO

    companion object {
        val IDLE = PlayerModel(PlayerStatus.IDLE, null, Millis.ZERO, meantToPlay = false, canGoPrevious = false, canGoNext = false)
    }
}

/** The one callback the player screens send (ADR-0003). */
sealed interface PlayerAction {
    data object PlayPause : PlayerAction

    data object Next : PlayerAction

    data object Previous : PlayerAction

    data class SeekTo(
        val position: Millis,
    ) : PlayerAction

    /** The mini-player was tapped: open the player. Navigation, not a Command. */
    data object Expand : PlayerAction

    data object ToggleShuffle : PlayerAction

    /** Off, then the whole queue, then this song, then off again. */
    data object CycleRepeat : PlayerAction

    /** Open the queue's sheet. Navigation, not a Command. */
    data object OpenQueue : PlayerAction

    /** Open the listening tools' sheet. Navigation, not a Command. */
    data object OpenTools : PlayerAction

    /** Favourite the playing song, or stop. Library's, not Playback's. */
    data object ToggleFavourite : PlayerAction
}

/** The player's model at [now], on the monotonic clock the marks use. */
fun PlaybackState.toPlayerModel(now: Millis): PlayerModel =
    playerModel(now).copy(shuffle = options.shuffle, repeat = options.repeat)

private fun PlaybackState.playerModel(now: Millis): PlayerModel =
    when (val player = player) {
        PlayerState.Idle -> PlayerModel.IDLE
        is PlayerState.Loading -> model(PlayerStatus.LOADING, player.queue, player.from.position, player.playWhenReady)
        is PlayerState.Buffering -> model(PlayerStatus.BUFFERING, player.queue, player.mark.positionAt(now), meantToPlay = true)
        is PlayerState.Playing -> model(PlayerStatus.PLAYING, player.queue, player.mark.positionAt(now), meantToPlay = true)
        is PlayerState.Paused -> model(PlayerStatus.PAUSED, player.queue, player.mark.positionAt(now), meantToPlay = false)
        is PlayerState.Ended -> model(PlayerStatus.ENDED, player.queue, player.queue.current.duration, meantToPlay = false)
        is PlayerState.Error ->
            model(PlayerStatus.ERROR, player.queue, Millis.ZERO, meantToPlay = false).copy(failure = player.failure)
    }

private fun model(
    status: PlayerStatus,
    queue: Queue,
    position: Millis,
    meantToPlay: Boolean,
) = PlayerModel(
    status = status,
    track = queue.current,
    position = position.coerceIn(Millis.ZERO, queue.current.duration),
    meantToPlay = meantToPlay,
    // At the first track, Previous restarts it.
    canGoPrevious = true,
    canGoNext = queue.hasNext,
)

/** The Command [this] action sends to Playback, or null when it is not one for Playback. */
fun PlayerAction.toCommand(model: PlayerModel): PlaybackCommand? =
    when (this) {
        PlayerAction.PlayPause -> if (model.meantToPlay) PlaybackCommand.Pause else PlaybackCommand.Resume
        PlayerAction.Next -> PlaybackCommand.Next
        PlayerAction.Previous -> PlaybackCommand.Previous
        is PlayerAction.SeekTo -> PlaybackCommand.SeekTo(position)
        PlayerAction.ToggleShuffle -> PlaybackCommand.SetShuffle(!model.shuffle)
        PlayerAction.CycleRepeat -> PlaybackCommand.SetRepeat(model.repeat.next())
        PlayerAction.Expand, PlayerAction.OpenQueue, PlayerAction.OpenTools, PlayerAction.ToggleFavourite -> null
    }

/** Repeat's next mode: off, then the whole queue, then this song, then off again. */
internal fun RepeatMode.next(): RepeatMode =
    when (this) {
        RepeatMode.OFF -> RepeatMode.ALL
        RepeatMode.ALL -> RepeatMode.ONE
        RepeatMode.ONE -> RepeatMode.OFF
    }

/** [millis] as the player prints it: `3:07`, or `1:02:09` past an hour. */
fun formatTime(millis: Millis): String {
    val totalSeconds = millis.value.coerceAtLeast(0) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
