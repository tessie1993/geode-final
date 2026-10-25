package dev.geode.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryPlaylist
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.PlaylistRef
import dev.geode.feature.library.api.Song
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The old Library screens draw the Library's state, and their calls become Library Commands (#17). */
class OldLibraryModelsTest {
    private fun song(
        name: String,
        folder: String = "/music",
    ) = Song(
        id = TrackId("content://media/$name"),
        title = name.uppercase(),
        artist = "Ann",
        album = "First",
        albumArtist = "",
        genre = "Jazz",
        composer = "",
        path = "$folder/$name.mp3",
        format = "mp3",
        duration = Millis(200_000),
        addedAt = Millis(86_400_000),
        artwork = "content://art/$name",
    )

    private val a = song("a")
    private val b = song("b")
    private val ringtone = song("ring", folder = "/ringtones/old")
    private val evening = LibraryPlaylist(PlaylistRef(7), "Evening", trackCount = 2)
    private val state =
        LibraryState(
            index = IndexStatus.Ready,
            songs = listOf(a, b, ringtone),
            excludedFolders = setOf("/ringtones"),
            playlists = listOf(evening),
        )

    @Test
    fun `the old lists show the Library's songs, its excluded folders left out`() {
        assertEquals(
            listOf(
                DeviceTrack(a.id, "A", "Ann", "First", "/music", Millis(200_000), addedSec = 86_400),
                DeviceTrack(b.id, "B", "Ann", "First", "/music", Millis(200_000), addedSec = 86_400),
            ),
            OldLibraryModels.deviceTracks(state),
        )
    }

    @Test
    fun `scanning shows only while the Library looks through the phone`() {
        assertTrue(OldLibraryModels.scanning(state.copy(index = IndexStatus.Scanning)))
        assertFalse(OldLibraryModels.scanning(state))
        assertFalse(OldLibraryModels.scanning(state.copy(index = IndexStatus.Loading)))
    }

    @Test
    fun `the old refresh asks the Library to look again only when it could not read the music`() {
        assertEquals(LibraryCommand.Rescan, OldLibraryModels.refresh(state.copy(index = IndexStatus.NeedsPermission)))
        assertNull(OldLibraryModels.refresh(state))
        assertNull(OldLibraryModels.refresh(state.copy(index = IndexStatus.Scanning)))
    }

    @Test
    fun `playing a list from a song is the Library's Play of those songs`() {
        assertEquals(LibraryCommand.Play(Pick.Songs(listOf(a.id, b.id)), b.id), OldLibraryModels.play(listOf(a.id, b.id), b.id))
        assertEquals(LibraryCommand.Play(Pick.Songs(listOf(a.id)), null), OldLibraryModels.play(listOf(a.id)))
    }

    @Test
    fun `a playlist named by the old screens is played and deleted by Playback's id`() {
        assertEquals(
            LibraryCommand.Play(Pick.Group(GroupKey(GroupKind.PLAYLIST, "7"))),
            OldLibraryModels.playPlaylist(state, "Evening"),
        )
        assertEquals(LibraryCommand.DeletePlaylist(PlaylistRef(7)), OldLibraryModels.deletePlaylist(state, "Evening"))
        assertNull(OldLibraryModels.playPlaylist(state, "Morning"))
        assertNull(OldLibraryModels.deletePlaylist(state, "Morning"))
    }

    @Test
    fun `a playlist named by the old screens is renamed, added to, reordered and cut by Playback's id`() {
        assertEquals(LibraryCommand.RenamePlaylist(PlaylistRef(7), "Dusk"), OldLibraryModels.renamePlaylist(state, "Evening", " Dusk "))
        assertEquals(
            LibraryCommand.AddToPlaylist(PlaylistRef(7), Pick.Songs(listOf(b.id))),
            OldLibraryModels.addToPlaylist(state, "Evening", listOf(b.id)),
        )
        assertEquals(LibraryCommand.MoveInPlaylist(PlaylistRef(7), 1, 0), OldLibraryModels.moveInPlaylist(state, "Evening", 1, 0))
        assertEquals(LibraryCommand.RemoveFromPlaylist(PlaylistRef(7), 1), OldLibraryModels.removeFromPlaylist(state, "Evening", 1))
        assertNull(OldLibraryModels.renamePlaylist(state, "Morning", "Dusk"))
        assertNull(OldLibraryModels.addToPlaylist(state, "Morning", listOf(b.id)))
        assertNull(OldLibraryModels.moveInPlaylist(state, "Morning", 1, 0))
        assertNull(OldLibraryModels.removeFromPlaylist(state, "Morning", 1))
    }

    @Test
    fun `a new playlist may start empty`() {
        assertEquals(LibraryCommand.CreatePlaylist("Later", Pick.Songs(emptyList())), OldLibraryModels.createPlaylist("Later", emptyList()))
    }

    @Test
    fun `a new playlist is made of the songs it was asked for, its name trimmed`() {
        assertEquals(
            LibraryCommand.CreatePlaylist("Night", Pick.Songs(listOf(b.id))),
            OldLibraryModels.createPlaylist("  Night ", listOf(b.id)),
        )
    }

    @Test
    fun `the old smart playlists count plays from Playback's history`() {
        val played =
            PlaybackState(
                history =
                    listOf(
                        PlayedTrack(Track(a.id, "A", "Ann", a.duration), Millis(3)),
                        PlayedTrack(Track(b.id, "B", "Ann", b.duration), Millis(2)),
                        PlayedTrack(Track(a.id, "A", "Ann", a.duration), Millis(1)),
                    ),
            )
        assertEquals(mapOf(a.id to 2, b.id to 1), OldLibraryModels.playsOf(played))
    }
}
