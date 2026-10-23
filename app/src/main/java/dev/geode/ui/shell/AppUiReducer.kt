package dev.geode.ui.shell

import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Effect
import dev.geode.kernel.Emit
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.StartTimer

/** What reaches the App UI reducer: the shell's commands, failures from the error channel, and the banner's timer. */
sealed interface AppUiEvent {
    data class Command(
        val command: AppUiCommand,
    ) : AppUiEvent

    /** A feature reported [failure] to the app-wide error channel. */
    data class Failed(
        val failure: FeatureFailure,
    ) : AppUiEvent

    /** The banner [id] has been on show for [BANNER_TIME]. */
    data class BannerExpired(
        val id: Int,
    ) : AppUiEvent
}

/** The App UI's one piece of work: following the app-wide error channel for as long as the app runs. */
sealed interface AppUiWork {
    data object WatchFailures : AppUiWork
}

typealias AppUiEffect = Effect<AppUiWork, AppUiEvent, AppUiFact>

/**
 * The App UI reducer (#182). Pure: it decides where the person is and what covers it, and the
 * shell draws that.
 *
 * Back closes what is on top first: the open sheet, then the full-screen Visual, then any page goes
 * to Home, and Back on Home leaves the app (PR #186). A new failure replaces the one on show, and
 * each showing stays [BANNER_TIME] from when it appeared.
 */
object AppUiReducer : Reducer<AppUiState, AppUiEvent, AppUiEffect> {
    /** Following the error channel starts with the store. */
    val start: List<AppUiEffect> = listOf(Perform(AppUiWork.WatchFailures))

    /** The banner's timer; one at a time, since one failure shows at a time. */
    const val BANNER_TIMER = "banner"

    override fun reduce(
        state: AppUiState,
        event: AppUiEvent,
    ): Next<AppUiState, AppUiEffect> =
        when (event) {
            is AppUiEvent.Command -> command(state, event.command)
            is AppUiEvent.Failed -> show(state, event.failure)
            is AppUiEvent.BannerExpired ->
                if (state.banner?.id == event.id) Next(state.copy(banner = null)) else Next(state)
        }

    private fun command(
        state: AppUiState,
        command: AppUiCommand,
    ): Next<AppUiState, AppUiEffect> =
        when (command) {
            is AppUiCommand.Open ->
                if (command.place == state.place) Next(state) else Next(state.copy(place = command.place, sheet = null))
            AppUiCommand.Back -> back(state)
            AppUiCommand.EnterFullScreen -> Next(state.copy(fullScreen = true, sheet = null))
            AppUiCommand.ExitFullScreen -> Next(state.copy(fullScreen = false, sheet = null))
            is AppUiCommand.OpenSheet -> Next(state.copy(sheet = command.sheet))
            AppUiCommand.CloseSheet -> Next(state.copy(sheet = null))
            AppUiCommand.DismissBanner ->
                if (state.banner == null) Next(state) else Next(state.copy(banner = null), listOf(CancelTimer(BANNER_TIMER)))
        }

    private fun back(state: AppUiState): Next<AppUiState, AppUiEffect> =
        when {
            state.sheet != null -> Next(state.copy(sheet = null))
            state.fullScreen -> Next(state.copy(fullScreen = false))
            state.place != Place.HOME -> Next(state.copy(place = Place.HOME))
            else -> Next(state, listOf(Emit(AppUiFact.LeaveApp)))
        }

    private fun show(
        state: AppUiState,
        failure: FeatureFailure,
    ): Next<AppUiState, AppUiEffect> {
        val id = state.shown + 1
        return Next(
            state.copy(banner = Banner(id, failure.feature, failure.message), shown = id),
            listOf(StartTimer(BANNER_TIMER, BANNER_TIME, AppUiEvent.BannerExpired(id))),
        )
    }
}
