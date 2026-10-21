package dev.geode.feature.playback.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import kotlinx.coroutines.flow.Flow

/**
 * What Playback keeps (ADR-0005 data ownership): its playlists, its play history, and where playback
 * was left off. [RoomPlaybackStorage] keeps them in Playback's own Room database. Any call may throw;
 * the handlers turn that into a named failure.
 */
interface PlaybackStorage {
    /** The playlists, newest first, for as long as it is collected. */
    val playlists: Flow<List<PlaylistSummary>>

    /** The latest [HISTORY_LIMIT] plays, most recent first, for as long as it is collected. */
    val history: Flow<List<PlayedTrack>>

    suspend fun playlistTracks(id: PlaylistId): List<Track>

    suspend fun createPlaylist(
        name: String,
        tracks: List<Track>,
        createdAt: Millis,
    ): PlaylistId

    suspend fun deletePlaylist(id: PlaylistId)

    /** Adds [track], played at [playedAt] (wall-clock time), keeping only the latest [HISTORY_LIMIT] plays. */
    suspend fun recordPlay(
        track: Track,
        playedAt: Millis,
    )

    /** Saves [queue] at [position], replacing what was saved before. */
    suspend fun saveSession(
        queue: Queue,
        position: Millis,
    )

    /** Where playback was left off, or null when nothing usable was saved. */
    suspend fun loadSession(): SavedSession?
}

data class SavedSession(
    val queue: Queue,
    val position: Millis,
)

/** How many plays the history keeps and shows. */
const val HISTORY_LIMIT = 200
