package dev.geode.library

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.data.SmartPlaylistStore
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.Library
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.Song
import dev.geode.feature.library.handlers.LibrarySources
import dev.geode.feature.library.handlers.MediaScanner
import dev.geode.feature.library.handlers.PlaylistFiles
import dev.geode.feature.library.handlers.ScanResult
import dev.geode.feature.library.handlers.TagReader
import dev.geode.feature.library.handlers.libraryStore
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.handlers.PlaybackClocks
import dev.geode.feature.playback.handlers.playbackStore
import dev.geode.feature.playback.ui.MiniPlayerRoute
import dev.geode.feature.playback.ui.PlayerRoute
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import dev.geode.playback.MemoryPlaybackStorage
import dev.geode.ui.OldScreenLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Library slice of the switch-over (#17): the old Library screens play through the one app-scoped
 * Library store, which hands the songs to Playback, and where the user is browsing lives in that store,
 * so it survives the places moving the way navigation moves them.
 *
 * Both stores are built as `:app` builds them, over in-memory storage and the audio adapter's fake. The
 * plain Application keeps Robolectric away from GeodeApp, which loads the engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LibraryJourneyRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Where { LIBRARY, PLAYER, FULL_SCREEN }

    private class PhoneMusic(
        songs: List<Song>,
    ) : MediaScanner {
        private val found = ScanResult.Found(songs)
        override val changes: Flow<Unit> = emptyFlow()

        override suspend fun scan(): ScanResult = found
    }

    private class NoFiles : PlaylistFiles {
        override suspend fun read(file: String): String = error("no files here")

        override suspend fun write(
            file: String,
            text: String,
        ) = error("no files here")
    }

    private class NoSettings : Settings {
        override val state = MutableStateFlow<SettingsState>(SettingsState.Loading())
        override val events: Flow<Nothing> = emptyFlow()

        override fun send(command: SettingsCommand) = Unit
    }

    private fun song(
        name: String,
        album: String,
    ) = Song(
        id = TrackId("content://media/$name"),
        title = name,
        artist = "Ann",
        album = album,
        albumArtist = "",
        genre = "",
        composer = "",
        path = "/music/$name.mp3",
        format = "mp3",
        duration = Millis(200_000),
        addedAt = Millis.ZERO,
    )

    private val alpha = song("Alpha", "First")
    private val beta = song("Beta", "First")
    private val gamma = song("Gamma", "Second")
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val audio = FakeAudioAdapter()
    private val playback: Playback =
        playbackStore(audio, MemoryPlaybackStorage(), PlaybackClocks({ clock.now }, { clock.now }), inPlaceRuntime(clock, failures))
    private val library: Library =
        libraryStore(
            playback,
            LibrarySources(PhoneMusic(listOf(alpha, beta, gamma)), TagReader.NONE, MemoryLibraryStorage(), NoFiles()),
            inPlaceRuntime(failures = failures),
        )
    private val app = RuntimeEnvironment.getApplication()
    private val oldScreens =
        OldScreenLibrary(library, playback, NoSettings(), SmartPlaylistStore(app), CoroutineScope(UnconfinedTestDispatcher()))

    private val where: MutableState<Where> = mutableStateOf(Where.LIBRARY)

    @Test
    fun `a playlist made empty in the old Library is filled, reordered, cut and renamed in Playback`() {
        oldScreens.createPlaylist("Later", emptyList())
        oldScreens.addToPlaylist("Later", listOf(alpha.id, beta.id, gamma.id))
        oldScreens.moveInPlaylist("Later", from = 2, to = 0)
        oldScreens.removeFromPlaylist("Later", index = 1)
        oldScreens.renamePlaylist("Later", "Dusk")

        val playlist =
            library.state.value.playlists
                .single()
        assertEquals("Dusk", playlist.name)
        assertEquals(listOf(gamma.id, beta.id), playlist.songs)
        assertEquals(
            listOf(gamma.id, beta.id),
            playback.state.value.playlists
                .single()
                .tracks
                .map { it.id },
        )
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a song played from the old Library reaches Playback, with the list it was in`() {
        val shown = oldScreens.deviceTracks.value.map { it.uri }
        assertEquals(listOf(alpha.id, beta.id, gamma.id), shown)

        oldScreens.play(shown, from = beta.id)

        val loading = playback.state.value.player as PlayerState.Loading
        assertEquals(listOf(alpha.id, beta.id, gamma.id), loading.queue.tracks.map { it.id })
        assertEquals(beta.id, loading.queue.current.id)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `where the user browses, the favourites and the music survive the places moving`() {
        library.send(LibraryCommand.Show(LibraryView.ALBUMS))
        library.send(LibraryCommand.Open(GroupKey(GroupKind.ALBUM, "First")))
        oldScreens.toggleFavourite(gamma.id)
        oldScreens.play(listOf(alpha.id, beta.id), from = alpha.id)
        val browsing = library.state.value
        val music = playback.state.value.player

        compose.mainClock.autoAdvance = false
        compose.setContent { App() }
        compose.mainClock.advanceTimeByFrame()
        for (next in listOf(Where.PLAYER, Where.FULL_SCREEN, Where.LIBRARY, Where.PLAYER, Where.LIBRARY)) {
            where.value = next
            compose.mainClock.advanceTimeByFrame()
        }

        assertEquals(Place.Group(LibraryView.ALBUMS, GroupKey(GroupKind.ALBUM, "First")), library.state.value.place)
        assertEquals(browsing, library.state.value)
        assertEquals(setOf(gamma.id), oldScreens.favourites.value)
        assertEquals(music, playback.state.value.player)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    /** A stand-in for the app shell: the old Library reading the store, the player, and full screen. */
    @Composable
    private fun App() {
        val tracks = oldScreens.deviceTracks.collectAsState()
        Column {
            when (where.value) {
                Where.LIBRARY -> {
                    tracks.value.forEach { Text(it.title) }
                    MiniPlayerRoute(playback, elapsedNow = { clock.now }, onExpand = { where.value = Where.PLAYER })
                }
                Where.PLAYER -> PlayerRoute(playback, elapsedNow = { clock.now })
                Where.FULL_SCREEN -> Text("Full screen")
            }
        }
    }
}
