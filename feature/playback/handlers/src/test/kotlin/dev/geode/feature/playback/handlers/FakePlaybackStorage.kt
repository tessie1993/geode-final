package dev.geode.feature.playback.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** [PlaybackStorage] in memory, with a switch that makes every write fail. */
class FakePlaybackStorage : PlaybackStorage {
    var session: SavedSession? = null
    var failWrites = false
    val tracksOf = mutableMapOf<PlaylistId, List<Track>>()

    private val saved = MutableStateFlow<List<Pair<PlaylistSummary, List<Track>>>>(emptyList())
    private val plays = MutableStateFlow<List<PlayedTrack>>(emptyList())
    private var nextId = 1L

    override val playlists: Flow<List<PlaylistSummary>> = saved.map { list -> list.map { it.first } }

    override val history: Flow<List<PlayedTrack>> = plays

    override suspend fun playlistTracks(id: PlaylistId): List<Track> = tracksOf[id].orEmpty()

    override suspend fun createPlaylist(
        name: String,
        tracks: List<Track>,
        createdAt: Millis,
    ): PlaylistId {
        checkWrites()
        val id = PlaylistId(nextId++)
        tracksOf[id] = tracks
        saved.value = listOf(PlaylistSummary(id, name, tracks.size) to tracks) + saved.value
        return id
    }

    override suspend fun deletePlaylist(id: PlaylistId) {
        checkWrites()
        tracksOf.remove(id)
        saved.value = saved.value.filterNot { it.first.id == id }
    }

    override suspend fun recordPlay(
        track: Track,
        playedAt: Millis,
    ) {
        checkWrites()
        plays.value = listOf(PlayedTrack(track, playedAt)) + plays.value
    }

    override suspend fun saveSession(
        queue: Queue,
        position: Millis,
    ) {
        checkWrites()
        session = SavedSession(queue, position)
    }

    override suspend fun loadSession(): SavedSession? = session

    private fun checkWrites() = check(!failWrites) { "disk full" }
}
