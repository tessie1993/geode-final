package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.settings.api.ExportCodec
import dev.geode.feature.settings.api.ExportFrameRate
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportQuality
import dev.geode.feature.settings.api.ExportRatio
import dev.geode.feature.settings.api.ExportSettings
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.PerformanceSettings
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSession
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.Project
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
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.VideosOnDelete
import dev.geode.feature.studio.api.edit.Clip
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.EditorHistory
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.Lane
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.Timeline
import dev.geode.feature.studio.core.NewRender
import dev.geode.feature.studio.core.RenderEnd
import dev.geode.feature.studio.core.RenderSettings
import dev.geode.feature.studio.core.StudioEvent
import dev.geode.feature.studio.handlers.edit.EditDrafts
import dev.geode.feature.studio.handlers.edit.EditEngine
import dev.geode.feature.studio.handlers.edit.EditTools
import dev.geode.feature.studio.handlers.edit.EditVideos
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.geode.feature.settings.api.EditSources as SettingsEditSources

/** Studio end to end through its store, over fake storage, Settings and files. */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioStoreTest {
    private val storage = FakeStudioStorage()
    private val files = FakeVideoFiles()
    private val settings = FakeSettings()
    private val failures = mutableListOf<FeatureFailure>()
    private val take = NewVideo(VideoKind.RECORDING, "take.mp4", "content://tree/videos/take.mp4", null, Millis(4_000), 1080, 1920, "Song")
    private val looks = FakeRenderLooks()
    private val engine = FakeRenderEngine()
    private val night = RenderTrack(TrackId("content://media/12"), "Night Drive", Millis(215_000))
    private val tide = RenderTrack(TrackId("content://media/7"), "Low Tide", Millis(187_000))
    private val drafts = FakeEditDrafts()
    private val editVideos = FakeEditVideos()
    private val editEngine = FakeEditEngine()

    private fun store() =
        studioStore(
            StudioSources(
                storage,
                saveFolderOf(settings),
                files,
                { Millis(500) },
                RenderSources(renderSettingsOf(settings), looks, engine),
                edits = EditTools(drafts, editVideos, editEngine),
            ),
            inPlaceRuntime(failures = failures),
        )

    /** A store with a default Project and the Visual on, ready to make Renders. */
    private fun renderingStore(): Pair<Studio, ProjectId> {
        settings.ready(null)
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
        return studio to
            studio.state.value.projects
                .single()
                .id
    }

    @Test
    fun `nothing is made up on first start, no Project and no default`() {
        val studio = store()

        assertFalse(studio.state.value.loading)
        assertTrue(
            studio.state.value.projects
                .isEmpty(),
        )
        assertEquals(null, studio.state.value.defaultProject)
        assertTrue(storage.projectsById.isEmpty())
    }

    @Test
    fun `the save folder follows Settings and the first-run step is due until one is picked`() {
        val studio = store()
        assertEquals(SaveFolder.Unknown, studio.state.value.saveFolder)

        settings.ready(null)
        assertEquals(SaveFolder.NotChosen, studio.state.value.saveFolder)
        assertTrue(studio.state.value.needsSetup)

        settings.ready(FolderUri("content://tree/videos"))
        assertEquals(SaveFolder.Chosen("content://tree/videos"), studio.state.value.saveFolder)
        assertFalse(studio.state.value.needsSetup)
    }

    @Test
    fun `a new Project is saved, made the default when asked, and announced`() =
        runTest {
            val studio = store()
            val facts = mutableListOf<StudioFact>()
            val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { studio.events.collect { facts += it } }

            studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))

            val trip =
                studio.state.value.projects
                    .single()
            assertEquals(Project(trip.id, "Trip", Millis(500)), trip)
            assertEquals(trip.id, studio.state.value.defaultProject)
            assertEquals(trip.id, storage.defaultProject())
            assertEquals(listOf(StudioFact.ProjectCreated(trip.id)), facts)
            collecting.cancel()
        }

    @Test
    fun `the default Project the user chooses is kept by Studio across a restart`() {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = false))
        studio.send(StudioCommand.CreateProject("Gigs", makeDefault = false))
        val trip =
            studio.state.value.projects
                .single { it.name == "Trip" }

        studio.send(StudioCommand.SetDefaultProject(trip.id))

        assertEquals(trip.id, store().state.value.defaultProject)
    }

    @Test
    fun `videos without a Project go to the default and are announced with their ids`() =
        runTest {
            val studio = store()
            studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
            val facts = mutableListOf<StudioFact>()
            val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { studio.events.collect { facts += it } }

            studio.send(StudioCommand.AddVideos(listOf(take)))

            val added =
                studio.state.value.videos
                    .single()
            assertEquals(studio.state.value.defaultProject, added.project)
            assertEquals(take.file, added.file)
            assertEquals(listOf(added), storage.videos())
            assertEquals(listOf(StudioFact.VideosAdded(added.project, listOf(added.id))), facts)
            collecting.cancel()
        }

    @Test
    fun `videos without a Project and no default are handed back, not dropped`() =
        runTest {
            val studio = store()
            val facts = mutableListOf<StudioFact>()
            val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { studio.events.collect { facts += it } }

            studio.send(StudioCommand.AddVideos(listOf(take)))

            assertEquals(listOf(StudioFact.ProjectNeeded(listOf(take))), facts)
            assertTrue(storage.videosById.isEmpty())
            collecting.cancel()
        }

    @Test
    fun `rename and move are saved`() {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
        studio.send(StudioCommand.CreateProject("Gigs", makeDefault = false))
        val (gigs, trip) = studio.state.value.projects
        studio.send(StudioCommand.AddVideos(listOf(take)))
        val video =
            studio.state.value.videos
                .single()

        studio.send(StudioCommand.RenameProject(trip.id, "Holiday"))
        studio.send(StudioCommand.MoveVideo(video.id, gigs.id))

        assertEquals("Holiday", storage.projectsById.getValue(trip.id).name)
        assertEquals(gigs.id, storage.videosById.getValue(video.id).project)
        assertEquals(studio.state.value.videos, storage.videosById.values.toList())
    }

    @Test
    fun `Delete N videos too, yes, deletes the files with the Project`() =
        runTest {
            val (studio, trip) = projectWithTake()

            studio.send(StudioCommand.DeleteProject(trip, VideosOnDelete.Delete))

            assertEquals(listOf(take.file), files.deleted)
            assertTrue(storage.projectsById.isEmpty())
            assertTrue(storage.videosById.isEmpty())
            assertEquals(null, storage.defaultProject())
            assertTrue(failures.isEmpty())
        }

    @Test
    fun `Delete N videos too, no, moves them and touches no file`() {
        val (studio, trip) = projectWithTake()
        studio.send(StudioCommand.CreateProject("Gigs", makeDefault = false))
        val gigs =
            studio.state.value.projects
                .first()
                .id

        studio.send(StudioCommand.DeleteProject(trip, VideosOnDelete.MoveTo(gigs)))

        assertEquals(emptyList<String>(), files.deleted)
        assertEquals(listOf(gigs), storage.projectsById.keys.toList())
        assertEquals(
            gigs,
            storage.videosById.values
                .single()
                .project,
        )
        assertEquals(studio.state.value.videos, storage.videosById.values.toList())
    }

    @Test
    fun `files that cannot be deleted are reported`() {
        val (studio, trip) = projectWithTake()
        files.refuse = true

        studio.send(StudioCommand.DeleteProject(trip, VideosOnDelete.Delete))

        assertEquals(listOf("Some video files could not be deleted"), failures.map { it.message })
        assertEquals(STUDIO, failures.single().feature)
    }

    @Test
    fun `a Project that cannot be forgotten keeps its files`() {
        val (studio, trip) = projectWithTake()
        storage.broken = true

        studio.send(StudioCommand.DeleteProject(trip, VideosOnDelete.Delete))

        assertEquals(emptyList<String>(), files.deleted)
        assertEquals(listOf("Could not save the change to the Studio library"), failures.map { it.message })
    }

    @Test
    fun `videos that cannot be saved are reported`() {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
        storage.broken = true

        studio.send(StudioCommand.AddVideos(listOf(take)))

        assertEquals(listOf("Could not add the new videos to the Studio library"), failures.map { it.message })
    }

    @Test
    fun `changes that cannot be saved are reported`() {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = false))
        studio.send(StudioCommand.CreateProject("Gigs", makeDefault = false))
        val (gigs, trip) = studio.state.value.projects
        storage.broken = true

        studio.send(StudioCommand.CreateProject("Live", makeDefault = false))
        studio.send(StudioCommand.RenameProject(trip.id, "Holiday"))
        studio.send(StudioCommand.SetDefaultProject(gigs.id))

        assertEquals(3, failures.count { it.message == "Could not save the change to the Studio library" })
    }

    @Test
    fun `a library that cannot be read shows empty and is reported`() {
        storage.broken = true

        val studio = store()

        assertFalse(studio.state.value.loading)
        assertTrue(
            studio.state.value.projects
                .isEmpty(),
        )
        assertEquals(listOf("Could not read the Studio library"), failures.map { it.message })
    }

    @Test
    fun `Settings still loading reads as an unknown save folder`() =
        runTest {
            assertEquals(SaveFolder.Unknown, saveFolderOf(FakeSettings()).first())
        }

    @Test
    fun `a queued Render freezes its look, is saved, is made, and its video lands in its Project`() {
        val (studio, trip) = renderingStore()

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night), look = RenderLook.Preset(PresetId("Calm")))))

        assertEquals(listOf<RenderLook>(RenderLook.Preset(PresetId("Calm"))), looks.frozen)
        val job =
            studio.state.value.renders
                .single()
        assertEquals(RenderStatus.Done, job.status)
        assertEquals(listOf(job.id to FrozenLook("look 1")), engine.made.map { it.first.id to it.second })
        val video =
            studio.state.value.videos
                .single()
        assertEquals(trip, video.project)
        assertEquals("content://media/video/Night Drive", video.file)
        assertEquals(StoredRender(job.copy(status = RenderStatus.Queued), FrozenLook("look 1"), video.file), storage.rendersById[job.id])
        assertEquals(emptyList<RenderJob>(), storage.unfinishedRendersNow())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a Render is made again at another shape with its own look, and becomes its own Render`() {
        val (studio, trip) = renderingStore()
        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night), look = RenderLook.Preset(PresetId("Calm")), milkdrop = "a.milk")))
        val first =
            studio.state.value.videos
                .single()
        val madeWith = checkNotNull(first.madeWith)
        assertEquals(listOf(night), madeWith.tracks)
        assertEquals("a.milk", madeWith.milkdrop)

        // The Customization changes meanwhile; the Render made again keeps the look frozen for the first.
        val square = RenderFormat(aspect = RenderAspect.R1_1, size = RenderSize.HD720)
        studio.send(StudioCommand.RenderAgain(first.id, madeWith.copy(format = square, look = RenderLook.Current)))

        assertEquals(listOf<RenderLook>(RenderLook.Preset(PresetId("Calm"))), looks.frozen)
        val again = engine.made.last()
        assertEquals(FrozenLook("look 1"), again.second)
        assertEquals(square, again.first.format)
        assertEquals(RenderLook.Preset(PresetId("Calm")), again.first.look)
        assertEquals(listOf(night), again.first.tracks)
        assertEquals(2, studio.state.value.videos.size)
        assertEquals(
            listOf(trip, trip),
            studio.state.value.videos
                .map { it.project },
        )
        assertEquals(
            square,
            studio.state.value.videos
                .last()
                .madeWith
                ?.format,
        )
        // A fresh Render of the song still starts from Settings, not from either Render.
        assertEquals(RenderFormat(), studio.state.value.renderFormat)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a Render whose look is gone cannot be made again, and says so`() {
        val (studio, _) = renderingStore()
        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night))))
        val first =
            studio.state.value.videos
                .single()
        storage.loseLooks = true

        studio.send(StudioCommand.RenderAgain(first.id, checkNotNull(first.madeWith)))

        assertEquals(1, engine.made.size)
        assertEquals(1, studio.state.value.renders.size)
        assertEquals(listOf("Could not read the look for the Render, so it was not queued"), failures.map { it.message })
    }

    @Test
    fun `a playlist is made one video per track, in order, each its own Render`() {
        val (studio, _) = renderingStore()

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night, tide), playlist = PlaylistMode.ONE_VIDEO_PER_TRACK)))

        val renders = studio.state.value.renders
        assertEquals(listOf("Night Drive", "Low Tide"), engine.made.map { it.first.title })
        assertEquals(listOf(RenderStatus.Done, RenderStatus.Done), renders.map { it.status })
        assertEquals(listOf(renders[0].id, renders[0].id), renders.map { it.batch })
        assertEquals(1f, studio.state.value.batchProgress(renders[0].id), 0f)
        assertEquals(2, studio.state.value.videos.size)
        // The look was frozen once for the whole playlist.
        assertEquals(1, looks.frozen.size)
    }

    @Test
    fun `the running Render reports its progress and can be cancelled, leaving no Render behind`() {
        val (studio, _) = renderingStore()
        val gate = engine.hold()

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night, tide))))
        val (first, second) = studio.state.value.renders
        engine.progress?.invoke(RenderPhase.RENDERING, 0.5f)
        assertEquals(
            RenderStatus.Running(RenderPhase.RENDERING, 0.5f),
            studio.state.value.runningRender
                ?.status,
        )
        assertEquals(RenderStatus.Queued, second.status)

        studio.send(StudioCommand.CancelRenders(setOf(first.id)))
        assertEquals(
            true,
            (
                studio.state.value.runningRender
                    ?.status as? RenderStatus.Running
            )?.cancelling,
        )
        assertTrue(engine.cancelled?.invoke() == true)
        engine.release()
        gate.complete(Unit)

        assertEquals(
            listOf(RenderStatus.Cancelled, RenderStatus.Done),
            studio.state.value.renders
                .map { it.status },
        )
        assertEquals(listOf(second.id), storage.rendersById.keys.toList())
    }

    @Test
    fun `a waiting Render that is cancelled never starts and is forgotten`() {
        val (studio, _) = renderingStore()
        engine.hold()

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night, tide))))
        val second = studio.state.value.renders[1]
        studio.send(StudioCommand.CancelRenders(setOf(second.id)))

        assertEquals(
            RenderStatus.Cancelled,
            studio.state.value.renders[1]
                .status,
        )
        assertEquals(false, second.id in storage.rendersById)
        assertEquals(1, engine.made.size)
    }

    @Test
    fun `Renders that had not finished carry on after a restart`() {
        val (studio, _) = renderingStore()
        engine.hold()
        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night, tide))))
        val queued =
            studio.state.value.renders
                .map { it.copy(status = RenderStatus.Queued) }

        engine.release()
        val restarted = store()

        assertEquals(queued.map { it.id }, engine.made.drop(1).map { it.first.id })
        assertEquals(
            listOf(RenderStatus.Done, RenderStatus.Done),
            restarted.state.value.renders
                .map { it.status },
        )
        assertEquals(queued.map { it.copy(status = RenderStatus.Done) }, restarted.state.value.renders)
    }

    @Test
    fun `no Render is queued while Visuals off is on`() {
        settings.ready(null, visualsOff = true)
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night))))

        assertTrue(studio.state.value.visualsOff)
        assertTrue(looks.frozen.isEmpty())
        assertEquals(listOf("No Render can be made while Visuals off is on"), failures.map { it.message })
    }

    @Test
    fun `a look that cannot be frozen queues nothing and is reported`() {
        val (studio, _) = renderingStore()
        looks.broken = true

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night))))

        assertTrue(
            studio.state.value.renders
                .isEmpty(),
        )
        assertTrue(storage.rendersById.isEmpty())
        assertEquals(listOf("Could not read the look for the Render, so it was not queued"), failures.map { it.message })
    }

    @Test
    fun `a Render that cannot be saved to the queue is reported`() {
        val (studio, _) = renderingStore()
        storage.broken = true

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night))))

        assertTrue(
            studio.state.value.renders
                .isEmpty(),
        )
        assertEquals(listOf("Could not add the Render to the queue"), failures.map { it.message })
    }

    @Test
    fun `an engine that throws fails the Render, which is reported and forgotten`() {
        val (studio, _) = renderingStore()
        engine.broken = true

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night))))

        val job =
            studio.state.value.renders
                .single()
        assertEquals(RenderStatus.Failed(RenderProblem.ENGINE_FAILED), job.status)
        assertTrue(storage.rendersById.isEmpty())
        assertEquals(listOf("The Render of “Night Drive” failed: the engine stopped unexpectedly"), failures.map { it.message })
    }

    @Test
    fun `a Render whose look is gone fails without reaching the engine`() {
        val (studio, _) = renderingStore()
        storage.loseLooks = true

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night))))

        assertEquals(
            RenderStatus.Failed(RenderProblem.LOOK_UNAVAILABLE),
            studio.state.value.renders
                .single()
                .status,
        )
        assertTrue(engine.made.isEmpty())
    }

    @Test
    fun `a Render the engine could not make is forgotten and reported`() {
        val (studio, _) = renderingStore()
        engine.ending = RenderEnd.Failed(RenderProblem.ENCODER_REFUSED)

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night))))

        assertEquals(
            RenderStatus.Failed(RenderProblem.ENCODER_REFUSED),
            studio.state.value.renders
                .single()
                .status,
        )
        assertTrue(storage.rendersById.isEmpty())
        assertTrue(
            studio.state.value.videos
                .isEmpty(),
        )
    }

    @Test
    fun `a finished Render that cannot be kept is reported, and so is its video`() {
        val (studio, _) = renderingStore()
        engine.onRender = { storage.broken = true }

        studio.send(StudioCommand.QueueRender(RenderSpec(listOf(night))))

        assertEquals(
            RenderStatus.Done,
            studio.state.value.renders
                .single()
                .status,
        )
        assertEquals(
            listOf("Could not save the change to the Studio library", "Could not add the new videos to the Studio library"),
            failures.map { it.message },
        )
    }

    @Test
    fun `new Renders start from Settings' Export preferences`() {
        settings.ready(
            null,
            export =
                ExportSettings(
                    quality = ExportQuality.QHD1440,
                    frameRate = ExportFrameRate.FPS30,
                    ratio = ExportRatio.R4_5,
                    codec = ExportCodec.HEVC,
                    loudness = ExportLoudness.YOUTUBE,
                ),
        )

        val studio = store()

        assertEquals(RenderFormat(RenderAspect.R4_5, RenderSize.QHD1440, 30, codec = RenderCodec.HEVC), studio.state.value.renderFormat)
        assertEquals(RenderExtras(RenderLoudness.MINUS_14_LUFS), studio.state.value.renderExtras)
    }

    @Test
    fun `every Export preference maps to the Render's own choice`() {
        val sizes = ExportQuality.entries.map { renderSettingsOf(SettingsValues(export = ExportSettings(quality = it))).format.size }
        assertEquals(RenderSize.entries, sizes)
        val shapes = ExportRatio.entries.map { renderSettingsOf(SettingsValues(export = ExportSettings(ratio = it))).format.aspect }
        assertEquals(RenderAspect.entries, shapes)
        val levels = ExportLoudness.entries.map { renderSettingsOf(SettingsValues(export = ExportSettings(loudness = it))).extras.loudness }
        assertEquals(listOf(RenderLoudness.MINUS_14_LUFS, RenderLoudness.MINUS_11_LUFS, RenderLoudness.LEAVE_AS_IS), levels)
        assertEquals(RenderCodec.H264, renderSettingsOf(SettingsValues()).format.codec)
        assertEquals(60, renderSettingsOf(SettingsValues()).format.fps)
        assertEquals(
            RenderSettings(true, RenderFormat(), RenderExtras()),
            renderSettingsOf(SettingsValues(performance = PerformanceSettings(visualsOff = true))),
        )
    }

    @Test
    fun `progress reaches the reducer on each new phase and every half percent, with the time left`() {
        val reported = mutableListOf<StudioEvent>()
        var now = 0L
        val reporter = RenderProgressReporter(RenderId(1), { reported += it }, { Millis(now) })

        reporter.report(RenderPhase.ANALYSING, 0f)
        reporter.report(RenderPhase.ANALYSING, 0.004f)
        reporter.report(RenderPhase.RENDERING, 0.004f)
        now = 12_000
        reporter.report(RenderPhase.RENDERING, 0.25f)
        reporter.report(RenderPhase.RENDERING, 0.252f)
        reporter.report(RenderPhase.SAVING, 1f)
        reporter.report(RenderPhase.SAVING, 1f)

        assertEquals(
            listOf(
                StudioEvent.RenderProgress(RenderId(1), RenderPhase.ANALYSING, 0f, null),
                StudioEvent.RenderProgress(RenderId(1), RenderPhase.RENDERING, 0.004f, null),
                StudioEvent.RenderProgress(RenderId(1), RenderPhase.RENDERING, 0.25f, 36),
                StudioEvent.RenderProgress(RenderId(1), RenderPhase.SAVING, 1f, 0),
                StudioEvent.RenderProgress(RenderId(1), RenderPhase.SAVING, 1f, 0),
            ),
            reported,
        )
    }

    /** A store with the take in its default Project, the clip editor open on it, and [sources] chosen. */
    private fun editingTake(sources: SettingsEditSources): Studio {
        settings.ready(FolderUri("content://tree/videos"), export = ExportSettings(editSources = sources))
        val (studio, _) = projectWithTake()
        studio.send(
            EditCommand.OpenVideo(
                studio.state.value.videos
                    .single()
                    .id,
            ),
        )
        studio.send(EditCommand.ChangeClip(ClipEdit(startMs = 1_000L)))
        return studio
    }

    @Test
    fun `an Edit with Reference is queued pointing at its video, exported, and lands as an Edit`() {
        val studio = editingTake(SettingsEditSources.REFERENCE)

        studio.send(EditCommand.Export())

        val job =
            studio.state.value.renders
                .single()
        val edit = requireNotNull(job.edit)
        assertEquals(EditSources.REFERENCE, edit.sources)
        assertEquals(take.file, (edit.cut as EditCut.OneClip).source.file)
        assertEquals(ClipEdit(startMs = 1_000L), (edit.cut as EditCut.OneClip).edit)
        assertTrue(editVideos.copied.isEmpty())
        assertEquals(listOf(edit), editEngine.exported)
        assertEquals(RenderStatus.Done, job.status)
        val made =
            studio.state.value.videos
                .last()
        assertEquals(VideoKind.EDIT, made.kind)
        assertEquals("take", made.name)
        assertEquals(null, made.madeWith)
        assertEquals(FrozenLook(""), storage.rendersById[job.id]?.look)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `an Edit with Copy copies its video into the save folder and points at the copy`() {
        val studio = editingTake(SettingsEditSources.COPY)

        studio.send(EditCommand.Export())

        assertEquals(listOf(take.file to "content://tree/videos"), editVideos.copied)
        val edit =
            requireNotNull(
                studio.state.value.renders
                    .single()
                    .edit,
            )
        assertEquals(EditSources.COPY, edit.sources)
        assertEquals("content://tree/videos/copy-1", (edit.cut as EditCut.OneClip).source.file)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `an Edit whose videos cannot be copied is not queued, and says so`() {
        val studio = editingTake(SettingsEditSources.COPY)
        editVideos.broken = true

        studio.send(EditCommand.Export())

        assertTrue(
            studio.state.value.renders
                .isEmpty(),
        )
        assertTrue(storage.rendersById.isEmpty())
        assertEquals(listOf("Could not copy the Edit's videos, so it was not exported"), failures.map { it.message })
    }

    @Test
    fun `the Timeline opens on its saved cut, and every change is kept`() {
        settings.ready(null)
        val saved = EditorProject(Timeline(listOf(Lane(LaneId("lane-1"), LaneKind.Media, "Media 1"))))
        drafts.kept = saved
        val studio = store()

        studio.send(EditCommand.OpenCut)
        assertEquals(EditSession.Cut(EditorHistory(saved)), studio.state.value.edit)

        studio.send(EditCommand.AddLane(LaneKind.Text, "Text 1"))
        assertEquals(
            2,
            drafts.kept
                ?.timeline
                ?.lanes
                ?.size,
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a Timeline whose cut cannot be read opens empty and says so, and a change that cannot be kept is reported`() {
        settings.ready(null)
        drafts.broken = true
        val studio = store()

        studio.send(EditCommand.OpenCut)
        studio.send(EditCommand.AddLane(LaneKind.Text, "Text 1"))

        assertEquals(
            1,
            (studio.state.value.edit as EditSession.Cut)
                .project.timeline.lanes.size,
        )
        assertEquals(
            listOf("Could not read the saved cut, so the Timeline starts empty", "Could not save the last change to the cut"),
            failures.map { it.message },
        )
    }

    @Test
    fun `a picked video is read before the clip editor opens, and one that cannot be read says so`() {
        settings.ready(null)
        val studio = store()

        studio.send(EditCommand.OpenFile("content://picked/dune.mp4"))
        assertEquals(EditSession.OneClip(editVideos.readable), studio.state.value.edit)

        studio.send(EditCommand.OpenFile("content://picked/broken.mp4"))
        assertEquals(EditSession.Closed, studio.state.value.edit)
        assertEquals(listOf("Could not read that video"), failures.map { it.message })
    }

    @Test
    fun `an Edit's export and cut are kept with it in the queue's format`() {
        val clip =
            NewRender(
                emptyList(),
                RenderLook.Current,
                RenderFormat(),
                RenderLength.WholeTrack,
                ProjectId(3),
                RenderExtras(),
                edit =
                    EditJob(
                        "take",
                        EditCut.OneClip(editVideos.readable, ClipEdit(speed = 2f, lutUri = "content://lut")),
                        EditSources.COPY,
                    ),
            )
        assertEquals(clip, RenderSpecFormat.decode(RenderSpecFormat.encode(clip)))
        val project =
            EditorProject(
                Timeline(
                    listOf(
                        Lane(
                            LaneId("lane-1"),
                            LaneKind.Media,
                            "Media 1",
                            listOf(Clip(ClipId("clip-1"), ClipContent.Video("content://v/a"), 0L, 2_000L, sourceDurationMs = 5_000L)),
                        ),
                    ),
                    2_000L,
                ),
            )
        val cut = clip.copy(edit = EditJob("Cut", EditCut.Cut(project), EditSources.REFERENCE))
        assertEquals(cut, RenderSpecFormat.decode(RenderSpecFormat.encode(cut)))
        assertEquals(null, RenderSpecFormat.decode(RenderSpecFormat.encode(clip.copy(edit = null))).edit)
    }

    @Test
    fun `Settings' choice for an Edit's videos reaches Studio`() {
        assertEquals(EditSources.REFERENCE, renderSettingsOf(SettingsValues()).editSources)
        assertEquals(
            EditSources.COPY,
            renderSettingsOf(SettingsValues(export = ExportSettings(editSources = SettingsEditSources.COPY))).editSources,
        )
    }

    private fun projectWithTake(): Pair<Studio, ProjectId> {
        val studio = store()
        studio.send(StudioCommand.CreateProject("Trip", makeDefault = true))
        studio.send(StudioCommand.AddVideos(listOf(take)))
        return studio to
            studio.state.value.projects
                .single()
                .id
    }
}

/** Settings as Studio sees it: only its state is read. */
private class FakeSettings : Settings {
    private val mutable = MutableStateFlow<SettingsState>(SettingsState.Loading())
    override val state: StateFlow<SettingsState> = mutable
    override val events: Flow<Nothing> = emptyFlow()

    override fun send(command: SettingsCommand) = Unit

    fun ready(
        videoFolder: FolderUri?,
        visualsOff: Boolean = false,
        export: ExportSettings = ExportSettings(),
    ) {
        mutable.value =
            SettingsState.Ready(
                SettingsValues(
                    folders = FolderSettings(videoFolder = videoFolder),
                    performance = PerformanceSettings(visualsOff = visualsOff),
                    export = export,
                ),
            )
    }
}

/** Freezes each look as a numbered stand-in, and remembers what it was asked. */
private class FakeRenderLooks : RenderLooks {
    val frozen = mutableListOf<RenderLook>()
    var broken = false

    override suspend fun freeze(look: RenderLook): FrozenLook {
        check(!broken) { "no look" }
        frozen += look
        return FrozenLook("look ${frozen.size}")
    }
}

/**
 * Makes each Render at once, saving a video named after its Track, unless [hold] asks it to wait for
 * a gate; remembers each Render it was handed, and the last one's progress and cancel switches.
 */
private class FakeRenderEngine : RenderEngine {
    val made = mutableListOf<Pair<RenderJob, FrozenLook>>()
    var progress: ((RenderPhase, Float) -> Unit)? = null
    var cancelled: (() -> Boolean)? = null
    var broken = false
    var ending: RenderEnd? = null
    var onRender: () -> Unit = {}
    private var gate: CompletableDeferred<Unit>? = null

    fun hold(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gate = it }

    /** Later Renders are made at once again. */
    fun release() {
        gate = null
    }

    override suspend fun render(
        job: RenderJob,
        look: FrozenLook,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd {
        made += job to look
        this.progress = progress
        cancelled = isCancelled
        check(!broken) { "engine crashed" }
        onRender()
        gate?.await()
        if (isCancelled()) return RenderEnd.Cancelled
        val title = job.title
        return ending
            ?: RenderEnd.Saved(NewVideo(VideoKind.RENDER, title, "content://media/video/$title", null, Millis(30_000), 1920, 1080, title))
    }
}

/** A Render as the fake storage keeps it: the job as queued, its look, and its video once made. */
private data class StoredRender(
    val job: RenderJob,
    val look: FrozenLook,
    val file: String?,
)

private class FakeStudioStorage : StudioStorage {
    val projectsById = LinkedHashMap<ProjectId, Project>()
    val videosById = LinkedHashMap<VideoId, Video>()
    val rendersById = LinkedHashMap<RenderId, StoredRender>()
    private var default: ProjectId? = null
    var broken = false
    var loseLooks = false
    private var nextId = 1L

    private fun ensureWorking() = check(!broken) { "storage broken" }

    override suspend fun projects(): List<Project> {
        ensureWorking()
        return projectsById.values.sortedByDescending { it.createdAt.value * 1_000 + it.id.value }
    }

    override suspend fun videos(): List<Video> {
        ensureWorking()
        return videosById.values.toList()
    }

    override suspend fun defaultProject(): ProjectId? {
        ensureWorking()
        return default
    }

    override suspend fun createProject(
        name: String,
        createdAt: Millis,
        makeDefault: Boolean,
    ): Project {
        ensureWorking()
        val project = Project(ProjectId(nextId++), name, createdAt).also { projectsById[it.id] = it }
        if (makeDefault) default = project.id
        return project
    }

    override suspend fun renameProject(
        project: ProjectId,
        name: String,
    ) {
        ensureWorking()
        projectsById[project] = projectsById.getValue(project).copy(name = name)
    }

    override suspend fun setDefaultProject(project: ProjectId) {
        ensureWorking()
        default = project
    }

    override suspend fun deleteProject(
        project: ProjectId,
        moveTo: ProjectId?,
    ) {
        ensureWorking()
        projectsById.remove(project)
        if (moveTo == null) {
            videosById.values.removeAll { it.project == project }
        } else {
            videosById.replaceAll { _, video -> if (video.project == project) video.copy(project = moveTo) else video }
        }
        if (default == project) default = null
    }

    override suspend fun moveVideo(
        video: VideoId,
        to: ProjectId,
    ) {
        ensureWorking()
        videosById[video] = videosById.getValue(video).copy(project = to)
    }

    override suspend fun addVideos(
        project: ProjectId,
        videos: List<NewVideo>,
    ): List<Video> {
        ensureWorking()
        return videos.map {
            Video(VideoId(nextId++), project, it.kind, it.name, it.file, it.thumbnail, it.length, it.width, it.height, it.song, it.madeWith)
                .also { video -> videosById[video.id] = video }
        }
    }

    override suspend fun queueRenders(
        renders: List<NewRender>,
        look: FrozenLook,
        queuedAt: Millis,
    ): List<RenderJob> {
        ensureWorking()
        val batch = RenderId(nextId)
        return renders.map { render ->
            render.queuedAs(RenderId(nextId++), batch).also { rendersById[it.id] = StoredRender(it, look, null) }
        }
    }

    override suspend fun unfinishedRenders(): List<RenderJob> {
        ensureWorking()
        return unfinishedRendersNow()
    }

    fun unfinishedRendersNow(): List<RenderJob> = rendersById.values.filter { it.file == null }.map { it.job }

    override suspend fun lookOf(render: RenderId): FrozenLook? {
        ensureWorking()
        return if (loseLooks) null else rendersById[render]?.look
    }

    override suspend fun lookOfVideo(file: String): FrozenLook? {
        ensureWorking()
        return if (loseLooks) null else rendersById.values.lastOrNull { it.file == file }?.look
    }

    override suspend fun renderSaved(
        render: RenderId,
        file: String,
    ) {
        ensureWorking()
        rendersById[render] = rendersById.getValue(render).copy(file = file)
    }

    override suspend fun forgetRender(render: RenderId) {
        ensureWorking()
        rendersById.remove(render)
    }
}

/** Keeps the Timeline's cut in memory, or throws when [broken]. */
private class FakeEditDrafts : EditDrafts {
    var kept: EditorProject? = null
    var broken = false

    override suspend fun load(): EditorProject? {
        check(!broken) { "no draft" }
        return kept
    }

    override suspend fun save(project: EditorProject) {
        check(!broken) { "no draft" }
        kept = project
    }
}

/** Reads one picked file, and copies each video as a numbered copy in the folder, unless [broken]. */
private class FakeEditVideos : EditVideos {
    val readable = EditSource("content://picked/dune.mp4", "dune.mp4", Millis(5_000), 720, 1280)
    val copied = mutableListOf<Pair<String, String>>()
    var broken = false

    override suspend fun read(file: String): EditSource? = readable.takeIf { file == it.file }

    override suspend fun copy(
        file: String,
        folder: String,
    ): String {
        check(!broken) { "no room" }
        copied += file to folder
        return "$folder/copy-${copied.size}"
    }
}

/** Exports each Edit at once as a video named after it, remembering what it was handed. */
private class FakeEditEngine : EditEngine {
    val exported = mutableListOf<EditJob>()

    override suspend fun export(
        job: EditJob,
        format: RenderFormat,
        progress: (RenderPhase, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): RenderEnd {
        exported += job
        return RenderEnd.Saved(NewVideo(VideoKind.EDIT, job.name, "content://media/edit/${job.name}", null, Millis(3_000), 1080, 1920, ""))
    }
}

private class FakeVideoFiles : VideoFiles {
    val deleted = mutableListOf<String>()
    var refuse = false

    override suspend fun delete(file: String): Boolean {
        if (refuse) return false
        deleted += file
        return true
    }
}
