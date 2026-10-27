package dev.geode.audio

import dev.geode.engine.audio.PcmSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** One write reaches the analysis and the scenes, each through its own gain. */
class VisualSinkTest {
    private class Recorder : PcmSink {
        val writes = mutableListOf<Triple<FloatArray, Int, Int>>()

        override fun write(
            interleaved: FloatArray,
            frameCount: Int,
            sourceChannelCount: Int,
        ) {
            writes += Triple(interleaved.copyOf(frameCount * sourceChannelCount), frameCount, sourceChannelCount)
        }
    }

    private val analysis = Recorder()
    private val scenes = Recorder()
    private val sink = VisualSink(analysis, scenes)

    @Test
    fun `as written, a write reaches both rings unchanged`() {
        sink.write(floatArrayOf(0.5f, -0.5f, 0.25f, 0f), 2, 2)
        assertEquals(1, analysis.writes.size)
        assertEquals(1, scenes.writes.size)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0.25f, 0f), analysis.writes[0].first, 0f)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0.25f, 0f), scenes.writes[0].first, 0f)
        assertEquals(2 to 2, analysis.writes[0].second to analysis.writes[0].third)
    }

    @Test
    fun `the Sensitivity is a gain before both rings`() {
        sink.sensitivityDb = 6.0206f
        assertEquals(6.0206f, sink.sensitivityDb, 1e-3f)
        sink.write(floatArrayOf(0.25f, -0.25f), 1, 2)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), analysis.writes[0].first, 1e-4f)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), scenes.writes[0].first, 1e-4f)
    }

    @Test
    fun `the Sensitivity is held to its range and nothing is not a number`() {
        sink.sensitivityDb = 90f
        assertEquals(VisualSink.MAX_SENSITIVITY_DB, sink.sensitivityDb, 1e-3f)
        sink.sensitivityDb = -90f
        assertEquals(VisualSink.MIN_SENSITIVITY_DB, sink.sensitivityDb, 1e-3f)
        sink.sensitivityDb = Float.NaN
        assertEquals(0f, sink.sensitivityDb, 1e-6f)
    }

    @Test
    fun `the scene gain raises the scenes' ring only and limits it`() {
        sink.sceneGain = 4f
        sink.write(floatArrayOf(0.1f, -0.3f), 2, 1)
        assertArrayEquals(floatArrayOf(0.1f, -0.3f), analysis.writes[0].first, 0f)
        assertArrayEquals(floatArrayOf(0.4f, -1f), scenes.writes[0].first, 1e-6f)
    }

    @Test
    fun `the scene gain is clamped and a bad one reads as one`() {
        sink.sceneGain = 100f
        assertEquals(VisualSink.MAX_SCENE_GAIN, sink.sceneGain, 0f)
        sink.sceneGain = -2f
        assertEquals(0f, sink.sceneGain, 0f)
        sink.sceneGain = Float.NaN
        assertEquals(1f, sink.sceneGain, 0f)
    }

    @Test
    fun `the two gains multiply on the scenes' ring`() {
        sink.sensitivityDb = 6.0206f
        sink.sceneGain = 2f
        sink.write(floatArrayOf(0.1f), 1, 1)
        assertEquals(0.2f, analysis.writes[0].first[0], 1e-4f)
        assertEquals(0.4f, scenes.writes[0].first[0], 1e-4f)
    }

    @Test
    fun `an empty or short write reaches nothing`() {
        sink.write(FloatArray(4), 0, 2)
        sink.write(FloatArray(4), 2, 0)
        sink.write(FloatArray(2), 2, 2)
        assertEquals(0, analysis.writes.size)
        assertEquals(0, scenes.writes.size)
    }
}
