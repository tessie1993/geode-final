package dev.geode.feature.studio.handlers

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.NewRender
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `a version 2 database keeps its library and render queue, and no import is done yet`() =
        runBlocking {
            val name = "studio-v2-sample.db"
            writeSample(name, version = 2, script = "db/studio-v2.sql")

            val database = StudioDatabase.open(context, name)
            val storage = RoomStudioStorage(database.dao())
            try {
                val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
                val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_000))
                val videos = storage.videos()
                assertEquals(listOf(VideoId(1), VideoId(2)), videos.map { it.id })
                assertEquals(
                    RenderSpec(listOf(night), format = RenderFormat(), project = ProjectId(1)),
                    videos.first().madeWith,
                )
                assertEquals(ProjectId(2), storage.defaultProject())
                assertEquals(
                    listOf(
                        RenderJob(
                            RenderId(2),
                            RenderId(2),
                            listOf(tide),
                            RenderLook.Current,
                            RenderFormat(aspect = RenderAspect.R9_16, fps = 30),
                            RenderLength.WholeTrack,
                            ProjectId(2),
                            RenderExtras(RenderLoudness.MINUS_14_LUFS),
                        ),
                    ),
                    storage.unfinishedRenders(),
                )
                // Version 3's imports start undone, and are kept once done (#347).
                assertFalse(storage.imported("old-studio-videos"))
                val old =
                    NewVideo(VideoKind.RENDER, "old.mp4", "content://media/external/video/media/9", null, Millis(3_000), 1920, 1080, "")
                val imported = checkNotNull(storage.import("old-studio-videos", "Earlier videos", Millis(5), listOf(old)))
                assertEquals(Project(imported.project.id, "Earlier videos", Millis(5)), imported.project)
                assertEquals(listOf("content://media/external/video/media/9"), imported.videos.map { it.file })
                assertEquals(listOf(imported.project.id), imported.videos.map { it.project })
                assertTrue(storage.imported("old-studio-videos"))
                assertEquals(imported.videos.single(), storage.videos().last())
                assertEquals(null, storage.import("nothing-to-bring", "Unused", Millis(6), emptyList()))
                assertTrue(storage.imported("nothing-to-bring"))
                assertEquals(3, storage.projects().size)
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
