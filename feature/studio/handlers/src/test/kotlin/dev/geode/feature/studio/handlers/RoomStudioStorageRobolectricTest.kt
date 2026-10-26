package dev.geode.feature.studio.handlers

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderCodec
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderQuality
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.NewRender
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Studio's storage on a real Room database (Robolectric's SQLite). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomStudioStorageRobolectricTest {
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StudioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val storage = RoomStudioStorage(database.dao())

    private val take =
        NewVideo(
            VideoKind.RECORDING,
            "take.mp4",
            "content://tree/videos/take.mp4",
            "content://thumb",
            Millis(3_000),
            1080,
            1920,
            "Teardrop",
        )

    @After
    fun close() = database.close()

    @Test
    fun `a new library has no Project and no default`() =
        runBlocking {
            assertEquals(emptyList<Any>(), storage.projects())
            assertEquals(null, storage.defaultProject())
        }

    @Test
    fun `Projects and their videos are saved, renamed and moved, with every field kept`() =
        runBlocking {
            val trip = storage.createProject("Trip", Millis(1), makeDefault = true)
            val gigs = storage.createProject("Gigs", Millis(2), makeDefault = false)
            assertEquals(listOf(gigs, trip), storage.projects())
            assertEquals(trip.id, storage.defaultProject())

            val video = storage.addVideos(trip.id, listOf(take)).single()
            assertEquals(listOf(video), storage.videos())
            assertEquals(
                take,
                NewVideo(video.kind, video.name, video.file, video.thumbnail, video.length, video.width, video.height, video.song),
            )

            storage.renameProject(trip.id, "Holiday")
            storage.moveVideo(video.id, gigs.id)
            storage.setDefaultProject(gigs.id)
            assertEquals("Holiday", storage.projects().last().name)
            assertEquals(gigs.id, storage.videos().single().project)
            assertEquals(gigs.id, storage.defaultProject())
        }

    @Test
    fun `deleting a Project moves its videos or forgets them, and clears it as the default`() =
        runBlocking {
            val trip = storage.createProject("Trip", Millis(1), makeDefault = true)
            val gigs = storage.createProject("Gigs", Millis(2), makeDefault = false)
            storage.addVideos(trip.id, listOf(take))

            storage.deleteProject(trip.id, moveTo = gigs.id)
            assertEquals(listOf(gigs), storage.projects())
            assertEquals(gigs.id, storage.videos().single().project)
            assertEquals(null, storage.defaultProject())

            storage.setDefaultProject(gigs.id)
            storage.deleteProject(gigs.id, moveTo = null)
            assertEquals(emptyList<Any>(), storage.projects())
            assertEquals(emptyList<Any>(), storage.videos())
            assertEquals(null, storage.defaultProject())
        }

    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
    private val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_000))

    private fun render(
        tracks: List<RenderTrack>,
        look: RenderLook,
        length: RenderLength,
    ) = NewRender(
        tracks,
        look,
        RenderFormat(RenderAspect.R21_9, RenderSize.QHD1440, 24, RenderQuality.BEST, RenderCodec.HEVC),
        length,
        ProjectId(3),
        RenderExtras(RenderLoudness.MINUS_11_LUFS),
    )

    @Test
    fun `a queued Render reads back exactly as it was queued, with its look, after a restart`() =
        runBlocking {
            val first =
                render(listOf(night), RenderLook.Preset(PresetId("Calm")), RenderLength.Section(Millis(1_000), Millis(59_000)))
                    .copy(milkdrop = "Geiss - Cosmic Dust.milk")
            val second = render(listOf(night, tide), RenderLook.Current, RenderLength.WholeTrack)

            val queued = storage.queueRenders(listOf(first, second), FrozenLook("{\"style\":\"fluid_ink\"}"), Millis(7))

            val (one, two) = queued
            assertEquals(listOf(first.queuedAs(one.id, one.id), second.queuedAs(two.id, one.id)), queued)
            assertEquals(queued, RoomStudioStorage(database.dao()).unfinishedRenders())
            assertEquals(FrozenLook("{\"style\":\"fluid_ink\"}"), storage.lookOf(two.id))
            assertEquals(null, storage.lookOf(RenderId(99)))
        }

    @Test
    fun `a made Render is kept but no longer waits, and a cancelled one is forgotten`() =
        runBlocking {
            val (made, cancelled, waiting) =
                storage.queueRenders(
                    listOf(render(listOf(night), RenderLook.Current, RenderLength.WholeTrack)).let { it + it + it },
                    FrozenLook("look"),
                    Millis(1),
                )

            storage.renderSaved(made.id, "content://media/video/9")
            storage.forgetRender(cancelled.id)

            assertEquals(listOf(waiting), storage.unfinishedRenders())
            assertEquals(FrozenLook("look"), storage.lookOf(made.id))
            assertEquals(null, storage.lookOf(cancelled.id))
            // A batch queued later is its own.
            val later =
                storage.queueRenders(
                    listOf(render(listOf(tide), RenderLook.Current, RenderLength.WholeTrack)),
                    FrozenLook("look"),
                    Millis(2),
                )
            assertEquals(later.single().id, later.single().batch)
            assertEquals(emptyList<RenderJob>(), storage.queueRenders(emptyList(), FrozenLook("look"), Millis(3)))
        }

    @Test
    fun `a made Render's video keeps the setup it was made from, and its look, after a restart`() =
        runBlocking {
            val trip = storage.createProject("Trip", Millis(1), makeDefault = true)
            val chosen = render(listOf(night), RenderLook.Preset(PresetId("Calm")), RenderLength.WholeTrack).copy(milkdrop = "a.milk")
            val made = storage.queueRenders(listOf(chosen), FrozenLook("calm look"), Millis(1)).single()
            val file = "content://media/video/9"
            storage.renderSaved(made.id, file)
            val video = take.copy(kind = VideoKind.RENDER, file = file, madeWith = chosen.toSpec())

            assertEquals(chosen.toSpec(), storage.addVideos(trip.id, listOf(video)).single().madeWith)
            val reread = RoomStudioStorage(database.dao())
            assertEquals(listOf(chosen.toSpec(), null), (reread.videos() + reread.addVideos(trip.id, listOf(take))).map { it.madeWith })
            assertEquals(FrozenLook("calm look"), reread.lookOfVideo(file))
            assertEquals(null, reread.lookOfVideo("content://media/video/1"))
        }

    @Test
    fun `a loop and an N-minute Render read back as queued`() =
        runBlocking {
            val loop = render(listOf(night), RenderLook.Current, RenderLength.Loop)
            val hour = render(listOf(tide), RenderLook.Current, RenderLength.Minutes(60))

            val queued = storage.queueRenders(listOf(loop, hour), FrozenLook("look"), Millis(4))

            assertEquals(queued, RoomStudioStorage(database.dao()).unfinishedRenders())
            assertEquals(listOf(RenderLength.Loop, RenderLength.Minutes(60)), queued.map { it.length })
        }
}
