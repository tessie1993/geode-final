package dev.geode.presentation

enum class SearchPhase { IDLE, LOADING, READY, FAILED }

data class SearchState<T>(
    val query: String = "",
    val revision: Long = 0,
    val phase: SearchPhase = SearchPhase.IDLE,
    val results: T? = null,
)

sealed interface SearchAction<out T> {
    data class Request(
        val query: String,
    ) : SearchAction<Nothing>

    data class Complete<T>(
        val revision: Long,
        val results: T,
    ) : SearchAction<T>

    data class Fail(
        val revision: Long,
    ) : SearchAction<Nothing>
}

object SearchReducer {
    fun <T> reduce(
        state: SearchState<T>,
        action: SearchAction<T>,
    ): SearchState<T> =
        when (action) {
            is SearchAction.Request ->
                SearchState(
                    query = action.query,
                    revision = state.revision + 1,
                    phase = if (action.query.isBlank()) SearchPhase.IDLE else SearchPhase.LOADING,
                )
            is SearchAction.Complete ->
                if (action.revision == state.revision && state.phase == SearchPhase.LOADING) {
                    state.copy(phase = SearchPhase.READY, results = action.results)
                } else {
                    state
                }
            is SearchAction.Fail ->
                if (action.revision == state.revision && state.phase == SearchPhase.LOADING) {
                    state.copy(phase = SearchPhase.FAILED, results = null)
                } else {
                    state
                }
        }
}
