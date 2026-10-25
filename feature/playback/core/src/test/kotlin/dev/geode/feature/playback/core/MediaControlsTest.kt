package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure.ENGINE_NOT_RESPONDING
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.PlayerState.Buffering
import dev.geode.feature.playback.api.PlayerState.Ended
import dev.geode.feature.playback.api.PlayerState.Error
import dev.geode.feature.playback.api.PlayerState.Idle
import dev.geode.feature.playback.api.PlayerState.Loading
import dev.geode.feature.playback.api.PlayerState.Paused
import dev.geode.feature.playback.api.PlayerState.Playing
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.core.SessionStatus.BUFFERING
import dev.geode.feature.playback.core.SessionStatus.ENDED
import dev.geode.feature.playback.core.SessionStatus.IDLE
import dev.geode.feature.playback.core.SessionStatus.READY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The media session's side of Playback (#36) as tables: what the session shows in every player state,
 * and which Command every media control sends from every state.
 */
class MediaControlsTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val c = Track(TrackId("content://c"), "Gamma", "Cy", Millis(240_000))
    private val atA = Queue(listOf(a, b, c), 0)
    private val atB = Queue(listOf(a, b, c), 1)
    private val atC = Queue(listOf(a, b, c), 2)
    private val zero = PlayMark.still(Millis.ZERO)

    private val moving = PlayMark(Millis(30_000), Millis(1_000_000), moving = true)
    private val stopped = PlayMark(Millis(40_000), Millis(1_010_000), moving = false)

    private val idle = Idle
    private val loading = Loading(atB, moving, playWhenReady = true, LoadReason.RECOVER)
    private val loadingPaused = Loading(atB, stopped, playWhenReady = false, LoadReason.RESTORE)
    private val loadingFirst = Loading(atA, zero, playWhenReady = true, LoadReason.PLAY)
    private val loadingLast = Loading(atC, zero, playWhenReady = true, LoadReason.PLAY)
    private val buffering = Buffering(atB, moving)
    private val playing = Playing(atB, moving)
    private val playingFirst = Playing(atA, moving)
    private val playingLast = Playing(atC, moving)
    private val paused = Paused(atB, stopped)
    private val ended = Ended(atC)
    private val error = Error(ENGINE_NOT_RESPONDING, atB)
    private val errorLast = Error(ENGINE_NOT_RESPONDING, atC)
    private val states: List<PlayerState> = listOf(idle, loading, loadingPaused, buffering, playing, paused, ended, error)

    @Test
    fun `the session shows every player state`() {
        val table: List<Pair<PlayerState, SessionView>> =
            listOf(
                idle to view(IDLE, plays = false, queue = null, mark = zero, seek = false, next = false, prev = false),
                // Opening: the position waits where the track opens, whatever the mark it opens from.
                loading to view(BUFFERING, plays = true, atB, PlayMark.still(Millis(30_000)), seek = false, next = true, prev = true),
                loadingPaused to
                    view(BUFFERING, plays = false, atB, PlayMark.still(Millis(40_000)), seek = false, next = true, prev = true),
                loadingFirst to view(BUFFERING, plays = true, atA, zero, seek = false, next = true, prev = false),
                loadingLast to view(BUFFERING, plays = true, atC, zero, seek = false, next = false, prev = true),
                // Waiting for data: the position holds still until the start is announced.
                buffering to view(BUFFERING, plays = true, atB, PlayMark.still(Millis(30_000)), seek = true, next = true, prev = true),
                playing to view(READY, plays = true, atB, moving, seek = true, next = true, prev = true),
                // Previous on the first track restarts it, so it is always offered once a track is open.
                playingFirst to view(READY, plays = true, atA, moving, seek = true, next = true, prev = true),
                playingLast to view(READY, plays = true, atC, moving, seek = true, next = false, prev = true),
                paused to view(READY, plays = false, atB, stopped, seek = true, next = true, prev = true),
                ended to
                    view(ENDED, plays = false, atC, PlayMark.still(c.duration), seek = false, next = false, prev = true),
                error to
                    view(IDLE, plays = false, atB, zero, seek = false, next = true, prev = true, failed = true),
                errorLast to
                    view(IDLE, plays = false, atC, zero, seek = false, next = false, prev = true, failed = true),
            )

        table.forEach { (state, expected) -> assertEquals("session of $state", expected, MediaControls.sessionOf(state)) }
    }

    @Test
    fun `the music keeps playing in the background exactly while Playback means to play`() {
        val table: List<Pair<PlayerState, Boolean>> =
            listOf(
                idle to false,
                loading to true,
                loadingPaused to false,
                buffering to true,
                playing to true,
                paused to false,
                ended to false,
                error to false,
            )

        table.forEach { (state, expected) ->
            assertEquals("plays in background in $state", expected, MediaControls.sessionOf(state).playsInBackground)
        }
    }

    @Test
    fun `playing in the background needs both the wish to play and something to play`() {
        val table: List<Pair<SessionView, Boolean>> =
            SessionStatus.entries.flatMap { status ->
                listOf(
                    view(status, plays = true, atB, zero, false, false, false) to (status == BUFFERING || status == READY),
                    view(status, plays = false, atB, zero, false, false, false) to false,
                )
            }

        table.forEach { (session, expected) -> assertEquals("$session", expected, session.playsInBackground) }
    }

    @Test
    fun `the transport controls send the same Command from every state`() {
        val seek = Millis(60_000)
        val table: List<Pair<MediaControl, PlaybackCommand>> =
            listOf(
                MediaControl.Play to PlaybackCommand.Resume,
                MediaControl.Pause to PlaybackCommand.Pause,
                // The system's stop pauses: the queue stays so the lock screen can resume it.
                MediaControl.Stop to PlaybackCommand.Pause,
                MediaControl.Next to PlaybackCommand.Next,
                MediaControl.Previous to PlaybackCommand.Previous,
                MediaControl.SeekTo(seek) to PlaybackCommand.SeekTo(seek),
            )

        states.forEach { state ->
            table.forEach { (control, expected) ->
                assertEquals("$control in $state", expected, MediaControls.commandFor(control, state))
            }
        }
    }

    @Test
    fun `a tap on a track in the session's queue plays it from the place given, or seeks within the current one`() {
        val at = Millis(12_000)
        val table: List<Triple<PlayerState, MediaControl, PlaybackCommand?>> =
            listOf(
                // Nothing queued: there is no track to tap.
                Triple(idle, MediaControl.SkipTo(0, at), null),
                Triple(loading, MediaControl.SkipTo(0, at), PlaybackCommand.Play(atB.tracks, 0, at)),
                Triple(loading, MediaControl.SkipTo(1, at), PlaybackCommand.SeekTo(at)),
                Triple(buffering, MediaControl.SkipTo(2, at), PlaybackCommand.Play(atB.tracks, 2, at)),
                Triple(playing, MediaControl.SkipTo(1, at), PlaybackCommand.SeekTo(at)),
                Triple(playing, MediaControl.SkipTo(0, Millis.ZERO), PlaybackCommand.Play(atB.tracks, 0)),
                Triple(paused, MediaControl.SkipTo(2, at), PlaybackCommand.Play(atB.tracks, 2, at)),
                Triple(paused, MediaControl.SkipTo(1, at), PlaybackCommand.SeekTo(at)),
                Triple(ended, MediaControl.SkipTo(0, at), PlaybackCommand.Play(atC.tracks, 0, at)),
                Triple(ended, MediaControl.SkipTo(2, at), PlaybackCommand.SeekTo(at)),
                Triple(error, MediaControl.SkipTo(0, at), PlaybackCommand.Play(atB.tracks, 0, at)),
                // Outside the queue: asks for nothing.
                Triple(playing, MediaControl.SkipTo(-1, at), null),
                Triple(playing, MediaControl.SkipTo(3, at), null),
            )

        table.forEach { (state, control, expected) ->
            assertEquals("$control in $state", expected, MediaControls.commandFor(control, state))
        }
    }

    @Suppress("LongParameterList")
    private fun view(
        status: SessionStatus,
        plays: Boolean,
        queue: Queue?,
        mark: PlayMark,
        seek: Boolean,
        next: Boolean,
        prev: Boolean,
        failed: Boolean = false,
    ) = SessionView(status, plays, queue, mark, if (failed) ENGINE_NOT_RESPONDING else null, seek, next, prev)

    @Test
    fun `leaving the app pauses music that is meant to play, when it is not to keep playing in the background`() {
        val pauses = listOf(loading, loadingFirst, buffering, playing)
        val nothingToPause = listOf(Idle, loadingPaused, paused, ended, error)

        for (player in pauses) {
            assertEquals("$player", PlaybackCommand.Pause, MediaControls.commandOnLeavingApp(player, musicKeepsPlaying = false))
        }
        for (player in nothingToPause) {
            assertNull("$player", MediaControls.commandOnLeavingApp(player, musicKeepsPlaying = false))
        }
    }

    @Test
    fun `leaving the app asks nothing while music keeps playing in the background`() {
        for (player in listOf(Idle, loading, buffering, playing, paused, ended, error)) {
            assertNull("$player", MediaControls.commandOnLeavingApp(player, musicKeepsPlaying = true))
        }
    }
}
