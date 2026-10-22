package dev.geode.feature.visuals.handlers

import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.feature.visuals.core.StyleEntries
import dev.geode.feature.visuals.core.VISUALS_FEATURE
import dev.geode.feature.visuals.core.VisualsEvent
import dev.geode.feature.visuals.core.VisualsReducer
import dev.geode.feature.visuals.core.VisualsWork
import dev.geode.feature.visuals.core.WorkFailure
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where the person's library is kept. Any call may throw; the handlers turn that into a named failure.
 */
interface VisualsStorage {
    /** The saved library; an empty one when nothing has been saved yet. */
    suspend fun load(): StyleLibrary

    /** Saves [library], replacing what was saved before. */
    suspend fun save(library: StyleLibrary)
}

/**
 * Carries out the Visuals feature's work (ADR-0005): it follows the Style Customization shows and asks
 * Customization for a Style, through Customization's `:api` only, and reads and writes the library in
 * [storage]. Saves are written one at a time, in the order they start. Everything that can fail is
 * caught here and reported as a named event.
 */
class VisualsHandlers(
    private val customization: CustomizationFeature,
    private val storage: VisualsStorage,
) : EffectRunner<VisualsWork, VisualsEvent> {
    private val writing = Mutex()

    override suspend fun run(
        work: VisualsWork,
        results: Results<VisualsEvent>,
    ) {
        when (work) {
            VisualsWork.FollowCurrentStyle ->
                customization.state
                    .map { it.current.style }
                    .distinctUntilChanged()
                    .collect { results.report(VisualsEvent.CurrentStyle(it)) }
            VisualsWork.LoadLibrary ->
                attempt(results, WorkFailure.LIBRARY_NOT_LOADED) { storage.load() }
                    ?.let { results.report(VisualsEvent.LibraryLoaded(it)) }
            is VisualsWork.SaveLibrary ->
                attempt(results, WorkFailure.LIBRARY_NOT_SAVED) { writing.withLock { storage.save(work.library) } }
            is VisualsWork.ChooseStyle ->
                attempt(results, WorkFailure.STYLE_NOT_CHOSEN) { customization.send(CustomizationCommand.ChooseStyle(work.style)) }
        }
    }

    /** Runs [block], turning anything it throws into a report of [failure]. Cancellation is passed on. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> attempt(
        results: Results<VisualsEvent>,
        failure: WorkFailure,
        block: suspend () -> T,
    ): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            results.report(VisualsEvent.WorkFailed(failure))
            null
        }
}

/**
 * The Visuals feature's one app-scoped store, as `:app` builds it when it wires the feature in (#17):
 * over the app's one Customization store, listing the Styles of [manifests]. Following Customization
 * and reading the library are its start effects.
 */
fun visualsStore(
    customization: CustomizationFeature,
    storage: VisualsStorage,
    manifests: List<StyleManifest>,
    runtime: FeatureRuntime,
): Visuals =
    FeatureStore<VisualsState, VisualsCommand, VisualsEvent, VisualsWork, VisualsFact>(
        name = VISUALS_FEATURE,
        initial = VisualsState(styles = StyleEntries.from(manifests)),
        reducer = VisualsReducer,
        toEvent = VisualsEvent::Command,
        runner = VisualsHandlers(customization, storage),
        runtime = runtime,
        start = VisualsReducer.start,
    )
