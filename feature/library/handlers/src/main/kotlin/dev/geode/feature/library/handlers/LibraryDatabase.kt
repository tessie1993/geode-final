package dev.geode.feature.library.handlers

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
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

/**
 * The Library's own database (ADR-0004): the library index with the tags Geode's reader filled in,
 * the user's edits to song info, the favourites, the excluded folders and, since version 2, the smart
 * playlists (#146). The playlists and the play history are Playback's (ADR-0005), in Playback's
 * database.
 *
 * Every version is migrated step by step (ADR-0004). A new version adds its [Migration] to
 * [MIGRATIONS] and a sample of the version before it under `src/test/resources/db`, which the
 * migration test opens at the current version to prove that nothing is lost.
 */
@Database(
    entities = [
        SongEntity::class,
        EditEntity::class,
        FavouriteEntity::class,
        ExcludedFolderEntity::class,
        SmartPlaylistEntity::class,
        SmartRuleEntity::class,
    ],
    version = LibraryDatabase.VERSION,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        const val VERSION = 2
        const val NAME = "library.db"

        /** Every migration, oldest first. */
        val MIGRATIONS: Array<Migration> = arrayOf(AddSmartPlaylists)

        fun open(
            context: Context,
            name: String = NAME,
        ): LibraryDatabase =
            Room
                .databaseBuilder(context, LibraryDatabase::class.java, name)
                .apply { MIGRATIONS.forEach { addMigrations(it) } }
                .build()
    }
}

/**
 * One song of the index, as the phone's media index and Geode's tag reader found it, before the
 * user's edits. [tagsRead] is whether the tag reader has filled in what the media index left blank.
 */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val composer: String,
    val path: String,
    val format: String,
    val durationMs: Long,
    val addedAtMs: Long,
    val tagsRead: Boolean,
)

/** The user's edit to one song's info. */
@Entity(tableName = "edits")
data class EditEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String?,
)

@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val trackId: String,
)

@Entity(tableName = "excluded_folders")
data class ExcludedFolderEntity(
    @PrimaryKey val path: String,
)

/** Version 2 (#146): the smart playlists and their rules. */
object AddSmartPlaylists : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `smart_playlists` (`name` TEXT NOT NULL, `matchAll` INTEGER NOT NULL, " +
                "`songLimit` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `smart_rules` (`playlist` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "`field` TEXT NOT NULL, `op` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`playlist`, `position`))",
        )
    }
}

/**
 * One smart playlist: its name, whether a song must match all its rules, and how many songs it holds
 * at most (0 for all). Its rules are [SmartRuleEntity] rows.
 */
@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey val name: String,
    val matchAll: Boolean,
    val songLimit: Int,
)

/** The rule at [position] of the smart playlist named [playlist]. [field] and [op] are the enum names. */
@Entity(tableName = "smart_rules", primaryKeys = ["playlist", "position"])
data class SmartRuleEntity(
    val playlist: String,
    val position: Int,
    val field: String,
    val op: String,
    val value: String,
)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM songs")
    abstract suspend fun songs(): List<SongEntity>

    @Query("SELECT * FROM edits")
    abstract suspend fun edits(): List<EditEntity>

    @Query("SELECT trackId FROM favourites")
    abstract suspend fun favourites(): List<String>

    @Query("SELECT path FROM excluded_folders")
    abstract suspend fun excludedFolders(): List<String>

    /** Makes [rows] the whole index. */
    @Transaction
    open suspend fun replaceSongs(rows: List<SongEntity>) {
        clearSongs()
        insertSongs(rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSong(row: SongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putEdit(row: EditEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addFavourite(row: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE trackId = :trackId")
    abstract suspend fun removeFavourite(trackId: String)

    @Transaction
    open suspend fun replaceExcludedFolders(rows: List<ExcludedFolderEntity>) {
        clearExcludedFolders()
        insertExcludedFolders(rows)
    }

    @Query("SELECT * FROM smart_playlists")
    abstract suspend fun smartPlaylists(): List<SmartPlaylistEntity>

    @Query("SELECT * FROM smart_rules")
    abstract suspend fun smartRules(): List<SmartRuleEntity>

    /** Saves [playlist] with [rules], in place of the smart playlist named [replacing] when there is one. */
    @Transaction
    open suspend fun replaceSmartPlaylist(
        replacing: String?,
        playlist: SmartPlaylistEntity,
        rules: List<SmartRuleEntity>,
    ) {
        if (replacing != null) deleteSmartPlaylist(replacing)
        deleteSmartPlaylist(playlist.name)
        insertSmartPlaylist(playlist)
        insertSmartRules(rules)
    }

    @Transaction
    open suspend fun deleteSmartPlaylist(name: String) {
        deleteSmartPlaylistRow(name)
        deleteSmartRules(name)
    }

    @Query("DELETE FROM smart_playlists WHERE name = :name")
    protected abstract suspend fun deleteSmartPlaylistRow(name: String)

    @Query("DELETE FROM smart_rules WHERE playlist = :name")
    protected abstract suspend fun deleteSmartRules(name: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSmartPlaylist(row: SmartPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSmartRules(rows: List<SmartRuleEntity>)

    @Query("DELETE FROM songs")
    protected abstract suspend fun clearSongs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSongs(rows: List<SongEntity>)

    @Query("DELETE FROM excluded_folders")
    protected abstract suspend fun clearExcludedFolders()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertExcludedFolders(rows: List<ExcludedFolderEntity>)
}
