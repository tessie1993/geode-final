package dev.geode.feature.playback.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Bookmark
import dev.geode.feature.playback.api.BookmarkId
import dev.geode.feature.playback.api.ListeningOptions
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

    var listening: ListeningOptions? = null

    /** What [knownTracks] answers. */
    var known: List<Track> = emptyList()

    val marks = MutableStateFlow<List<Bookmark>>(emptyList())
    val points = MutableStateFlow<Map<TrackId, Millis>>(emptyMap())
    private var nextBookmark = 1L

    override val bookmarks: Flow<List<Bookmark>> = marks

    override suspend fun addBookmark(
        track: Track,
        position: Millis,
        createdAt: Millis,
    ): BookmarkId {
        checkWrites()
        val id = BookmarkId(nextBookmark++)
        marks.value = listOf(Bookmark(id, track, position)) + marks.value
        return id
    }

    override suspend fun deleteBookmark(id: BookmarkId) {
        checkWrites()
        marks.value = marks.value.filterNot { it.id == id }
    }

    override val resumePoints: Flow<Map<TrackId, Millis>> = points

    override suspend fun saveResumePoint(
        track: TrackId,
        position: Millis,
        savedAt: Millis,
    ) {
        checkWrites()
        points.value = points.value + (track to position)
    }

    override suspend fun forgetResumePoint(track: TrackId) {
        checkWrites()
        points.value = points.value - track
    }

    override suspend fun saveListening(options: ListeningOptions) {
        checkWrites()
        listening = options
    }

    override suspend fun loadListening(): ListeningOptions? = listening

    override suspend fun knownTracks(): List<Track> = known

    private fun checkWrites() = check(!failWrites) { "disk full" }
}
