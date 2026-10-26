package dev.geode.studio

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.LibraryPlaylist
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.PlaylistRef
import dev.geode.feature.library.api.Song
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.ui.LookChoice
import dev.geode.feature.studio.ui.RenderPlaylist
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.SavedPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the Render setup offers, read from the Library and the saved presets (#316, #325). */
class RenderChoicesOfTest {
    private fun song(
        id: String,
        title: String,
        path: String = "/Music/$id.mp3",
    ) = Song(TrackId(id), title, "", "", "", "", "", path, "mp3", Millis(200_000), Millis(1))

    private val tide = song("content://media/7", "Low Tide")
    private val night = song("content://media/12", "night drive")
    private val untitled = song("content://media/3", "", path = "/Music/Field recording.wav")

    @Test
    fun `songs come by title, playlists in their own order without songs the Library lost, presets by name`() {
        val library =
            LibraryState(
                songs = listOf(tide, night, untitled),
                playlists =
                    listOf(
                        LibraryPlaylist(PlaylistRef(1), "Evening", 3, listOf(night.id, TrackId("content://media/gone"), tide.id)),
                        LibraryPlaylist(PlaylistRef(2), "Lost", 1, listOf(TrackId("content://media/gone"))),
                    ),
            )
        val presets = PresetShelf(saved = listOf(SavedPreset(PresetId("Calm"), SceneId("fluid_ink"))))

        val choices = renderChoicesOf(library, presets)

        val tideTrack = RenderTrack(tide.id, "Low Tide", Millis(200_000))
        val nightTrack = RenderTrack(night.id, "night drive", Millis(200_000))
        val untitledTrack = RenderTrack(untitled.id, "Field recording.wav", Millis(200_000))
        assertEquals(listOf(untitledTrack, tideTrack, nightTrack), choices.tracks)
        assertEquals(listOf(RenderPlaylist("Evening", listOf(nightTrack, tideTrack))), choices.playlists)
        assertEquals(listOf(LookChoice(PresetId("Calm"), "Calm")), choices.presets)
        assertEquals(emptyList<LookChoice>(), renderChoicesOf(library, null).presets)
    }
}
