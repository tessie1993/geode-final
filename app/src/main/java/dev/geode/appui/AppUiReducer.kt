package dev.geode.appui

import dev.geode.engine.types.Millis
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.StartTimer

/** How long a failure stays on the banner once drawn, as the old notice banner did. */
val BANNER_VISIBLE = Millis(8_000)

/** The banner's one timer: armed only while a failure is [Banner.OnScreen]. */
object BannerTimer

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
 * - **The app-wide banner** (ADR-0004): it hears every failure reported to the shared error channel
 *   ([AppUiWork.CollectFailures], started with the store) and shows one at a time, a newer one replacing
 *   the older. A failure waits until the banner has drawn it ([AppUiCommand.BannerShown]), then stays for
 *   [BANNER_VISIBLE], or until it is dismissed. One that replaces a failure on screen is on screen at
 *   once, so its time starts when it arrives.
 */
object AppUiReducer : Reducer<AppUiState, AppUiEvent, AppUiEffect> {
    /** What the store carries out once, when it is built: hear the shared error channel. */
    val start: List<AppUiEffect> = listOf(Perform(AppUiWork.CollectFailures))

    private val countdown: AppUiEffect = StartTimer(BannerTimer, BANNER_VISIBLE, AppUiEvent.BannerTimedOut)

    override fun reduce(
        state: AppUiState,
        event: AppUiEvent,
    ): Next<AppUiState, AppUiEffect> =
        when (event) {
            is AppUiEvent.Command -> command(state, event.command)
            is AppUiEvent.FailureReported -> reported(state, event.message)
            AppUiEvent.BannerTimedOut -> timedOut(state)
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
            AppUiCommand.BannerShown -> shown(state)
            AppUiCommand.DismissBanner -> dismissed(state)
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

    private fun reported(
        state: AppUiState,
        message: String,
    ): Next<AppUiState, AppUiEffect> =
        when (state.banner) {
            Banner.Hidden, is Banner.Waiting -> Next(state.with(banner = Banner.Waiting(message)))
            is Banner.OnScreen -> Next(state.with(banner = Banner.OnScreen(message)), listOf(countdown))
        }

    private fun shown(state: AppUiState): Next<AppUiState, AppUiEffect> =
        when (val banner = state.banner) {
            is Banner.Waiting -> Next(state.with(banner = Banner.OnScreen(banner.message)), listOf(countdown))
            Banner.Hidden, is Banner.OnScreen -> Next(state)
        }

    private fun dismissed(state: AppUiState): Next<AppUiState, AppUiEffect> =
        when (state.banner) {
            Banner.Hidden -> Next(state)
            is Banner.Waiting -> Next(state.with(banner = Banner.Hidden))
            is Banner.OnScreen -> Next(state.with(banner = Banner.Hidden), listOf(CancelTimer(BannerTimer)))
        }

    private fun timedOut(state: AppUiState): Next<AppUiState, AppUiEffect> =
        when (state.banner) {
            is Banner.OnScreen -> Next(state.with(banner = Banner.Hidden))
            Banner.Hidden, is Banner.Waiting -> Next(state)
        }
}
