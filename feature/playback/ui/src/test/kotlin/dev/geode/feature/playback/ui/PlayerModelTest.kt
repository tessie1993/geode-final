package dev.geode.feature.playback.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.AbLoop
import dev.geode.feature.playback.api.Banner
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource
import dev.geode.feature.playback.api.Notice
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.SyncedLine
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.api.TrackLookup
import dev.geode.feature.playback.api.Waveform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The screens' model follows Playback's state, and their actions become Commands. */
class PlayerModelTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val queue = Queue(listOf(a, b), 0)
    private val moving = PlayMark(Millis(10_000), Millis(1_000), moving = true)

    @Test
    fun `the position moves with the clock while playing`() {
        val model = PlaybackState(PlayerState.Playing(queue, moving)).toPlayerModel(Millis(4_000))

        assertEquals(
            PlayerModel(PlayerStatus.PLAYING, a, Millis(13_000), meantToPlay = true, canGoPrevious = true, canGoNext = true),
            model,
        )
    }

    @Test
    fun `the position never runs past the track`() {
        val model = PlaybackState(PlayerState.Buffering(queue, moving)).toPlayerModel(Millis(900_000))

        assertEquals(a.duration, model.position)
        assertEquals(PlayerStatus.BUFFERING, model.status)
    }

    @Test
    fun `every state has its status`() {
        val states =
            listOf(
                PlayerState.Idle,
                PlayerState.Loading(queue, PlayMark.still(Millis(5_000)), playWhenReady = false, LoadReason.RESTORE),
                PlayerState.Paused(queue, PlayMark.still(Millis(7_000))),
                PlayerState.Ended(queue.next()),
                PlayerState.Error(PlaybackFailure.ENGINE_NOT_RESPONDING, queue),
            )

        val models = states.map { PlaybackState(it).toPlayerModel(Millis.ZERO) }

        assertEquals(
            listOf(PlayerStatus.IDLE, PlayerStatus.LOADING, PlayerStatus.PAUSED, PlayerStatus.ENDED, PlayerStatus.ERROR),
            models.map { it.status },
        )
        assertEquals(listOf(Millis.ZERO, Millis(5_000), Millis(7_000), b.duration, Millis.ZERO), models.map { it.position })
        assertEquals(PlaybackFailure.ENGINE_NOT_RESPONDING, models.last().failure)
        assertEquals(false, models[3].canGoNext)
    }

    @Test
    fun `actions become Commands`() {
        val playing = PlaybackState(PlayerState.Playing(queue, moving)).toPlayerModel(Millis.ZERO)
        val paused = playing.copy(meantToPlay = false)

        assertEquals(PlaybackCommand.Pause, PlayerAction.PlayPause.toCommand(playing))
        assertEquals(PlaybackCommand.Resume, PlayerAction.PlayPause.toCommand(paused))
        assertEquals(PlaybackCommand.Next, PlayerAction.Next.toCommand(playing))
        assertEquals(PlaybackCommand.Previous, PlayerAction.Previous.toCommand(playing))
        assertEquals(PlaybackCommand.SeekTo(Millis(3_000)), PlayerAction.SeekTo(Millis(3_000)).toCommand(playing))
        assertNull(PlayerAction.Expand.toCommand(playing))
    }

    @Test
    fun `the current track's waveform and lyrics are drawn, with the line being sung`() {
        val lyrics =
            Lyrics.Synced(
                listOf(SyncedLine(Millis(5_000), "one"), SyncedLine(Millis(12_000), "two"), SyncedLine(Millis(20_000), "three")),
                LyricsSource.LRC_FILE,
            )
        val state =
            PlaybackState(
                PlayerState.Playing(queue, moving),
                lyrics = TrackLookup.Found(a.id, lyrics),
                waveform = TrackLookup.Found(a.id, Waveform(listOf(0.5f, 1f))),
            )

        val model = state.toPlayerModel(Millis(4_000))

        assertEquals(listOf(0.5f, 1f), model.waveform)
        assertEquals(lyrics, model.lyrics)
        assertEquals(1, model.lyricLine)
        assertEquals(NO_LINE, state.toPlayerModel(Millis(-7_000)).lyricLine)
    }

    @Test
    fun `untimed lyrics have no line being sung, and lookups not found draw nothing`() {
        val plain = Lyrics.Plain(listOf("one"), LyricsSource.TRACK_TAGS)
        val playing = PlayerState.Playing(queue, moving)

        val withPlain = PlaybackState(playing, lyrics = TrackLookup.Found(a.id, plain)).toPlayerModel(Millis(4_000))
        val looking = PlaybackState(playing, lyrics = TrackLookup.Looking(a.id), waveform = TrackLookup.Missing(a.id))

        assertEquals(plain, withPlain.lyrics)
        assertEquals(NO_LINE, withPlain.lyricLine)
        assertNull(looking.toPlayerModel(Millis.ZERO).lyrics)
        assertNull(looking.toPlayerModel(Millis.ZERO).waveform)
    }

    @Test
    fun `the A-B loop is shaded only on its own track`() {
        val playing = PlayerState.Playing(queue, moving)

        fun loopOf(loop: AbLoop) = PlaybackState(playing, loop = loop).toPlayerModel(Millis.ZERO).loop

        assertNull(loopOf(AbLoop.Off))
        assertEquals(LoopSpan(Millis(1_000), null), loopOf(AbLoop.Starting(a.id, Millis(1_000))))
        assertEquals(LoopSpan(Millis(1_000), Millis(9_000)), loopOf(AbLoop.On(a.id, Millis(1_000), Millis(9_000))))
        assertNull(loopOf(AbLoop.Starting(b.id, Millis(1_000))))
        assertNull(loopOf(AbLoop.On(b.id, Millis(1_000), Millis(9_000))))
    }

    @Test
    fun `with nothing playing there is nothing beside it`() {
        val state =
            PlaybackState(
                PlayerState.Idle,
                lyrics = TrackLookup.Found(a.id, Lyrics.Plain(listOf("one"), LyricsSource.TRACK_TAGS)),
                loop = AbLoop.On(a.id, Millis(1_000), Millis(9_000)),
            )

        assertEquals(PlayerModel.IDLE, state.toPlayerModel(Millis.ZERO))
    }

    @Test
    fun `the banner shows the notice and says when it is on screen`() {
        val notice = Notice("library", "Could not read the music")

        assertNull(Banner.Hidden.toBannerModel())
        assertEquals(BannerModel("Could not read the music", counting = false), Banner.Waiting(notice).toBannerModel())
        assertEquals(BannerModel("Could not read the music", counting = true), Banner.Showing(notice).toBannerModel())
        assertEquals(PlaybackCommand.BannerShown, BannerAction.Shown.toCommand())
        assertEquals(PlaybackCommand.DismissBanner, BannerAction.Dismiss.toCommand())
    }

    @Test
    fun `times print as the player shows them`() {
        assertEquals("0:00", formatTime(Millis.ZERO))
        assertEquals("3:07", formatTime(Millis(187_400)))
        assertEquals("1:02:09", formatTime(Millis(3_729_000)))
        assertEquals("0:00", formatTime(Millis(-5)))
    }
}
