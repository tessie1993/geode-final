package dev.geode.feature.search.handlers

import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.library.api.Library
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.search.api.PresetResult
import dev.geode.feature.search.api.Search
import dev.geode.feature.search.api.SearchCommand
import dev.geode.feature.search.api.SearchState
import dev.geode.feature.search.core.SEARCH_FEATURE
import dev.geode.feature.search.core.SearchEvent
import dev.geode.feature.search.core.SearchReducer
import dev.geode.feature.search.core.SearchWork
import dev.geode.feature.search.core.WorkFailure
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FeatureRuntime
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * Carries out Search's work (ADR-0005): it follows the Library's songs, Playback's playlists and
 * [presets], and sends each pick to the Library, Playback or Customization, through their `:api` only.
 * A Command that throws is reported as a named event.
 *
 * [presets] is every preset, built in and saved, that can be applied, as `:app` lists them:
 * Customization's `:api` has no list of presets of its own yet, so the switch-over (#17) passes the
 * list the Visuals Presets browser (#144) shows.
 */
class SearchHandlers(
    private val library: Library,
    private val playback: Playback,
    private val customization: CustomizationFeature,
    private val presets: Flow<List<PresetResult>>,
) : EffectRunner<SearchWork, SearchEvent> {
    override suspend fun run(
        work: SearchWork,
        results: Results<SearchEvent>,
    ) {
        when (work) {
            SearchWork.FollowLibrary ->
                library.state
                    .map { SearchEvent.LibraryChanged(it.songs, it.excludedFolders) }
                    .distinctUntilChanged()
                    .collect { results.report(it) }
            SearchWork.FollowPlaylists ->
                playback.state
                    .map { it.playlists }
                    .distinctUntilChanged()
                    .collect { results.report(SearchEvent.PlaylistsChanged(it)) }
            SearchWork.FollowPresets ->
                presets
                    .distinctUntilChanged()
                    .collect { results.report(SearchEvent.PresetsChanged(it)) }
            is SearchWork.TellLibrary -> attempt(results, WorkFailure.LIBRARY_REFUSED) { library.send(work.command) }
            is SearchWork.TellPlayback -> attempt(results, WorkFailure.PLAYBACK_REFUSED) { playback.send(work.command) }
            is SearchWork.TellCustomization ->
                attempt(results, WorkFailure.CUSTOMIZATION_REFUSED) { customization.send(work.command) }
        }
    }

    /** Runs [block], turning anything it throws into a report of [failure]. Cancellation is passed on. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Handlers catch everything at their edge (ADR-0005).
    private inline fun attempt(
        results: Results<SearchEvent>,
        failure: WorkFailure,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (thrown: Exception) {
            results.report(SearchEvent.WorkFailed(failure))
        }
    }
}

/**
 * Search's one app-scoped store, as `:app` builds it when it wires the feature in (#17): over the app's
 * one Library, Playback and Customization stores, and the [presets] that can be applied. Following the
 * Library, Playback and the presets are its start effects.
 */
fun searchStore(
    library: Library,
    playback: Playback,
    customization: CustomizationFeature,
    presets: Flow<List<PresetResult>>,
    runtime: FeatureRuntime,
): Search =
    FeatureStore<SearchState, SearchCommand, SearchEvent, SearchWork, Nothing>(
        name = SEARCH_FEATURE,
        initial = SearchState(),
        reducer = SearchReducer,
        toEvent = SearchEvent::Command,
        runner = SearchHandlers(library, playback, customization, presets),
        runtime = runtime,
        start = SearchReducer.start,
    )
