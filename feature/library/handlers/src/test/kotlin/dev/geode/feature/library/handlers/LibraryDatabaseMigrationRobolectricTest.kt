package dev.geode.feature.library.handlers

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every saved version of the Library's database opens at the current version with nothing lost
 * (ADR-0004): a real sample of each old version, written as that version wrote it, is opened through
 * [LibraryDatabase.open] and its migrations, and read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LibraryDatabaseMigrationRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private val night =
        Song(
            id = TrackId("content://media/external/audio/media/12"),
            title = "Night Drive",
            artist = "Ann",
            album = "Roads",
            albumArtist = "Various",
            genre = "Synthwave",
            composer = "Cole",
            path = "/storage/emulated/0/Music/night.flac",
            format = "flac",
            duration = Millis(215_000),
            addedAt = Millis(1_700_000_000_000),
        )

    private val tide =
        Song(
            id = TrackId("content://media/external/audio/media/7"),
            title = "Low Tide (edit)",
            artist = "Bob B",
            album = "Coastline",
            albumArtist = "",
            genre = "",
            composer = "",
            path = "/storage/emulated/0/Music/tide.mp3",
            format = "mp3",
            duration = Millis(187_000),
            addedAt = Millis(1_700_000_100_000),
            artwork = "content://media/external/images/media/3",
        )

    @Test
    fun `a version 1 database keeps its index, edits, favourites and excluded folders, and gains smart playlists`() =
        runBlocking {
            val name = "library-v1-sample.db"
            writeSample(name, version = 1, script = "db/library-v1.sql")

            val database = LibraryDatabase.open(context, name)
            val storage = RoomLibraryStorage(database.dao())
            try {
                val saved = storage.read()
                assertEquals(setOf(night, tide), saved.songs.toSet())
                assertEquals(setOf(night.id), saved.favourites)
                assertEquals(setOf("/storage/emulated/0/Ringtones"), saved.excludedFolders)
                // The tags of the second song were never read, and are still to be.
                assertEquals(listOf(tide.id), storage.songsWithoutTags().map { it.id })
                // Version 2 adds the smart playlists: none yet, and room for them.
                assertEquals(emptyList<SmartPlaylist>(), saved.smartPlaylists)
                val nightly = SmartPlaylist("Night", listOf(SmartRule(RuleField.TITLE, RuleOp.CONTAINS, "night")))
                storage.saveSmartPlaylist(nightly, replacing = null)
                assertEquals(listOf(nightly), storage.read().smartPlaylists)
            } finally {
                database.close()
            }
        }

    @Test
    fun `the database is at the version the samples lead up to`() {
        assertEquals(1 + LibraryDatabase.MIGRATIONS.size, LibraryDatabase.VERSION)
    }

    private fun writeSample(
        name: String,
        version: Int,
        script: String,
    ) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()
        val sql = checkNotNull(javaClass.classLoader?.getResource(script)) { "no sample $script" }.readText()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            statementsIn(sql).forEach(database::execSQL)
            database.version = version
        } finally {
            database.close()
        }
    }

    private fun statementsIn(sql: String): List<String> =
        sql
            .lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
