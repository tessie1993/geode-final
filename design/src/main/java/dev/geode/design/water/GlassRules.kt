package dev.geode.design.water

import dev.geode.design.forest.ForestGlass

/**
 * How frosted stone glass is drawn on this phone. Android 12 and later all have `RenderEffect`
 * (minSdk is 31), so [Frosted] is the norm; [Tinted] is the designed fallback, a nearly opaque
 * stone panel with the same grain and edge light and no blur.
 */
enum class GlassTier {
    /** The backdrop is blurred live under the tint. */
    Frosted,

    /** No live blur: the person asked for less transparency, or the screen's blur budget is spent. */
    Tinted,
    ;

    companion object {
        /** The first Android version with `RenderEffect` blur. */
        const val BLUR_MIN_SDK = 31

        /** The tier for one panel. */
        fun select(
            sdkInt: Int,
            reduceTransparency: Boolean,
            blurGranted: Boolean,
        ): GlassTier =
            if (sdkInt >= BLUR_MIN_SDK && !reduceTransparency && blurGranted) Frosted else Tinted
    }
}

/**
 * How opaque frosted stone glass is. Glass that carries text never drops under
 * [TEXT_OPACITY_MIN] (blur, tint and scrim combined) and rises toward [TEXT_OPACITY_BRIGHT] as the
 * backdrop behind it brightens, so text keeps WCAG AA over a bright mote or artwork
 * (`docs/design/research-high-end-references.md`, section 2).
 */
object GlassOpacity {
    /** The least opacity of glass under text, over the darkest backdrop. */
    const val TEXT_OPACITY_MIN = ForestGlass.TEXT_OPACITY_MIN

    /** The opacity of glass under text over the brightest backdrop. */
    const val TEXT_OPACITY_BRIGHT = 0.96f

    /** The least opacity of glass that carries no text: a bar of icons, a decorative plate. */
    const val BARE_OPACITY_MIN = 0.78f

    /** The opacity of glass without text over the brightest backdrop. */
    const val BARE_OPACITY_BRIGHT = 0.9f

    /** The opacity of [GlassTier.Tinted] glass: nothing blurs behind it, so it is nearly solid stone. */
    const val TINTED_OPACITY = 0.94f

    /** Fully opaque: the person asked Android or Geode for less transparency. */
    const val OPAQUE = 1f

    /**
     * The opacity of one panel over a backdrop of relative [backdropLuminance] (0 black, 1 white):
     * opaque when [reduceTransparency] is on, otherwise rising with the backdrop's brightness from
     * the floor for glass that [carriesText] or not. A [GlassTier.Tinted] panel never goes under
     * [TINTED_OPACITY].
     */
    fun of(
        backdropLuminance: Float,
        carriesText: Boolean,
        tier: GlassTier,
        reduceTransparency: Boolean,
    ): Float {
        if (reduceTransparency) return OPAQUE
        val lum = backdropLuminance.coerceIn(0f, 1f)
        val (floor, bright) =
            if (carriesText) TEXT_OPACITY_MIN to TEXT_OPACITY_BRIGHT else BARE_OPACITY_MIN to BARE_OPACITY_BRIGHT
        val live = floor + (bright - floor) * lum
        return if (tier == GlassTier.Tinted) maxOf(live, TINTED_OPACITY) else live
    }
}

/**
 * The screen's budget of live blurred surfaces: at most [MAX_LIVE_BLURS] at once (the bottom bar
 * and one sheet). Pure and immutable; a panel asks for a slot when it appears and returns it when
 * it leaves. A panel refused a slot draws as [GlassTier.Tinted], so the screen never pays for a third.
 */
data class LiveBlurBudget(
    val holders: List<Any> = emptyList(),
) {
    /** How many live blurs the screen draws now. */
    val live: Int get() = holders.size

    /** Whether [holder] draws a live blur. */
    fun holds(holder: Any): Boolean = holder in holders

    /** The budget after [holder] asks for a slot: granted while one is free, refused once both are taken. */
    fun request(holder: Any): LiveBlurBudget =
        if (holds(holder) || live >= MAX_LIVE_BLURS) this else copy(holders = holders + holder)

    /** The budget after [holder] gives its slot back. */
    fun release(holder: Any): LiveBlurBudget = if (holds(holder)) copy(holders = holders - holder) else this

    companion object {
        /** The most live blurred surfaces on one screen (`docs/design/app-ui-design-system.md`). */
        const val MAX_LIVE_BLURS = 2
    }
}
