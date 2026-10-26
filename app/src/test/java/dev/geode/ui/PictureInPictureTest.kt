package dev.geode.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.ui.PlayerModel
import dev.geode.feature.playback.ui.PlayerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPictureTest {
    private val song = Track(TrackId("1"), "Song", "Artist", Millis(200_000))

    private val playing =
        PlayerModel(PlayerStatus.PLAYING, song, Millis(10_000), meantToPlay = true, canGoPrevious = true, canGoNext = true)

    @Test
    fun `only the full-screen Visual goes into picture-in-picture, and never with Visuals off`() {
        assertTrue(PictureInPicture.allowed(fullScreen = true, visualsOff = false))
        assertFalse(PictureInPicture.allowed(fullScreen = true, visualsOff = true))
        assertFalse(PictureInPicture.allowed(fullScreen = false, visualsOff = false))
        assertFalse(PictureInPicture.allowed(fullScreen = false, visualsOff = true))
    }

    @Test
    fun `play-pause pauses what is meant to play and resumes what is not`() {
        assertEquals(PlaybackCommand.Pause, PipButton.PLAY_PAUSE.command(playing))
        val paused = playing.copy(status = PlayerStatus.PAUSED, meantToPlay = false)
        assertEquals(PlaybackCommand.Resume, PipButton.PLAY_PAUSE.command(paused))
    }

    @Test
    fun `play-pause is off while nothing is loaded`() {
        assertFalse(PipButton.PLAY_PAUSE.enabledFor(PlayerModel.IDLE))
        assertNull(PipButton.PLAY_PAUSE.command(PlayerModel.IDLE))
        assertTrue(PipButton.PLAY_PAUSE.enabledFor(playing))
    }

    @Test
    fun `next skips while there is a next track and is off at the end of the queue`() {
        assertTrue(PipButton.NEXT.enabledFor(playing))
        assertEquals(PlaybackCommand.Next, PipButton.NEXT.command(playing))
        val last = playing.copy(canGoNext = false)
        assertFalse(PipButton.NEXT.enabledFor(last))
        assertNull(PipButton.NEXT.command(last))
    }

    @Test
    fun `the window keeps the Visual's shape`() {
        assertEquals(1080 to 2340, PictureInPicture.aspect(1080, 2340))
        assertEquals(1920 to 1080, PictureInPicture.aspect(1920, 1080))
        assertEquals(478 to 200, PictureInPicture.aspect(478, 200))
        assertEquals(200 to 478, PictureInPicture.aspect(200, 478))
    }

    @Test
    fun `a shape past the platform's limits is cut to the limit`() {
        assertEquals(239 to 100, PictureInPicture.aspect(2400, 1000))
        assertEquals(100 to 239, PictureInPicture.aspect(1000, 2400))
    }

    @Test
    fun `an unmeasured Visual gets the default shape`() {
        assertEquals(PictureInPicture.defaultAspect, PictureInPicture.aspect(0, 1080))
        assertEquals(PictureInPicture.defaultAspect, PictureInPicture.aspect(1080, 0))
        assertEquals(PictureInPicture.defaultAspect, PictureInPicture.aspect(-1, -1))
        assertEquals(16 to 9, PictureInPicture.defaultAspect)
    }
}
