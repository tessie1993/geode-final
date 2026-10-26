package dev.geode.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** A cached analysis draws exactly what the same analysis did fresh (#302). */
class AnalysisCacheTest {
    /** Three hops with levels between the cache's steps, drum hits and a waveform. */
    private val fresh =
        FeatureTimeline(
            (0 until 3).map { i ->
                TimelineFrame(
                    21L + i * 16L,
                    AudioFeatures(
                        bands = floatArrayOf(0.123456f + i, 0.987654f, -0.000061f, 5f),
                        waveform = floatArrayOf(0.333333f, -0.777777f, 1.5f),
                        rms = 0.31f + i,
                        bpm = 128.4f,
                        beat = i == 1,
                        kick = 0.71f * i,
                        snare = 0.13f,
                        hat = 0.29f,
                        chroma = FloatArray(12) { it / 7f },
                        beatInBar = i + 1,
                        downbeat = i == 2,
                        harmonicity = 0.62f,
                    ),
                )
            },
            hopMs = 16,
            key = "A minor",
            hopRateHz = 60f,
        )

    private fun cached(t: FeatureTimeline): FeatureTimeline? {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { AnalysisCache.write(t, it) }
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { AnalysisCache.read(it) }
    }

    private fun assertSameAnalysis(
        expected: FeatureTimeline,
        actual: FeatureTimeline,
    ) {
        assertEquals(expected.hopMs, actual.hopMs)
        assertEquals(expected.hopRateHz, actual.hopRateHz, 0f)
        assertEquals(expected.key, actual.key)
        assertEquals(expected.frames.size, actual.frames.size)
        for (i in expected.frames.indices) {
            val e = expected.frames[i]
            val a = actual.frames[i]
            assertEquals(e.timeMs, a.timeMs)
            assertArrayEquals(e.features.bands, a.features.bands, 0f)
            assertArrayEquals(e.features.waveform, a.features.waveform, 0f)
            assertArrayEquals(e.features.chroma, a.features.chroma, 0f)
            val none = FloatArray(0)
            assertEquals(
                e.features.copy(bands = none, waveform = none, chroma = none),
                a.features.copy(bands = none, waveform = none, chroma = none),
            )
        }
    }

    @Test
    fun `an analysis read back from the cache is the fresh one at the cache's precision`() {
        val stored = AnalysisCache.stored(fresh)

        assertSameAnalysis(stored, cached(fresh)!!)
        assertSameAnalysis(stored, cached(stored)!!)
    }

    @Test
    fun `the drum channels come back as analysed`() {
        val back = cached(fresh)!!

        assertEquals(listOf(0f, 0.71f, 1.42f), back.frames.map { it.features.kick })
        assertEquals(0.13f, back.frames[0].features.snare, 0f)
        assertEquals(0.29f, back.frames[0].features.hat, 0f)
    }

    @Test
    fun `levels are rounded to the nearest stored step, and kept within a short`() {
        val stored = AnalysisCache.stored(fresh).frames[0].features

        assertEquals(1011f / 8192f, stored.bands[0], 0f)
        assertEquals(0f, stored.bands[2], 0f)
        assertEquals(32767f / 8192f, stored.bands[3], 0f)
        assertEquals(10922f / 32767f, stored.waveform[0], 0f)
        assertEquals(1f, stored.waveform[2], 0f)
        assertEquals(1f / 7f, stored.chroma[1], 1f / 8192f)
        assertSameAnalysis(AnalysisCache.stored(fresh), AnalysisCache.stored(AnalysisCache.stored(fresh)))
    }

    @Test
    fun `an empty analysis is kept as it is, and another version is not read`() {
        val empty = FeatureTimeline(emptyList(), hopMs = 16)
        assertSame(empty, AnalysisCache.stored(empty))

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeInt(0x4D564143)
            it.writeInt(2)
        }
        assertNull(DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { AnalysisCache.read(it) })
    }
}
