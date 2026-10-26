package dev.geode.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveInputPolicyTest {
    @Test
    fun `48 kHz is asked for first`() {
        assertArrayEquals(intArrayOf(48_000, 44_100, 22_050), LiveInputPolicy.rates)
    }

    @Test
    fun `retries wait 100 ms, doubling to at most a second, six times`() {
        val delays = (0 until LiveInputPolicy.MAX_RETRIES).map { LiveInputPolicy.retryDelayMs(it) }
        assertEquals(listOf(100L, 200L, 400L, 800L, 1_000L, 1_000L), delays)
        assertNull(LiveInputPolicy.retryDelayMs(LiveInputPolicy.MAX_RETRIES))
        assertNull(LiveInputPolicy.retryDelayMs(-1))
    }

    @Test
    fun `the unprocessed microphone is raised 18 dB more than the voice path`() {
        assertEquals(2f, LiveInputPolicy.windowGain(unprocessed = false), 0f)
        assertEquals(16f, LiveInputPolicy.windowGain(unprocessed = true), 0f)
        val db = 20.0 * kotlin.math.log10((LiveInputPolicy.UNPROCESSED_GAIN / LiveInputPolicy.VOICE_GAIN).toDouble())
        assertEquals(18.0, db, 0.1)
    }

    @Test
    fun `a read is about 5 ms at 48 kHz`() {
        assertTrue(LiveInputPolicy.READ_FRAMES * 1000 / 48_000 in 4..6)
    }
}
