package dev.geode.feature.search.core

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.Song
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.search.api.PresetResult
import dev.geode.feature.search.api.SearchCommand
import dev.geode.kernel.Effect

/** The name Search reports its failures under, on the app-wide error channel. */
const val SEARCH_FEATURE = "search"

/** How long typing must pause before the search runs, as today's Search waited. */
val SETTLE_DELAY = Millis(250)

/** The timer that runs the search once typing pauses. */
data object SettleTimer

/** Everything the reducer handles: the Commands, the settle timer, and what the handlers report back. */
sealed interface SearchEvent {
    data class Command(
        val command: SearchCommand,
    ) : SearchEvent

    /** Typing paused with [query] in the field. */
    data class Settled(
        val query: String,
    ) : SearchEvent

    /** The Library now holds [songs], with [excludedFolders] hidden from it. */
    data class LibraryChanged(
        val songs: List<Song>,
        val excludedFolders: Set<String>,
    ) : SearchEvent

    /** Playback now keeps [playlists]. */
    data class PlaylistsChanged(
        val playlists: List<PlaylistSummary>,
    ) : SearchEvent

    /** The presets that can be applied are now [presets]. */
    data class PresetsChanged(
        val presets: List<PresetResult>,
    ) : SearchEvent

    /** A piece of work failed at the handlers' edge; the reducer reports it. */
    data class WorkFailed(
        val failure: WorkFailure,
    ) : SearchEvent
}

/** Work the handlers could not carry out, named for the log and the app-wide banner. */
enum class WorkFailure(
    val message: String,
) {
    LIBRARY_REFUSED("could not ask the Library to play or queue a song found"),
    PLAYBACK_REFUSED("could not ask Playback to play a playlist found"),
    CUSTOMIZATION_REFUSED("could not ask Customization to apply a preset found"),
}

/** The handlers' work, carried out by `:feature:search:handlers`. */
sealed interface SearchWork {
    /** Follow the Library's songs for the life of the app; answered with [SearchEvent.LibraryChanged]. */
    data object FollowLibrary : SearchWork

    /** Follow Playback's playlists for the life of the app; answered with [SearchEvent.PlaylistsChanged]. */
    data object FollowPlaylists : SearchWork

    /** Follow the presets for the life of the app; answered with [SearchEvent.PresetsChanged]. */
    data object FollowPresets : SearchWork

    /** Send [command] to the Library. */
    data class TellLibrary(
        val command: LibraryCommand,
    ) : SearchWork

    /** Send [command] to Playback. */
    data class TellPlayback(
        val command: PlaybackCommand,
    ) : SearchWork

    /** Send [command] to Customization. */
    data class TellCustomization(
        val command: CustomizationCommand,
    ) : SearchWork
}

typealias SearchEffect = Effect<SearchWork, SearchEvent, Nothing>
