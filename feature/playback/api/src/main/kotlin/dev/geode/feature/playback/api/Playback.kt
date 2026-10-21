package dev.geode.feature.playback.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.kernel.Feature

/**
 * Playback as everything outside it sees it (ADR-0003, ADR-0005): what is playing and where, the
 * queue it owns, the playlists and the play history it keeps, and the Commands that change them.
 * Library, Studio, the widget and the notification only send it Commands (ADR-0004).
 */
typealias Playback = Feature<PlaybackState, PlaybackCommand, PlaybackFact>

/** One song as Playback knows it: enough to show it and to play it. */
data class Track(
    val id: TrackId,
    val title: String,
    val artist: String,
    val duration: Millis,
)

/**
 * The tracks Playback plays, in order, and which one is current. Never empty, and [index] is always
 * one of [tracks].
 */
data class Queue(
    val tracks: List<Track>,
    val index: Int,
) {
    init {
        require(index in tracks.indices) { "queue index $index is not one of ${tracks.size} tracks" }
    }

    val current: Track get() = tracks[index]

    val hasNext: Boolean get() = index < tracks.lastIndex

    val hasPrevious: Boolean get() = index > 0

    /** The same queue one track on. Only when [hasNext]. */
    fun next(): Queue = copy(index = index + 1)

    /** The same queue one track back. Only when [hasPrevious]. */
    fun previous(): Queue = copy(index = index - 1)

    /** The same queue at its first track. */
    fun first(): Queue = copy(index = 0)
}

/**
 * Where playback stood at one moment: [position] in the track at [at] on the monotonic clock,
 * advancing in real time while [moving]. The position at any later moment is worked out from the
 * clock with [positionAt], so nothing polls the player (ADR-0004).
 */
data class PlayMark(
    val position: Millis,
    val at: Millis,
    val moving: Boolean,
) {
    fun positionAt(now: Millis): Millis = if (moving) position + (now - at) else position

    companion object {
        /** A mark that does not move: [position], whatever the clock says. */
        fun still(position: Millis): PlayMark = PlayMark(position, Millis.ZERO, moving = false)
    }
}

/** Why a track is being loaded. */
enum class LoadReason {
    /** Someone asked for it, or the queue moved on to it: it counts as a play in the history. */
    PLAY,

    /** The player failed under the current track; it is loaded again where it was. */
    RECOVER,

    /** The app opened where it was left off (ADR-0004), paused. */
    RESTORE,
}

/** What can stop playback. A track that cannot be played is not one: it is skipped silently. */
enum class PlaybackFailure {
    /** The audio engine did not answer a load in time. */
    ENGINE_NOT_RESPONDING,
}

/** The player's state machine (#34). */
sealed interface PlayerState {
    /** Nothing has been played since the app opened, and nothing was left off. */
    data object Idle : PlayerState

    /**
     * [queue]'s current track is being opened at [from]. [playWhenReady] is what happens once it is
     * open; a pause while it opens wins.
     */
    data class Loading(
        val queue: Queue,
        val from: PlayMark,
        val playWhenReady: Boolean,
        val reason: LoadReason,
    ) : PlayerState

    /** Meant to be playing, but the player is waiting: for data, or to start. */
    data class Buffering(
        val queue: Queue,
        val mark: PlayMark,
    ) : PlayerState

    data class Playing(
        val queue: Queue,
        val mark: PlayMark,
    ) : PlayerState

    data class Paused(
        val queue: Queue,
        val mark: PlayMark,
    ) : PlayerState

    /** The queue played to its end. */
    data class Ended(
        val queue: Queue,
    ) : PlayerState

    /** Playback stopped because of [failure]; playing again retries the current track. */
    data class Error(
        val failure: PlaybackFailure,
        val queue: Queue,
    ) : PlayerState
}

/** A playlist Playback keeps. */
@JvmInline
value class PlaylistId(
    val value: Long,
) {
    override fun toString(): String = "playlist $value"
}

data class PlaylistSummary(
    val id: PlaylistId,
    val name: String,
    val trackCount: Int,
)

/** One entry of the play history: [track] started playing at [playedAt], wall-clock time. */
data class PlayedTrack(
    val track: Track,
    val playedAt: Millis,
)

/** Everything Playback lets the outside read. */
data class PlaybackState(
    val player: PlayerState = PlayerState.Idle,
    val playlists: List<PlaylistSummary> = emptyList(),
    /** The most recent plays first. */
    val history: List<PlayedTrack> = emptyList(),
)

sealed interface PlaybackCommand {
    /** Replaces the queue with [tracks] and plays from [startAt]. */
    data class Play(
        val tracks: List<Track>,
        val startAt: Int = 0,
    ) : PlaybackCommand

    data object Resume : PlaybackCommand

    data object Pause : PlaybackCommand

    data object Next : PlaybackCommand

    data object Previous : PlaybackCommand

    data class SeekTo(
        val position: Millis,
    ) : PlaybackCommand

    /** Replaces the queue with the playlist's tracks and plays it from the start. */
    data class PlayPlaylist(
        val id: PlaylistId,
    ) : PlaybackCommand

    data class CreatePlaylist(
        val name: String,
        val tracks: List<Track>,
    ) : PlaybackCommand

    /** Saves the play history, each track once, most recent first, as a playlist named [name]. */
    data class SaveHistoryAsPlaylist(
        val name: String,
    ) : PlaybackCommand

    data class DeletePlaylist(
        val id: PlaylistId,
    ) : PlaybackCommand
}

/** One-off facts Playback announces. */
sealed interface PlaybackFact {
    /** A playlist named [name] was saved. */
    data class PlaylistSaved(
        val name: String,
    ) : PlaybackFact
}
