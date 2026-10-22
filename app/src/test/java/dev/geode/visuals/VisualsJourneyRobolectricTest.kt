package dev.geode.visuals

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.audio.adapter.PlaybackAnnouncement
import dev.geode.audio.adapter.PlaybackMark
import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.engine.types.TrackId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.CUSTOMIZATION_FEATURE
import dev.geode.feature.customization.core.CustomizationEvent
import dev.geode.feature.customization.core.CustomizationReducer
import dev.geode.feature.customization.core.CustomizationWork
import dev.geode.feature.customization.core.Defaults
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.handlers.PlaybackClocks
import dev.geode.feature.playback.handlers.PlaybackStorage
import dev.geode.feature.playback.handlers.SavedSession
import dev.geode.feature.playback.handlers.playbackStore
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.handlers.VisualsStorage
import dev.geode.feature.visuals.handlers.visualsStore
import dev.geode.feature.visuals.ui.FullScreenVisualRoute
import dev.geode.feature.visuals.ui.VisualsRoute
import dev.geode.kernel.EffectRunner
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.FeatureStore
import dev.geode.kernel.Results
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The acceptance test of #43: the music and the Visual survive entering and leaving full-screen visual
 * mode and switching tabs.
 *
 * The real app-scoped stores (ADR-0003) are built as `:app` will build them at the switch-over (#17):
 * Playback over the audio adapter's fake, Customization over its reducer with the engine hand-off
 * recorded, and Visuals over both. The places are the real routes, moved between the way navigation
 * moves between them; the one live Visual is a stand-in hosted outside every place, as the app hosts the
 * engine's surface. While the person moves around, the audio adapter is asked nothing, Playback keeps
 * playing from the same mark, Customization hands the engine nothing new, and the Visual's surface is
 * never taken down. The plain Application keeps Robolectric away from GeodeApp, which loads the engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VisualsJourneyRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Place { VISUALS, LIBRARY, FULL_SCREEN }

    private val track = Track(TrackId("content://song"), "Song", "Singer", Millis(200_000))
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val runtime = inPlaceRuntime(clock, failures)
    private val audio = FakeAudioAdapter()

    /** Every Customization handed to the engine: a new entry is the Visual being set up again. */
    private val handedToEngine = mutableListOf<Customization>()

    private val first: SceneId = BuiltInStyles.all[0].id
    private val second: SceneId = BuiltInStyles.all[1].id

    private val playback: Playback = playbackStore(audio, MemoryPlaybackStorage(), PlaybackClocks({ clock.now }, { clock.now }), runtime)

    private val customization: CustomizationFeature =
        FeatureStore<CustomizationState, CustomizationCommand, CustomizationEvent, CustomizationWork, CustomizationFact>(
            name = CUSTOMIZATION_FEATURE,
            initial = CustomizationState(Defaults.customization(BuiltInStyles.catalogue.manifestOf(first))),
            reducer = CustomizationReducer(BuiltInStyles.catalogue),
            toEvent = CustomizationEvent::Command,
            runner =
                object : EffectRunner<CustomizationWork, CustomizationEvent> {
                    override suspend fun run(
                        work: CustomizationWork,
                        results: Results<CustomizationEvent>,
                    ) {
                        if (work is CustomizationWork.Publish) handedToEngine += work.customization
                    }
                },
            runtime = runtime,
        )

    private val visuals: Visuals = visualsStore(customization, MemoryVisualsStorage(), BuiltInStyles.all, runtime)

    private val place: MutableState<Place> = mutableStateOf(Place.VISUALS)
    private var visualStarted = 0
    private var visualStopped = 0

    @Before
    fun playAndChooseAStyle() {
        playback.send(PlaybackCommand.Play(listOf(track)))
        audio.announce(PlaybackAnnouncement.TrackChanged(track.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))
        audio.announce(PlaybackAnnouncement.Started(PlaybackMark(Millis.ZERO, Millis(5), moving = true)))
        visuals.send(VisualsCommand.Choose(second))
    }

    @Test
    fun `the music and the Visual survive full-screen mode and switching tabs`() {
        val playing = PlayerState.Playing(Queue(listOf(track), 0), PlayMark(Millis.ZERO, Millis(5), moving = true))
        assertEquals(playing, playback.state.value.player)
        assertEquals(second, customization.state.value.current.style)
        val askedOfAudio = audio.calls.toList()
        val shown = customization.state.value
        val handed = handedToEngine.toList()
        assertEquals(1, handed.size)

        compose.setContent { App() }

        compose.onNodeWithText("Full screen").performClick()
        compose.onNodeWithContentDescription("Leave full screen").performClick()
        compose.onNodeWithText("Full screen").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        go(Place.LIBRARY)
        compose.onNodeWithText("Library").assertExists()
        go(Place.VISUALS)
        compose.onNodeWithText("Full screen").performClick()
        go(Place.LIBRARY)
        go(Place.VISUALS)

        assertEquals(playing, playback.state.value.player)
        assertEquals(askedOfAudio, audio.calls)
        assertEquals(shown, customization.state.value)
        assertEquals(handed, handedToEngine)
        assertEquals(second, visuals.state.value.current)
        assertEquals(1, visualStarted)
        assertEquals(0, visualStopped)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a Style chosen in full-screen mode reaches the Visual without touching the music`() {
        val askedOfAudio = audio.calls.toList()
        compose.setContent { App() }

        compose.onNodeWithText("Full screen").performClick()
        compose.onNodeWithContentDescription("Next Style").performClick()
        compose.waitForIdle()

        val third = BuiltInStyles.all[2].id
        assertEquals(third, customization.state.value.current.style)
        assertEquals(third, handedToEngine.last().style)
        assertEquals(askedOfAudio, audio.calls)
        assertEquals(PlayMark(Millis.ZERO, Millis(5), moving = true), (playback.state.value.player as PlayerState.Playing).mark)
        assertEquals(1, visualStarted)
    }

    /** The app as `:app` will host it: the one live Visual behind whichever place is shown. */
    @Composable
    private fun App() {
        Box {
            LiveVisual()
            when (place.value) {
                Place.VISUALS -> VisualsRoute(visuals, onEnterFullScreen = { place.value = Place.FULL_SCREEN })
                Place.LIBRARY -> Text("Library")
                Place.FULL_SCREEN -> FullScreenVisualRoute(visuals, keepScreenOn = false, onExit = { place.value = Place.VISUALS })
            }
        }
    }

    /** A stand-in for the engine's surface: it counts being set up and taken down. */
    @Composable
    private fun LiveVisual() {
        DisposableEffect(Unit) {
            visualStarted++
            onDispose { visualStopped++ }
        }
    }

    private fun go(to: Place) {
        compose.runOnUiThread { place.value = to }
        compose.waitForIdle()
    }

    private class MemoryVisualsStorage : VisualsStorage {
        private var saved = StyleLibrary()

        override suspend fun load(): StyleLibrary = saved

        override suspend fun save(library: StyleLibrary) {
            saved = library
        }
    }

    private class MemoryPlaybackStorage : PlaybackStorage {
        override val playlists: Flow<List<PlaylistSummary>> = MutableStateFlow(emptyList())
        override val history: Flow<List<PlayedTrack>> = MutableStateFlow(emptyList())

        override suspend fun playlistTracks(id: PlaylistId): List<Track> = emptyList()

        override suspend fun createPlaylist(
            name: String,
            tracks: List<Track>,
            createdAt: Millis,
        ): PlaylistId = PlaylistId(1)

        override suspend fun deletePlaylist(id: PlaylistId) = Unit

        override suspend fun recordPlay(
            track: Track,
            playedAt: Millis,
        ) = Unit

        override suspend fun saveSession(
            queue: Queue,
            position: Millis,
        ) = Unit

        override suspend fun loadSession(): SavedSession? = null
    }
}
