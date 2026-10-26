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
        // 48000 / 60 is 800 samples, more than one 512-sample window: the native session clamps it.
        assertEquals(512, AnalysisHop.samples(sampleRateHz = 48_000, fftSize = 512))
    }

    @Test
    fun `hi-res sources are decimated to 48 kHz or less before they are analysed`() {
        assertEquals(1, AnalysisHop.decimation(44_100))
        assertEquals(1, AnalysisHop.decimation(48_000))
        assertEquals(2, AnalysisHop.decimation(48_001))
        assertEquals(2, AnalysisHop.decimation(88_200))
        assertEquals(2, AnalysisHop.decimation(96_000))
        assertEquals(4, AnalysisHop.decimation(176_400))
        assertEquals(4, AnalysisHop.decimation(192_000))
    }

    @Test
    fun `a hi-res hop and window last as long as at 48 kHz`() {
        // 192 kHz is analysed at 48 kHz: 800 hops of its samples, each 4 of the source's frames.
        assertEquals(3200, AnalysisHop.samples(sampleRateHz = 192_000, fftSize = 2048))
        assertEquals(1f / 60f, AnalysisHop.seconds(sampleRateHz = 192_000, fftSize = 2048), 1e-7f)
        assertEquals(1470, AnalysisHop.samples(sampleRateHz = 88_200, fftSize = 2048))
        assertEquals(8192, AnalysisHop.windowFrames(sampleRateHz = 192_000, fftSize = 2048))
        assertEquals(2048, AnalysisHop.windowFrames(sampleRateHz = 48_000, fftSize = 2048))
    }

    @Test
    fun `a hop is at least one sample`() {
        assertEquals(1, AnalysisHop.samples(sampleRateHz = 30, fftSize = 2048))
    }
}
