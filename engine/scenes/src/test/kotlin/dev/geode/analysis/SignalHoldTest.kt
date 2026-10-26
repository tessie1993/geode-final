package dev.geode.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** Freezing reactions (#366): the Sound signals hold where they were, and no event fires while held. */
class SignalHoldTest {
    private fun frame(
        level: Float,
        beat: Boolean = false,
    ): AudioFeatures =
        AudioFeatures.empty().copy(
            rms = level,
            bass = level,
            beat = beat,
            beatStrength = if (beat) 0.9f else 0f,
            kick = if (beat) 0.8f else 0f,
            drop = beat,
        )

    @Test
    fun `while not held every live frame passes through unchanged`() {
        val hold = SignalHold()
        val live = frame(0.4f, beat = true)
        assertSame(live, hold.frame(live))
        assertFalse(hold.held)
    }

    @Test
    fun `while held the signals stay as they stood on the first held frame`() {
        val hold = SignalHold()
        hold.frame(frame(0.2f))
        hold.held = true
        val first = hold.frame(frame(0.6f))
        assertEquals(0.6f, first.rms)
        for (level in listOf(0.1f, 0.9f, 0f)) {
            val held = hold.frame(frame(level))
            assertEquals(0.6f, held.rms)
            assertEquals(0.6f, held.bass)
        }
    }

    @Test
    fun `a held frame carries no one-hop event, even one that fired on it`() {
        val hold = SignalHold()
        hold.held = true
        val first = hold.frame(frame(0.5f, beat = true))
        val later = hold.frame(frame(0.5f, beat = true))
        for (held in listOf(first, later)) {
            assertFalse(held.beat)
            assertEquals(0f, held.beatStrength)
            assertEquals(0f, held.kick)
            assertFalse(held.drop)
        }
    }

    @Test
    fun `releasing hands back the live frames, and the next hold starts from then`() {
        val hold = SignalHold()
        hold.held = true
        hold.frame(frame(0.3f))
        hold.held = false
        val live = frame(0.7f, beat = true)
        assertSame(live, hold.frame(live))
        hold.held = true
        assertEquals(0.8f, hold.frame(frame(0.8f)).rms)
        assertEquals(0.8f, hold.frame(frame(0.1f)).rms)
    }

    @Test
    fun `holding again while already held keeps the frame it froze`() {
        val hold = SignalHold()
        hold.held = true
        hold.frame(frame(0.3f))
        hold.held = true
        assertEquals(0.3f, hold.frame(frame(0.9f)).rms)
    }
}
