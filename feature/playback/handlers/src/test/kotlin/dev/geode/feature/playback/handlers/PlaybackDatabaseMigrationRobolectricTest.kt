package dev.geode.feature.playback.handlers

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every saved version of Playback's database opens at the current version with nothing lost
 * (ADR-0004): a real sample of each old version, written as that version wrote it, is opened through
 * [PlaybackDatabase.open] and its migrations, and read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlaybackDatabaseMigrationRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val night = Track(TrackId("content://media/external/audio/media/12"), "Night Drive", "Ann", Millis(215_000))
    private val tide = Track(TrackId("content://media/external/audio/media/7"), "Low Tide", "Bob", Millis(187_000))

    @Test
    fun `a version 1 database keeps its playlists, history and session`() =
        runBlocking {
            val name = "playback-v1-sample.db"
            writeSample(name, version = 1, script = "db/playback-v1.sql")

            val database = PlaybackDatabase.open(context, name)
            val storage = RoomPlaybackStorage(database.dao())
            try {
                assertEquals(listOf(PlaylistSummary(PlaylistId(1), "Evening", 2)), storage.playlists.first())
                assertEquals(listOf(night, tide), storage.playlistTracks(PlaylistId(1)))
                assertEquals(
                    listOf(PlayedTrack(night, Millis(1_700_000_200_000)), PlayedTrack(tide, Millis(1_700_000_100_000))),
                    storage.history.first(),
                )
                assertEquals(SavedSession(Queue(listOf(night, tide), 1), Millis(64_000)), storage.loadSession())
            } finally {
                database.close()
            }
        }

    @Test
    fun `the database is at the version the samples lead up to`() {
        assertEquals(1 + PlaybackDatabase.MIGRATIONS.size, PlaybackDatabase.VERSION)
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
