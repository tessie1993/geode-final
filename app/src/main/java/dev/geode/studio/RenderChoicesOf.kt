package dev.geode.studio

import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.ui.LookChoice
import dev.geode.feature.studio.ui.RenderChoices
import dev.geode.feature.studio.ui.RenderPlaylist
import dev.geode.feature.visuals.api.PresetShelf

/**
 * What the Render setup offers (#316, #325), read from the features that own it: the Library's songs,
 * by title, and its playlists, in their own order, and the presets the person saved. A playlist keeps
 * only the songs the Library still has.
 */
fun renderChoicesOf(
    library: LibraryState,
    presets: PresetShelf?,
): RenderChoices {
    val tracks =
        library.songs.map { song ->
            RenderTrack(song.id, song.title.ifBlank { song.path.substringAfterLast('/') }, song.duration)
        }
    val byId = tracks.associateBy { it.id }
    val playlists =
        library.playlists
            .map { playlist -> RenderPlaylist(playlist.name, playlist.songs.mapNotNull(byId::get)) }
            .filter { it.tracks.isNotEmpty() }
    val looks = presets?.saved.orEmpty().map { LookChoice(it.id, it.id.value) }
    return RenderChoices(tracks.sortedBy { it.title.lowercase() }, playlists, looks)
}
