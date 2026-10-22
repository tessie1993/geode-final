package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.PlayerState.Buffering
import dev.geode.feature.playback.api.PlayerState.Ended
import dev.geode.feature.playback.api.PlayerState.Error
import dev.geode.feature.playback.api.PlayerState.Idle
import dev.geode.feature.playback.api.PlayerState.Loading
import dev.geode.feature.playback.api.PlayerState.Paused
import dev.geode.feature.playback.api.PlayerState.Playing
import dev.geode.feature.playback.api.Queue

/**
 * What a media control asks for (#36): a button on the notification or the lock screen, a headset's
 * or a car's buttons, a watch. Every one becomes a [PlaybackCommand] through [MediaControls.commandFor]
 * and goes to Playback like any other; nothing outside Playback reaches the audio engine (ADR-0004).
 */
sealed interface MediaControl {
    data object Play : MediaControl

    data object Pause : MediaControl

    /** The system's stop. Playback pauses and keeps its queue, so it can be resumed from the lock screen. */
    data object Stop : MediaControl

    data object Next : MediaControl

    data object Previous : MediaControl

    /** To [position] in the current track. */
    data class SeekTo(
        val position: Millis,
    ) : MediaControl

    /** To the queue's track at [index], at [position]: a tap on a track in the session's queue. */
    data class SkipTo(
        val index: Int,
        val position: Millis,
    ) : MediaControl
}

/** Where the media session stands, in the session's own terms. */
enum class SessionStatus {
    /** Nothing to play, or stopped by a failure. */
    IDLE,

    /** Meant to be playing or opening a track, and waiting. */
    BUFFERING,

    /** Playing, or paused and ready to play. */
    READY,

    /** The queue played to its end. */
    ENDED,
}

/**
 * Playback as the media session publishes it to the notification, the lock screen, Bluetooth and
 * the widget (ADR-0004): one view of [PlayerState], never a second truth.
 *
 * [mark] gives the position at any moment; [canSeek], [canNext] and [canPrevious] say which buttons
 * Playback would answer, so the session offers only those.
 */
data class SessionView(
    val status: SessionStatus,
    val playWhenReady: Boolean,
    val queue: Queue?,
    val mark: PlayMark,
    val failure: PlaybackFailure?,
    val canSeek: Boolean,
    val canNext: Boolean,
    val canPrevious: Boolean,
) {
    /**
     * Whether the music keeps playing with no screen in front of it: Playback is meant to be playing
     * and has something to play. While this holds, the session's service stays in the foreground, so
     * leaving the app or locking the phone does not stop the music.
     */
    val playsInBackground: Boolean
        get() = playWhenReady && (status == SessionStatus.BUFFERING || status == SessionStatus.READY)
}

/** The media session's side of Playback (#36), as plain functions of its state. */
object MediaControls {
    /** The Command [control] stands for while the player is [player], or null when it asks for nothing. */
    fun commandFor(
        control: MediaControl,
        player: PlayerState,
    ): PlaybackCommand? =
        when (control) {
            MediaControl.Play -> PlaybackCommand.Resume
            MediaControl.Pause, MediaControl.Stop -> PlaybackCommand.Pause
            MediaControl.Next -> PlaybackCommand.Next
            MediaControl.Previous -> PlaybackCommand.Previous
            is MediaControl.SeekTo -> PlaybackCommand.SeekTo(control.position)
            is MediaControl.SkipTo -> skipTo(control, queueOf(player))
        }

    /**
     * What leaving the app or locking the phone asks of Playback while the player is [player] (#28):
     * a Pause when the music is not to keep playing in the background ([musicKeepsPlaying], Settings'
     * "Music keeps playing") and would play on without a screen, otherwise nothing.
     */
    fun commandOnLeavingApp(
        player: PlayerState,
        musicKeepsPlaying: Boolean,
    ): PlaybackCommand? = PlaybackCommand.Pause.takeIf { !musicKeepsPlaying && sessionOf(player).playsInBackground }

    /** What the media session shows while the player is [player]. */
    fun sessionOf(player: PlayerState): SessionView =
        when (player) {
            Idle ->
                SessionView(
                    SessionStatus.IDLE,
                    playWhenReady = false,
                    queue = null,
                    mark = STILL_AT_START,
                    failure = null,
                    canSeek = false,
                    canNext = false,
                    canPrevious = false,
                )
            is Loading ->
                SessionView(
                    SessionStatus.BUFFERING,
                    playWhenReady = player.playWhenReady,
                    queue = player.queue,
                    mark = PlayMark.still(player.from.position),
                    failure = null,
                    canSeek = false,
                    canNext = player.queue.hasNext,
                    canPrevious = player.queue.hasPrevious,
                )
            is Buffering -> settled(SessionStatus.BUFFERING, playWhenReady = true, player.queue, PlayMark.still(player.mark.position))
            is Playing -> settled(SessionStatus.READY, playWhenReady = true, player.queue, player.mark)
            is Paused -> settled(SessionStatus.READY, playWhenReady = false, player.queue, player.mark)
            is Ended ->
                SessionView(
                    SessionStatus.ENDED,
                    playWhenReady = false,
                    queue = player.queue,
                    mark = PlayMark.still(player.queue.current.duration),
                    failure = null,
                    canSeek = false,
                    canNext = player.queue.hasNext,
                    canPrevious = true,
                )
            is Error ->
                SessionView(
                    SessionStatus.IDLE,
                    playWhenReady = false,
                    queue = player.queue,
                    mark = STILL_AT_START,
                    failure = player.failure,
                    canSeek = false,
                    canNext = player.queue.hasNext,
                    canPrevious = true,
                )
        }

    /** A track is open: it can be sought, and Previous at least restarts it. */
    private fun settled(
        status: SessionStatus,
        playWhenReady: Boolean,
        queue: Queue,
        mark: PlayMark,
    ) = SessionView(status, playWhenReady, queue, mark, null, canSeek = true, canNext = queue.hasNext, canPrevious = true)

    private fun skipTo(
        control: MediaControl.SkipTo,
        queue: Queue?,
    ): PlaybackCommand? =
        when {
            queue == null || control.index !in queue.tracks.indices -> null
            control.index == queue.index -> PlaybackCommand.SeekTo(control.position)
            else -> PlaybackCommand.Play(queue.tracks, control.index)
        }

    private fun queueOf(player: PlayerState): Queue? =
        when (player) {
            Idle -> null
            is Loading -> player.queue
            is Buffering -> player.queue
            is Playing -> player.queue
            is Paused -> player.queue
            is Ended -> player.queue
            is Error -> player.queue
        }

    private val STILL_AT_START = PlayMark.still(Millis.ZERO)
}
