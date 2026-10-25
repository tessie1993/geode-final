package dev.geode.feature.library.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryFact
import dev.geode.feature.library.api.LibraryPlaylist
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Page
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
import dev.geode.feature.library.core.CLOCK_TICK
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Track
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Library end to end through its store: the saved index read and scanned again, tags filled in,
 * Commands to a fake Playback through its `:api`, Playback's playlists and history followed, playlist
 * files read and written, and failures on the app-wide error channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStoreTest {
    private val alpha = song("a", "Alpha", "/music/alpha.mp3")
    private val beta = song("b", "Beta", "/music/beta.flac")
    private val playback = FakePlayback()
    private val scanner = FakeScanner()
    private val storage = FakeLibraryStorage()
    private val files = FakePlaylistFiles()
    private var tags = TagReader.NONE
    private var writer = TagWriter.NONE
    private val failures = mutableListOf<FeatureFailure>()
    private val timers = FakeClock()
    private var wallClock = Millis(DAY)

    private fun store() =
        libraryStore(
            playback,
            LibrarySources(scanner, { tags.read(it) }, storage, files, clock = { wallClock }, tagWriter = {
                song,
                edit,
                ->
                writer.write(song, edit)
            }),
            inPlaceRuntime(timers = timers, failures = failures),
        )

    @Test
    fun `the saved index shows, then what the scan finds`() {
        storage.songs += alpha
        storage.favourites += alpha.id
        scanner.result = ScanResult.Found(listOf(alpha, beta))

        val library = store()

        assertEquals(IndexStatus.Ready, library.state.value.index)
        assertEquals(listOf(alpha, beta), library.state.value.songs)
        assertEquals(setOf(alpha.id), library.state.value.favourites)
        assertEquals(listOf(alpha, beta), storage.songs)
        assertEquals(1, scanner.scans)
    }

    @Test
    fun `the tag reader fills in what the media index left blank, once`() {
        tags = TagReader { FileTags(albumArtist = "Various", composer = "Cole", genre = "Rock") }
        scanner.result = ScanResult.Found(listOf(alpha, beta.copy(genre = "Jazz")))

        val library = store()

        val filled =
            listOf(
                alpha.copy(albumArtist = "Various", composer = "Cole", genre = "Rock"),
                beta.copy(albumArtist = "Various", composer = "Cole", genre = "Jazz"),
            )
        assertEquals(filled, library.state.value.songs)
        assertEquals(setOf(alpha.id, beta.id), storage.tagged)

        tags = TagReader { error("read twice") }
        library.send(LibraryCommand.Rescan)

        assertEquals(filled, library.state.value.songs)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a file the tag reader cannot read keeps what the media index found`() {
        tags = TagReader { error("not a music file") }
        scanner.result = ScanResult.Found(listOf(alpha))

        val library = store()

        assertEquals(listOf(alpha), library.state.value.songs)
        assertEquals(IndexStatus.Ready, library.state.value.index)
    }

    @Test
    fun `without permission the Library says so, and scans again when asked`() {
        scanner.result = ScanResult.NoPermission
        val library = store()
        assertEquals(IndexStatus.NeedsPermission, library.state.value.index)

        scanner.result = ScanResult.Found(listOf(beta))
        library.send(LibraryCommand.Rescan)

        assertEquals(IndexStatus.Ready, library.state.value.index)
        assertEquals(listOf(beta), library.state.value.songs)
    }

    @Test
    fun `a media index that cannot be read is reported`() {
        scanner.result = null

        val library = store()

        assertEquals(IndexStatus.Ready, library.state.value.index)
        assertEquals(listOf("Could not look through the music on this phone"), failures.map { it.message })
        assertEquals(listOf(LIBRARY), failures.map { it.feature }.distinct())
    }

    @Test
    fun `without its database the Library still shows what the scan finds`() {
        storage.failing = true
        scanner.result = ScanResult.Found(listOf(alpha))

        val library = store()

        assertEquals(listOf(alpha), library.state.value.songs)
        assertEquals(IndexStatus.Ready, library.state.value.index)
        assertEquals(listOf("Could not read the saved library", "Could not save the library"), failures.map { it.message })
    }

    @Test
    fun `playing anything is a Command to Playback`() {
        scanner.result = ScanResult.Found(listOf(alpha, beta))
        val library = store()

        library.send(LibraryCommand.Play(Pick.Shown, from = beta.id))
        library.send(LibraryCommand.PlayNext(Pick.Songs(listOf(alpha.id))))
        library.send(LibraryCommand.AddToQueue(Pick.Songs(listOf(beta.id))))
        library.send(LibraryCommand.DeletePlaylist(PlaylistRef(4)))

        assertEquals(
            listOf(
                PlaybackCommand.Play(listOf(track(alpha), track(beta)), startAt = 1),
                PlaybackCommand.PlayNext(listOf(track(alpha))),
                PlaybackCommand.AddToQueue(listOf(track(beta))),
                PlaybackCommand.DeletePlaylist(PlaylistId(4)),
            ),
            playback.sent,
        )
    }

    @Test
    fun `Playback's playlists and history are followed`() {
        scanner.result = ScanResult.Found(listOf(alpha))
        val library = store()

        playback.state.value =
            PlaybackState(
                playlists = listOf(PlaylistSummary(PlaylistId(2), "Evening", trackCount = 1, tracks = listOf(track(alpha)))),
                history = listOf(PlayedTrack(track(alpha), Millis(500))),
            )

        assertEquals(listOf(LibraryPlaylist(PlaylistRef(2), "Evening", 1, listOf(alpha.id))), library.state.value.playlists)
        assertEquals(listOf(Play(alpha.id, Millis(500))), library.state.value.plays)
    }

    @Test
    fun `favourites, edits and excluded folders are saved`() {
        scanner.result = ScanResult.Found(listOf(alpha))
        val library = store()

        library.send(LibraryCommand.ToggleFavourite(alpha.id))
        library.send(LibraryCommand.EditSong(alpha.id, SongEdit("Alpha (live)", "Ann", "Live")))
        library.send(LibraryCommand.ExcludeFolder("/ringtones"))

        assertEquals(setOf(alpha.id), storage.favourites)
        assertEquals(
            mapOf(alpha.id to SongEdit("Alpha (live)", "Ann", "Live", genre = alpha.genre, year = 0, trackNumber = 0, comment = "")),
            storage.edits,
        )
        assertEquals(setOf("/ringtones"), storage.excludedFolders)

        storage.failing = true
        library.send(LibraryCommand.ToggleFavourite(alpha.id))
        library.send(LibraryCommand.EditSong(alpha.id, SongEdit("A", "B", "C")))
        library.send(LibraryCommand.IncludeFolder("/ringtones"))

        assertEquals(
            listOf("Could not save the favourite", "Could not save the song info", "Could not save the excluded folders"),
            failures.map { it.message },
        )
    }

    @Test
    fun `an edit written into the file is saved once the file is written, and only then`() =
        runTest(UnconfinedTestDispatcher()) {
            scanner.result = ScanResult.Found(listOf(alpha))
            val written = mutableListOf<Pair<TrackId, SongEdit>>()
            var outcome = TagWrite.WRITTEN
            writer =
                TagWriter { song, edit ->
                    written += song to edit
                    outcome
                }
            val library = store()
            val facts = mutableListOf<LibraryFact>()
            val collecting = launch { library.events.collect { facts += it } }
            val tagged = SongEdit("Alpha", "Ann", "First", genre = "Soul", year = 1999, trackNumber = 3, comment = "live")

            library.send(LibraryCommand.EditSong(alpha.id, tagged, writeToFile = true))

            assertEquals(listOf(alpha.id to tagged), written)
            assertEquals(mapOf(alpha.id to tagged), storage.edits)
            assertEquals(
                alpha.copy(genre = "Soul", year = 1999, trackNumber = 3, comment = "live"),
                library.state.value.songs
                    .single(),
            )

            outcome = TagWrite.NEEDS_CONSENT
            library.send(LibraryCommand.EditSong(alpha.id, tagged.copy(title = "Alpha 2"), writeToFile = true))
            outcome = TagWrite.FAILED
            library.send(LibraryCommand.EditSong(alpha.id, tagged.copy(title = "Alpha 3"), writeToFile = true))
            writer = TagWriter { _, _ -> error("no file") }
            library.send(LibraryCommand.EditSong(alpha.id, tagged.copy(title = "Alpha 4"), writeToFile = true))

            assertEquals(listOf(LibraryFact.TagsNeedConsent(alpha.id, tagged.copy(title = "Alpha 2"))), facts)
            assertEquals(
                listOf("Could not write the song info into the file", "Could not write the song info into the file"),
                failures.map { it.message },
            )
            // Nothing that failed reached the Library.
            assertEquals(mapOf(alpha.id to tagged), storage.edits)
            assertEquals(
                "Alpha",
                library.state.value.songs
                    .single()
                    .title,
            )
            collecting.cancel()
        }

    @Test
    fun `an M3U file becomes a playlist in Playback, and a list becomes an M3U file`() =
        runTest(UnconfinedTestDispatcher()) {
            scanner.result = ScanResult.Found(listOf(alpha, beta))
            files.files["content://in.m3u"] = "#EXTM3U\n/music/beta.flac\n/gone.mp3\n"
            val library = store()
            val facts = mutableListOf<LibraryFact>()
            val collecting = launch { library.events.collect { facts += it } }

            library.send(LibraryCommand.ImportPlaylist("content://in.m3u", "Trip"))
            library.send(LibraryCommand.ExportPlaylist(Pick.Shown, "content://out.m3u"))

            assertEquals(listOf(PlaybackCommand.CreatePlaylist("Trip", listOf(track(beta)))), playback.sent)
            assertEquals(
                "#EXTM3U\n#EXTINF:180,Ann - Alpha\n/music/alpha.mp3\n#EXTINF:180,Ann - Beta\n/music/beta.flac\n",
                files.files["content://out.m3u"],
            )
            assertEquals(
                listOf(LibraryFact.PlaylistImported("Trip", found = 1, missing = 1), LibraryFact.PlaylistExported("content://out.m3u", 2)),
                facts,
            )
            collecting.cancel()
        }

    @Test
    fun `playlist files that cannot be read or written are reported`() {
        scanner.result = ScanResult.Found(listOf(alpha))
        val library = store()

        library.send(LibraryCommand.ImportPlaylist("content://missing.m3u", "Trip"))
        library.send(LibraryCommand.ExportPlaylist(Pick.Shown, FakePlaylistFiles.READ_ONLY + "out.m3u"))

        assertEquals(listOf("Could not read the playlist file", "Could not write the playlist file"), failures.map { it.message })
    }

    @Test
    fun `smart playlists are read, saved, renamed and deleted`() {
        val long = SmartPlaylist("Long", listOf(SmartRule(RuleField.DURATION_MINUTES, RuleOp.AT_LEAST, "3")))
        storage.smartPlaylists += long
        scanner.result = ScanResult.Found(listOf(alpha, beta))
        val library = store()
        assertEquals(listOf(long), library.state.value.smartPlaylists)

        val betas = SmartPlaylist("Betas", listOf(SmartRule(RuleField.TITLE, RuleOp.CONTAINS, "bet")))
        library.send(LibraryCommand.SaveSmartPlaylist(betas))
        library.send(LibraryCommand.SaveSmartPlaylist(long.copy(name = "Longer"), replacing = "Long"))
        assertEquals(listOf(betas, long.copy(name = "Longer")), storage.smartPlaylists)

        library.send(LibraryCommand.Show(LibraryView.PLAYLISTS))
        library.send(LibraryCommand.Open(GroupKey(GroupKind.SMART_PLAYLIST, "Betas")))
        assertEquals(listOf(beta), (library.state.value.page as Page.Songs).rows.map { it.song })

        library.send(LibraryCommand.DeleteSmartPlaylist("Betas"))
        assertEquals(listOf(long.copy(name = "Longer")), storage.smartPlaylists)
        assertEquals(Place.Shelf(LibraryView.PLAYLISTS), library.state.value.place)

        storage.failing = true
        library.send(LibraryCommand.SaveSmartPlaylist(betas))
        library.send(LibraryCommand.DeleteSmartPlaylist("Longer"))
        assertEquals(listOf("Could not save the smart playlist", "Could not delete the smart playlist"), failures.map { it.message })
    }

    @Test
    fun `a smart playlist plays the songs its rules pick when it is played`() {
        scanner.result = ScanResult.Found(listOf(alpha, beta))
        val library = store()
        val played = GroupKey(GroupKind.SMART_PLAYLIST, "Played")
        library.send(
            LibraryCommand.SaveSmartPlaylist(SmartPlaylist("Played", listOf(SmartRule(RuleField.PLAY_COUNT, RuleOp.AT_LEAST, "1")))),
        )

        library.send(LibraryCommand.Play(Pick.Group(played)))
        playback.state.value = PlaybackState(history = listOf(PlayedTrack(track(beta), Millis(500))))
        library.send(LibraryCommand.Play(Pick.Group(played)))

        assertEquals(listOf(PlaybackCommand.Play(listOf(track(beta)), startAt = 0)), playback.sent)
    }

    @Test
    fun `the clock is read at the start and every hour after, for the rules that count days`() {
        scanner.result = ScanResult.Found(listOf(alpha))
        val library = store()
        assertEquals(Millis(DAY), library.state.value.now)

        wallClock = Millis(DAY * 2)
        timers.advanceBy(Millis(CLOCK_TICK.value - 1))
        assertEquals(Millis(DAY), library.state.value.now)
        timers.advanceBy(Millis(1))
        assertEquals(Millis(DAY * 2), library.state.value.now)
        assertEquals(1, timers.pendingCount)
    }

    @Test
    fun `missing tags are filled in from the file's own`() {
        val bare = alpha.copy(albumArtist = "", composer = "", genre = "")

        assertEquals(bare.copy(albumArtist = "V", composer = "C", genre = "G"), bare.filledFrom(FileTags("V", "C", "G")))
        val pop = alpha.copy(genre = "Pop")
        assertEquals(pop.copy(albumArtist = "V", composer = "C"), pop.filledFrom(FileTags("V", "C", "G")))
        assertEquals(null, TagReader.NONE.read(alpha))
    }

    private fun track(song: Song) = Track(song.id, song.title, song.artist, song.duration)

    private companion object {
        const val DAY = 86_400_000L
    }

    private fun song(
        id: String,
        title: String,
        path: String,
    ) = Song(
        id = TrackId("content://$id"),
        title = title,
        artist = "Ann",
        album = "First",
        albumArtist = "",
        genre = "",
        composer = "",
        path = path,
        format = path.substringAfterLast('.'),
        duration = Millis(180_000),
        addedAt = Millis(1),
    )
}
