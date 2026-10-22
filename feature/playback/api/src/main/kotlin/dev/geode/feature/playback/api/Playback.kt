package dev.geode.feature.playback.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.kernel.Feature

/**
 * Playback as everything outside it sees it (ADR-0003, ADR-0005): what is playing and where, the
 * queue it owns, the playlists and the play history it keeps, and the Commands that change them.
 * Library, Studio, the widget and the notification only send it Commands (ADR-0004).
 *
 * It also holds what the Player shows beside the music (#147): the current track's lyrics. Tracks are
 * never analysed (owner, 2026-10-22), so there is no seek waveform, and the app-wide error banner is
 * the App UI's, not Playback's.
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
    /** How the queue plays and how tracks join (#35). */
    val options: ListeningOptions = ListeningOptions(),
    val sleep: SleepTimer = SleepTimer.Off,
    val loop: AbLoop = AbLoop.Off,
    /** Every bookmark, newest first. */
    val bookmarks: List<Bookmark> = emptyList(),
    /** Where each long track was left off, so playing it again goes on from there. */
    val resumePoints: Map<TrackId, Millis> = emptyMap(),
    val shuffle: ShuffleOrder = ShuffleOrder(),
    /** The current track's lyrics (#147). */
    val lyrics: TrackLookup<Lyrics> = TrackLookup.Idle,
)

/**
 * What Playback found out about the current track, looked up again each time the current track
 * changes (#147). A result for a track that is no longer current is dropped.
 */
sealed interface TrackLookup<out T> {
    /** The track it is about; null when nothing is playing. */
    val track: TrackId?

    /** Nothing is playing, so there is nothing to look up. */
    data object Idle : TrackLookup<Nothing> {
        override val track: TrackId? = null
    }

    data class Looking(
        override val track: TrackId,
    ) : TrackLookup<Nothing>

    data class Found<out T>(
        override val track: TrackId,
        val value: T,
    ) : TrackLookup<T>

    /** [track] has none. */
    data class Missing(
        override val track: TrackId,
    ) : TrackLookup<Nothing>
}

/** Where a track's lyrics were found. Nothing is fetched: the app has no network access. */
enum class LyricsSource {
    /** An `.lrc` file with the track's name, next to the track. */
    LRC_FILE,

    /** The words written into the track's own tags. */
    TRACK_TAGS,
}

/** A track's lyrics: timed, so they follow the music, or plain. */
sealed interface Lyrics {
    val source: LyricsSource

    /** Lines timed to the music, in time order. */
    data class Synced(
        val lines: List<SyncedLine>,
        override val source: LyricsSource,
    ) : Lyrics {
        /** The line being sung at [position]: the last one whose time has come, or -1 before the first. */
        fun lineAt(position: Millis): Int {
            var low = 0
            var high = lines.lastIndex
            var found = -1
            while (low <= high) {
                val middle = (low + high) / 2
                if (lines[middle].at <= position) {
                    found = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return found
        }
    }

    /** Lines with no times: shown whole, not followed. */
    data class Plain(
        val lines: List<String>,
        override val source: LyricsSource,
    ) : Lyrics
}

/** One timed line: [text] is sung from [at]. */
data class SyncedLine(
    val at: Millis,
    val text: String,
)

/** What happens when a track ends (#35). */
enum class RepeatMode {
    /** On to the next track; at the end of the queue, [QueueEnd] decides. */
    OFF,

    /** The whole queue again from its first track. */
    ALL,

    /** The same track again. */
    ONE,
}

/** What happens when the queue runs out and repeat is off: a setting (owner decision on #35). */
enum class QueueEnd {
    STOP,

    /** Keep going with similar songs, picked offline from the user's own music. */
    SIMILAR,
}

/**
 * The listening choices (#35). [crossfade] is how long one track fades into the next, zero for none;
 * with [gapless] the next track is opened early so that it joins with no gap.
 */
data class ListeningOptions(
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val crossfade: Millis = Millis.ZERO,
    val gapless: Boolean = true,
    val queueEnd: QueueEnd = QueueEnd.STOP,
) {
    /** Whether the next track is opened ahead of time, for a gapless join or a crossfade. */
    val joinsTracks: Boolean get() = gapless || crossfade > Millis.ZERO
}

/** The longest crossfade, as the old player settings allowed; a longer one is cut to this. */
val MAX_CROSSFADE = Millis(12_000)

/** The sleep timer (#35): it pauses playback after a while, or at the end of the track. */
sealed interface SleepTimer {
    data object Off : SleepTimer

    /** Counting down; with [finishTrack], the track playing when it runs out still plays to its end. */
    data class Counting(
        val remaining: Millis,
        val finishTrack: Boolean,
    ) : SleepTimer

    /** Playback pauses when the current track ends. */
    data object AfterTrack : SleepTimer
}

/** The A-B loop (#35): a stretch of one track played over and over. */
sealed interface AbLoop {
    data object Off : AbLoop

    /** Its start, A, is set on [track]; its end, B, is not yet. */
    data class Starting(
        val track: TrackId,
        val start: Millis,
    ) : AbLoop

    /** Playing [track] from [start] to [end], then from [start] again. */
    data class On(
        val track: TrackId,
        val start: Millis,
        val end: Millis,
    ) : AbLoop
}

@JvmInline
value class BookmarkId(
    val value: Long,
) {
    override fun toString(): String = "bookmark $value"
}

/** A place in a track the user marked to come back to (#35). */
data class Bookmark(
    val id: BookmarkId,
    val track: Track,
    val position: Millis,
)

/**
 * What shuffling needs to be undone and to stay repeatable: the [seed] of the next shuffle, and the
 * queue's order before it was shuffled. Only Playback's reducer uses it.
 */
data class ShuffleOrder(
    val seed: Long = 0,
    val unshuffled: List<Track> = emptyList(),
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

    // The queue (#35). Indexes are positions in the queue as it plays.

    /** Puts [tracks] right after the current one; with nothing playing, plays them. */
    data class PlayNext(
        val tracks: List<Track>,
    ) : PlaybackCommand

    /** Adds [tracks] to the end of the queue; with nothing playing, plays them. */
    data class AddToQueue(
        val tracks: List<Track>,
    ) : PlaybackCommand

    /** Plays the queue's track at [index]. */
    data class PlayQueueItem(
        val index: Int,
    ) : PlaybackCommand

    /** Takes the track at [index] out of the queue. The current track stays. */
    data class RemoveFromQueue(
        val index: Int,
    ) : PlaybackCommand

    /** Moves the track at [from] to [to]. */
    data class MoveInQueue(
        val from: Int,
        val to: Int,
    ) : PlaybackCommand

    data class SetShuffle(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetRepeat(
        val mode: RepeatMode,
    ) : PlaybackCommand

    /** Sets the crossfade to [length]; zero turns it off. */
    data class SetCrossfade(
        val length: Millis,
    ) : PlaybackCommand

    data class SetGapless(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetQueueEnd(
        val end: QueueEnd,
    ) : PlaybackCommand

    // Listening tools (#35).

    /** Pauses playback [after] this long; with [finishTrack], at the end of the track then playing. */
    data class StartSleepTimer(
        val after: Millis,
        val finishTrack: Boolean = false,
    ) : PlaybackCommand

    /** Pauses playback when the current track ends. */
    data object SleepAfterTrack : PlaybackCommand

    data object CancelSleepTimer : PlaybackCommand

    /**
     * Marks the A-B loop at [at] in the current track: its start, then its end, then off again. An end
     * too close after the start moves the start instead.
     */
    data class MarkLoop(
        val at: Millis,
    ) : PlaybackCommand

    data object ClearLoop : PlaybackCommand

    /** Bookmarks the current track where it is now. */
    data object AddBookmark : PlaybackCommand

    data class RemoveBookmark(
        val id: BookmarkId,
    ) : PlaybackCommand

    /** Plays the bookmarked track from the bookmark. */
    data class GoToBookmark(
        val id: BookmarkId,
    ) : PlaybackCommand
}

/** One-off facts Playback announces. */
sealed interface PlaybackFact {
    /** A playlist named [name] was saved. */
    data class PlaylistSaved(
        val name: String,
    ) : PlaybackFact
}
