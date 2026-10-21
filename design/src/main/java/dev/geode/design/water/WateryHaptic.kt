package dev.geode.design.water

/**
 * The App UI's watery haptics (`docs/design/app-ui-design-system.md`, "Haptics"): soft droplets and
 * spreading ripples rather than default clicks. They answer touch only, never the music, and the
 * person can switch them all off in Settings.
 */
enum class WateryHaptic {
    /** A single drop landing: a light tap on a control. */
    Droplet,

    /** A ring spreading out and fading: a touch that sets a ripple running through glass. */
    Ripple,

    /** A pebble settling into moss: a choice confirmed, a sheet coming to rest. */
    Settle,
}

/**
 * One of Android's haptic primitives (`VibrationEffect.Composition`), named here so the choice of
 * pattern stays pure Kotlin that the JVM tests can check.
 */
enum class HapticPrimitive {
    Tick,
    LowTick,
    Click,
    Thud,
    QuickRise,
    QuickFall,
}

/** One beat of a pattern: [primitive] at [scale] (0 to 1), after [delayMillis] of stillness. */
data class HapticStep(
    val primitive: HapticPrimitive,
    val scale: Float,
    val delayMillis: Int = 0,
) {
    init {
        require(scale in 0f..1f) { "a haptic scale lies between 0 and 1" }
        require(delayMillis >= 0)
    }
}

/** The plain platform feedback used when the phone's vibrator cannot play the composed primitives. */
enum class HapticFallback {
    /** `HapticFeedbackConstants.CLOCK_TICK`. */
    Tick,

    /** `HapticFeedbackConstants.CONTEXT_CLICK`. */
    Click,

    /** `HapticFeedbackConstants.CONFIRM`. */
    Confirm,
}

/** What the phone does for one [WateryHaptic]. */
sealed interface HapticPlan {
    /** Nothing: haptics are switched off in Settings. */
    data object Off : HapticPlan

    /** A composed pattern of primitives the vibrator supports. */
    data class Composed(
        val steps: List<HapticStep>,
    ) : HapticPlan

    /** The phone's own feedback, for vibrators without the primitives. */
    data class Platform(
        val fallback: HapticFallback,
    ) : HapticPlan
}

/** Chooses how each [WateryHaptic] plays, from the Settings switch and what the vibrator supports. */
object WateryHaptics {
    /** Where the Settings switch starts on a fresh install: watery haptics on. */
    const val ENABLED_BY_DEFAULT = true

    /** The patterns as authored: soft, short, and always fading out rather than stopping hard. */
    val patterns: Map<WateryHaptic, List<HapticStep>> =
        mapOf(
            WateryHaptic.Droplet to listOf(HapticStep(HapticPrimitive.LowTick, DROPLET_SCALE)),
            WateryHaptic.Ripple to
                listOf(
                    HapticStep(HapticPrimitive.LowTick, RIPPLE_FIRST_SCALE),
                    HapticStep(HapticPrimitive.Tick, RIPPLE_SECOND_SCALE, RIPPLE_GAP_MILLIS),
                    HapticStep(HapticPrimitive.Tick, RIPPLE_THIRD_SCALE, RIPPLE_GAP_MILLIS * RIPPLE_SPREAD),
                ),
            WateryHaptic.Settle to
                listOf(
                    HapticStep(HapticPrimitive.QuickFall, SETTLE_FALL_SCALE),
                    HapticStep(HapticPrimitive.Thud, SETTLE_THUD_SCALE, SETTLE_GAP_MILLIS),
                ),
        )

    /** The feedback each haptic falls back to when a primitive is missing. */
    private val fallbacks: Map<WateryHaptic, HapticFallback> =
        mapOf(
            WateryHaptic.Droplet to HapticFallback.Tick,
            WateryHaptic.Ripple to HapticFallback.Click,
            WateryHaptic.Settle to HapticFallback.Confirm,
        )

    /** A softer primitive that stands in for one the vibrator lacks, when it has that one. */
    private val substitutes: Map<HapticPrimitive, HapticPrimitive> =
        mapOf(
            HapticPrimitive.LowTick to HapticPrimitive.Tick,
            HapticPrimitive.Thud to HapticPrimitive.Click,
            HapticPrimitive.QuickFall to HapticPrimitive.Tick,
            HapticPrimitive.QuickRise to HapticPrimitive.Tick,
        )

    /**
     * The plan for [haptic]: [HapticPlan.Off] when [enabled] is false (the Settings switch), the
     * authored pattern when the vibrator [supported] every primitive (after soft substitutes), and
     * the platform's own feedback otherwise.
     */
    fun plan(
        haptic: WateryHaptic,
        enabled: Boolean,
        supported: Set<HapticPrimitive>,
    ): HapticPlan {
        if (!enabled) return HapticPlan.Off
        val steps =
            patterns.getValue(haptic).map { step ->
                val primitive =
                    step.primitive.takeIf { it in supported }
                        ?: substitutes[step.primitive]?.takeIf { it in supported }
                        ?: return HapticPlan.Platform(fallbacks.getValue(haptic))
                step.copy(primitive = primitive)
            }
        return HapticPlan.Composed(steps)
    }

    private const val DROPLET_SCALE = 0.55f
    private const val RIPPLE_FIRST_SCALE = 0.6f
    private const val RIPPLE_SECOND_SCALE = 0.32f
    private const val RIPPLE_THIRD_SCALE = 0.14f
    private const val RIPPLE_GAP_MILLIS = 38
    private const val RIPPLE_SPREAD = 2
    private const val SETTLE_FALL_SCALE = 0.35f
    private const val SETTLE_THUD_SCALE = 0.45f
    private const val SETTLE_GAP_MILLIS = 24
}
