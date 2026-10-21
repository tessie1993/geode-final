package dev.geode.feature.playback.handlers

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

/**
 * Playback's own database (ADR-0004, ADR-0005): the playlists, the play history and where playback
 * was left off. Nothing outside Playback opens it; other features read through Playback's `:api`.
 *
 * Every version is migrated step by step (ADR-0004). A new version adds its [Migration] to
 * [MIGRATIONS] and a sample of the version before it under `src/test/resources/db`, which the
 * migration test opens at the current version to prove that nothing is lost.
 */
@Database(
    entities = [PlaylistRow::class, PlaylistTrackRow::class, PlayRow::class, SessionRow::class, SessionTrackRow::class],
    version = PlaybackDatabase.VERSION,
    exportSchema = false,
)
abstract class PlaybackDatabase : RoomDatabase() {
    abstract fun dao(): PlaybackDao

    companion object {
        const val VERSION = 1
        const val NAME = "playback.db"

        /** Every migration, oldest first. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun open(
            context: Context,
            name: String = NAME,
        ): PlaybackDatabase =
            Room
                .databaseBuilder(context, PlaybackDatabase::class.java, name)
                .apply { MIGRATIONS.forEach { addMigrations(it) } }
                .build()
    }
}

/** A song as the database keeps it, inside a playlist, the history and the saved queue. */
data class TrackColumns(
    val trackId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "position"])
data class PlaylistTrackRow(
    val playlistId: Long,
    val position: Int,
    @Embedded val track: TrackColumns,
)

@Entity(tableName = "plays")
data class PlayRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val track: TrackColumns,
    val playedAtMs: Long,
)

/** The one saved session: which track of the saved queue, and where in it. */
@Entity(tableName = "session")
data class SessionRow(
    @PrimaryKey val id: Int = SESSION_ID,
    val currentIndex: Int,
    val positionMs: Long,
)

@Entity(tableName = "session_tracks")
data class SessionTrackRow(
    @PrimaryKey val position: Int,
    @Embedded val track: TrackColumns,
)

data class PlaylistCount(
    val id: Long,
    val name: String,
    val trackCount: Int,
)

/** The saved session and its queue, read together. */
data class SessionRows(
    val session: SessionRow,
    val tracks: List<SessionTrackRow>,
)

/** The id of the one row in the session table. */
const val SESSION_ID = 0

@Dao
abstract class PlaybackDao {
    @Query(
        "SELECT p.id AS id, p.name AS name, COUNT(t.position) AS trackCount FROM playlists p " +
            "LEFT JOIN playlist_tracks t ON t.playlistId = p.id GROUP BY p.id ORDER BY p.createdAtMs DESC, p.id DESC",
    )
    abstract fun playlists(): Flow<List<PlaylistCount>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :id ORDER BY position")
    abstract suspend fun playlistTracks(id: Long): List<PlaylistTrackRow>

    @Transaction
    open suspend fun createPlaylist(
        playlist: PlaylistRow,
        tracks: List<TrackColumns>,
    ): Long {
        val id = insertPlaylist(playlist)
        insertPlaylistTracks(tracks.mapIndexed { position, track -> PlaylistTrackRow(id, position, track) })
        return id
    }

    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        deletePlaylistTracks(id)
        deletePlaylistRow(id)
    }

    @Query("SELECT * FROM plays ORDER BY playedAtMs DESC, id DESC LIMIT :limit")
    abstract fun history(limit: Int): Flow<List<PlayRow>>

    @Transaction
    open suspend fun recordPlay(
        play: PlayRow,
        keep: Int,
    ) {
        insertPlay(play)
        trimPlays(keep)
    }

    @Transaction
    open suspend fun saveSession(rows: SessionRows) {
        clearSessionTracks()
        insertSessionTracks(rows.tracks)
        putSession(rows.session)
    }

    @Transaction
    open suspend fun loadSession(): SessionRows? = session()?.let { SessionRows(it, sessionTracks()) }

    @Insert
    protected abstract suspend fun insertPlaylist(row: PlaylistRow): Long

    @Insert
    protected abstract suspend fun insertPlaylistTracks(rows: List<PlaylistTrackRow>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    protected abstract suspend fun deletePlaylistTracks(id: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    protected abstract suspend fun deletePlaylistRow(id: Long)

    @Insert
    protected abstract suspend fun insertPlay(row: PlayRow)

    @Query("DELETE FROM plays WHERE id NOT IN (SELECT id FROM plays ORDER BY playedAtMs DESC, id DESC LIMIT :keep)")
    protected abstract suspend fun trimPlays(keep: Int)

    @Query("SELECT * FROM session WHERE id = $SESSION_ID")
    protected abstract suspend fun session(): SessionRow?

    @Query("SELECT * FROM session_tracks ORDER BY position")
    protected abstract suspend fun sessionTracks(): List<SessionTrackRow>

    @Query("DELETE FROM session_tracks")
    protected abstract suspend fun clearSessionTracks()

    @Insert
    protected abstract suspend fun insertSessionTracks(rows: List<SessionTrackRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putSession(row: SessionRow)
}
