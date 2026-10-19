package dev.geode.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Latest request wins, including when a cancelled backend returns a late result. */
class LatestSearch<T>(
    scope: CoroutineScope,
    private val search: suspend (String) -> T,
) {
    private val mutableState = MutableStateFlow(SearchState<T>())
    val state = mutableState.asStateFlow()
    private val requests = MutableStateFlow(0L)

    init {
        scope.launch {
            requests.collectLatest { revision ->
                val request = state.value
                if (request.revision != revision || request.phase != SearchPhase.LOADING) return@collectLatest
                try {
                    delay(DEBOUNCE_MS)
                    val results = search(request.query)
                    mutableState.update { SearchReducer.reduce(it, SearchAction.Complete(revision, results)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { SearchReducer.reduce(it, SearchAction.Fail(revision)) }
                }
            }
        }
    }

    /** Call on the owning UI/controller thread, as with other presentation actions. */
    fun request(query: String) {
        val action = SearchAction.Request(query)
        mutableState.update { SearchReducer.reduce(it, action) }
        requests.value = state.value.revision
    }

    fun refresh() = request(state.value.query)

    private companion object {
        const val DEBOUNCE_MS = 250L
    }
}
