package dev.geode.ui.shell

import dev.geode.engine.types.Millis
import dev.geode.kernel.Feature

/**
 * The App UI's own state machine (#182): where the person is, what covers it, and the one failure the
 * banner shows. Features never read it; only the shell does. Screen-local state (a search text, a
 * scroll position) stays in Compose.
 */
typealias AppUi = Feature<AppUiState, AppUiCommand, AppUiFact>

/** The pages of the Bar, in the order it shows them (PR #186). The same for every person and every Element. */
enum class Place {
    MUSIC,
    VISUALS,
    HOME,
    STUDIO,
    SETTINGS,
    ;

    companion object {
        /** Where the app starts, and where Back goes. */
        val START = HOME

        /** The place saved as [name], or [START] when nothing matches (a place renamed or removed since). */
        fun named(name: String?): Place = entries.firstOrNull { it.name == name } ?: START
    }
}

/** What can open over a place or the full-screen Visual. At most one is open. */
enum class Sheet {
    /** The queue (Playback). */
    QUEUE,

    /** The listening tools: the A-B loop, the sleep timer, bookmarks (Playback). */
    LISTENING,

    /** Customize, over the live Visual (#12). */
    CUSTOMIZE,

    /** The Live input from the microphone, over the full-screen Visual (#17). */
    CAPTURE,

    /** Edit and export a video or the project, until Studio's own export lands (#17). */
    EXPORT,
}

/** A failure the banner shows; [id] tells one showing from the next, so a stale timer clears nothing. */
data class Banner(
    val id: Int,
    val feature: String,
    val message: String,
)

/**
 * The App UI's state. [place] is the Bar page on screen; [fullScreen] is the full-screen Visual over
 * it; [sheet] is what covers either; [banner] is the failure on show. [shown] counts the failures
 * shown so far, so each new one gets a fresh banner id.
 */
data class AppUiState(
    val place: Place = Place.START,
    val fullScreen: Boolean = false,
    val sheet: Sheet? = null,
    val banner: Banner? = null,
    val shown: Int = 0,
) {
    /**
     * Whether the player bar shows, with a song [loaded]: on every page except Home, and never over
     * the full-screen Visual, which has the full Player controls (owner, #190).
     */
    fun playerBarShown(loaded: Boolean): Boolean = loaded && !fullScreen && place != Place.HOME
}

/** What the shell asks of the App UI. */
sealed interface AppUiCommand {
    /** A Bar page was chosen. Choosing the page on screen changes nothing. */
    data class Open(
        val place: Place,
    ) : AppUiCommand

    /** System Back, or a screen's back button. */
    data object Back : AppUiCommand

    /** The player bar was tapped, or a place asked for full screen. */
    data object EnterFullScreen : AppUiCommand

    /** Full screen's leave button. */
    data object ExitFullScreen : AppUiCommand

    data class OpenSheet(
        val sheet: Sheet,
    ) : AppUiCommand

    /** The open sheet was swiped away or closed. */
    data object CloseSheet : AppUiCommand

    /** The banner was dismissed by hand. */
    data object DismissBanner : AppUiCommand
}

/** One-off facts for the shell. */
sealed interface AppUiFact {
    /** Back on Home: the app goes to the background (PR #186). */
    data object LeaveApp : AppUiFact
}

/** How long a failure stays on the banner unless it is dismissed or replaced (#182). */
val BANNER_TIME = Millis(8_000)
