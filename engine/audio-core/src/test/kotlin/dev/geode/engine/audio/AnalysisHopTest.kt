package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Live playback and offline analysis hop by the same definition: a whole number of samples, lasting
 * exactly that many samples, so every per-hop time constant means the same thing on both paths.
 */
class AnalysisHopTest {
    @Test
    fun `a hop is sixty per second of the common sample rates`() {
        assertEquals(735, AnalysisHop.samples(sampleRateHz = 44_100, fftSize = 2048))
        assertEquals(800, AnalysisHop.samples(sampleRateHz = 48_000, fftSize = 2048))
    }

    @Test
    fun `a hop lasts exactly the samples it holds`() {
        // 22050 / 60 is 367.5: the hop is 367 samples, which last 16.644 ms rather than 1/60 s.
        assertEquals(367, AnalysisHop.samples(sampleRateHz = 22_050, fftSize = 2048))
        assertEquals(367f / 22_050f, AnalysisHop.seconds(sampleRateHz = 22_050, fftSize = 2048), 0f)
        assertEquals(800f / 48_000f, AnalysisHop.seconds(sampleRateHz = 48_000, fftSize = 2048), 0f)
    }

    @Test
    fun `a hop never outgrows the analysis window`() {
        // 192000 / 60 is 3200 samples, more than one 2048-sample window: the native session clamps it.
        assertEquals(2048, AnalysisHop.samples(sampleRateHz = 192_000, fftSize = 2048))
    }

    @Test
    fun `a hop is at least one sample`() {
        assertEquals(1, AnalysisHop.samples(sampleRateHz = 30, fftSize = 2048))
    }
}
