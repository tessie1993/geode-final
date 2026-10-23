package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.StudioState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The feature name failures are reported under. */
const val STUDIO = "studio"

/** The video files the user's Projects hold. */
fun interface VideoFiles {
    /** Deletes [file]; false when it could not. */
    suspend fun delete(file: String): Boolean
}

/**
 * Where Studio's handlers read and write: its own [storage], the [saveFolder] it follows in Settings
 * > Default paths, the [files] of deleted videos, and the wall clock that stamps a new Project.
 */
class StudioSources(
    val storage: StudioStorage,
    val saveFolder: Flow<SaveFolder>,
    val files: VideoFiles,
    val now: () -> Millis,
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
 * database, the save folder from Settings, and the files of deleted videos. Every failure is caught
 * here and reported as a named event.
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
                    results.report(StudioEvent.Loaded(storage.projects(), storage.videos(), storage.defaultProject()))
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
        }
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
        start = listOf(Perform(StudioWork.Load), Perform(StudioWork.FollowSaveFolder)),
    )
