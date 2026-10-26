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
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.api.currentQueue
import dev.geode.feature.playback.core.BUFFERING_GRACE
import dev.geode.feature.playback.handlers.PlaybackClocks
import dev.geode.feature.playback.handlers.playbackStore
import dev.geode.feature.playback.ui.MiniPlayerRoute
import dev.geode.feature.playback.ui.PlayerRoute
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Playback slice of the switch-over (#17): the one app-scoped Playback store is what every screen
 * plays through, so moving between places never restarts the music.
 *
 * Playback is built as `:app` builds it, over the audio adapter's fake, and is sent its Commands as the
 * App UI sends them; the places are Playback's own routes and stand-ins moved between the way navigation
 * moves between them, with the one live Visual hosted outside every place. The plain Application keeps
 * Robolectric away from GeodeApp, which loads the engine.
 *
 * It also pins the three playback bugs #17 folds in.
 */
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
    private val place: MutableState<Place> = mutableStateOf(Place.PLAYER)
    private var visualStarted = 0
    private var visualStopped = 0

    private fun heard(
        at: Long,
        moving: Boolean,
    ) = PlaybackMark(Millis.ZERO, Millis(at), moving)

    private fun playA() {
        playback.send(PlaybackCommand.Play(listOf(a, b), 0))
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
    fun `the queue shown is Playback's, and Next reaches Playback`() {
        playA()

        assertEquals(
            listOf(a, b),
            playback.state.value.player.currentQueue
                ?.tracks,
        )
        assertEquals(
            0,
            playback.state.value.player.currentQueue
                ?.index,
        )

        playback.send(PlaybackCommand.Next)
        assertEquals(b, (playback.state.value.player as PlayerState.Loading).queue.current)
        // A load is always followed by whether to play once it opens, as Playback's own store tests pin.
        assertEquals(listOf("load(${b.id}, ${Millis.ZERO})", "setPlaying(true)"), audio.calls.takeLast(2))
    }

    @Test
    fun `bug 1 - buffering never lasts forever`() {
        playA()
        // The player stalls while meant to play.
        audio.announce(PlaybackAnnouncement.Paused(heard(10, moving = false)))
        assertTrue(playback.state.value.player is PlayerState.Buffering)

        clock.advanceBy(BUFFERING_GRACE + Millis(1))

        assertTrue("it gives way to Paused", playback.state.value.player is PlayerState.Paused)
        assertEquals(
            false,
            playback.state.value.player.currentQueue
                ?.tracks
                .isNullOrEmpty(),
        )
    }

    @Test
    fun `bug 2 - a pause while a song opens wins over the open`() {
        playback.send(PlaybackCommand.Play(listOf(a, b), 0))
        // The play button while the song opens to play.
        playback.send(PlaybackCommand.Pause)
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
}
