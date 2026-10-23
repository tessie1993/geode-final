package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Bookmark
import dev.geode.feature.playback.api.BookmarkId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.kernel.Effect

/** Everything Playback's reducer handles: Commands, the audio adapter's announcements, timers and results. */
sealed interface PlaybackEvent {
    data class Command(
        val command: PlaybackCommand,
    ) : PlaybackEvent

    // What the audio adapter announces (ADR-0004), from the media session.

    /** The position is advancing from [mark]. */
    data class Started(
        val mark: PlayMark,
    ) : PlaybackEvent

    /** The position stopped at [mark]: paused by someone, or waiting for data. */
    data class Paused(
        val mark: PlayMark,
    ) : PlaybackEvent

    data class Seeked(
        val mark: PlayMark,
    ) : PlaybackEvent

    /** The player is now on [track] (null when nothing is loaded), at [mark]. */
    data class TrackChanged(
        val track: TrackId?,
        val mark: PlayMark,
    ) : PlaybackEvent

    /** The loaded track played to its end. */
    data object Ended : PlaybackEvent

    /** The player stopped with an error, such as a song it cannot decode. */
    data object Failed : PlaybackEvent

    // Timers.

    /** The engine did not open a track within [LOAD_TIMEOUT]. */
    data object LoadTimedOut : PlaybackEvent

    /** Buffering lasted [BUFFERING_GRACE] with no start: the player is paused, not waiting. */
    data object BufferingGraceOver : PlaybackEvent

    /** A [SLEEP_TICK] of the sleep timer went by, or what was left of it. */
    data object SleepTicked : PlaybackEvent

    /** Playing reached the end of the A-B loop. */
    data object LoopEndReached : PlaybackEvent

    // What the handlers found out.

    /** Where playback was left off: [queue] at [position] in its current track. */
    data class Restored(
        val queue: Queue,
        val position: Millis,
    ) : PlaybackEvent

    data class PlaylistOpened(
        val tracks: List<Track>,
    ) : PlaybackEvent

    data class PlaylistsChanged(
        val playlists: List<PlaylistSummary>,
    ) : PlaybackEvent

    data class HistoryChanged(
        val history: List<PlayedTrack>,
    ) : PlaybackEvent

    data class PlaylistSaved(
        val name: String,
    ) : PlaybackEvent

    data class StorageFailed(
        val failure: StorageFailure,
    ) : PlaybackEvent

    /** The listening options saved last time, or the defaults when none were. */
    data class ListeningRestored(
        val options: ListeningOptions,
    ) : PlaybackEvent

    data class BookmarksChanged(
        val bookmarks: List<Bookmark>,
    ) : PlaybackEvent

    data class ResumePointsChanged(
        val points: Map<TrackId, Millis>,
    ) : PlaybackEvent

    /** Songs like the queue's, to keep going with at its end; none when nothing fits. */
    data class SimilarPicked(
        val tracks: List<Track>,
    ) : PlaybackEvent
}

/** What went wrong with Playback's database, as the handlers report it. */
enum class StorageFailure {
    READ,
    RECORD_PLAY,
    SAVE_SESSION,
    SAVE_PLAYLIST,
    DELETE_PLAYLIST,
    SAVE_LISTENING,
    SAVE_BOOKMARK,
    DELETE_BOOKMARK,
    SAVE_RESUME_POINT,
}

/** Work for Playback's handlers. */
sealed interface PlaybackWork {
    /** Listen to the audio adapter's playback announcements for the life of the store. */
    data object Listen : PlaybackWork

    /** Follow the playlists and the play history for the life of the store. */
    data object Watch : PlaybackWork

    /** Find where playback was left off, if anywhere. */
    data object Restore : PlaybackWork

    /** Ask the audio adapter for [steps], in order. */
    data class Drive(
        val steps: List<EngineStep>,
    ) : PlaybackWork

    /** Add [track] to the play history, now. */
    data class RecordPlay(
        val track: Track,
    ) : PlaybackWork

    /** Save [queue] and the position [mark] gives now, so the app opens where it was left off. */
    data class SaveSession(
        val queue: Queue,
        val mark: PlayMark,
    ) : PlaybackWork

    data class OpenPlaylist(
        val id: PlaylistId,
    ) : PlaybackWork

    data class CreatePlaylist(
        val name: String,
        val tracks: List<Track>,
    ) : PlaybackWork

    data class DeletePlaylist(
        val id: PlaylistId,
    ) : PlaybackWork

    /** Read the listening options saved last time. */
    data object RestoreListening : PlaybackWork

    data class SaveListening(
        val options: ListeningOptions,
    ) : PlaybackWork

    /** Bookmark [track] at the position [mark] gives now. */
    data class AddBookmark(
        val track: Track,
        val mark: PlayMark,
    ) : PlaybackWork

    data class DeleteBookmark(
        val id: BookmarkId,
    ) : PlaybackWork

    /** Keep where long [track] is now, from [mark], as [resumeChoice] decides. */
    data class SaveResumePoint(
        val track: Track,
        val mark: PlayMark,
    ) : PlaybackWork

    /** [track] was heard to its end: it starts from the top next time. */
    data class ForgetResumePoint(
        val track: TrackId,
    ) : PlaybackWork

    /** Find songs like [queue]'s to keep going with, offline. */
    data class PickSimilar(
        val queue: Queue,
    ) : PlaybackWork
}

/** One request to the audio adapter. */
sealed interface EngineStep {
    /** Load [track] at the position [from] gives when the step runs. */
    data class Load(
        val track: TrackId,
        val from: PlayMark,
    ) : EngineStep

    data object Play : EngineStep

    data object Pause : EngineStep

    data class Seek(
        val position: Millis,
    ) : EngineStep

    /** How one track gives way to the next: fading over [crossfade], and without a gap when [gapless]. */
    data class Transitions(
        val crossfade: Millis,
        val gapless: Boolean,
    ) : EngineStep

    /** Open [track] ahead, to follow the loaded one by itself; null when nothing should. */
    data class PrepareNext(
        val track: TrackId?,
    ) : EngineStep
}

typealias PlaybackEffect = Effect<PlaybackWork, PlaybackEvent, PlaybackFact>

/** The timer armed while a track opens. */
data object LoadTimeout

/** The timer armed while buffering. */
data object BufferingGrace

/** The sleep timer's countdown timer. */
data object SleepTick

/** The timer armed, while playing, for the end of the A-B loop. */
data object LoopEnd

/** How long the engine may take to open a track before Playback stops with an error. */
val LOAD_TIMEOUT = Millis(10_000)

/**
 * How long Playback waits for a start while buffering. The adapter announces a pause and a wait for
 * data alike; a pause that lasts this long is a pause, so buffering never lasts forever.
 */
val BUFFERING_GRACE = Millis(1_500)

/** How often the sleep timer counts down. */
val SLEEP_TICK = Millis(60_000)

/** A track at least this long is a long track, which resumes where it was left off. */
val LONG_TRACK = Millis(20 * 60_000L)

/** Closer than this to either end of a long track, there is nothing worth resuming. */
val RESUME_EDGE = Millis(10_000)

/** The shortest A-B loop. */
val MIN_LOOP = Millis(1_000)

/** How many similar songs are added at a time when the queue runs out. */
const val SIMILAR_BATCH = 10
