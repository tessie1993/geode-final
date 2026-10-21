package dev.geode.feature.customization.api

/** Every Style has exactly this many Macros (#11). */
const val MACRO_COUNT: Int = 6

/** Where a Macro rests: at this value every linked property sits at its own slider value. */
const val MACRO_REST: Float = 0.5f

/**
 * The one shared set of Macro names, the same on every Style (#11, rounds 4 and 5). Each Style maps
 * them to its own settings; the person can still rename, clear and remap any of them.
 */
enum class MacroRole(
    val title: String,
) {
    ENERGY("Energy"),
    DEPTH("Depth"),
    WARMTH("Warmth"),
    FLOW("Flow"),
    DETAIL("Detail"),
    CALM("Calm"),
}

/**
 * One property a Macro moves. At Macro 0 the [target] sits at [low], at [MACRO_REST] at its own
 * slider value, and at 1 at [high]; [low] and [high] are fractions 0..1 of the target's range. A link
 * whose [low] is above its [high] turns the target down as the Macro goes up.
 */
data class MacroLink(
    val target: Target,
    val low: Float,
    val high: Float,
)

/** One large control of the basic Customize view: a single value that moves every linked property. */
data class Macro(
    val name: String,
    val value: Float = MACRO_REST,
    val links: List<MacroLink> = emptyList(),
)
