package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.UNSORTED_NAME
import dev.geode.feature.studio.core.OldVideo
import dev.geode.feature.studio.core.StudioEvent
import dev.geode.feature.studio.core.StudioFailure
import dev.geode.feature.studio.core.StudioReducer
import dev.geode.feature.studio.core.StudioWork
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Perform
import dev.geode.kernel.Results
import kotlinx.coroutines.CancellationException

/** The feature name failures are reported under. */
const val STUDIO = "studio"

/** The videos the old Studio saved on the phone (built alongside until the switch-over, #17). */
fun interface OldStudioVideos {
    suspend fun list(): List<OldVideo>
}

/** The files and folder permissions the user's Projects use. */
interface ProjectFiles {
    /** Deletes [file]; false when it could not. */
    suspend fun delete(file: String): Boolean

    /** Gives up the persisted permission to [folder]. */
    suspend fun release(folder: String)
}

/** Where Studio's handlers read and write, and the wall clock that stamps a new Project. */
class StudioSources(
    val storage: StudioStorage,
    val oldVideos: OldStudioVideos,
    val files: ProjectFiles,
    val now: () -> Millis,
)

/**
 * Carries out Studio's work (ADR-0005): the Projects and videos in Studio's database, the old
 * Studio's videos, and the files and folders of deleted Projects. Every failure is caught here and
 * reported as a named event.
 */
class StudioHandlers(
    private val sources: StudioSources,
) : EffectRunner<StudioWork, StudioEvent> {
    private val storage get() = sources.storage

    override suspend fun run(
        work: StudioWork,
        results: Results<StudioEvent>,
    ) {
        when (work) {
            StudioWork.Load ->
                guarded(results, StudioFailure.LOAD) {
                    val projects = storage.projects()
                    val videos = storage.videos()
                    // The library still shows when the old videos cannot be listed.
                    val old = attempt { sources.oldVideos.list() }.orEmpty()
                    results.report(StudioEvent.Loaded(projects, videos, old))
                }
            is StudioWork.CreateProject ->
                guarded(results, StudioFailure.SAVE) {
                    results.report(StudioEvent.ProjectCreated(storage.createProject(work.name, work.folder, sources.now())))
                }
            is StudioWork.RenameProject -> guarded(results, StudioFailure.SAVE) { storage.renameProject(work.project, work.name) }
            is StudioWork.MoveVideo -> guarded(results, StudioFailure.SAVE) { storage.moveVideo(work.video, work.to) }
            is StudioWork.DeleteProject -> delete(work, results)
            is StudioWork.Import ->
                guarded(results, StudioFailure.IMPORT) {
                    val project = work.into ?: storage.createProject(UNSORTED_NAME, null, sources.now())
                    results.report(StudioEvent.Imported(project, storage.addOldVideos(project.id, work.videos)))
                }
        }
    }

    private suspend fun delete(
        work: StudioWork.DeleteProject,
        results: Results<StudioEvent>,
    ) {
        if (attempt { storage.deleteProject(work.project) } == null) {
            // The Project is still saved: keep its files and folder too.
            return results.report(StudioEvent.Failed(StudioFailure.SAVE))
        }
        work.folder?.let { folder -> attempt { sources.files.release(folder) } }
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

/** How `:app` builds Studio's one app-scoped store (#17): it reads the library when built. */
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
        start = listOf(Perform(StudioWork.Load)),
    )
