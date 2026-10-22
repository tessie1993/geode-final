package dev.geode.playback

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
import dev.geode.feature.playback.handlers.PlaybackStorage
import dev.geode.feature.playback.handlers.SavedSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Playback's storage in memory, for the switch-over's tests (#17). */
class MemoryPlaybackStorage : PlaybackStorage {
    var session: SavedSession? = null
    var listening: ListeningOptions? = null
    val plays = MutableStateFlow<List<PlayedTrack>>(emptyList())
    val points = MutableStateFlow<Map<TrackId, Millis>>(emptyMap())
    private val lists = MutableStateFlow<List<Pair<PlaylistSummary, List<Track>>>>(emptyList())
    private val marks = MutableStateFlow<List<Bookmark>>(emptyList())

    override val playlists: Flow<List<PlaylistSummary>> = lists.map { all -> all.map { it.first } }

    override val history: Flow<List<PlayedTrack>> = plays

    override suspend fun playlistTracks(id: PlaylistId): List<Track> =
        lists.value
            .firstOrNull { it.first.id == id }
            ?.second
            .orEmpty()

    override suspend fun createPlaylist(
        name: String,
        tracks: List<Track>,
        createdAt: Millis,
    ): PlaylistId {
        val id = PlaylistId(lists.value.size + 1L)
        lists.value = listOf(PlaylistSummary(id, name, tracks.size) to tracks) + lists.value
        return id
    }

    override suspend fun deletePlaylist(id: PlaylistId) {
        lists.value = lists.value.filterNot { it.first.id == id }
    }

    override suspend fun recordPlay(
        track: Track,
        playedAt: Millis,
    ) {
        plays.value = listOf(PlayedTrack(track, playedAt)) + plays.value
    }

    override suspend fun saveSession(
        queue: Queue,
        position: Millis,
    ) {
        session = SavedSession(queue, position)
    }

    override suspend fun loadSession(): SavedSession? = session

    override val bookmarks: Flow<List<Bookmark>> = marks

    override suspend fun addBookmark(
        track: Track,
        position: Millis,
        createdAt: Millis,
    ): BookmarkId {
        val id = BookmarkId(marks.value.size + 1L)
        marks.value = listOf(Bookmark(id, track, position)) + marks.value
        return id
    }

    override suspend fun deleteBookmark(id: BookmarkId) {
        marks.value = marks.value.filterNot { it.id == id }
    }

    override val resumePoints: Flow<Map<TrackId, Millis>> = points

    override suspend fun saveResumePoint(
        track: TrackId,
        position: Millis,
        savedAt: Millis,
    ) {
        points.value = points.value + (track to position)
    }

    override suspend fun forgetResumePoint(track: TrackId) {
        points.value = points.value - track
    }

    override suspend fun saveListening(options: ListeningOptions) {
        listening = options
    }

    override suspend fun loadListening(): ListeningOptions? = listening

    override suspend fun knownTracks(): List<Track> = plays.value.map { it.track }.distinct()
}
