package dev.geode.feature.customization.handlers

import dev.geode.feature.customization.core.LegacyEnvelope
import dev.geode.feature.customization.core.LegacyLfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** The old app-wide slots, as an older Geode's `LfoStore` wrote them. */
class LegacyModulationFormatTest {
    @Test
    fun `old LFO slots are read as they were saved`() {
        val json =
            """
            [
              {"enabled": true, "source": "BASS", "target": "ZOOM", "wave": "SAW", "rateSeconds": 4.0,
               "depth": 0.6, "polarity": "NEGATIVE", "curve": "LINEAR"},
              {"enabled": false, "source": "LFO", "target": "NONE", "wave": "SINE", "rateHz": 4.0},
              {}
            ]
            """.trimIndent()
        assertEquals(
            listOf(
                LegacyLfo(true, "BASS", "ZOOM", "SAW", periodSeconds = 4.0, depth = 0.6, polarity = "NEGATIVE"),
                LegacyLfo(false, "LFO", "NONE", "SINE", periodSeconds = 0.25, depth = 0.3, polarity = "BIPOLAR"),
                LegacyLfo(false, "LFO", "NONE", "SINE", periodSeconds = 2.0, depth = 0.3, polarity = "BIPOLAR"),
            ),
            LegacyModulationFormat.lfos(json),
        )
    }

    @Test
    fun `an old rate outside the slots' range is brought into it`() {
        val json = """[{"rateSeconds": 600.0}, {"rateHz": 100.0}]"""
        assertEquals(listOf(60.0, 0.05), LegacyModulationFormat.lfos(json).map { it.periodSeconds })
    }

    @Test
    fun `old envelopes are read as they were saved`() {
        val json =
            """
            [
              {"enabled": true, "targets": ["ZOOM", "BLOOM"], "attack": 0.02, "decay": 0.25, "sustain": 0.5,
               "release": 0.8, "amount": 0.7, "band": "RMS", "gateThreshold": 0.25},
              {}
            ]
            """.trimIndent()
        assertEquals(
            listOf(
                LegacyEnvelope(true, listOf("ZOOM", "BLOOM"), attackSeconds = 0.02, releaseSeconds = 0.8, amount = 0.7, band = "RMS"),
                LegacyEnvelope(false, emptyList(), attackSeconds = 0.05, releaseSeconds = 0.35, amount = 0.5, band = "BASS"),
            ),
            LegacyModulationFormat.envelopes(json),
        )
    }

    @Test
    fun `nothing saved, or something unreadable, is no slots at all`() {
        assertEquals(emptyList<LegacyLfo>(), LegacyModulationFormat.lfos(null))
        assertEquals(emptyList<LegacyLfo>(), LegacyModulationFormat.lfos("not json"))
        assertEquals(emptyList<LegacyEnvelope>(), LegacyModulationFormat.envelopes(""))
    }
}
