package dev.geode.feature.playback.handlers

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Background play (#36): while Playback means to play, its media session's service stays in the
 * foreground with its notification, so leaving the app or locking the phone keeps the music going,
 * and what the notification and the lock screen send arrives at Playback as Commands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlaybackSessionServiceRobolectricTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val queue = Queue(listOf(a, b), 0)
    private val playing = PlayerState.Playing(queue, PlayMark(Millis.ZERO, Millis.ZERO, moving = true))
    private val paused = PlayerState.Paused(queue, PlayMark.still(Millis(5_000)))

    private var controller: ServiceController<TestPlaybackSessionService>? = null

    @After
    fun destroy() {
        controller?.destroy()
        idle()
    }

    private fun start(playback: FakePlayback): TestPlaybackSessionService {
        TestPlaybackSessionService.playback = playback
        val built = Robolectric.buildService(TestPlaybackSessionService::class.java).create()
        controller = built
        idle()
        return built.get()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `while Playback plays, the service stays in the foreground with its notification`() {
        val service = start(FakePlayback(playing))

        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `swiping the app away while it plays keeps the music playing`() {
        val service = start(FakePlayback(playing))

        service.onTaskRemoved(Intent())

        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `swiping the app away while paused ends the service`() {
        val service = start(FakePlayback(paused))

        service.onTaskRemoved(Intent())

        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `the session's controls send Commands to Playback`() {
        val playback = FakePlayback(playing)
        val service = start(playback)
        val session = service.sessions.single()
        assertEquals(PLAYBACK_SESSION_ID, session.id)
        val connecting = MediaController.Builder(ApplicationProvider.getApplicationContext(), session.token).buildAsync()
        idle()
        val remote = connecting.get()

        remote.pause()
        remote.seekToNext()
        idle()
        remote.release()

        assertEquals(listOf(PlaybackCommand.Pause, PlaybackCommand.Next), playback.sent)
    }
}

/** Playback's session service as `:app` will subclass it, over the Playback a test hands it. */
class TestPlaybackSessionService : PlaybackSessionService() {
    override val playback: Playback get() = Companion.playback

    companion object {
        lateinit var playback: Playback
    }
}
