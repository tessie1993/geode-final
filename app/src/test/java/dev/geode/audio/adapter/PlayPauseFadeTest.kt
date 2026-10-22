package dev.geode.audio.adapter

import dev.geode.engine.types.Millis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayPauseFadeTest {
    private class Player : FadedPlayer {
        override var volume = 1f
        override var playWhenReady = false
        override val isPlaying: Boolean get() = playWhenReady
        val calls = mutableListOf<String>()

        override fun play() {
            playWhenReady = true
            calls += "play"
        }

        override fun pause() {
            playWhenReady = false
            calls += "pause"
        }
    }

    private val length = Millis(200)

    private fun TestScope.fade() = PlayPauseFade(backgroundScope, stepMs = 25)

    @Test
    fun `a play asked for while a pause fades out is not paused when that fade ends`() =
        runTest {
            val fade = fade()
            val player = Player().apply { playWhenReady = true }

            fade.setPlaying(player, playing = false, length)
            advanceTimeBy(100)
            runCurrent()
            val halfway = player.volume
            fade.setPlaying(player, playing = true, length)
            advanceUntilIdle()

            assertTrue(player.playWhenReady)
            assertEquals(listOf("play"), player.calls)
            assertEquals(1f, player.volume)
            assertTrue("the play goes on from where the pause's fade got to", halfway in 0.1f..0.9f)
        }

    @Test
    fun `a pause asked for while a play fades in pauses once its own fade ends`() =
        runTest {
            val fade = fade()
            val player = Player()

            fade.setPlaying(player, playing = true, length)
            advanceTimeBy(100)
            runCurrent()
            fade.setPlaying(player, playing = false, length)
            advanceUntilIdle()

            assertEquals(listOf("play", "pause"), player.calls)
            assertEquals(1f, player.volume)
        }

    @Test
    fun `a play fades in from silence to full volume`() =
        runTest {
            val fade = fade()
            val player = Player()

            fade.setPlaying(player, playing = true, length)
            assertEquals(0f, player.volume)
            advanceUntilIdle()

            assertEquals(1f, player.volume)
            assertEquals(listOf("play"), player.calls)
        }

    @Test
    fun `settling a pause that fades out pauses at once at full volume`() =
        runTest {
            val fade = fade()
            val player = Player().apply { playWhenReady = true }

            fade.setPlaying(player, playing = false, length)
            advanceTimeBy(50)
            runCurrent()
            fade.settle()
            advanceUntilIdle()

            assertEquals(listOf("pause"), player.calls)
            assertEquals(1f, player.volume)
        }

    @Test
    fun `with no fade length play and pause are immediate`() =
        runTest {
            val fade = fade()
            val player = Player().apply { volume = 0.3f }

            fade.setPlaying(player, playing = true, Millis.ZERO)
            fade.setPlaying(player, playing = false, Millis.ZERO)

            assertEquals(listOf("play", "pause"), player.calls)
            assertEquals(1f, player.volume)
        }

    @Test
    fun `pausing what is not playing pauses without a fade`() =
        runTest {
            val fade = fade()
            val player = Player().apply { volume = 0.5f }

            fade.setPlaying(player, playing = false, length)

            assertEquals(listOf("pause"), player.calls)
            assertEquals(1f, player.volume)
        }

    @Test
    fun `the sleep fade takes what plays to silence without pausing, and the pause after it stays silent`() =
        runTest {
            val fade = fade()
            val player = Player().apply { playWhenReady = true }
            val heard = mutableListOf<Float>()

            fade.fadeOut(player, length)
            advanceUntilIdle()
            heard += player.volume
            fade.setPlaying(player, playing = false, Millis.ZERO)

            assertEquals(listOf(0f), heard)
            assertEquals(listOf("pause"), player.calls)
            assertEquals(1f, player.volume)
        }

    @Test
    fun `a sleep fade on music that is not playing does nothing, and restoring brings the volume back`() =
        runTest {
            val fade = fade()
            val paused = Player().apply { volume = 0.4f }
            fade.fadeOut(paused, length)
            advanceUntilIdle()
            assertEquals(0.4f, paused.volume)

            val playing = Player().apply { playWhenReady = true }
            fade.fadeOut(playing, length)
            advanceTimeBy(100)
            runCurrent()
            fade.restore(playing)
            advanceUntilIdle()
            assertEquals(1f, playing.volume)
            assertTrue(playing.calls.isEmpty())
        }
}
