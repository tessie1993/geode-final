package dev.geode.feature.library.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Filter
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryFact
import dev.geode.feature.library.api.LibraryPlaylist
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.Play
import dev.geode.feature.library.api.PlaylistRef
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongEdit
import dev.geode.feature.library.api.Sort
import dev.geode.feature.library.api.SortKey
import dev.geode.feature.library.core.Fixtures.alpha
import dev.geode.feature.library.core.Fixtures.annSongs
import dev.geode.feature.library.core.Fixtures.beta
import dev.geode.feature.library.core.Fixtures.gamma
import dev.geode.feature.library.core.Fixtures.played
import dev.geode.feature.library.core.Fixtures.ready
import dev.geode.feature.library.core.Fixtures.shown
import dev.geode.feature.library.core.Fixtures.smart
import dev.geode.feature.library.core.Fixtures.voice
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.Track
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Library's Commands and the results that come back for them, as a transition table: each row is
 * a state, a Command, and the state and effects it leads to. Play Commands go to Playback.
 */
class LibraryCommandsTest {
    private val first = GroupKey(GroupKind.ALBUM, "First")
    private val evening = GroupKey(GroupKind.PLAYLIST, "7")
    private val unknown = TrackId("content://unknown")
    private val searching = shown(ready.copy(query = "ann"))
    private val inAlbums = shown(ready.copy(place = Place.Shelf(LibraryView.ALBUMS)))
    private val inFirst = shown(ready.copy(place = Place.Group(LibraryView.ALBUMS, first)))
    private val inArtist = shown(ready.copy(place = Place.Group(LibraryView.ARTISTS, GroupKey(GroupKind.ARTIST, "Ann"))))
    private val ringtonesHidden = shown(ready.copy(excludedFolders = setOf("/ringtones")))
    private val edit = SongEdit(" Beta 2 ", " Bo ", " Second ", " ")
    private val trimmed = SongEdit("Beta 2", "Bo", "Second", artwork = null)
    private val tagEdit = edit.copy(genre = " Soul ", year = 1999, trackNumber = 3, comment = " live ")
    private val tagsTrimmed = trimmed.copy(genre = "Soul", year = 1999, trackNumber = 3, comment = "live")
    private val betaTagged = edited(beta, artwork = null).copy(genre = "Soul", year = 1999, trackNumber = 3, comment = "live")
    private val m3u = "#EXTM3U\n/music/rock/alpha.mp3\n/elsewhere/missing.mp3\n"
    private val everything = tracks(alpha, beta, gamma, voice)
    private val playedKey = GroupKey(GroupKind.SMART_PLAYLIST, "played")
    private val annKey = GroupKey(GroupKind.SMART_PLAYLIST, "Ann songs")
    private val inSmartShelf = shown(smart.copy(place = Place.Shelf(LibraryView.PLAYLISTS)))
    private val inPlayed = shown(smart.copy(place = Place.Group(LibraryView.PLAYLISTS, playedKey)))
    private val inAnn = shown(smart.copy(place = Place.Group(LibraryView.PLAYLISTS, annKey)))
    private val long = SmartPlaylist("Long", listOf(SmartRule(RuleField.DURATION_MINUTES, RuleOp.AT_LEAST, "4")))
    private val heard = played.copy(name = "Heard")

    private val table: List<Row> =
        listOf(
            // Browsing.
            row(searching, LibraryCommand.Show(LibraryView.ALBUMS), inAlbums),
            row(inAlbums, LibraryCommand.Open(first), inFirst),
            row(inArtist, LibraryCommand.Open(first), ready.copy(place = Place.Group(LibraryView.ARTISTS, first))),
            row(searching, LibraryCommand.Open(first), ready.copy(place = Place.Group(LibraryView.SONGS, first))),
            row(inAlbums, LibraryCommand.Open(evening), inAlbums),
            row(inFirst, LibraryCommand.Back, inAlbums),
            row(searching, LibraryCommand.Back, ready),
            row(ready, LibraryCommand.Back, ready),
            row(ready, LibraryCommand.Search("ann"), searching),
            row(ready, LibraryCommand.SortBy(Sort(SortKey.LENGTH, descending = true)), ready.copy(sort = Sort(SortKey.LENGTH, true))),
            row(ready, LibraryCommand.FilterBy(Filter(hideShortClips = true)), ready.copy(filter = Filter(hideShortClips = true))),
            // Favourites, song info and folders, saved as they change.
            row(ready, LibraryCommand.ToggleFavourite(alpha.id), ready.copy(favourites = emptySet()), save(alpha.id, false)),
            row(ready, LibraryCommand.ToggleFavourite(beta.id), ready.copy(favourites = setOf(alpha.id, beta.id)), save(beta.id, true)),
            row(ready, LibraryCommand.ToggleFavourite(unknown), ready),
            row(
                ready,
                LibraryCommand.EditSong(beta.id, edit),
                ready.copy(songs = listOf(alpha, edited(beta, artwork = null), gamma, voice)),
                // What the edit leaves as it was is saved as the song has it.
                Perform(LibraryWork.SaveEdit(beta.id, unchangedTags(trimmed, beta))),
            ),
            row(
                ready,
                LibraryCommand.EditSong(alpha.id, edit.copy(artwork = "content://art/new")),
                ready.copy(songs = listOf(edited(alpha, artwork = "content://art/new"), beta, gamma, voice)),
                Perform(LibraryWork.SaveEdit(alpha.id, unchangedTags(trimmed.copy(artwork = "content://art/new"), alpha))),
            ),
            row(
                ready,
                LibraryCommand.EditSong(beta.id, tagEdit),
                ready.copy(songs = listOf(alpha, betaTagged, gamma, voice)),
                Perform(LibraryWork.SaveEdit(beta.id, tagsTrimmed)),
            ),
            row(
                ready,
                LibraryCommand.EditSong(beta.id, tagEdit.copy(year = -5)),
                ready.copy(songs = listOf(alpha, betaTagged.copy(year = 0), gamma, voice)),
                Perform(LibraryWork.SaveEdit(beta.id, tagsTrimmed.copy(year = 0))),
            ),
            // Written into the file first: the Library changes once the file has.
            row(
                ready,
                LibraryCommand.EditSong(beta.id, tagEdit, writeToFile = true),
                ready,
                Perform(LibraryWork.WriteTags(beta.id, tagsTrimmed)),
            ),
            row(ready, LibraryCommand.EditSong(beta.id, edit.copy(title = "  ")), ready),
            row(ready, LibraryCommand.EditSong(beta.id, edit.copy(title = "  "), writeToFile = true), ready),
            row(ready, LibraryCommand.EditSong(unknown, edit), ready),
            row(
                ready,
                LibraryCommand.ExcludeFolder("/ringtones"),
                ringtonesHidden,
                Perform(LibraryWork.SaveExcludedFolders(setOf("/ringtones"))),
            ),
            row(ready, LibraryCommand.ExcludeFolder(" "), ready),
            row(ringtonesHidden, LibraryCommand.ExcludeFolder("/ringtones"), ringtonesHidden),
            row(ringtonesHidden, LibraryCommand.IncludeFolder("/ringtones"), ready, Perform(LibraryWork.SaveExcludedFolders(emptySet()))),
            row(ready, LibraryCommand.IncludeFolder("/ringtones"), ready),
            // Playing goes to Playback.
            row(ready, LibraryCommand.Play(Pick.Shown, from = gamma.id), ready, tell(PlaybackCommand.Play(everything, 2))),
            row(ready, LibraryCommand.Play(Pick.Shown), ready, tell(PlaybackCommand.Play(everything, 0))),
            row(ready, LibraryCommand.Play(Pick.Group(first), from = unknown), ready, tell(PlaybackCommand.Play(tracks(alpha, beta), 0))),
            row(ready, LibraryCommand.Play(Pick.Group(evening)), ready, tell(PlaybackCommand.PlayPlaylist(PlaylistId(7)))),
            row(ready, LibraryCommand.Play(Pick.Group(GroupKey(GroupKind.PLAYLIST, "seven"))), ready),
            row(ready, LibraryCommand.Play(Pick.Songs(emptyList())), ready),
            // Shuffle turns shuffle on, then plays what was picked; nothing picked, nothing changes.
            row(
                ready,
                LibraryCommand.Shuffle(Pick.Shown),
                ready,
                tell(PlaybackCommand.SetShuffle(true)),
                tell(PlaybackCommand.Play(everything, 0)),
            ),
            row(
                ready,
                LibraryCommand.Shuffle(Pick.Group(evening)),
                ready,
                tell(PlaybackCommand.SetShuffle(true)),
                tell(PlaybackCommand.PlayPlaylist(PlaylistId(7))),
            ),
            row(ready, LibraryCommand.Shuffle(Pick.Songs(emptyList())), ready),
            row(
                ready,
                LibraryCommand.PlayNext(Pick.Songs(listOf(gamma.id, alpha.id))),
                ready,
                tell(PlaybackCommand.PlayNext(tracks(gamma, alpha))),
                Emit(LibraryFact.Queued(2, next = true)),
            ),
            row(
                ready,
                LibraryCommand.AddToQueue(Pick.Group(first)),
                ready,
                tell(PlaybackCommand.AddToQueue(tracks(alpha, beta))),
                Emit(LibraryFact.Queued(2, next = false)),
            ),
            row(ready, LibraryCommand.PlayNext(Pick.Songs(listOf(unknown))), ready),
            row(ready, LibraryCommand.AddToQueue(Pick.Group(evening)), ready),
            // Playlists are Playback's.
            row(
                ready,
                LibraryCommand.CreatePlaylist(" Mix ", Pick.Songs(listOf(beta.id))),
                ready,
                tell(PlaybackCommand.CreatePlaylist("Mix", tracks(beta))),
            ),
            row(ready, LibraryCommand.CreatePlaylist(" ", Pick.Songs(listOf(beta.id))), ready),
            // An empty one, to fill later.
            row(
                ready,
                LibraryCommand.CreatePlaylist("Mix", Pick.Songs(emptyList())),
                ready,
                tell(PlaybackCommand.CreatePlaylist("Mix", emptyList())),
            ),
            row(
                ready,
                LibraryCommand.RenamePlaylist(PlaylistRef(7), "Dusk"),
                ready,
                tell(PlaybackCommand.RenamePlaylist(PlaylistId(7), "Dusk")),
            ),
            row(
                ready,
                LibraryCommand.AddToPlaylist(PlaylistRef(7), Pick.Songs(listOf(gamma.id, unknown, alpha.id))),
                ready,
                tell(PlaybackCommand.AddToPlaylist(PlaylistId(7), tracks(gamma, alpha))),
            ),
            row(ready, LibraryCommand.AddToPlaylist(PlaylistRef(7), Pick.Songs(listOf(unknown))), ready),
            row(
                ready,
                LibraryCommand.MoveInPlaylist(PlaylistRef(7), from = 1, to = 0),
                ready,
                tell(PlaybackCommand.MoveInPlaylist(PlaylistId(7), from = 1, to = 0)),
            ),
            row(
                ready,
                LibraryCommand.RemoveFromPlaylist(PlaylistRef(7), index = 1),
                ready,
                tell(PlaybackCommand.RemoveFromPlaylist(PlaylistId(7), index = 1)),
            ),
            row(ready, LibraryCommand.DeletePlaylist(PlaylistRef(7)), ready, tell(PlaybackCommand.DeletePlaylist(PlaylistId(7)))),
            row(
                ready,
                LibraryCommand.ImportPlaylist("content://docs/Road trip.m3u", " "),
                ready,
                Perform(LibraryWork.ReadPlaylistFile("content://docs/Road trip.m3u", "Road trip")),
            ),
            row(
                ready,
                LibraryCommand.ImportPlaylist("content://docs/a.m3u", " Mine "),
                ready,
                Perform(LibraryWork.ReadPlaylistFile("content://docs/a.m3u", "Mine")),
            ),
            row(ready, LibraryCommand.ImportPlaylist(" ", "Mine"), ready),
            row(
                ready,
                LibraryCommand.ExportPlaylist(Pick.Songs(listOf(alpha.id, beta.id)), "content://docs/out.m3u"),
                ready,
                Perform(LibraryWork.WritePlaylistFile("content://docs/out.m3u", M3u.write(listOf(alpha, beta)), 2)),
            ),
            row(ready, LibraryCommand.ExportPlaylist(Pick.Songs(listOf(alpha.id)), " "), ready),
            row(ready, LibraryCommand.ExportPlaylist(Pick.Songs(emptyList()), "content://docs/out.m3u"), ready),
            // Smart playlists are the Library's own, kept by name; a name is trimmed and taken once.
            row(
                smart,
                LibraryCommand.SaveSmartPlaylist(long.copy(name = " Long ", limit = -2)),
                smart.copy(smartPlaylists = listOf(annSongs, long, played)),
                Perform(LibraryWork.SaveSmartPlaylist(long, replacing = null)),
            ),
            row(ready, LibraryCommand.SaveSmartPlaylist(long), ready.copy(smartPlaylists = listOf(long)), saveSmart(long, null)),
            row(smart, LibraryCommand.SaveSmartPlaylist(long.copy(name = "  ")), smart),
            row(smart, LibraryCommand.SaveSmartPlaylist(long.copy(name = "played")), smart),
            row(
                smart,
                LibraryCommand.SaveSmartPlaylist(played.copy(limit = 1), replacing = "played"),
                smart.copy(smartPlaylists = listOf(annSongs, played.copy(limit = 1))),
                saveSmart(played.copy(limit = 1), "played"),
            ),
            // Renamed while it is shown, it keeps showing.
            row(
                inPlayed,
                LibraryCommand.SaveSmartPlaylist(heard, replacing = "played"),
                smart.copy(
                    smartPlaylists = listOf(annSongs, heard),
                    place = Place.Group(LibraryView.PLAYLISTS, playedKey.copy(name = "Heard")),
                ),
                saveSmart(heard, "played"),
            ),
            row(
                inAnn,
                LibraryCommand.SaveSmartPlaylist(heard, replacing = "played"),
                inAnn.copy(smartPlaylists = listOf(annSongs, heard)),
                saveSmart(heard, "played"),
            ),
            row(inPlayed, LibraryCommand.SaveSmartPlaylist(heard.copy(name = "Ann songs"), replacing = "played"), inPlayed),
            row(
                smart,
                LibraryCommand.DeleteSmartPlaylist("played"),
                smart.copy(smartPlaylists = listOf(annSongs)),
                Perform(LibraryWork.DeleteSmartPlaylist("played")),
            ),
            // Deleted while it is shown, the Library goes back to its view.
            row(
                inPlayed,
                LibraryCommand.DeleteSmartPlaylist("played"),
                inSmartShelf.copy(smartPlaylists = listOf(annSongs)),
                Perform(LibraryWork.DeleteSmartPlaylist("played")),
            ),
            row(
                inAnn,
                LibraryCommand.DeleteSmartPlaylist("played"),
                inAnn.copy(smartPlaylists = listOf(annSongs)),
                Perform(LibraryWork.DeleteSmartPlaylist("played")),
            ),
            row(smart, LibraryCommand.DeleteSmartPlaylist("Gone"), smart),
            // A smart playlist opens to its songs, and plays, queues and saves them like any group.
            row(inSmartShelf, LibraryCommand.Open(annKey), inAnn),
            row(smart, LibraryCommand.Play(Pick.Group(annKey)), smart, tell(PlaybackCommand.Play(tracks(alpha, gamma), 0))),
            row(
                smart,
                LibraryCommand.AddToQueue(Pick.Group(playedKey)),
                smart,
                tell(PlaybackCommand.AddToQueue(tracks(alpha, beta))),
                Emit(LibraryFact.Queued(2, next = false)),
            ),
            row(
                smart,
                LibraryCommand.CreatePlaylist("Now", Pick.Group(annKey)),
                smart,
                tell(PlaybackCommand.CreatePlaylist("Now", tracks(alpha, gamma))),
            ),
        ) +
            listOf(
                // What the handlers report back.
                Row(
                    ready,
                    LibraryEvent.PlaylistFileRead("Trip", m3u),
                    Next(
                        ready,
                        listOf(
                            tell(PlaybackCommand.CreatePlaylist("Trip", tracks(alpha))),
                            Emit(LibraryFact.PlaylistImported("Trip", 1, 1)),
                        ),
                    ),
                ),
                Row(
                    ready,
                    LibraryEvent.PlaylistFileRead("Trip", "/elsewhere/missing.mp3"),
                    Next(ready, listOf(Emit(LibraryFact.PlaylistImported("Trip", 0, 1)))),
                ),
                Row(
                    ready,
                    LibraryEvent.PlaylistFileWritten("content://docs/out.m3u", 2),
                    Next(ready, listOf(Emit(LibraryFact.PlaylistExported("content://docs/out.m3u", 2)))),
                ),
                Row(
                    ready,
                    LibraryEvent.TagsWritten(beta.id, tagsTrimmed),
                    Next(
                        shown(ready.copy(songs = listOf(alpha, betaTagged, gamma, voice))),
                        listOf(Perform(LibraryWork.SaveEdit(beta.id, tagsTrimmed))),
                    ),
                ),
                // Fields left as they were keep what the song has, in the Library as in the file.
                Row(
                    ready,
                    LibraryEvent.TagsWritten(beta.id, trimmed),
                    Next(
                        shown(ready.copy(songs = listOf(alpha, edited(beta, artwork = null), gamma, voice))),
                        listOf(Perform(LibraryWork.SaveEdit(beta.id, unchangedTags(trimmed, beta)))),
                    ),
                ),
                Row(ready, LibraryEvent.TagsWritten(unknown, trimmed), Next(ready)),
                Row(
                    ready,
                    LibraryEvent.TagsNeedConsent(beta.id, tagsTrimmed),
                    Next(ready, listOf(Emit(LibraryFact.TagsNeedConsent(beta.id, tagsTrimmed)))),
                ),
                Row(
                    ready,
                    LibraryEvent.StorageFailed(StorageFailure.WRITE_TAGS),
                    Next(ready, listOf(ReportFailure("Could not write the song info into the file"))),
                ),
            )

    @Test
    fun `every Command does what the table says`() {
        for (row in table) {
            assertEquals("${row.event}", row.next, LibraryReducer.reduce(row.state, row.event))
        }
    }

    @Test
    fun `Playback's playlists and history are followed`() {
        val playlists = listOf(LibraryPlaylist(PlaylistRef(9), "Night", trackCount = 4))
        val plays = listOf(Play(gamma.id, Millis(99)))

        assertEquals(
            Next<LibraryState, LibraryEffect>(shown(ready.copy(playlists = playlists))),
            LibraryReducer.reduce(ready, LibraryEvent.PlaylistsChanged(playlists)),
        )
        assertEquals(
            Next<LibraryState, LibraryEffect>(shown(ready.copy(plays = plays))),
            LibraryReducer.reduce(ready, LibraryEvent.PlaysChanged(plays)),
        )
    }

    @Test
    fun `a song becomes a track as Playback plays it`() {
        assertEquals(Track(alpha.id, "Alpha", "Ann", alpha.duration), alpha.toTrack())
    }

    private fun edited(
        song: Song,
        artwork: String?,
    ) = song.copy(title = "Beta 2", artist = "Bo", album = "Second", artwork = artwork)

    /** [edit] with the fields it left as they were filled in from [song]. */
    private fun unchangedTags(
        edit: SongEdit,
        song: Song,
    ) = edit.copy(genre = song.genre, year = song.year, trackNumber = song.trackNumber, comment = song.comment)

    private fun tracks(vararg songs: Song) = songs.map { it.toTrack() }

    private fun tell(command: PlaybackCommand): LibraryEffect = Perform(LibraryWork.Tell(command))

    private fun saveSmart(
        playlist: SmartPlaylist,
        replacing: String?,
    ): LibraryEffect = Perform(LibraryWork.SaveSmartPlaylist(playlist, replacing))

    private fun save(
        song: TrackId,
        favourite: Boolean,
    ): LibraryEffect = Perform(LibraryWork.SaveFavourite(song, favourite))

    private fun row(
        state: LibraryState,
        command: LibraryCommand,
        next: LibraryState,
        vararg effects: LibraryEffect,
    ) = Row(state, LibraryEvent.Command(command), Next(shown(next), effects.toList()))

    private class Row(
        val state: LibraryState,
        val event: LibraryEvent,
        val next: Next<LibraryState, LibraryEffect>,
    )
}
