package dev.geode.feature.studio.handlers.edit

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.geode.feature.studio.core.Loudness
import java.nio.ByteBuffer

/**
 * Levels an Edit's sound by a fixed gain (#329): every 16-bit sample times [factor], held to the 16-bit
 * range. The loudness target's gain never lifts the true peak past its ceiling, so this does not clip.
 */
@UnstableApi
class GainProcessor(
    private val factor: Double,
) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        while (inputBuffer.remaining() >= Short.SIZE_BYTES) output.putShort(Loudness.scaled(inputBuffer.short, factor))
        // An odd byte left over is passed on as it is.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
    }

    companion object {
        /** A processor for [gainDb], or null when there is no gain to apply. */
        fun forGain(gainDb: Double): GainProcessor? = if (gainDb == 0.0) null else GainProcessor(Loudness.factorOf(gainDb))
    }
}
