package dev.geode.playback

import dev.geode.audio.dsp.NativeDspProcessor
import dev.geode.engine.bridge.GeodeNative

/**
 * Mirrors the equalizer settings the Media3 processor holds onto a chain built at the native player's
 * output rate. Every call runs on the main thread.
 */
class NativePlayerDsp(
    private val processor: NativeDspProcessor,
) {
    private var player = 0L
    private var handle = 0L
    private var rate = 0

    fun attach(player: Long) {
        this.player = player
        processor.onSettings = { settings ->
            val h = handle
            if (h != 0L) processor.apply(h, settings)
        }
    }

    /**
     * Rebuilds the chain when the output rate changed. Handing a chain to [GeodeNative.playerSetDsp]
     * transfers ownership: the native player retires the previous chain and destroys it itself once
     * the audio thread has moved past it, so this side must never call dspDestroy on a handle it
     * has already installed. Only a chain that was never installed is ours to free.
     */
    fun sync() {
        val current = GeodeNative.playerOutputSampleRate(player)
        if (current <= 0 || current == rate) return
        val next = GeodeNative.dspCreate(current, CHANNELS)
        if (next != 0L) processor.apply(next, processor.settings)
        GeodeNative.playerSetDsp(player, next)
        handle = next
        rate = current
    }

    fun release() {
        processor.onSettings = null
        if (player != 0L) {
            // Retires and later destroys the installed chain natively; see sync().
            GeodeNative.playerSetDsp(player, 0L)
        } else if (handle != 0L) {
            GeodeNative.dspDestroy(handle)
        }
        handle = 0L
        rate = 0
        player = 0L
    }

    private companion object {
        const val CHANNELS = 2
    }
}
