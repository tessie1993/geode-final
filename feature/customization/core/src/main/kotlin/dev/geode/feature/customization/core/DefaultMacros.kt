package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.MacroRole
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.Target

/**
 * The proposed default mapping of the 6 shared Macros (#11, rounds 4 and 5), as data in this one
 * place, for owner review. Every Style gets the same 6 names; each maps them onto the Shared core and
 * onto the first of its own Extras that fits. Low and high are fractions of the target's range at
 * Macro 0 and Macro 1; at rest (0.5) every target sits at its own slider value, so an untouched Macro
 * never changes how a Style looks.
 */
object DefaultMacros {
    /** One link of the proposal: a Shared core property, or the first of [extras] the Style has. */
    data class LinkRule(
        val property: SharedProperty?,
        val extras: List<ExtraKind>,
        val low: Float,
        val high: Float,
        val flight: Boolean? = null,
    )

    private fun shared(
        property: SharedProperty,
        low: Float,
        high: Float,
        flight: Boolean? = null,
    ) = LinkRule(property, emptyList(), low, high, flight)

    private fun extra(
        vararg extras: ExtraKind,
        low: Float,
        high: Float,
    ) = LinkRule(null, extras.toList(), low, high)

    /** The proposal for every family but MilkDrop. */
    val LINKS: Map<MacroRole, List<LinkRule>> =
        mapOf(
            MacroRole.ENERGY to
                listOf(
                    shared(SharedProperty.SPEED, 0.12f, 0.5f),
                    shared(SharedProperty.AUDIO_DRIVE, 0.15f, 0.7f),
                    extra(ExtraKind.FLIGHT, ExtraKind.FORCE, ExtraKind.SWIRL, ExtraKind.DRIFT, low = 0.2f, high = 0.9f),
                ),
            MacroRole.DEPTH to
                listOf(
                    shared(SharedProperty.PUSH_IN, 0f, 0.6f, flight = true),
                    shared(SharedProperty.ZOOM, 0.15f, 0.55f, flight = false),
                    extra(ExtraKind.FOG, ExtraKind.COMPLEXITY, ExtraKind.SCALE, low = 0.2f, high = 0.85f),
                ),
            MacroRole.WARMTH to
                listOf(
                    shared(SharedProperty.TEMPERATURE, 0.2f, 0.85f),
                    shared(SharedProperty.SATURATION, 0.4f, 0.9f),
                ),
            MacroRole.FLOW to
                listOf(
                    shared(SharedProperty.WARP, 0f, 0.5f),
                    shared(SharedProperty.TRAILS, 0f, 0.6f),
                    extra(ExtraKind.DRIFT, ExtraKind.SWIRL, ExtraKind.VISCOSITY, low = 0.2f, high = 0.85f),
                ),
            MacroRole.DETAIL to
                listOf(
                    shared(SharedProperty.DETAIL, 0.2f, 0.9f),
                    extra(ExtraKind.COUNT, ExtraKind.COMPLEXITY, ExtraKind.THICKNESS, low = 0.2f, high = 0.85f),
                ),
            MacroRole.CALM to
                listOf(
                    shared(SharedProperty.SPEED, 0.4f, 0.08f),
                    shared(SharedProperty.AUDIO_DRIVE, 0.6f, 0.1f),
                    shared(SharedProperty.SHAKE, 0.3f, 0f),
                    shared(SharedProperty.SOFT_FOCUS, 0f, 0.3f),
                ),
        )

    /** MilkDrop: speed, audio drive and band gains do not apply (#11, round 3), so its Macros move the picture. */
    val MILKDROP_LINKS: Map<MacroRole, List<LinkRule>> =
        mapOf(
            MacroRole.ENERGY to listOf(shared(SharedProperty.SHAKE, 0f, 0.4f), shared(SharedProperty.CHROMATIC, 0f, 0.4f)),
            MacroRole.DEPTH to listOf(shared(SharedProperty.ZOOM, 0.15f, 0.55f)),
            MacroRole.WARMTH to listOf(shared(SharedProperty.TEMPERATURE, 0.2f, 0.85f), shared(SharedProperty.SATURATION, 0.4f, 0.9f)),
            MacroRole.FLOW to listOf(shared(SharedProperty.WARP, 0f, 0.5f), shared(SharedProperty.TRAILS, 0f, 0.6f)),
            MacroRole.DETAIL to listOf(shared(SharedProperty.DETAIL, 0.2f, 0.9f)),
            MacroRole.CALM to listOf(shared(SharedProperty.SOFT_FOCUS, 0f, 0.5f), shared(SharedProperty.MOTION_BLUR, 0f, 0.4f)),
        )

    /** The 6 suggested Macros of a Style of [family], with or without its own flight, that has [extras]. */
    fun forStyle(
        family: StyleFamily,
        ownFlight: Boolean,
        extras: Set<ExtraKind>,
    ): List<Macro> {
        val table = if (family == StyleFamily.MILKDROP) MILKDROP_LINKS else LINKS
        return MacroRole.entries.map { role ->
            Macro(name = role.title, links = table.getValue(role).mapNotNull { link(it, ownFlight, extras) })
        }
    }

    private fun link(
        rule: LinkRule,
        ownFlight: Boolean,
        extras: Set<ExtraKind>,
    ): MacroLink? {
        if (rule.flight != null && rule.flight != ownFlight) return null
        val target =
            if (rule.property != null) {
                Target.Shared(rule.property)
            } else {
                rule.extras.firstOrNull { it in extras }?.let { Target.Extra(it) } ?: return null
            }
        return MacroLink(target, rule.low, rule.high)
    }
}
