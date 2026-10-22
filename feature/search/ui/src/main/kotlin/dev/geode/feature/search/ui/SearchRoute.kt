package dev.geode.feature.search.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.search.api.Search
import dev.geode.feature.search.api.SearchCommand

/**
 * Search's place (ADR-0003), opened from Home's Search shortcut: the only part that touches the Search
 * store. It reads the store's state and turns the screen's actions into Commands. `:app` wires it at
 * the switch-over (#17); until then the old Search overlay stays in use.
 *
 * Leaving, by back, the close button or picking a song, a playlist or a preset, empties the field, so
 * Search opens empty next time, as today, and then calls [onClose], a move the app's navigation makes.
 */
@Composable
fun SearchRoute(
    search: Search,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by search.state.collectAsStateWithLifecycle()
    val leave = {
        search.send(SearchCommand.Clear)
        onClose()
    }
    BackHandler(onBack = leave)
    SearchScreen(
        state.toSearchModel(),
        onAction = { action ->
            action.toCommand()?.let(search::send)
            if (action.leaves) leave()
        },
        modifier,
    )
}
