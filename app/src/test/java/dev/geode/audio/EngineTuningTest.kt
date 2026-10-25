package dev.geode.audio

import dev.geode.audio.dsp.DspSettings
import dev.geode.audio.dsp.NativeDspProcessor
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.Millibels
import org.junit.Assert.assertEquals
import org.junit.Test

/** The engine's tuning comes from Settings' values, with the processor's own stages left alone (#17). */
class EngineTuningTest {
    @Test
    fun `the equalizer runs Settings' bands over the processor's other stages`() {
        val equalizer =
            EqualizerSettings(
                enabled = true,
                bands = EqualizerPreset.ROCK.levels,
                preset = EqualizerPreset.ROCK,
                bassBoost = 300,
                loudness = Millibels(200),
            )
        val current = DspSettings(gainDb = 2f, crossfeed = true, limiter = true)

        val dsp = EngineTuning.dspOf(equalizer, current)

        assertEquals(
            DspSettings(
                enabled = true,
                bandsMb = EqualizerPreset.ROCK.levels.map { it.value },
                bassBoost = 300,
                loudnessMb = 200,
                gainDb = 2f,
                crossfeed = true,
                limiter = true,
            ),
            dsp,
        )
    }

    @Test
    fun `the equalizer's defaults run as a flat, off processor`() {
        assertEquals(
            DspSettings(),
            EngineTuning.dspOf(EqualizerSettings(), DspSettings()),
        )
    }

    @Test
    fun `a band Settings does not carry runs flat instead of dropping the chain`() {
        val dsp =
            EngineTuning.dspOf(
                EqualizerSettings(bands = listOf(Millibels(-150))),
                DspSettings(gainDb = 1f),
            )

        assertEquals(NativeDspProcessor.BANDS, dsp.bandsMb.size)
        assertEquals(-150, dsp.bandsMb.first())
        assertEquals(List(NativeDspProcessor.BANDS - 1) { 0 }, dsp.bandsMb.drop(1))
        assertEquals(1f, dsp.gainDb)
    }

    @Test
    fun `bands past the processor's own are not run`() {
        val bands = List(NativeDspProcessor.BANDS + 2) { Millibels(it * 100) }

        val dsp = EngineTuning.dspOf(EqualizerSettings(bands = bands), DspSettings())

        assertEquals(bands.take(NativeDspProcessor.BANDS).map { it.value }, dsp.bandsMb)
    }
}
