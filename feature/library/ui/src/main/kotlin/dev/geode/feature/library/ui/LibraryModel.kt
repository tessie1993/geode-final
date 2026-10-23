package dev.geode.feature.library.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Filter
import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Page
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.library.api.Sort
import java.util.Locale

/**
 * Everything the Library screen draws (ADR-0003: an immutable screen model), read from the Library's
 * state. App UI only: nothing in it follows the music.
 */
@Immutable
data class LibraryModel(
    val index: IndexStatus,
    val place: Place,
    val query: String,
    val sort: Sort,
    val filter: Filter,
    val page: Page,
    /** The formats the songs come in, for the format filter. */
    val formats: List<String>,
    /** The folders songs are in, for the folder filter. */
    val folders: List<String>,
    val excludedFolders: Set<String>,
    /** The smart playlists, for their editor. */
    val smartPlaylists: List<SmartPlaylist> = emptyList(),
) {
    /** The view whose chip is selected. */
    val view: LibraryView
        get() =
            when (place) {
                is Place.Shelf -> place.view
                is Place.Group -> place.view
            }

    /** Whether Back leaves a search or a group, rather than the Library. */
    val canGoBack: Boolean get() = query.isNotEmpty() || place is Place.Group

    /** The smart playlist named [name], if there is one. */
    fun smartPlaylist(name: String): SmartPlaylist? = smartPlaylists.firstOrNull { it.name == name }

    /** The names a smart playlist may not take: every other one's. [editing] is the one being edited, if any. */
    fun takenSmartNames(editing: SmartPlaylist?): Set<String> = smartPlaylists.map { it.name }.filterNot { it == editing?.name }.toSet()

    companion object {
        val EMPTY = LibraryState().toLibraryModel()
    }
}

fun LibraryState.toLibraryModel(): LibraryModel =
    LibraryModel(
        index = index,
        place = place,
        query = query,
        sort = sort,
        filter = filter,
        page = page,
        formats = formats,
        folders = folders,
        excludedFolders = excludedFolders,
        smartPlaylists = smartPlaylists,
    )

/**
 * What the song info editor holds while it is open: App UI state (ADR-0003). [artwork] is a content
 * uri the user picked, or null for the file's own art.
 */
data class SongEditorFields(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: String?,
) {
    /** A song needs a title. */
    val canSave: Boolean get() = title.isNotBlank()

    fun toEdit(): SongEdit = SongEdit(title, artist, album, artwork)

    companion object {
        fun of(song: Song): SongEditorFields = SongEditorFields(song.title, song.artist, song.album, song.artwork)
    }
}

/**
 * What the smart playlist editor holds while it is open: App UI state (ADR-0003). [limit] is the text
 * typed, digits only; blank is no limit.
 */
data class SmartPlaylistFields(
    val name: String,
    val rules: List<SmartRule>,
    val matchAll: Boolean,
    val limit: String,
) {
    /** Whether another smart playlist has the name. */
    fun nameTaken(taken: Set<String>): Boolean = name.trim() in taken

    /** A smart playlist needs a name of its own. */
    fun canSave(taken: Set<String>): Boolean = name.isNotBlank() && !nameTaken(taken)

    fun toPlaylist(): SmartPlaylist = SmartPlaylist(name.trim(), rules, matchAll, limit.toIntOrNull() ?: 0)

    /** With one more rule, on the artist, as the old editor added them. */
    fun withNewRule(): SmartPlaylistFields = copy(rules = rules + SmartRule(RuleField.ARTIST, RuleOp.CONTAINS, ""))

    fun withRule(
        index: Int,
        rule: SmartRule,
    ): SmartPlaylistFields = copy(rules = rules.mapIndexed { i, old -> if (i == index) rule else old })

    fun withoutRule(index: Int): SmartPlaylistFields = copy(rules = rules.filterIndexed { i, _ -> i != index })

    /** With the limit typed as [text]; anything but digits is dropped. */
    fun withLimit(text: String): SmartPlaylistFields = copy(limit = text.filter(Char::isDigit))

    companion object {
        /** The editor for [playlist], or for a new smart playlist when it is null. */
        fun of(playlist: SmartPlaylist?): SmartPlaylistFields =
            SmartPlaylistFields(
                name = playlist?.name.orEmpty(),
                rules = playlist?.rules.orEmpty(),
                matchAll = playlist?.matchAll ?: true,
                limit =
                    playlist
                        ?.limit
                        ?.takeIf { it > 0 }
                        ?.toString()
                        .orEmpty(),
            )

        /** [rule] set to look at [field], with the comparison that field starts with. */
        fun withField(
            rule: SmartRule,
            field: RuleField,
        ): SmartRule = if (rule.field == field) rule else rule.copy(field = field, op = field.defaultOp)
    }
}

/** The one callback the Library screen sends (ADR-0003). */
sealed interface LibraryAction {
    /** A Command for the Library. */
    data class Send(
        val command: LibraryCommand,
    ) : LibraryAction

    /** Ask the phone for permission to read its music. A launcher, so the route's. */
    data object AskPermission : LibraryAction

    /** Pick an M3U file to import. A launcher, so the route's. */
    data object ImportPlaylist : LibraryAction

    /** Pick where to write [pick] as an M3U file, suggesting [name]. A launcher, so the route's. */
    data class ExportPlaylist(
        val pick: Pick,
        val name: String,
    ) : LibraryAction

    /** Delete [song]'s file from the phone, once the phone asks the person. A launcher, so the route's. */
    data class DeleteFile(
        val song: TrackId,
    ) : LibraryAction

    /** Pick an image as cover art. A launcher, so the route's; [onPicked] gets its content uri. */
    data class PickArtwork(
        val onPicked: (String) -> Unit,
    ) : LibraryAction
}

/** [millis] as a list prints a length: `3:07`, or `1:02:09` past an hour. */
fun formatLength(millis: Millis): String {
    val totalSeconds = millis.value.coerceAtLeast(0) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

/** Sizes of the Library screens, in one place, until the App UI's tokens move to `:design` (#61). */
internal object LibraryDimens {
    val gutter = 16.dp
    val gap = 12.dp
    val small = 8.dp
    val tiny = 4.dp
    val rowArtwork = 48.dp
    val rowIcon = 24.dp
    val headerArtwork = 96.dp
    val headerIcon = 48.dp
    val editorArtwork = 72.dp
}
