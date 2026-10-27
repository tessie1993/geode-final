package dev.geode.analysis

import dev.geode.engine.audio.SampleRing
import dev.geode.engine.bridge.NativeSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live analysis loop: it hands the native session every frame the ring receives, once and in
 * order, publishes the newest hop, starts with the tuning it always has and passes every change on whole.
 */
class AnalysisEngineTest {
    private class Recorder : AnalysisEngine.Analyzer {
        override var sampleRateHz: Int = 48_000
        val tunings = mutableListOf<List<Float>>()
        var resets = 0
        var closed = false
        val pushes = mutableListOf<Pair<Int, Int>>()
        var pending = 0
        var marker = 0f

        override fun setTuning(
            sensitivity: Float,
            refractoryMs: Float,
            attackSeconds: Float,
            releaseSeconds: Float,
        ) {
            tunings += listOf(sensitivity, refractoryMs, attackSeconds, releaseSeconds)
        }

        override fun reset() {
            resets++
        }

        override fun attachSnapshots(snapshots: NativeSnapshots?) = Unit

        override fun push(
            interleaved: FloatArray,
            frames: Int,
            channels: Int,
        ) {
            pushes += frames to channels
            pending++
            marker += frames
        }

        override fun pull(): Boolean = pending-- > 0

        override fun snapshot(): AudioFeatures = AudioFeatures.empty().copy(rms = marker)

        override fun close() {
            closed = true
        }
    }

    private val ring = SampleRing(capacityFrames = 1 shl 14, channelCount = 2)
    private val recorder = Recorder()
    private val engine = AnalysisEngine(ring, recorder, fftSize = 2048)

    @Test
    fun `the analysis starts with a 60 ms beat gap and a sensitivity of 3`() {
        assertEquals(
            listOf(
                listOf(
                    3f,
                    60f,
                    BeatTuning.envelopeSeconds(AnalysisEngine.DEFAULT_ATTACK),
                    BeatTuning.envelopeSeconds(AnalysisEngine.DEFAULT_DECAY),
                ),
            ),
            recorder.tunings,
        )
    }

    @Test
    fun `Settings' beat gap and sensitivity are clamped and sent with the rest`() {
        engine.beatMinIntervalMs = 1f
        engine.beatSensitivity = 100f
        assertEquals(listOf(BeatTuning.SENSITIVITY_MAX, BeatTuning.INTERVAL_MS_MIN), recorder.tunings.last().take(2))
        assertEquals(3, recorder.tunings.size)
    }

    @Test
    fun `envelope changes are sent as seconds`() {
        engine.attack = 0.3f
        engine.decay = 0.2f
        assertEquals(BeatTuning.envelopeSeconds(0.3f), recorder.tunings[1][2])
        assertEquals(BeatTuning.envelopeSeconds(0.2f), recorder.tunings[2][3])
    }

    @Test
    fun `the sample rate follows the source`() {
        engine.sampleRateHz = 44_100
        assertEquals(44_100, recorder.sampleRateHz)
    }

    @Test
    fun `a pass hands the session every frame the ring received, once and in order`() {
        val pass = engine.Pass()
        assertFalse(pass.tick())
        // The first read starts a window behind the writer, so a backlog is not replayed.
        ring.write(FloatArray(3000 * 2), 3000, 2)
        assertTrue(pass.tick())
        assertEquals(listOf(2048 to 2), recorder.pushes)
        assertEquals(2048f, engine.features.value.rms, 0f)
        ring.write(FloatArray(100 * 2), 100, 2)
        assertTrue(pass.tick())
        assertEquals(100 to 2, recorder.pushes.last())
        assertEquals(2148f, engine.features.value.rms, 0f)
        assertFalse(pass.tick())
    }

    @Test
    fun `a reset empties the features and reaches the session on the next pass`() {
        val pass = engine.Pass()
        ring.write(FloatArray(100 * 2), 100, 2)
        pass.tick()
        engine.reset()
        assertEquals(0f, engine.features.value.rms, 0f)
        pass.reset()
        assertEquals(1, recorder.resets)
    }

    @Test
    fun `closing destroys the session and nothing reaches it after`() {
        engine.close()
        assertTrue(recorder.closed)
        engine.beatSensitivity = 5f
        engine.sampleRateHz = 22_050
        assertEquals(1, recorder.tunings.size)
        assertEquals(48_000, recorder.sampleRateHz)
    }
}
