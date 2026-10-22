package dev.geode.feature.playback.handlers

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.audio.adapter.PlaybackAnnouncement
import dev.geode.audio.adapter.PlaybackMark
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.kernel.FakeClock
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The media session's player (#36): it shows Playback's state, and every control on it becomes a
 * Command to Playback, never a call to the audio engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlaybackSessionPlayerRobolectricTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val c = Track(TrackId("content://c"), "Gamma", "Cy", Millis(240_000))
    private val atB = Queue(listOf(a, b, c), 1)
    private val clock = FakeClock()
    private val clocks = PlaybackClocks(elapsed = { clock.now }, wall = { clock.now })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val playing = PlayerState.Playing(atB, PlayMark(Millis(30_000), Millis.ZERO, moving = true))
    private val paused = PlayerState.Paused(atB, PlayMark.still(Millis(40_000)))

    @After
    fun stop() = scope.cancel()

    private fun player(playback: FakePlayback) = PlaybackSessionPlayer(playback, clocks, scope).also { idle() }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `it shows what Playback is playing, where, and which buttons Playback answers`() {
        val player = player(FakePlayback(playing))
        clock.advanceBy(Millis(1_500))

        assertEquals(Player.STATE_READY, player.playbackState)
        assertTrue(player.playWhenReady)
        assertTrue(player.isPlaying)
        assertEquals(3, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("content://b", player.currentMediaItem?.mediaId)
        assertEquals("Beta", player.mediaMetadata.title.toString())
        assertEquals("Bob", player.mediaMetadata.artist.toString())
        assertEquals(b.duration.value, player.duration)
        assertEquals(31_500L, player.currentPosition)
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertNull(player.playerError)
    }

    @Test
    fun `with nothing to play it offers nothing to skip or seek`() {
        val player = player(FakePlayback(PlayerState.Idle))

        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertEquals(0, player.mediaItemCount)
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        assertTrue(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun `opening, ending and failing show as the session's own states`() {
        val playback = FakePlayback(PlayerState.Loading(atB, PlayMark.still(Millis.ZERO), playWhenReady = true, LoadReason.PLAY))
        val player = player(playback)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue(player.playWhenReady)

        playback.player = PlayerState.Ended(atB)
        idle()
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertFalse(player.playWhenReady)

        playback.player = PlayerState.Error(PlaybackFailure.ENGINE_NOT_RESPONDING, atB)
        idle()
        assertEquals(Player.STATE_IDLE, player.playbackState)
        assertNotNull(player.playerError)
        assertEquals(1, player.currentMediaItemIndex)
    }

    @Test
    fun `it follows Playback's state as it changes`() {
        val playback = FakePlayback(playing)
        val player = player(playback)
        val heard = mutableListOf<Boolean>()
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    heard += isPlaying
                }
            },
        )

        playback.player = paused
        idle()

        assertFalse(player.playWhenReady)
        assertEquals(40_000L, player.currentPosition)
        assertEquals(listOf(false), heard)
    }

    @Test
    fun `every control sends Playback a Command`() {
        val playback = FakePlayback(playing)
        val player = player(playback)

        player.pause()
        player.play()
        player.seekToNext()
        player.seekToPrevious()
        player.seekTo(5_000)
        player.seekTo(2, 0)
        player.seekToDefaultPosition(0)
        player.stop()
        idle()

        assertEquals(
            listOf(
                PlaybackCommand.Pause,
                PlaybackCommand.Resume,
                PlaybackCommand.Next,
                PlaybackCommand.Previous,
                PlaybackCommand.SeekTo(Millis(5_000)),
                PlaybackCommand.Play(atB.tracks, 2),
                PlaybackCommand.Play(atB.tracks, 0),
                PlaybackCommand.Pause,
            ),
            playback.sent,
        )
    }

    @Test
    fun `a button reaches the audio only through Playback`() {
        val audio = FakeAudioAdapter()
        val playback = playbackStore(audio, FakePlaybackStorage(), clocks, inPlaceRuntime(clock))
        playback.send(PlaybackCommand.Play(listOf(a, b)))
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))
        audio.announce(PlaybackAnnouncement.Started(PlaybackMark(Millis.ZERO, Millis.ZERO, moving = true)))
        val player = PlaybackSessionPlayer(playback, clocks, scope).also { idle() }
        audio.calls.clear()

        player.pause()
        idle()

        assertEquals(listOf("setPlaying(false)"), audio.calls)
        val atA = Queue(listOf(a, b), 0)
        assertEquals(PlayerState.Paused(atA, PlayMark(Millis.ZERO, Millis.ZERO, moving = true)), playback.state.value.player)
        assertFalse(player.playWhenReady)
    }

    private fun item(track: Track): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(track.id.value)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setDurationMs(track.duration.value)
                    .build(),
            ).build()

    @Test
    fun `songs a car hands over are played or queued by Playback`() {
        val playback = FakePlayback(playing)
        val player = player(playback)

        player.setMediaItems(listOf(item(a), item(c)), 1, 7_000)
        player.prepare()
        player.addMediaItems(listOf(item(c)))
        player.addMediaItems(2, listOf(item(a)))
        player.removeMediaItems(0, 2)
        player.moveMediaItem(2, 0)
        idle()

        assertEquals(
            listOf(
                PlaybackCommand.Play(listOf(a, c), 1, Millis(7_000)),
                PlaybackCommand.AddToQueue(listOf(c)),
                PlaybackCommand.PlayNext(listOf(a)),
                PlaybackCommand.RemoveFromQueue(1),
                PlaybackCommand.RemoveFromQueue(0),
                PlaybackCommand.MoveInQueue(2, 0),
            ),
            playback.sent,
        )
    }

    @Test
    fun `an item with nothing known but its id plays from the start of the first song`() {
        val playback = FakePlayback(PlayerState.Idle)
        val player = player(playback)

        player.setMediaItem(MediaItem.Builder().setMediaId("content://z").build())
        idle()

        assertEquals(listOf(PlaybackCommand.Play(listOf(Track(TrackId("content://z"), "", "", Millis.ZERO)), 0)), playback.sent)
    }

    @Test
    fun `a car's resume opens the song where it was left, with Playback's own reducer`() {
        val audio = FakeAudioAdapter()
        val playback = playbackStore(audio, FakePlaybackStorage(), clocks, inPlaceRuntime(clock))
        val player = PlaybackSessionPlayer(playback, clocks, scope).also { idle() }

        player.setMediaItems(listOf(item(a), item(c)), 1, 7_000)
        idle()
        audio.announce(PlaybackAnnouncement.TrackChanged(c.id, PlaybackMark(Millis(7_000), Millis.ZERO, moving = false)))

        val atC = Queue(listOf(a, c), 1)
        assertEquals(listOf("load(${c.id}, ${Millis(7_000)})", "setPlaying(true)"), audio.calls.take(2))
        assertEquals(PlayerState.Buffering(atC, PlayMark.still(Millis(7_000))), playback.state.value.player)
    }
}
