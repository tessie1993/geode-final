package dev.geode.feature.playback.core

import dev.geode.feature.playback.api.Waveform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The seek waveform is worked out as the old player did (#147): the loudest moment of each slice. */
class WaveformTest {
    @Test
    fun `each bar is the loudest level of its slice, the loudest bar being one`() {
        val levels = floatArrayOf(0.1f, 0.2f, 0.4f, 0.3f, 0f, 0.1f, 0.8f, 0.2f)

        assertEquals(Waveform(listOf(0.25f, 0.5f, 0.125f, 1f)), waveformOf(levels, bars = 4))
    }

    @Test
    fun `slices share out levels that do not divide evenly`() {
        val levels = floatArrayOf(0.5f, 1f, 0.25f, 0.5f, 0.75f)

        // Five levels in two bars: the first takes two of them, the second the other three.
        assertEquals(Waveform(listOf(1f, 0.75f)), waveformOf(levels, bars = 2))
    }

    @Test
    fun `exactly as many levels as bars gives one level a bar`() {
        assertEquals(Waveform(listOf(0.5f, 1f, 0.25f)), waveformOf(floatArrayOf(0.2f, 0.4f, 0.1f), bars = 3))
    }

    @Test
    fun `too few levels or only silence is no waveform`() {
        assertNull(waveformOf(floatArrayOf(0.2f, 0.4f), bars = 3))
        assertNull(waveformOf(FloatArray(6), bars = 3))
        assertNull(waveformOf(FloatArray(0), bars = 3))
    }

    @Test
    fun `the player draws as many bars as the old one`() {
        assertEquals(240, WAVEFORM_BARS)
        assertEquals(WAVEFORM_BARS, waveformOf(FloatArray(WAVEFORM_BARS) { 1f })?.levels?.size)
        assertNull(waveformOf(FloatArray(WAVEFORM_BARS - 1) { 1f }))
    }
}
