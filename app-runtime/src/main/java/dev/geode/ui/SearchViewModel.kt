package dev.geode.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.analysis.SearchMatcher
import dev.geode.data.MusicPlaylist
import dev.geode.data.Preset
import dev.geode.di.PlayerSessionProvider
import dev.geode.presentation.LatestSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SearchTrackRow(
    val uri: String,
    val title: String,
    val subtitle: String,
    val fields: List<String>,
    val fromDevice: Boolean,
)

data class SearchResults(
    val tracks: List<SearchTrackRow>,
    val playlists: List<MusicPlaylist>,
    val presets: List<Preset>,
)

/** Search work and playback commands belong to the controller, never a composable callback. */
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        sessions: PlayerSessionProvider,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val session = sessions.get()
        private val search = LatestSearch(viewModelScope, ::find)
        val state = search.state

        init {
            search.request(savedState[QUERY] ?: "")
            viewModelScope.launch {
                combine(
                    session.deviceTracks,
                    session.library,
                    session.vizState.map { it.presets }.distinctUntilChanged(),
                ) { _, _, _ -> Unit }.collect { search.refresh() }
            }
        }

        fun open() = session.refreshDeviceTracks()

        fun setQuery(query: String) {
            if (query == state.value.query) return
            savedState[QUERY] = query
            search.request(query)
        }

        fun playTrack(uri: String): Boolean {
            val tracks = state.value.results?.tracks ?: return false
            if (tracks.none { it.uri == uri }) return false
            session.playFrom(tracks.map { QueueTrack(it.uri, it.title, it.subtitle) }, uri)
            return true
        }

        fun enqueue(uri: String) {
            if (state.value.results
                    ?.tracks
                    ?.any { it.uri == uri } == true
            ) {
                session.enqueue(uri)
            }
        }

        fun playPlaylist(name: String): Boolean {
            if (state.value.results
                    ?.playlists
                    ?.none { it.name == name } != false
            ) {
                return false
            }
            session.playPlaylist(name)
            return true
        }

        fun applyPreset(preset: Preset): Boolean {
            if (state.value.results
                    ?.presets
                    ?.contains(preset) != true
            ) {
                return false
            }
            session.applyPreset(preset)
            return true
        }

        private suspend fun find(query: String): SearchResults {
            val device = session.deviceTracks.value
            val library = session.library.value
            val presets = session.vizState.value.presets
            return withContext(Dispatchers.Default) {
                val terms = SearchMatcher.terms(query)
                val candidates =
                    device.map { t ->
                        SearchTrackRow(
                            t.uri,
                            t.title,
                            listOf(t.artist, t.album)
                                .filter {
                                    it.isNotBlank()
                                }.joinToString(" · "),
                            listOf(t.title, t.artist, t.album, t.folder),
                            true,
                        )
                    } +
                        library.tracks.map { t ->
                            SearchTrackRow(
                                t.uri,
                                t.title,
                                listOf(t.artist, t.album)
                                    .filter {
                                        it.isNotBlank()
                                    }.joinToString(" · "),
                                listOf(t.title, t.artist, t.album, t.genre),
                                false,
                            )
                        }
                SearchResults(
                    tracks =
                        SearchMatcher.filterTracks(terms = terms, items = candidates, uriOf = {
                            it.uri
                        }, fieldsOf = { it.fields }, preferred = { it.fromDevice }),
                    playlists = library.playlists.filter { SearchMatcher.matches(terms, listOf(it.name)) },
                    presets = presets.filter { SearchMatcher.matches(terms, listOf(it.name)) },
                )
            }
        }

        private companion object {
            const val QUERY = "global_search_query"
        }
    }
