package dev.geode.appui

import dev.geode.kernel.Effect

/** What the App UI's screens, routes and the features ask of the App UI reducer (#182). */
sealed interface AppUiCommand {
    /** Shows [place], from the bar, the rail or anything that opens a place ("open Library"). */
    data class Navigate(
        val place: Place,
    ) : AppUiCommand

    /** Opens Settings above Home. */
    data object OpenSettings : AppUiCommand

    /** Shows the Visuals tab on [tab]. */
    data class ShowVisualsTab(
        val tab: VisualsTab,
    ) : AppUiCommand

    /** The system Back: closes what is on top, else pops the stack; at Home it does nothing. */
    data object Back : AppUiCommand

    data class OpenSheet(
        val sheet: AppSheet,
    ) : AppUiCommand

    data object CloseSheet : AppUiCommand

    data object EnterFullScreen : AppUiCommand

    data object LeaveFullScreen : AppUiCommand

    data object OpenSearch : AppUiCommand

    data object CloseSearch : AppUiCommand

    /** The banner has drawn its failure: its time on screen starts now. */
    data object BannerShown : AppUiCommand

    data object DismissBanner : AppUiCommand

    /** A front-end event a feature or a screen raises for the App UI; it goes out as [signal]. */
    data class Signal(
        val signal: AppSignal,
    ) : AppUiCommand

    /** Puts back what was open before Android killed the app (ADR-0004). The banner is never restored. */
    data class Restore(
        val saved: SavedAppUi,
    ) : AppUiCommand
}

/**
 * The plain app signals the App UI sends out (#180 decision 5): render done, track changed, tapped at
 * x,y, favourited. Each Element's module turns the ones it cares about into its own private events; the
 * app never knows which Element is applied. Never anything from the audio: the App UI does not react to
 * music.
 */
sealed interface AppSignal {
    data object RenderDone : AppSignal

    data object TrackChanged : AppSignal

    /** A tap at [x], [y], in pixels of the App UI's window. */
    data class Tapped(
        val x: Float,
        val y: Float,
    ) : AppSignal

    data object Favourited : AppSignal
}

/**
 * What survives Android killing the app (ADR-0004): the place shown, the Visuals sub-tab, the open
 * sheet, full-screen visual mode and Search. [AppUiState.saved] takes it; [AppUiCommand.Restore] puts
 * it back. [names] and [fromNames] save it by name, never by position, so a renamed or removed place
 * falls back to Home instead of opening something else.
 */
data class SavedAppUi(
    val route: AppRoute = Place.HOME,
    val visualsTab: VisualsTab = VisualsTab.STYLE,
    val sheet: AppSheet? = null,
    val fullScreen: Boolean = false,
    val searching: Boolean = false,
) {
    fun names(): List<String> =
        listOf(
            nameOf(route),
            visualsTab.name,
            sheet?.let(::nameOf).orEmpty(),
            fullScreen.toString(),
            searching.toString(),
        )

    companion object {
        private const val SETTINGS = "SETTINGS"
        private const val CUSTOMIZE = "CUSTOMIZE:"

        /** Reads [names] back; anything unknown or missing falls back to its default. */
        fun fromNames(names: List<String>): SavedAppUi =
            SavedAppUi(
                route = routeNamed(names.getOrNull(0)),
                visualsTab = VisualsTab.entries.firstOrNull { it.name == names.getOrNull(1) } ?: VisualsTab.STYLE,
                sheet = sheetNamed(names.getOrNull(2)),
                fullScreen = names.getOrNull(3) == true.toString(),
                searching = names.getOrNull(4) == true.toString(),
            )

        private fun nameOf(route: AppRoute): String =
            when (route) {
                is Place -> route.name
                AppRoute.Settings -> SETTINGS
            }

        private fun nameOf(sheet: AppSheet): String =
            when (sheet) {
                is AppSheet.Customize -> CUSTOMIZE + sheet.from.name
            }

        private fun routeNamed(name: String?): AppRoute =
            if (name == SETTINGS) AppRoute.Settings else Place.entries.firstOrNull { it.name == name } ?: Place.HOME

        private fun sheetNamed(name: String?): AppSheet? {
            val from = name?.removePrefix(CUSTOMIZE)?.takeIf { it != name } ?: return null
            return CustomizeEntry.entries.firstOrNull { it.name == from }?.let(AppSheet::Customize)
        }
    }
}

/** What [AppUiState] can be saved as: the parts of it that survive Android killing the app. */
fun AppUiState.saved(): SavedAppUi =
    SavedAppUi(
        route = navigation.current,
        visualsTab = navigation.visualsTab,
        sheet = sheet,
        fullScreen = this is AppUiState.FullScreen,
        searching = navigation.searching,
    )

/** Everything the App UI reducer handles: the commands, the failures it hears and its banner's timer. */
sealed interface AppUiEvent {
    data class Command(
        val command: AppUiCommand,
    ) : AppUiEvent

    /** A failure some feature reported to the shared error channel (ADR-0004). */
    data class FailureReported(
        val message: String,
    ) : AppUiEvent

    /** The banner's failure has been on screen for [BANNER_VISIBLE]. */
    data object BannerTimedOut : AppUiEvent
}

/** The App UI's only work: hearing the shared error channel for the life of the app. */
sealed interface AppUiWork {
    data object CollectFailures : AppUiWork
}

typealias AppUiEffect = Effect<AppUiWork, AppUiEvent, AppSignal>
