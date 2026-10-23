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
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Playback's own database (ADR-0004, ADR-0005): the playlists, the play history and where playback
 * was left off, and since version 2 the listening tools' bookmarks, resume points and options (#35).
 * Nothing outside Playback opens it; other features read through Playback's `:api`.
 *
 * Every version is migrated step by step (ADR-0004). A new version adds its [Migration] to
 * [MIGRATIONS] and a sample of the version before it under `src/test/resources/db`, which the
 * migration test opens at the current version to prove that nothing is lost.
 */
@Database(
    entities = [
        PlaylistRow::class,
        PlaylistTrackRow::class,
        PlayRow::class,
        SessionRow::class,
        SessionTrackRow::class,
        BookmarkRow::class,
        ResumePointRow::class,
        ListeningRow::class,
    ],
    version = PlaybackDatabase.VERSION,
    exportSchema = false,
)
abstract class PlaybackDatabase : RoomDatabase() {
    abstract fun dao(): PlaybackDao

    companion object {
        const val VERSION = 2
        const val NAME = "playback.db"

        /** Every migration, oldest first. */
        val MIGRATIONS: Array<Migration> = arrayOf(AddListeningTools)

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

/** Version 2 (#35): the bookmarks, the resume points of long tracks and the listening options. */
object AddListeningTools : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`trackId` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "`positionMs` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `resume_points` (`trackId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`savedAtMs` INTEGER NOT NULL, PRIMARY KEY(`trackId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `listening` (`id` INTEGER NOT NULL, `shuffle` INTEGER NOT NULL, " +
                "`repeatMode` TEXT NOT NULL, `crossfadeMs` INTEGER NOT NULL, `gapless` INTEGER NOT NULL, " +
                "`queueEnd` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
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

@Entity(tableName = "bookmarks")
data class BookmarkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded val track: TrackColumns,
    val positionMs: Long,
    val createdAtMs: Long,
)

/** Where a long track was left off. */
@Entity(tableName = "resume_points")
data class ResumePointRow(
    @PrimaryKey val trackId: String,
    val positionMs: Long,
    val savedAtMs: Long,
)

/** The one row of listening options; the choices are kept by name. */
@Entity(tableName = "listening")
data class ListeningRow(
    @PrimaryKey val id: Int = LISTENING_ID,
    val shuffle: Boolean,
    val repeatMode: String,
    val crossfadeMs: Long,
    val gapless: Boolean,
    val queueEnd: String,
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

/** The id of the one row in the listening table. */
const val LISTENING_ID = 0

@Dao
abstract class PlaybackDao {
    @Query(
        "SELECT p.id AS id, p.name AS name, COUNT(t.position) AS trackCount FROM playlists p " +
            "LEFT JOIN playlist_tracks t ON t.playlistId = p.id GROUP BY p.id ORDER BY p.createdAtMs DESC, p.id DESC",
    )
    abstract fun playlists(): Flow<List<PlaylistCount>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :id ORDER BY position")
    abstract suspend fun playlistTracks(id: Long): List<PlaylistTrackRow>

    /** Every playlist's tracks, each playlist's in order. */
    @Query("SELECT * FROM playlist_tracks ORDER BY playlistId, position")
    abstract fun allPlaylistTracks(): Flow<List<PlaylistTrackRow>>

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun renamePlaylist(
        id: Long,
        name: String,
    )

    @Transaction
    open suspend fun replacePlaylistTracks(
        id: Long,
        tracks: List<TrackColumns>,
    ) {
        deletePlaylistTracks(id)
        insertPlaylistTracks(tracks.mapIndexed { position, track -> PlaylistTrackRow(id, position, track) })
    }

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

    @Query("SELECT * FROM bookmarks ORDER BY createdAtMs DESC, id DESC")
    abstract fun bookmarks(): Flow<List<BookmarkRow>>

    @Insert
    abstract suspend fun insertBookmark(row: BookmarkRow): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    abstract suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM resume_points")
    abstract fun resumePoints(): Flow<List<ResumePointRow>>

    @Transaction
    open suspend fun saveResumePoint(
        row: ResumePointRow,
        keep: Int,
    ) {
        putResumePoint(row)
        trimResumePoints(keep)
    }

    @Query("DELETE FROM resume_points WHERE trackId = :trackId")
    abstract suspend fun forgetResumePoint(trackId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putListening(row: ListeningRow)

    @Query("SELECT * FROM listening WHERE id = $LISTENING_ID")
    abstract suspend fun listening(): ListeningRow?

    /** The songs of the history, each once, the most played first. */
    @Query(
        "SELECT trackId, title, artist, durationMs FROM plays GROUP BY trackId " +
            "ORDER BY COUNT(*) DESC, MAX(playedAtMs) DESC, trackId",
    )
    abstract suspend fun playedTracks(): List<TrackColumns>

    /** The songs of the playlists, each once. */
    @Query(
        "SELECT trackId, title, artist, durationMs FROM playlist_tracks GROUP BY trackId " +
            "ORDER BY MIN(playlistId), MIN(position), trackId",
    )
    abstract suspend fun playlistedTracks(): List<TrackColumns>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putResumePoint(row: ResumePointRow)

    @Query(
        "DELETE FROM resume_points WHERE trackId NOT IN " +
            "(SELECT trackId FROM resume_points ORDER BY savedAtMs DESC, trackId LIMIT :keep)",
    )
    protected abstract suspend fun trimResumePoints(keep: Int)

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
