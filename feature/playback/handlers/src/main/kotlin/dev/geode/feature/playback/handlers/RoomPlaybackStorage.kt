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
import dev.geode.feature.playback.api.QueueEnd
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [PlaybackStorage] in Playback's own Room database. */
class RoomPlaybackStorage(
    private val dao: PlaybackDao,
) : PlaybackStorage {
    override val playlists: Flow<List<PlaylistSummary>> =
        dao.playlists().map { rows -> rows.map { PlaylistSummary(PlaylistId(it.id), it.name, it.trackCount) } }

    override val history: Flow<List<PlayedTrack>> =
        dao.history(HISTORY_LIMIT).map { rows -> rows.map { PlayedTrack(it.track.toTrack(), Millis(it.playedAtMs)) } }

    override suspend fun playlistTracks(id: PlaylistId): List<Track> = dao.playlistTracks(id.value).map { it.track.toTrack() }

    override suspend fun createPlaylist(
        name: String,
        tracks: List<Track>,
        createdAt: Millis,
    ): PlaylistId = PlaylistId(dao.createPlaylist(PlaylistRow(name = name, createdAtMs = createdAt.value), tracks.map { it.toColumns() }))

    override suspend fun deletePlaylist(id: PlaylistId) = dao.deletePlaylist(id.value)

    override suspend fun recordPlay(
        track: Track,
        playedAt: Millis,
    ) = dao.recordPlay(PlayRow(track = track.toColumns(), playedAtMs = playedAt.value), HISTORY_LIMIT)

    override suspend fun saveSession(
        queue: Queue,
        position: Millis,
    ) = dao.saveSession(
        SessionRows(
            SessionRow(currentIndex = queue.index, positionMs = position.value),
            queue.tracks.mapIndexed { index, track -> SessionTrackRow(index, track.toColumns()) },
        ),
    )

    override suspend fun loadSession(): SavedSession? {
        val rows = dao.loadSession() ?: return null
        val tracks = rows.tracks.map { it.track.toTrack() }
        // A queue that no longer makes sense is not restored rather than restored wrong.
        if (rows.session.currentIndex !in tracks.indices) return null
        return SavedSession(Queue(tracks, rows.session.currentIndex), Millis(rows.session.positionMs))
    }

    override val bookmarks: Flow<List<Bookmark>> =
        dao.bookmarks().map { rows -> rows.map { Bookmark(BookmarkId(it.id), it.track.toTrack(), Millis(it.positionMs)) } }

    override suspend fun addBookmark(
        track: Track,
        position: Millis,
        createdAt: Millis,
    ): BookmarkId {
        val row = BookmarkRow(track = track.toColumns(), positionMs = position.value, createdAtMs = createdAt.value)
        return BookmarkId(dao.insertBookmark(row))
    }

    override suspend fun deleteBookmark(id: BookmarkId) = dao.deleteBookmark(id.value)

    override val resumePoints: Flow<Map<TrackId, Millis>> =
        dao.resumePoints().map { rows -> rows.associate { TrackId(it.trackId) to Millis(it.positionMs) } }

    override suspend fun saveResumePoint(
        track: TrackId,
        position: Millis,
        savedAt: Millis,
    ) = dao.saveResumePoint(ResumePointRow(track.value, position.value, savedAt.value), RESUME_POINT_LIMIT)

    override suspend fun forgetResumePoint(track: TrackId) = dao.forgetResumePoint(track.value)

    override suspend fun saveListening(options: ListeningOptions) =
        dao.putListening(
            ListeningRow(
                shuffle = options.shuffle,
                repeatMode = options.repeat.name,
                crossfadeMs = options.crossfade.value,
                gapless = options.gapless,
                queueEnd = options.queueEnd.name,
            ),
        )

    override suspend fun loadListening(): ListeningOptions? {
        val row = dao.listening() ?: return null
        val defaults = ListeningOptions()
        // A choice this version does not know, written by a newer one, reads as the default.
        return ListeningOptions(
            shuffle = row.shuffle,
            repeat = RepeatMode.entries.firstOrNull { it.name == row.repeatMode } ?: defaults.repeat,
            crossfade = Millis(row.crossfadeMs),
            gapless = row.gapless,
            queueEnd = QueueEnd.entries.firstOrNull { it.name == row.queueEnd } ?: defaults.queueEnd,
        )
    }

    override suspend fun knownTracks(): List<Track> =
        (dao.playedTracks() + dao.playlistedTracks()).map { it.toTrack() }.distinctBy { it.id }

    private fun Track.toColumns() = TrackColumns(id.value, title, artist, duration.value)

    private fun TrackColumns.toTrack() = Track(TrackId(trackId), title, artist, Millis(durationMs))
}
