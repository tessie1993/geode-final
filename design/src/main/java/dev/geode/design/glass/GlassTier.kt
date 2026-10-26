package dev.geode.design.glass

/**
 * How much the glass may do (#270, section 5.2). Android gives apps no "reduce transparency" setting,
 * so the tier comes from what it does give, plus Geode's own switch ([GlassSignals]).
 */
enum class GlassTier {
    /** Live blur under bars and sheets, baked blur under cards. */
    FULL,

    /** Baked backdrops only: nothing is blurred live, so bars and sheets turn matte. */
    CALM,

    /** No translucency at all: every glass surface is an opaque tint with grain and an edge. */
    MATTE,
}

/** What decides the glass tier: the person's own switches and what Android reports. */
data class GlassSignals(
    /** Settings > Theme > Reduce transparency. */
    val reduceTransparency: Boolean = false,
    /** Android's high-contrast text (API 36 and later). */
    val highContrastText: Boolean = false,
    /** Android's contrast level, from -1 to 1, where 0 is standard (API 34 and later). */
    val contrast: Float = 0f,
    /** Android's battery saver. */
    val powerSave: Boolean = false,
    /** Android's thermal status, a `PowerManager.THERMAL_STATUS_*` value. */
    val thermalStatus: Int = 0,
    /** Android reports a low-RAM device. */
    val lowRam: Boolean = false,
    /** Settings > Battery > Visuals off: the person chose to save battery. */
    val batterySaving: Boolean = false,
)

/** Picks the glass tier from [GlassSignals]. */
object GlassTiers {
    /** The contrast level from which glass goes matte: Android's "medium" contrast and above. */
    const val CONTRAST_HIGH = 0.5f

    /** `PowerManager.THERMAL_STATUS_MODERATE`: from here on, nothing is blurred live. */
    const val THERMAL_MODERATE = 2

    /**
     * Matte when the person asks for less transparency, Android asks for more contrast, or the device is
     * low on memory; Calm under battery saver, thermal pressure or Visuals off; Full otherwise.
     */
    fun select(signals: GlassSignals): GlassTier {
        val moreContrast = signals.highContrastText || signals.contrast >= CONTRAST_HIGH
        val saving = signals.powerSave || signals.thermalStatus >= THERMAL_MODERATE || signals.batterySaving
        return when {
            signals.reduceTransparency || moreContrast || signals.lowRam -> GlassTier.MATTE
            saving -> GlassTier.CALM
            else -> GlassTier.FULL
        }
    }
}
