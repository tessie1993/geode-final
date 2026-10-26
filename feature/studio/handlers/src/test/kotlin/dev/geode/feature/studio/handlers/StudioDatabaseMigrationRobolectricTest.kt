package dev.geode.feature.studio.handlers

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.NewRender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every saved version of Studio's database opens at the current version with nothing lost
 * (ADR-0004): a real sample of each old version, written as that version wrote it, is opened through
 * [StudioDatabase.open] and its migrations, and read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StudioDatabaseMigrationRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `a version 1 database keeps its Projects, videos and default Project, and gains the render queue`() =
        runBlocking {
            val name = "studio-v1-sample.db"
            writeSample(name, version = 1, script = "db/studio-v1.sql")

            val database = StudioDatabase.open(context, name)
            val storage = RoomStudioStorage(database.dao())
            try {
                assertEquals(
                    listOf(
                        Project(ProjectId(2), "Gigs", Millis(1_700_000_100_000)),
                        Project(ProjectId(1), "Trip", Millis(1_700_000_000_000)),
                    ),
                    storage.projects(),
                )
                assertEquals(
                    listOf(
                        Video(
                            VideoId(1),
                            ProjectId(1),
                            VideoKind.RENDER,
                            "Beach.mp4",
                            "content://media/external/video/media/31",
                            null,
                            Millis(214_000),
                            1920,
                            1080,
                            "Night Drive",
                        ),
                        Video(
                            VideoId(2),
                            ProjectId(2),
                            VideoKind.RECORDING,
                            "Stage.mp4",
                            "content://tree/videos/stage.mp4",
                            "content://thumb/2",
                            Millis(65_000),
                            1080,
                            1920,
                            "",
                        ),
                    ),
                    storage.videos(),
                )
                assertEquals(ProjectId(2), storage.defaultProject())
                // Version 2's render queue starts empty, and works (#338).
                assertEquals(emptyList<RenderJob>(), storage.unfinishedRenders())
                val track = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
                val render =
                    NewRender(listOf(track), RenderLook.Current, RenderFormat(), RenderLength.WholeTrack, ProjectId(1), RenderExtras())
                val queued = storage.queueRenders(listOf(render), FrozenLook("{}"), Millis(1))
                assertEquals(queued, storage.unfinishedRenders())
            } finally {
                database.close()
            }
        }

    @Test
    fun `the database is at the version the samples lead up to`() {
        assertEquals(1 + StudioDatabase.MIGRATIONS.size, StudioDatabase.VERSION)
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
