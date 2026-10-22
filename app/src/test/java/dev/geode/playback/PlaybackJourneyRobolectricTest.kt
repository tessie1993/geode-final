package dev.geode.playback

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.audio.adapter.PlaybackAnnouncement
import dev.geode.audio.adapter.PlaybackMark
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.handlers.PlaybackClocks
import dev.geode.feature.playback.handlers.playbackStore
import dev.geode.feature.playback.ui.MiniPlayerRoute
import dev.geode.feature.playback.ui.PlayerRoute
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import dev.geode.ui.OldScreenPlayback
import dev.geode.ui.QueueTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Playback slice of the switch-over (#17): the one app-scoped Playback store is what every screen
 * plays through, the old screens included, so moving between places never restarts the music.
 *
 * Playback is built as `:app` builds it, over the audio adapter's fake. The old screens reach it through
 * [OldScreenPlayback], exactly as they do in the app; the places are Playback's own routes and stand-ins
 * moved between the way navigation moves between them, with the one live Visual hosted outside every
 * place. The plain Application keeps Robolectric away from GeodeApp, which loads the engine.
 *
 * It also pins the three playback bugs #17 folds in, through the path the old screens now take.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlaybackJourneyRobolectricTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class Place { PLAYER, LIBRARY, FULL_SCREEN }

    private val a = Track(TrackId("content://media/a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://media/b"), "Beta", "Bob", Millis(180_000))
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val audio = FakeAudioAdapter()
    private val playback: Playback =
        playbackStore(audio, MemoryPlaybackStorage(), PlaybackClocks({ clock.now }, { clock.now }), inPlaceRuntime(clock, failures))
    private val oldScreens =
        OldScreenPlayback(playback, RuntimeEnvironment.getApplication(), CoroutineScope(UnconfinedTestDispatcher()))

    private val place: MutableState<Place> = mutableStateOf(Place.PLAYER)
    private var visualStarted = 0
    private var visualStopped = 0

    private fun queued(vararg tracks: Track) = tracks.map { QueueTrack(it.id, it.title, it.artist, it.duration) }

    private fun heard(
        at: Long,
        moving: Boolean,
    ) = PlaybackMark(Millis.ZERO, Millis(at), moving)

    private fun playA() {
        oldScreens.playFrom(queued(a, b), a.id)
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, heard(1, moving = false)))
        audio.announce(PlaybackAnnouncement.Started(heard(5, moving = true)))
    }

    @Test
    fun `the music and the Visual survive switching places and full-screen visual mode`() {
        playA()
        val playing = PlayerState.Playing(Queue(listOf(a, b), 0), PlayMark(Millis.ZERO, Millis(5), moving = true))
        assertEquals(playing, playback.state.value.player)
        val askedOfAudio = audio.calls.toList()

        // The player's clock keeps ticking while the music plays, so frames are stepped by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent { App() }
        compose.mainClock.advanceTimeByFrame()
        for (next in listOf(Place.LIBRARY, Place.FULL_SCREEN, Place.PLAYER, Place.FULL_SCREEN, Place.LIBRARY, Place.PLAYER)) {
            place.value = next
            compose.mainClock.advanceTimeByFrame()
        }

        assertEquals("the audio was asked nothing", askedOfAudio, audio.calls)
        assertEquals("the music plays on from the same mark", playing, playback.state.value.player)
        assertEquals("the Visual was set up once", 1, visualStarted)
        assertEquals("and never taken down", 0, visualStopped)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `the old screens show Playback's queue, and their buttons reach Playback`() {
        playA()

        assertEquals(queued(a, b), oldScreens.queue.value.tracks)
        assertEquals(0, oldScreens.queue.value.index)

        oldScreens.next()
        assertEquals(b, (playback.state.value.player as PlayerState.Loading).queue.current)
        assertTrue(audio.calls.last(), audio.calls.last().startsWith("load("))
    }

    @Test
    fun `bug 1 - buffering never lasts forever`() {
        playA()
        // The player stalls while meant to play.
        audio.announce(PlaybackAnnouncement.Paused(heard(10, moving = false)))
        assertTrue(playback.state.value.player is PlayerState.Buffering)

        clock.advanceBy(Millis(BUFFERING_GRACE_MS))

        assertTrue("it gives way to Paused", playback.state.value.player is PlayerState.Paused)
        assertEquals(
            false,
            oldScreens.queue.value.tracks
                .isEmpty(),
        )
    }

    @Test
    fun `bug 2 - a pause while a song opens wins over the open`() {
        oldScreens.playFrom(queued(a, b), a.id)
        oldScreens.togglePlayPause()
        // The open that was in flight finishes, and the player starts as it was first asked to.
        audio.announce(PlaybackAnnouncement.Started(heard(3, moving = true)))
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, heard(4, moving = false)))

        assertTrue(playback.state.value.player is PlayerState.Paused)
        assertEquals("setPlaying(false)", audio.calls.last { it.startsWith("setPlaying") })
    }

    @Test
    fun `bug 3 - a failure while a song plays never stops it`() {
        playA()

        // The next song, opened ahead for a gapless join, cannot be decoded.
        audio.announce(PlaybackAnnouncement.Failed("ERROR_CODE_DECODING_FAILED"))

        val player = playback.state.value.player
        assertTrue("the current song is opened again, $player", player is PlayerState.Loading)
        player as PlayerState.Loading
        assertEquals(a, player.queue.current)
        assertTrue("and it goes on playing", player.playWhenReady)
        assertEquals(LoadReason.RECOVER, player.reason)
    }

    /** A stand-in for the app shell: the one live Visual outside every place, and the place shown. */
    @Composable
    private fun App() {
        Box {
            LiveVisual()
            Column {
                when (place.value) {
                    Place.PLAYER -> PlayerRoute(playback, elapsedNow = { clock.now })
                    Place.LIBRARY -> {
                        Text("Library")
                        MiniPlayerRoute(playback, elapsedNow = { clock.now }, onExpand = { place.value = Place.PLAYER })
                    }
                    Place.FULL_SCREEN -> Text("Full screen")
                }
            }
        }
    }

    @Composable
    private fun LiveVisual() {
        DisposableEffect(Unit) {
            visualStarted++
            onDispose { visualStopped++ }
        }
    }

    private companion object {
        /** Playback's grace for buffering (`BUFFERING_GRACE` in its core), and a little more. */
        const val BUFFERING_GRACE_MS = 1_600L
    }
}
