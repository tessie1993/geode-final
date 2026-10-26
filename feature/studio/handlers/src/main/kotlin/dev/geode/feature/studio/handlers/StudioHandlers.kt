package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.core.EditWork
import dev.geode.feature.studio.core.OLD_VIDEOS_PROJECT
import dev.geode.feature.studio.core.RecordingWork
import dev.geode.feature.studio.core.RenderEnd
import dev.geode.feature.studio.core.RenderEta
import dev.geode.feature.studio.core.StudioEvent
import dev.geode.feature.studio.core.StudioFailure
import dev.geode.feature.studio.core.StudioReducer
import dev.geode.feature.studio.core.StudioWork
import dev.geode.feature.studio.handlers.edit.EditTools
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Perform
import dev.geode.kernel.Results
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/** The feature name failures are reported under. */
const val STUDIO = "studio"

/** The name the import of the old Studio's videos is marked done under (#347). */
internal const val OLD_VIDEOS_IMPORT = "old-studio-videos"

/**
 * The old Studio's exports in `Movies/Geode` (#347), which Studio imports once after the upgrade. [list]
 * may throw; the import is then tried again next start.
 */
fun interface OldVideos {
    suspend fun list(): List<NewVideo>

    companion object {
        /** No old Studio: nothing to import. */
        val None: OldVideos = OldVideos { emptyList() }
    }
}

/** The video files the user's Projects hold. */
fun interface VideoFiles {
    /** Deletes [file]; false when it could not. */
    suspend fun delete(file: String): Boolean
}

/**
 * Where Studio's handlers read and write: its own [storage], the [saveFolder] it follows in Settings
 * > Default paths, the [files] of deleted videos, the wall clock that stamps a new Project and times a
 * Render, what the [renders] need (#338), the [recorder] of the live Visual (#317), what the editors need
 * ([edits], #318), and the old Studio's videos to import once ([oldVideos], #347).
 */
class StudioSources(
    val storage: StudioStorage,
    val saveFolder: Flow<SaveFolder>,
    val files: VideoFiles,
    val now: () -> Millis,
    val renders: RenderSources,
    val recorder: LiveRecorder = LiveRecorder.None,
    val edits: EditTools = EditTools(),
    val oldVideos: OldVideos = OldVideos.None,
)

/**
 * The save folder as Settings has it (Settings > Default paths, #101). Studio reads it through
 * Settings' interface only and never stores it itself.
 */
fun saveFolderOf(settings: Settings): Flow<SaveFolder> =
    settings.state
        .map { state ->
            when (state) {
                is SettingsState.Loading -> SaveFolder.Unknown
                is SettingsState.Ready ->
                    state.values.folders.videoFolder
                        ?.let { SaveFolder.Chosen(it.value) } ?: SaveFolder.NotChosen
            }
        }.distinctUntilChanged()

/**
 * Carries out Studio's work (ADR-0005): the Projects, videos and default Project in Studio's
 * database, the save folder from Settings, the files of deleted videos, and the Renders (#338): each
 * look is frozen as it is queued, each Render is saved before it waits, and the engine makes one at a
 * time as the reducer asks. Every failure is caught here and reported as a named event.
 */
class StudioHandlers(
    private val sources: StudioSources,
) : EffectRunner<StudioWork, StudioEvent> {
    private val storage get() = sources.storage

    // The Renders asked to stop; the engine reads it from its own thread.
    private val stopping: MutableSet<RenderId> = ConcurrentHashMap.newKeySet()

    private val recordings = RecordingHandlers(sources.recorder)

    private val edits = EditHandlers(sources)

    override suspend fun run(
        work: StudioWork,
        results: Results<StudioEvent>,
    ) {
        when (work) {
            StudioWork.Load ->
                guarded(results, StudioFailure.LOAD) {
                    results.report(
                        StudioEvent.Loaded(storage.projects(), storage.videos(), storage.defaultProject(), storage.unfinishedRenders()),
                    )
                }
            StudioWork.FollowSaveFolder -> sources.saveFolder.collect { results.report(StudioEvent.SaveFolderChanged(it)) }
            is StudioWork.CreateProject ->
                guarded(results, StudioFailure.SAVE) {
                    val project = storage.createProject(work.name, sources.now(), work.makeDefault)
                    results.report(StudioEvent.ProjectCreated(project, work.makeDefault))
                }
            is StudioWork.RenameProject -> guarded(results, StudioFailure.SAVE) { storage.renameProject(work.project, work.name) }
            is StudioWork.SetDefaultProject -> guarded(results, StudioFailure.SAVE) { storage.setDefaultProject(work.project) }
            is StudioWork.MoveVideo -> guarded(results, StudioFailure.SAVE) { storage.moveVideo(work.video, work.to) }
            is StudioWork.DeleteProject -> delete(work, results)
            is StudioWork.AddVideos ->
                guarded(results, StudioFailure.ADD_VIDEOS) {
                    results.report(StudioEvent.VideosAdded(work.project, storage.addVideos(work.project, work.videos)))
                }
            StudioWork.FollowRenderSettings -> sources.renders.settings.collect { results.report(StudioEvent.RenderSettingsChanged(it)) }
            StudioWork.FollowAppInFront -> sources.renders.inFront.collect { results.report(StudioEvent.AppInFront(it)) }
            is StudioWork.QueueRenders -> queue(work, results)
            is StudioWork.QueueRenderAgain -> queueAgain(work, results)
            is StudioWork.Render -> render(work.render, results)
            is StudioWork.StopRender -> stopping += work.render
            is StudioWork.ForgetRender -> guarded(results, StudioFailure.SAVE) { storage.forgetRender(work.render) }
            is RecordingWork -> recordings.run(work, results)
            is EditWork -> edits.run(work, results)
            StudioWork.ImportOldVideos -> importOldVideos(results)
        }
    }

    /**
     * Brings the old Studio's videos that the library does not hold yet into a Project of their own, once
     * (#347): Renders and Recordings Studio made are saved to `Movies/Geode` too, and are already there.
     * The import is marked done with what it brought, even nothing, so the videos appear once.
     */
    private suspend fun importOldVideos(results: Results<StudioEvent>) {
        val imported =
            attempt {
                if (storage.imported(OLD_VIDEOS_IMPORT)) return@attempt Imported(null)
                val known = storage.videos().mapTo(HashSet()) { it.file }
                val found =
                    sources.oldVideos
                        .list()
                        .filter { it.file !in known }
                        .distinctBy { it.file }
                Imported(storage.import(OLD_VIDEOS_IMPORT, OLD_VIDEOS_PROJECT, sources.now(), found))
            }
        val brought = imported?.videos
        when {
            imported == null -> results.report(StudioEvent.Failed(StudioFailure.IMPORT_OLD_VIDEOS))
            brought != null -> results.report(StudioEvent.OldVideosImported(brought.project, brought.videos))
        }
    }

    /** What an import came to: [videos] null when there was nothing to bring, or it was done before. */
    private class Imported(
        val videos: ImportedVideos?,
    )

    /** Freezes the look once, then saves the Renders with it: only a saved Render waits in the queue. */
    private suspend fun queue(
        work: StudioWork.QueueRenders,
        results: Results<StudioEvent>,
    ) {
        val first = work.renders.firstOrNull() ?: return
        val look = attempt { sources.renders.looks.freeze(first.look) }
        if (look == null) return results.report(StudioEvent.Failed(StudioFailure.FREEZE_LOOK))
        guarded(results, StudioFailure.QUEUE_RENDER) {
            results.report(StudioEvent.RendersQueued(storage.queueRenders(work.renders, look, sources.now())))
        }
    }

    /** Reads back the look frozen for the Render being made again, then saves the new Render with it (#327). */
    private suspend fun queueAgain(
        work: StudioWork.QueueRenderAgain,
        results: Results<StudioEvent>,
    ) {
        val look = attempt { storage.lookOfVideo(work.file) }
        if (look == null) return results.report(StudioEvent.Failed(StudioFailure.FREEZE_LOOK))
        guarded(results, StudioFailure.QUEUE_RENDER) {
            results.report(StudioEvent.RendersQueued(storage.queueRenders(listOf(work.render), look, sources.now())))
        }
    }

    /**
     * Makes [job]'s video with its frozen look. A saved Render is kept, so it can be made again; a
     * cancelled or failed one is forgotten, as it left no video. However it ends, the reducer hears.
     */
    private suspend fun render(
        job: RenderJob,
        results: Results<StudioEvent>,
    ) {
        val progress = RenderProgressReporter(job.id, results, sources.now)
        val end = attempt { made(job, progress) } ?: RenderEnd.Failed(RenderProblem.ENGINE_FAILED)
        stopping -= job.id
        val kept =
            attempt {
                if (end is RenderEnd.Saved) storage.renderSaved(job.id, end.video.file) else storage.forgetRender(job.id)
            }
        if (kept == null) results.report(StudioEvent.Failed(StudioFailure.SAVE))
        results.report(StudioEvent.RenderEnded(job.id, end))
    }

    /** [job]'s video: an Edit exported by the Edit pipeline (#318), or a Render drawn with its frozen look. */
    private suspend fun made(
        job: RenderJob,
        progress: RenderProgressReporter,
    ): RenderEnd {
        val stop = { job.id in stopping }
        val edit = job.edit
        if (edit != null) return sources.edits.engine.export(edit, job.format, progress::report, stop)
        val look = storage.lookOf(job.id) ?: return RenderEnd.Failed(RenderProblem.LOOK_UNAVAILABLE)
        return sources.renders.engine.render(job, look, progress::report, stop)
    }

    private suspend fun delete(
        work: StudioWork.DeleteProject,
        results: Results<StudioEvent>,
    ) {
        if (attempt { storage.deleteProject(work.project, work.moveTo) } == null) {
            // The Project is still saved: keep its files too.
            return results.report(StudioEvent.Failed(StudioFailure.SAVE))
        }
        val failed = work.files.count { attempt { sources.files.delete(it) } != true }
        if (failed > 0) results.report(StudioEvent.Failed(StudioFailure.DELETE_FILES))
    }

    /** [block]'s result, or null when it threw. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
    private suspend fun <T> attempt(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            null
        }

    private suspend fun guarded(
        results: Results<StudioEvent>,
        failure: StudioFailure,
        block: suspend () -> Unit,
    ) {
        if (attempt(block) == null) results.report(StudioEvent.Failed(failure))
    }
}

/**
 * How `:app` builds Studio's one app-scoped store (#17): it reads the library and starts following
 * the save folder when built.
 */
fun studioStore(
    sources: StudioSources,
    runtime: FeatureRuntime,
): Studio =
    FeatureStore<StudioState, StudioCommand, StudioEvent, StudioWork, StudioFact>(
        name = STUDIO,
        initial = StudioState(),
        reducer = StudioReducer,
        toEvent = StudioEvent::Command,
        runner = StudioHandlers(sources),
        runtime = runtime,
        start =
            listOf(
                Perform(StudioWork.Load),
                Perform(StudioWork.FollowSaveFolder),
                Perform(StudioWork.FollowRenderSettings),
                Perform(StudioWork.FollowAppInFront),
            ),
    )

/**
 * Hands a running Render's progress to the reducer with the time left: on every new phase, and
 * otherwise only each [STEP] of the way, so a long Render does not send an event every frame.
 */
internal class RenderProgressReporter(
    private val render: RenderId,
    private val results: Results<StudioEvent>,
    private val now: () -> Millis,
) {
    private val eta = RenderEta()
    private var phase: RenderPhase? = null
    private var reported = 0f

    @Synchronized
    fun report(
        phase: RenderPhase,
        progress: Float,
    ) {
        val clamped = progress.coerceIn(0f, 1f)
        val secondsLeft = eta.sample(clamped, now().value)
        if (phase == this.phase && clamped - reported < STEP && clamped < 1f) return
        this.phase = phase
        reported = clamped
        results.report(StudioEvent.RenderProgress(render, phase, clamped, secondsLeft))
    }

    private companion object {
        const val STEP = 0.005f
    }
}
