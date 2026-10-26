package dev.geode.design.glass

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tiers switch as decided on #324: the table of signals and the tier each one gives. */
class GlassTiersTest {
    private val table =
        listOf(
            GlassSignals() to GlassTier.FULL,
            GlassSignals(reduceTransparency = true) to GlassTier.MATTE,
            GlassSignals(highContrastText = true) to GlassTier.MATTE,
            GlassSignals(contrast = 0.5f) to GlassTier.MATTE,
            GlassSignals(contrast = 1f) to GlassTier.MATTE,
            GlassSignals(contrast = 0.49f) to GlassTier.FULL,
            GlassSignals(contrast = -1f) to GlassTier.FULL,
            GlassSignals(lowRam = true) to GlassTier.MATTE,
            GlassSignals(powerSave = true) to GlassTier.CALM,
            GlassSignals(thermalStatus = 1) to GlassTier.FULL,
            GlassSignals(thermalStatus = 2) to GlassTier.CALM,
            GlassSignals(thermalStatus = 6) to GlassTier.CALM,
            GlassSignals(batterySaving = true) to GlassTier.CALM,
            GlassSignals(powerSave = true, reduceTransparency = true) to GlassTier.MATTE,
            GlassSignals(thermalStatus = 3, highContrastText = true) to GlassTier.MATTE,
        )

    @Test
    fun `every signal gives its tier`() {
        for ((signals, tier) in table) assertEquals(signals.toString(), tier, GlassTiers.select(signals))
    }

    @Test
    fun `the thresholds are Android's medium contrast and moderate thermal status`() {
        assertEquals(0.5f, GlassTiers.CONTRAST_HIGH, 0f)
        assertEquals(2, GlassTiers.THERMAL_MODERATE)
    }
}
