package dev.geode.audio

import dev.geode.engine.audio.PcmSink
import kotlin.math.pow

/**
 * Where every source of the Visual's sound writes (#17, #276 undone): the player's tap and Live input,
 * each on its own thread. One write reaches two rings: [analysis] (the stereo ring the analysis reads)
 * and [scenes] (the mono ring the Styles' waveforms and MilkDrop's PCM read).
 *
 * Two gains sit in between, so the Visual follows the music and not the volume knob:
 * - [sensitivityDb], Settings' Sensitivity (#301): one gain before the analysis and every Style's audio,
 *   MilkDrop's PCM included, so quiet music reacts like loud music.
 * - [sceneGain], a gain on the scenes' ring only, never on what the analysis hears (#297): a room heard
 *   through the microphone is much quieter than a mastered track, so its waveform is raised to match.
 *   The raised samples are limited to [-1, 1].
 */
class VisualSink(
    private val analysis: PcmSink,
    private val scenes: PcmSink,
) : PcmSink {
    @Volatile
    private var sensitivity: Float = 1f

    @Volatile
    private var scene: Float = 1f

    /** Settings' Sensitivity in dB, held to [MIN_SENSITIVITY_DB]..[MAX_SENSITIVITY_DB]; 0 leaves the sound as it is. */
    var sensitivityDb: Float
        get() = DB_PER_DECADE * kotlin.math.log10(sensitivity)
        set(value) {
            val db = if (value.isNaN()) 0f else value.coerceIn(MIN_SENSITIVITY_DB, MAX_SENSITIVITY_DB)
            sensitivity = 10f.pow(db / DB_PER_DECADE)
        }

    /** The gain on the scenes' ring alone, held to 0..[MAX_SCENE_GAIN]; 1 leaves it as written. */
    var sceneGain: Float
        get() = scene
        set(value) {
            scene = if (value.isNaN()) 1f else value.coerceIn(0f, MAX_SCENE_GAIN)
        }

    private var scratch = FloatArray(0)

    // One write at a time: the tap and the microphone each write from their own thread, and the rings
    // behind this sink take one writer.
    @Synchronized
    override fun write(
        interleaved: FloatArray,
        frameCount: Int,
        sourceChannelCount: Int,
    ) {
        if (frameCount <= 0 || sourceChannelCount <= 0) return
        val samples = frameCount * sourceChannelCount
        if (samples > interleaved.size) return
        val gain = sensitivity
        val forAnalysis = if (gain == 1f) interleaved else scaled(interleaved, samples, gain, limit = false)
        analysis.write(forAnalysis, frameCount, sourceChannelCount)
        val forScenes = gain * scene
        val heard = if (forScenes == 1f) interleaved else scaled(interleaved, samples, forScenes, limit = true)
        scenes.write(heard, frameCount, sourceChannelCount)
    }

    private fun scaled(
        source: FloatArray,
        samples: Int,
        gain: Float,
        limit: Boolean,
    ): FloatArray {
        if (scratch.size < samples) scratch = FloatArray(samples)
        val out = scratch
        for (i in 0 until samples) {
            val value = source[i] * gain
            out[i] = if (limit) value.coerceIn(-1f, 1f) else value
        }
        return out
    }

    companion object {
        const val MIN_SENSITIVITY_DB = -12f
        const val MAX_SENSITIVITY_DB = 24f

        /** The most a scene gain may raise the waveform: 24 dB. */
        const val MAX_SCENE_GAIN = 16f

        private const val DB_PER_DECADE = 20f
    }
}
