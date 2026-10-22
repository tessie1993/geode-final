package dev.geode.appui

import dev.geode.kernel.Next
import dev.geode.kernel.Reducer

/**
 * The App UI's state machine (#182, ADR-0006): one pure reducer in `:app`, like the features' (ADR-0003),
 * owning what the App UI shows. It does no IO and reads no clock.
 *
 * - **Navigation** (#136): Home, then at most one place or Settings. Showing a place closes the sheet
 *   and Search over the one before and leaves full-screen visual mode.
 * - **Back** closes what is on top first: the sheet, then full-screen visual mode, then Search; then
 *   it pops to Home. At Home with nothing open it does nothing, and the system's Back leaves the app
 *   ([AppUiState.canGoBack]).
 * - **Sheets**: one at a time, a new one replacing the open one. A sheet belongs to the screen it was
 *   opened over, so entering or leaving full-screen visual mode closes it.
 */
object AppUiReducer : Reducer<AppUiState, AppUiEvent, AppUiEffect> {
    override fun reduce(
        state: AppUiState,
        event: AppUiEvent,
    ): Next<AppUiState, AppUiEffect> =
        when (event) {
            is AppUiEvent.Command -> command(state, event.command)
            is AppUiEvent.FailureReported -> Next(state)
            AppUiEvent.BannerTimedOut -> Next(state)
        }

    private fun command(
        state: AppUiState,
        command: AppUiCommand,
    ): Next<AppUiState, AppUiEffect> =
        when (command) {
            is AppUiCommand.Navigate -> show(state, state.navigation.to(command.place))
            AppUiCommand.OpenSettings -> show(state, state.navigation.to(AppRoute.Settings))
            is AppUiCommand.ShowVisualsTab -> show(state, state.navigation.to(Place.VISUALS).copy(visualsTab = command.tab))
            AppUiCommand.Back -> back(state)
            is AppUiCommand.OpenSheet -> Next(state.with(sheet = command.sheet))
            AppUiCommand.CloseSheet -> Next(state.with(sheet = null))
            AppUiCommand.EnterFullScreen -> enterFullScreen(state)
            AppUiCommand.LeaveFullScreen -> leaveFullScreen(state)
            AppUiCommand.OpenSearch -> openSearch(state)
            AppUiCommand.CloseSearch -> Next(state.with(navigation = state.navigation.copy(searching = false)))
            AppUiCommand.BannerShown, AppUiCommand.DismissBanner -> Next(state)
            is AppUiCommand.Signal, is AppUiCommand.Restore -> Next(state)
        }

    /** Browsing [navigation], with nothing over it. */
    private fun show(
        state: AppUiState,
        navigation: Navigation,
    ): Next<AppUiState, AppUiEffect> = Next(AppUiState.Browsing(navigation.copy(searching = false), sheet = null, banner = state.banner))

    private fun back(state: AppUiState): Next<AppUiState, AppUiEffect> {
        val navigation = state.navigation
        return when {
            state.sheet != null -> Next(state.with(sheet = null))
            state is AppUiState.FullScreen -> leaveFullScreen(state)
            navigation.searching -> Next(state.with(navigation = navigation.copy(searching = false)))
            else -> Next(state.with(navigation = navigation.copy(backStack = navigation.backStack.take(1))))
        }
    }

    private fun enterFullScreen(state: AppUiState): Next<AppUiState, AppUiEffect> =
        when (state) {
            is AppUiState.Browsing -> Next(AppUiState.FullScreen(state.navigation, sheet = null, banner = state.banner))
            is AppUiState.FullScreen -> Next(state)
        }

    private fun leaveFullScreen(state: AppUiState): Next<AppUiState, AppUiEffect> =
        when (state) {
            is AppUiState.Browsing -> Next(state)
            is AppUiState.FullScreen -> Next(AppUiState.Browsing(state.navigation, sheet = null, banner = state.banner))
        }

    private fun openSearch(state: AppUiState): Next<AppUiState, AppUiEffect> =
        when (state) {
            is AppUiState.Browsing -> Next(state.copy(navigation = state.navigation.copy(searching = true), sheet = null))
            // Search is reached from the places, not from over the Visual.
            is AppUiState.FullScreen -> Next(state)
        }
}
