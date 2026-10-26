package dev.geode.analysis

import dev.geode.engine.bridge.NativeSnapshots
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live analysis starts with the tuning it always has, and passes every change on whole. */
class AnalysisEngineTest {
    private class Recorder : AnalysisEngine.Target {
        val tunings = mutableListOf<List<Float>>()
        var resets = 0

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
    }

    @Test
    fun `the analysis starts with a 60 ms beat gap and a sensitivity of 3`() {
        val recorder = Recorder()
        AnalysisEngine(recorder)
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
        val recorder = Recorder()
        val engine = AnalysisEngine(recorder)
        engine.beatMinIntervalMs = 1f
        engine.beatSensitivity = 100f
        assertEquals(listOf(BeatTuning.SENSITIVITY_MAX, BeatTuning.INTERVAL_MS_MIN), recorder.tunings.last().take(2))
        assertEquals(3, recorder.tunings.size)
    }

    @Test
    fun `envelope changes are sent as seconds`() {
        val recorder = Recorder()
        val engine = AnalysisEngine(recorder)
        engine.attack = 0.3f
        engine.decay = 0.2f
        assertEquals(BeatTuning.envelopeSeconds(0.3f), recorder.tunings[1][2])
        assertEquals(BeatTuning.envelopeSeconds(0.2f), recorder.tunings[2][3])
    }

    @Test
    fun `a reset reaches the analysis`() {
        val recorder = Recorder()
        AnalysisEngine(recorder).reset()
        assertEquals(1, recorder.resets)
    }
}
