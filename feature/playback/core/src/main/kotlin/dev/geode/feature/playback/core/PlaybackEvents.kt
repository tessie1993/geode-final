package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
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
}

/** What went wrong with Playback's database, as the handlers report it. */
enum class StorageFailure {
    READ,
    RECORD_PLAY,
    SAVE_SESSION,
    SAVE_PLAYLIST,
    DELETE_PLAYLIST,
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
}

typealias PlaybackEffect = Effect<PlaybackWork, PlaybackEvent, PlaybackFact>

/** The timer armed while a track opens. */
data object LoadTimeout

/** The timer armed while buffering. */
data object BufferingGrace

/** How long the engine may take to open a track before Playback stops with an error. */
val LOAD_TIMEOUT = Millis(10_000)

/**
 * How long Playback waits for a start while buffering. The adapter announces a pause and a wait for
 * data alike; a pause that lasts this long is a pause, so buffering never lasts forever.
 */
val BUFFERING_GRACE = Millis(1_500)
