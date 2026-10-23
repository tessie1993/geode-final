package dev.geode.feature.library.ui

import android.content.res.Configuration
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.design.element.ElementSkins
import dev.geode.design.element.ElementTheme
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Filter
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.GroupRow
import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Page
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongRow
import dev.geode.feature.library.api.Sort
import dev.geode.feature.library.api.SortKey

// The previews (ADR-0003), inside the :design theme: two Elements, so a preview shows the screen
// follows whichever is chosen.

@Composable
internal fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ElementTheme(if (dark) ElementSkins.Stone else ElementSkins.Water, content)
}

@Composable
internal fun LibraryPreview(
    model: LibraryModel,
    dark: Boolean = false,
) {
    PreviewTheme(dark) { LibraryScreen(model, onAction = {}) }
}

@Preview(name = "Songs")
@Composable
internal fun LibrarySongsPreview() = LibraryPreview(PreviewModels.songs)

@Preview(name = "Songs, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun LibrarySongsDarkPreview() = LibraryPreview(PreviewModels.songs, dark = true)

@Preview(name = "Songs, sorted and filtered")
@Composable
internal fun LibraryFilteredPreview() = LibraryPreview(PreviewModels.filtered)

@Preview(name = "Albums")
@Composable
internal fun LibraryAlbumsPreview() = LibraryPreview(PreviewModels.albums)

@Preview(name = "An album")
@Composable
internal fun LibraryAlbumPreview() = LibraryPreview(PreviewModels.album)

@Preview(name = "Folders")
@Composable
internal fun LibraryFoldersPreview() = LibraryPreview(PreviewModels.folders)

@Preview(name = "Playlists")
@Composable
internal fun LibraryPlaylistsPreview() = LibraryPreview(PreviewModels.playlists)

@Preview(name = "A smart playlist")
@Composable
internal fun LibrarySmartPlaylistPreview() = LibraryPreview(PreviewModels.smartPlaylist)

@Preview(name = "A smart playlist that picks nothing")
@Composable
internal fun LibraryEmptySmartPlaylistPreview() = LibraryPreview(PreviewModels.emptySmartPlaylist)

@Preview(name = "Search")
@Composable
internal fun LibrarySearchPreview() = LibraryPreview(PreviewModels.search)

@Preview(name = "Search, nothing found")
@Composable
internal fun LibraryNothingFoundPreview() = LibraryPreview(PreviewModels.nothingFound)

@Preview(name = "Scanning")
@Composable
internal fun LibraryScanningPreview() = LibraryPreview(PreviewModels.scanning)

@Preview(name = "Needs permission")
@Composable
internal fun LibraryNeedsPermissionPreview() = LibraryPreview(PreviewModels.needsPermission)

@Preview(name = "Song info editor")
@Composable
internal fun SongEditorPreview() =
    PreviewTheme(dark = false) {
        Surface { SongEditor(SongEditorFields.of(PreviewModels.night), onChange = {}, onPickArtwork = {}, Modifier) }
    }

@Preview(name = "Song info editor, chosen art, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SongEditorDarkPreview() =
    PreviewTheme(dark = true) {
        Surface {
            SongEditor(
                SongEditorFields.of(PreviewModels.night).copy(artwork = "content://preview/art"),
                onChange = {},
                onPickArtwork = {},
            )
        }
    }

@Preview(name = "Smart playlist editor")
@Composable
internal fun SmartPlaylistEditorPreview() =
    PreviewTheme(dark = false) {
        Surface { SmartPlaylistEditor(SmartPlaylistFields.of(PreviewModels.longFavourites), taken = emptySet(), onChange = {}) }
    }

@Preview(name = "Smart playlist editor, new, name taken, dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SmartPlaylistEditorDarkPreview() =
    PreviewTheme(dark = true) {
        Surface {
            SmartPlaylistEditor(
                SmartPlaylistFields.of(null).copy(name = "Long favourites"),
                taken = setOf("Long favourites"),
                onChange = {},
            )
        }
    }

/** The models the previews draw. */
internal object PreviewModels {
    private fun song(
        id: Int,
        title: String,
        artist: String,
        album: String,
        folder: String,
        seconds: Long,
    ) = Song(
        id = TrackId("content://preview/$id"),
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        genre = "Electronic",
        composer = "",
        path = "$folder/$title.flac",
        format = "flac",
        duration = Millis(seconds * MILLIS_PER_SECOND),
        addedAt = Millis(id.toLong()),
    )

    val night = song(1, "Night Drive", "Ann Arbor", "Roads", "/storage/emulated/0/Music/Roads", 215)
    private val tide = song(2, "Low Tide", "Bob Coast", "Coast", "/storage/emulated/0/Music/Coast", 187)
    private val lantern = song(3, "Lantern", "Ann Arbor", "Roads", "/storage/emulated/0/Music/Roads", 242)
    private val moss = song(4, "Moss and Stone", "Cleo Fern", "Undergrowth", "/storage/emulated/0/Music/Undergrowth", 3_725)
    private val voice = song(5, "Voice 014", "", "", "/storage/emulated/0/Recordings", 14)

    private val rows =
        listOf(
            SongRow(lantern, favourite = false, plays = 3),
            SongRow(tide, favourite = true, plays = 7),
            SongRow(moss, favourite = false, plays = 0),
            SongRow(night, favourite = true, plays = 12),
            SongRow(voice, favourite = false, plays = 0),
        )

    private val base =
        LibraryModel(
            index = IndexStatus.Ready,
            place = Place.Shelf(LibraryView.SONGS),
            query = "",
            sort = Sort(),
            filter = Filter(),
            page = Page.Songs(rows),
            formats = listOf("flac", "m4a", "mp3"),
            folders = listOf("/storage/emulated/0/Music/Coast", "/storage/emulated/0/Music/Roads"),
            excludedFolders = emptySet(),
        )

    private fun group(
        kind: GroupKind,
        name: String,
        detail: String,
        songs: Int,
    ) = GroupRow(GroupKey(kind, name), name.substringAfterLast('/'), detail, songs, artwork = null)

    val songs = base

    val filtered =
        base.copy(
            sort = Sort(SortKey.PLAYS, descending = true),
            filter = Filter(formats = setOf("flac"), hideShortClips = true),
            page = Page.Songs(rows.filter { it.song != voice }.sortedByDescending { it.plays }),
        )

    val albums =
        base.copy(
            place = Place.Shelf(LibraryView.ALBUMS),
            page =
                Page.Groups(
                    listOf(
                        group(GroupKind.ALBUM, "", "", 1),
                        group(GroupKind.ALBUM, "Coast", "Bob Coast", 1),
                        group(GroupKind.ALBUM, "Roads", "Ann Arbor", 2),
                        group(GroupKind.ALBUM, "Undergrowth", "Cleo Fern", 1),
                    ),
                ),
        )

    val album =
        base.copy(
            place = Place.Group(LibraryView.ALBUMS, GroupKey(GroupKind.ALBUM, "Roads")),
            page = Page.Songs(rows.filter { it.song.album == "Roads" }),
        )

    val folders =
        base.copy(
            place = Place.Shelf(LibraryView.FOLDERS),
            excludedFolders = setOf("/storage/emulated/0/Ringtones"),
            page =
                Page.Groups(
                    listOf(
                        group(GroupKind.FOLDER, "/storage/emulated/0/Music/Coast", "", 1),
                        group(GroupKind.FOLDER, "/storage/emulated/0/Music/Roads", "", 2),
                        group(GroupKind.FOLDER, "/storage/emulated/0/Recordings", "", 1),
                    ),
                ),
        )

    /** A smart playlist of the favourites at least three minutes long, at most 25 of them. */
    val longFavourites =
        SmartPlaylist(
            name = "Long favourites",
            rules =
                listOf(
                    SmartRule(RuleField.FAVOURITE, RuleOp.IS, ""),
                    SmartRule(RuleField.DURATION_MINUTES, RuleOp.AT_LEAST, "3"),
                ),
            matchAll = true,
            limit = 25,
        )

    private val neverPlayed = SmartPlaylist("Never played", listOf(SmartRule(RuleField.PLAY_COUNT, RuleOp.IS, "0")))

    val playlists =
        base.copy(
            place = Place.Shelf(LibraryView.PLAYLISTS),
            smartPlaylists = listOf(longFavourites, neverPlayed),
            page =
                Page.Groups(
                    listOf(
                        group(GroupKind.PLAYLIST, "7", "", 12).copy(title = "Evening walk"),
                        group(GroupKind.PLAYLIST, "3", "", 1).copy(title = "Focus"),
                        group(GroupKind.SMART_PLAYLIST, longFavourites.name, "", 2),
                        group(GroupKind.SMART_PLAYLIST, neverPlayed.name, "", 2),
                    ),
                ),
        )

    val smartPlaylist =
        playlists.copy(
            place = Place.Group(LibraryView.PLAYLISTS, GroupKey(GroupKind.SMART_PLAYLIST, longFavourites.name)),
            page = Page.Songs(rows.filter { it.favourite }),
        )

    val emptySmartPlaylist =
        playlists.copy(
            place = Place.Group(LibraryView.PLAYLISTS, GroupKey(GroupKind.SMART_PLAYLIST, neverPlayed.name)),
            page = Page.Songs(emptyList()),
        )

    val search =
        base.copy(
            query = "ann",
            page =
                Page.Found(
                    query = "ann",
                    songs = rows.filter { it.song.artist == "Ann Arbor" },
                    albums = emptyList(),
                    artists = listOf(group(GroupKind.ARTIST, "Ann Arbor", "", 2)),
                    folders = emptyList(),
                    playlists = listOf(group(GroupKind.PLAYLIST, "9", "", 4).copy(title = "Annual mix")),
                ),
        )

    val nothingFound =
        base.copy(query = "zz", page = Page.Found("zz", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))

    val scanning = base.copy(index = IndexStatus.Scanning, page = Page.Songs(emptyList()))

    val needsPermission = base.copy(index = IndexStatus.NeedsPermission, page = Page.Songs(emptyList()))

    private const val MILLIS_PER_SECOND = 1_000L
}
