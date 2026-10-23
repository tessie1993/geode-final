package dev.geode.feature.library.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Filter
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.GroupRow
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Page
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.Play
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SHORT_CLIP
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.api.SongRow
import dev.geode.feature.library.api.Sort
import dev.geode.feature.library.api.SortKey
import dev.geode.feature.library.core.Fixtures.alpha
import dev.geode.feature.library.core.Fixtures.annSongs
import dev.geode.feature.library.core.Fixtures.beta
import dev.geode.feature.library.core.Fixtures.evening
import dev.geode.feature.library.core.Fixtures.gamma
import dev.geode.feature.library.core.Fixtures.morning
import dev.geode.feature.library.core.Fixtures.played
import dev.geode.feature.library.core.Fixtures.ready
import dev.geode.feature.library.core.Fixtures.smart
import dev.geode.feature.library.core.Fixtures.voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each place of the Library shows, sorted, filtered and searched. */
class LibraryPagesTest {
    private fun page(
        view: LibraryView,
        sort: Sort = Sort(),
        filter: Filter = Filter(),
        excluded: Set<String> = emptySet(),
    ): Page = LibraryPages.pageOf(ready.copy(place = Place.Shelf(view), sort = sort, filter = filter, excludedFolders = excluded))

    private fun songs(page: Page): List<Song> = (page as Page.Songs).rows.map { it.song }

    private fun names(page: Page): List<String> = (page as Page.Groups).rows.map { it.key.name }

    @Test
    fun `copies of one recording are grouped, whatever their case, punctuation or bracketed notes`() {
        val copy = alpha.copy(id = TrackId("content://a2"), title = "ALPHA! (Remastered)", artist = "ann", path = "/backup/alpha.mp3")
        val edge = alpha.copy(id = TrackId("content://a3"), path = "/old/alpha.mp3", duration = Millis(202_000))
        val tooLong = alpha.copy(id = TrackId("content://a4"), path = "/long/alpha.mp3", duration = Millis(204_001))

        val state = ready.copy(songs = listOf(alpha, beta, copy, edge, tooLong), place = Place.Shelf(LibraryView.DUPLICATES))

        val page = LibraryPages.pageOf(state)

        val groups = (page as Page.Duplicates).groups
        assertEquals(listOf("Alpha" to "Ann"), groups.map { it.title to it.artist })
        // By folder; the one more than two seconds longer than its nearest copy stands alone.
        assertEquals(listOf(copy, alpha, edge), groups.single().rows.map { it.song })
        assertEquals(SongRow(alpha, favourite = true, plays = 1), groups.single().rows[1])
    }

    @Test
    fun `different recordings are not duplicates, and groups go by title`() {
        val alpha2 = alpha.copy(id = TrackId("content://a2"), path = "/backup/alpha.mp3")
        val beta2 = beta.copy(id = TrackId("content://b2"), path = "/backup/beta.flac")
        val otherArtist = alpha.copy(id = TrackId("content://a3"), artist = "Amy", path = "/x/alpha.mp3")

        val state = ready.copy(songs = listOf(beta, alpha, beta2, alpha2, otherArtist), place = Place.Shelf(LibraryView.DUPLICATES))

        val page = LibraryPages.pageOf(state)

        assertEquals(listOf("Alpha", "beta"), (page as Page.Duplicates).groups.map { it.title })
        assertEquals(Page.Duplicates(emptyList()), LibraryPages.pageOf(ready.copy(place = Place.Shelf(LibraryView.DUPLICATES))))
    }

    @Test
    fun `the shown duplicates are every copy, in the order listed`() {
        val copy = alpha.copy(id = TrackId("content://a2"), path = "/backup/alpha.mp3")
        val state = ready.copy(songs = listOf(alpha, copy), place = Place.Shelf(LibraryView.DUPLICATES))

        val shown = LibraryPages.songsOf(state.copy(page = LibraryPages.pageOf(state)), Pick.Shown)

        assertEquals(listOf(copy, alpha), shown)
    }

    @Test
    fun `names are compared without case, punctuation or bracketed notes`() {
        assertEquals("alpha beta", normalised("  Alpha,  BETA [Live] "))
        assertEquals("song", normalised("Song (2011 Remaster)"))
    }

    @Test
    fun `songs are listed by name, ignoring case, with whether each is a favourite and how often it was played`() {
        assertEquals(
            Page.Songs(
                listOf(
                    SongRow(alpha, favourite = true, plays = 1),
                    SongRow(beta, favourite = false, plays = 2),
                    SongRow(gamma, favourite = false, plays = 0),
                    SongRow(voice, favourite = false, plays = 0),
                ),
            ),
            page(LibraryView.SONGS),
        )
    }

    @Test
    fun `songs sort by date added, length or plays, either way`() {
        assertEquals(listOf(beta, gamma, alpha, voice), songs(page(LibraryView.SONGS, Sort(SortKey.DATE_ADDED))))
        assertEquals(listOf(voice, alpha, gamma, beta), songs(page(LibraryView.SONGS, Sort(SortKey.DATE_ADDED, descending = true))))
        assertEquals(listOf(voice, beta, alpha, gamma), songs(page(LibraryView.SONGS, Sort(SortKey.LENGTH))))
        // Equal plays fall back to the name.
        assertEquals(listOf(gamma, voice, alpha, beta), songs(page(LibraryView.SONGS, Sort(SortKey.PLAYS))))
        assertEquals(listOf(voice, gamma, beta, alpha), songs(page(LibraryView.SONGS, Sort(SortKey.NAME, descending = true))))
    }

    @Test
    fun `songs with the same name keep a fixed order`() {
        val twin = alpha.copy(id = TrackId("content://a2"))
        val state = ready.copy(songs = listOf(twin, alpha))

        assertEquals(listOf(alpha, twin), songs(LibraryPages.pageOf(state)))
    }

    @Test
    fun `favourites, recently added, recently played and most played`() {
        assertEquals(listOf(alpha), songs(page(LibraryView.FAVOURITES)))
        assertEquals(listOf(voice, alpha, gamma, beta), songs(page(LibraryView.RECENTLY_ADDED)))
        assertEquals(listOf(beta, alpha), songs(page(LibraryView.RECENTLY_PLAYED)))
        assertEquals(listOf(beta, alpha), songs(page(LibraryView.MOST_PLAYED)))
    }

    @Test
    fun `songs added at the same time are listed by name`() {
        val state = ready.copy(songs = listOf(gamma.copy(addedAt = alpha.addedAt), alpha), place = Place.Shelf(LibraryView.RECENTLY_ADDED))

        assertEquals(listOf(alpha, gamma.copy(addedAt = alpha.addedAt)), songs(LibraryPages.pageOf(state)))
    }

    @Test
    fun `between songs played as often, the one played last comes first`() {
        val state =
            ready.copy(
                plays =
                    listOf(
                        Play(gamma.id, Millis(50)),
                        Play(alpha.id, Millis(40)),
                        Play(alpha.id, Millis(30)),
                        Play(gamma.id, Millis(20)),
                    ),
                place = Place.Shelf(LibraryView.MOST_PLAYED),
            )

        assertEquals(listOf(gamma, alpha), songs(LibraryPages.pageOf(state)))
    }

    @Test
    fun `recent and most played lists are only so long`() {
        val many =
            (0..RECENTLY_ADDED_LIMIT).map { alpha.copy(id = TrackId("content://$it"), title = "Song $it", addedAt = Millis(it.toLong())) }
        val state = ready.copy(songs = many, plays = many.map { Play(it.id, it.addedAt) })

        fun count(view: LibraryView) = (LibraryPages.pageOf(state.copy(place = Place.Shelf(view))) as Page.Songs).rows.size

        assertEquals(RECENTLY_ADDED_LIMIT, count(LibraryView.RECENTLY_ADDED))
        assertEquals(RECENTLY_PLAYED_LIMIT, count(LibraryView.RECENTLY_PLAYED))
        assertEquals(MOST_PLAYED_LIMIT, count(LibraryView.MOST_PLAYED))
    }

    @Test
    fun `albums list their album artist, how many songs, and artwork from any of them`() {
        assertEquals(
            Page.Groups(
                listOf(
                    GroupRow(GroupKey(GroupKind.ALBUM, ""), "", detail = "", songCount = 1, artwork = null),
                    GroupRow(GroupKey(GroupKind.ALBUM, "First"), "First", detail = "Ann", songCount = 2, artwork = "content://art/b"),
                    GroupRow(GroupKey(GroupKind.ALBUM, "Second"), "Second", detail = "Ann", songCount = 1, artwork = null),
                ),
            ),
            page(LibraryView.ALBUMS),
        )
    }

    @Test
    fun `artists, album artists, genres and composers group the songs by their tags`() {
        assertEquals(listOf("", "Ann", "Bob"), names(page(LibraryView.ARTISTS)))
        // An album artist falls back to the artist.
        assertEquals(listOf("", "Ann", "Various"), names(page(LibraryView.ALBUM_ARTISTS)))
        assertEquals(listOf("", "Jazz", "Rock"), names(page(LibraryView.GENRES)))
        assertEquals(listOf("", "Cole"), names(page(LibraryView.COMPOSERS)))
        assertEquals(listOf(2, 2), (page(LibraryView.COMPOSERS) as Page.Groups).rows.map { it.songCount })
        // Only albums name their album artist.
        assertEquals(listOf("", "", ""), (page(LibraryView.ARTISTS) as Page.Groups).rows.map { it.detail })
    }

    @Test
    fun `folders are titled by their last part`() {
        val folders = (page(LibraryView.FOLDERS) as Page.Groups).rows

        assertEquals(listOf("/music/jazz", "/music/rock", "/music/rock/live", "/recordings"), folders.map { it.key.name })
        assertEquals(listOf("jazz", "rock", "live", "recordings"), folders.map { it.title })
    }

    @Test
    fun `groups sort by name, newest song, length or plays`() {
        assertEquals(listOf("Second", "First", ""), names(page(LibraryView.ALBUMS, Sort(SortKey.NAME, descending = true))))
        assertEquals(listOf("Second", "First", ""), names(page(LibraryView.ALBUMS, Sort(SortKey.DATE_ADDED))))
        assertEquals(listOf("", "Second", "First"), names(page(LibraryView.ALBUMS, Sort(SortKey.LENGTH))))
        assertEquals(listOf("", "Second", "First"), names(page(LibraryView.ALBUMS, Sort(SortKey.PLAYS))))
        assertEquals(listOf("First", "Second", ""), names(page(LibraryView.ALBUMS, Sort(SortKey.PLAYS, descending = true))))
    }

    @Test
    fun `groups equal by the sort are listed by name`() {
        val state = ready.copy(songs = listOf(gamma, alpha.copy(addedAt = gamma.addedAt)), place = Place.Shelf(LibraryView.ALBUMS))

        assertEquals(listOf("First", "Second"), names(LibraryPages.pageOf(state.copy(sort = Sort(SortKey.DATE_ADDED)))))
        val sameLength = state.copy(songs = listOf(gamma, alpha.copy(duration = gamma.duration)), sort = Sort(SortKey.LENGTH))
        assertEquals(listOf("First", "Second"), names(LibraryPages.pageOf(sameLength)))
    }

    @Test
    fun `playlists are Playback's, in its order`() {
        assertEquals(
            Page.Groups(
                listOf(
                    GroupRow(GroupKey(GroupKind.PLAYLIST, "7"), "Evening", detail = "", songCount = 2, artwork = null),
                    GroupRow(GroupKey(GroupKind.PLAYLIST, "3"), "Morning", detail = "", songCount = 5, artwork = null),
                ),
            ),
            page(LibraryView.PLAYLISTS),
        )
        assertEquals(listOf(evening, morning), ready.playlists)
    }

    @Test
    fun `a group lists its songs in the sort's order`() {
        val first = GroupKey(GroupKind.ALBUM, "First")

        assertEquals(listOf(alpha, beta), songs(LibraryPages.pageOf(ready.copy(place = Place.Group(LibraryView.ALBUMS, first)))))
        assertEquals(
            listOf(beta, alpha),
            songs(LibraryPages.pageOf(ready.copy(place = Place.Group(LibraryView.ALBUMS, first), sort = Sort(SortKey.LENGTH)))),
        )
        assertEquals(
            listOf(alpha, gamma),
            songs(LibraryPages.pageOf(ready.copy(place = Place.Group(LibraryView.FOLDERS, GroupKey(GroupKind.COMPOSER, "Cole"))))),
        )
    }

    @Test
    fun `filters keep only some formats, one folder, or no short clips`() {
        assertEquals(listOf(alpha, gamma), songs(page(LibraryView.SONGS, filter = Filter(formats = setOf("mp3")))))
        assertEquals(listOf(alpha, gamma), songs(page(LibraryView.SONGS, filter = Filter(folder = "/music/rock"))))
        // A folder whose name only starts the same is another folder.
        assertEquals(emptyList<Song>(), songs(page(LibraryView.SONGS, filter = Filter(folder = "/music/ro"))))
        assertEquals(listOf(alpha, beta, gamma), songs(page(LibraryView.SONGS, filter = Filter(hideShortClips = true))))
    }

    @Test
    fun `a song exactly as long as a short clip is not one`() {
        val clip = voice.copy(duration = SHORT_CLIP)

        assertTrue(LibraryPages.passes(clip, Filter(hideShortClips = true)))
        assertFalse(LibraryPages.passes(clip.copy(duration = SHORT_CLIP - Millis(1)), Filter(hideShortClips = true)))
        assertTrue(LibraryPages.passes(clip.copy(duration = Millis(1)), Filter()))
    }

    @Test
    fun `excluded folders hide everything beneath them, everywhere`() {
        assertEquals(listOf(beta, voice), songs(page(LibraryView.SONGS, excluded = setOf("/music/rock"))))
        assertEquals(listOf(alpha, beta, voice), songs(page(LibraryView.SONGS, excluded = setOf("/music/rock/live"))))
        assertEquals(listOf("/music/jazz", "/recordings"), names(page(LibraryView.FOLDERS, excluded = setOf("/music/rock"))))
        assertEquals(listOf(alpha, beta, gamma, voice), songs(page(LibraryView.SONGS, excluded = setOf("/music/ro"))))
    }

    @Test
    fun `one search finds songs, albums, artists, folders and playlists`() {
        val byArtist = LibraryPages.pageOf(ready.copy(query = " ann "))

        assertEquals(
            Page.Found(
                query = "ann",
                songs = listOf(SongRow(alpha, favourite = true, plays = 1), SongRow(gamma, favourite = false, plays = 0)),
                albums = emptyList(),
                artists = listOf(GroupRow(GroupKey(GroupKind.ARTIST, "Ann"), "Ann", detail = "", songCount = 2, artwork = null)),
                folders = emptyList(),
                playlists = emptyList(),
            ),
            byArtist,
        )

        val byAlbum = LibraryPages.pageOf(ready.copy(query = "FIRST")) as Page.Found
        assertEquals(listOf(alpha, beta), byAlbum.songs.map { it.song })
        assertEquals(listOf("First"), byAlbum.albums.map { it.title })

        val byTitle = LibraryPages.pageOf(ready.copy(query = "gam")) as Page.Found
        assertEquals(listOf(gamma), byTitle.songs.map { it.song })

        val byFolder = LibraryPages.pageOf(ready.copy(query = "rock")) as Page.Found
        assertEquals(listOf("/music/rock", "/music/rock/live"), byFolder.folders.map { it.key.name })
        // Songs are found by their folder too, as the old search found them.
        assertEquals(listOf(alpha, gamma), byFolder.songs.map { it.song })

        val byPlaylist = LibraryPages.pageOf(ready.copy(query = "even")) as Page.Found
        assertEquals(listOf("Evening"), byPlaylist.playlists.map { it.title })
        assertFalse(byPlaylist.isEmpty)
        assertTrue((LibraryPages.pageOf(ready.copy(query = "zzz")) as Page.Found).isEmpty)
    }

    @Test
    fun `a search shows wherever the user is, and a blank one is no search`() {
        val inAlbum = ready.copy(place = Place.Group(LibraryView.ALBUMS, GroupKey(GroupKind.ALBUM, "Second")))

        assertTrue(LibraryPages.pageOf(inAlbum.copy(query = "a")) is Page.Found)
        assertEquals(listOf(gamma), songs(LibraryPages.pageOf(inAlbum.copy(query = "  "))))
    }

    @Test
    fun `a pick means the songs it names, a group's songs, or what the page lists`() {
        val excludedRock = Fixtures.shown(ready.copy(excludedFolders = setOf("/music/rock")))

        // Named songs are played even from an excluded folder; unknown ones are skipped.
        val named = Pick.Songs(listOf(gamma.id, TrackId("content://x"), alpha.id))
        assertEquals(listOf(gamma, alpha), LibraryPages.songsOf(excludedRock, named))
        val byLength = ready.copy(sort = Sort(SortKey.LENGTH))
        assertEquals(listOf(beta, alpha), LibraryPages.songsOf(byLength, Pick.Group(GroupKey(GroupKind.ALBUM, "First"))))
        assertEquals(emptyList<Song>(), LibraryPages.songsOf(ready, Pick.Group(GroupKey(GroupKind.PLAYLIST, "7"))))
        assertEquals(listOf(beta, voice), LibraryPages.songsOf(excludedRock, Pick.Shown))

        val found = Fixtures.shown(ready.copy(query = "ann"))
        assertEquals(listOf(alpha, gamma), LibraryPages.songsOf(found, Pick.Shown))

        val albums = Fixtures.shown(ready.copy(place = Place.Shelf(LibraryView.ALBUMS), sort = Sort(SortKey.LENGTH)))
        assertEquals(listOf(voice, beta, alpha, gamma), LibraryPages.songsOf(albums, Pick.Shown))
    }

    @Test
    fun `songs are grouped by the tag each kind of group names`() {
        assertEquals(
            listOf("First", "Ann", "Ann", "Rock", "Cole", "/music/rock", null, null),
            GroupKind.entries.map { LibraryPages.nameIn(alpha, it) },
        )
        assertEquals("Various", LibraryPages.nameIn(beta, GroupKind.ALBUM_ARTIST))
    }

    @Test
    fun `the formats and folders the songs come in`() {
        assertEquals(listOf("flac", "m4a", "mp3"), ready.formats)
        assertEquals(listOf("/music/jazz", "/music/rock", "/music/rock/live", "/recordings"), ready.folders)
        assertEquals(emptyList<String>(), LibraryState().formats)
    }

    @Test
    fun `smart playlists follow Playback's playlists, by name, with the songs their rules pick now`() {
        assertEquals(
            Page.Groups(
                listOf(
                    GroupRow(GroupKey(GroupKind.PLAYLIST, "7"), "Evening", detail = "", songCount = 2, artwork = null),
                    GroupRow(GroupKey(GroupKind.PLAYLIST, "3"), "Morning", detail = "", songCount = 5, artwork = null),
                    GroupRow(GroupKey(GroupKind.SMART_PLAYLIST, "Ann songs"), "Ann songs", detail = "", songCount = 2, artwork = null),
                    GroupRow(GroupKey(GroupKind.SMART_PLAYLIST, "played"), "played", "", songCount = 2, artwork = "content://art/b"),
                ),
            ),
            LibraryPages.pageOf(smart.copy(place = Place.Shelf(LibraryView.PLAYLISTS))),
        )
    }

    @Test
    fun `a smart playlist opens to its songs, sorted like any group`() {
        val inPlayed = smart.copy(place = Place.Group(LibraryView.PLAYLISTS, GroupKey(GroupKind.SMART_PLAYLIST, "played")))

        assertEquals(
            Page.Songs(listOf(SongRow(alpha, favourite = true, plays = 1), SongRow(beta, favourite = false, plays = 2))),
            LibraryPages.pageOf(inPlayed),
        )
        assertEquals(listOf(beta, alpha), songs(LibraryPages.pageOf(inPlayed.copy(sort = Sort(SortKey.PLAYS, descending = true)))))
        // One that is gone shows nothing.
        val gone = inPlayed.copy(place = Place.Group(LibraryView.PLAYLISTS, GroupKey(GroupKind.SMART_PLAYLIST, "Gone")))
        assertEquals(Page.Songs(emptyList()), LibraryPages.pageOf(gone))
    }

    @Test
    fun `a smart playlist's songs change as the Library does`() {
        val key = GroupKey(GroupKind.SMART_PLAYLIST, "Ann songs")

        assertEquals(listOf(alpha, gamma), LibraryPages.songsOf(smart, Pick.Group(key)))
        val renamed = smart.copy(songs = listOf(alpha, beta.copy(artist = "Ann"), gamma, voice))
        assertEquals(listOf(alpha, beta.copy(artist = "Ann"), gamma), LibraryPages.songsOf(renamed, Pick.Group(key)))
        // Hidden songs are left out, as everywhere in the Library.
        assertEquals(listOf(alpha), LibraryPages.songsOf(smart.copy(excludedFolders = setOf("/music/rock/live")), Pick.Group(key)))
        assertEquals(listOf(gamma), LibraryPages.songsOf(smart.copy(filter = Filter(folder = "/music/rock/live")), Pick.Group(key)))
        // Favourites and plays count as they are now.
        val playedKey = GroupKey(GroupKind.SMART_PLAYLIST, "played")
        assertEquals(
            listOf(gamma),
            LibraryPages.songsOf(smart.copy(favourites = setOf(gamma.id), plays = emptyList()), Pick.Group(playedKey)),
        )
    }

    @Test
    fun `a smart playlist's limit keeps the first songs by name, whatever the sort`() {
        val firstTwo = SmartPlaylist("Two", limit = 2)
        val state = ready.copy(smartPlaylists = listOf(firstTwo), sort = Sort(SortKey.LENGTH, descending = true))

        // By length alone the longest two would be Gamma and Alpha.
        assertEquals(listOf(alpha, beta), LibraryPages.songsOf(state, Pick.Group(GroupKey(GroupKind.SMART_PLAYLIST, "Two"))))
    }

    @Test
    fun `a smart playlist counts days from the Library's clock`() {
        val recent = SmartPlaylist("Recent", listOf(SmartRule(RuleField.ADDED_DAYS_AGO, RuleOp.AT_MOST, "1")))
        val key = GroupKey(GroupKind.SMART_PLAYLIST, "Recent")
        val state = ready.copy(smartPlaylists = listOf(recent))

        assertEquals(emptyList<Song>(), LibraryPages.songsOf(state, Pick.Group(key)))
        assertEquals(listOf(alpha, beta, gamma, voice), LibraryPages.songsOf(state.copy(now = Millis(5_000)), Pick.Group(key)))
    }

    @Test
    fun `a search finds smart playlists among the playlists`() {
        val found = LibraryPages.pageOf(smart.copy(query = "PLAY")) as Page.Found

        assertEquals(listOf(GroupKey(GroupKind.SMART_PLAYLIST, "played")), found.playlists.map { it.key })
        assertEquals(listOf(annSongs, played), smart.smartPlaylists)
    }
}
