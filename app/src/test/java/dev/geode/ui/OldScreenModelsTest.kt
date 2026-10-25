package dev.geode.ui

import androidx.media3.common.Player
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.playback.api.SleepTimer
import dev.geode.feature.playback.api.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.geode.feature.playback.api.AbLoop as PlaybackLoop

/** The old screens draw Playback's state, and their calls become Playback Commands (#17). */
class OldScreenModelsTest {
    private val a = Track(TrackId("content://media/a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://media/b"), "", "", Millis(180_000))
    private val atA = Queue(listOf(a, b), 0)
    private val atB = Queue(listOf(a, b), 1)

    private val moving = PlayMark(Millis(10_000), Millis(1_000), moving = true)

    @Test
    fun `each player state shows as the old player's state`() {
        val rows =
            listOf(
                PlayerState.Idle to Triple(false, false, Millis.ZERO),
                PlayerState.Loading(atA, PlayMark.still(Millis(4_000)), playWhenReady = true, LoadReason.PLAY) to
                    Triple(true, true, Millis(4_000)),
                PlayerState.Loading(atA, PlayMark.still(Millis(4_000)), playWhenReady = false, LoadReason.RESTORE) to
                    Triple(true, false, Millis(4_000)),
                PlayerState.Buffering(atA, PlayMark.still(Millis(5_000))) to Triple(true, true, Millis(5_000)),
                PlayerState.Playing(atA, moving) to Triple(true, true, Millis(12_500)),
                PlayerState.Paused(atA, PlayMark.still(Millis(7_000))) to Triple(true, false, Millis(7_000)),
                PlayerState.Ended(atA) to Triple(true, false, a.duration),
                PlayerState.Error(PlaybackFailure.ENGINE_NOT_RESPONDING, atA) to Triple(true, false, Millis.ZERO),
            )
        for ((player, expected) in rows) {
            val shown = OldScreenModels.playerUiState(PlaybackState(player = player), now = Millis(3_500))
            assertEquals("$player has media", expected.first, shown.hasMedia)
            assertEquals("$player plays", expected.second, shown.isPlaying)
            assertEquals("$player position", expected.third, shown.position)
        }
    }

    @Test
    fun `the current song, its place and the listening choices are shown`() {
        val state =
            PlaybackState(
                player = PlayerState.Paused(atB, PlayMark.still(Millis(1_000))),
                options = ListeningOptions(shuffle = true, repeat = RepeatMode.ONE),
            )

        val shown = OldScreenModels.playerUiState(state, Millis.ZERO)

        // A song with no title shows its file name.
        assertEquals("b", shown.title)
        assertNull(shown.artist)
        assertEquals(b.duration, shown.duration)
        assertEquals(2, shown.queueSize)
        assertEquals(1, shown.queueIndex)
        assertTrue(shown.shuffle)
        assertEquals(Player.REPEAT_MODE_ONE, shown.repeatMode)
        assertEquals(
            QueueUiState(listOf(QueueTrack(a.id, "Alpha", "Ann", a.duration), QueueTrack(b.id, "b", "", b.duration)), 1),
            OldScreenModels.queueUiState(state),
        )
    }

    @Test
    fun `a position is held inside the song, unless its length is unknown`() {
        val unknown = Track(TrackId("content://media/u"), "U", "", Millis.ZERO)
        val past = PlayerState.Playing(atA, PlayMark(Millis(199_000), Millis.ZERO, moving = true))
        val unknownPlaying = PlayerState.Playing(Queue(listOf(unknown), 0), PlayMark(Millis(5_000), Millis.ZERO, moving = true))

        assertEquals(a.duration, OldScreenModels.positionAt(past, Millis(10_000)))
        assertEquals(Millis(15_000), OldScreenModels.positionAt(unknownPlaying, Millis(10_000)))
    }

    @Test
    fun `the play button pauses what plays or opens to play, and plays anything else`() {
        assertEquals(PlaybackCommand.Pause, OldScreenModels.playPause(PlayerState.Playing(atA, moving)))
        assertEquals(PlaybackCommand.Pause, OldScreenModels.playPause(PlayerState.Buffering(atA, moving)))
        assertEquals(
            PlaybackCommand.Pause,
            OldScreenModels.playPause(PlayerState.Loading(atA, moving, playWhenReady = true, LoadReason.PLAY)),
        )
        assertEquals(
            PlaybackCommand.Resume,
            OldScreenModels.playPause(PlayerState.Loading(atA, moving, playWhenReady = false, LoadReason.PLAY)),
        )
        assertEquals(PlaybackCommand.Resume, OldScreenModels.playPause(PlayerState.Paused(atA, moving)))
        assertEquals(PlaybackCommand.Resume, OldScreenModels.playPause(PlayerState.Idle))
    }

    @Test
    fun `playing a list from a song queues the list from that song`() {
        assertEquals(PlaybackCommand.Play(listOf(a, b), 1), OldScreenModels.playFrom(listOf(a, b), b.id))
        assertNull(OldScreenModels.playFrom(listOf(a), b.id))
    }

    @Test
    fun `a very long list is cut to a window around the song`() {
        val many = (0 until PlaybackQueue.MAX_QUEUE + 500).map { Track(TrackId("content://media/$it"), "$it", "", Millis(1_000)) }

        val play = OldScreenModels.playFrom(many, many[1_200].id)!!

        assertEquals(PlaybackQueue.MAX_QUEUE, play.tracks.size)
        assertEquals(many[1_200], play.tracks[play.startAt])
    }

    @Test
    fun `the seek bar asks for a place in the song, and nothing while its length is unknown`() {
        assertEquals(PlaybackCommand.SeekTo(Millis(50_000)), OldScreenModels.seekToFraction(PlayerState.Playing(atA, moving), 0.25f))
        assertEquals(PlaybackCommand.SeekTo(a.duration), OldScreenModels.seekToFraction(PlayerState.Playing(atA, moving), 2f))
        assertNull(OldScreenModels.seekToFraction(PlayerState.Idle, 0.5f))
        val unknown = Queue(listOf(Track(TrackId("x"), "x", "", Millis.ZERO)), 0)
        assertNull(OldScreenModels.seekToFraction(PlayerState.Paused(unknown, moving), 0.5f))
    }

    @Test
    fun `repeat cycles off, all, one and back, as the old button did`() {
        assertEquals(RepeatMode.ALL, OldScreenModels.nextRepeat(RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, OldScreenModels.nextRepeat(RepeatMode.ALL))
        assertEquals(RepeatMode.OFF, OldScreenModels.nextRepeat(RepeatMode.ONE))
        assertEquals(Player.REPEAT_MODE_OFF, OldScreenModels.repeatModeOf(RepeatMode.OFF))
        assertEquals(Player.REPEAT_MODE_ALL, OldScreenModels.repeatModeOf(RepeatMode.ALL))
    }

    @Test
    fun `the loop, the sleep timer and the history read as the old screens show them`() {
        assertNull(OldScreenModels.abLoopOf(PlaybackLoop.Off))
        assertEquals(AbLoop(Millis(1_000)), OldScreenModels.abLoopOf(PlaybackLoop.Starting(a.id, Millis(1_000))))
        assertEquals(AbLoop(Millis(1_000), Millis(9_000)), OldScreenModels.abLoopOf(PlaybackLoop.On(a.id, Millis(1_000), Millis(9_000))))
        assertNull(OldScreenModels.sleepRemainingOf(SleepTimer.Off))
        assertNull(OldScreenModels.sleepRemainingOf(SleepTimer.AfterTrack))
        assertEquals(Millis(60_000), OldScreenModels.sleepRemainingOf(SleepTimer.Counting(Millis(60_000), finishTrack = false)))

        val history = listOf(PlayedTrack(b, Millis(3)), PlayedTrack(a, Millis(2)), PlayedTrack(b, Millis(1)))
        assertEquals(listOf(b.id, a.id), OldScreenModels.recentlyPlayed(PlaybackState(history = history)).map { it.uri })
        assertFalse(OldScreenModels.recentlyPlayed(PlaybackState()).isNotEmpty())
    }

    @Test
    fun `a song with no title is called by its file name`() {
        assertEquals(
            "song",
            OldScreenModels.fileNameOf(TrackId("content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.mp3")),
        )
        assertEquals("42", OldScreenModels.fileNameOf(TrackId("content://media/external/audio/media/42")))
    }
}
