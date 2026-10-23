package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.ValueKind

/**
 * The A/B compare's blend: a Customization part of the way from [Customization] A to B. On one Style,
 * the values in between move: the Shared core's continuous properties, the Extras and the Macros'
 * settings. Everything that cannot be in between (a switch, a whole number, a Reaction, a Modulator, a
 * Style) comes from the nearer side.
 */
object AbBlend {
    private const val HALF = 0.5f

    /** [a] and [b] blended at [position], 0 being [a] and 1 [b]. */
    fun between(
        a: Customization,
        b: Customization,
        position: Float,
    ): Customization {
        val near = if (position < HALF) a else b
        if (a.style != b.style) return near
        return near.copy(
            shared =
                near.shared.mapValues { (property, value) ->
                    sharedAt(property, a.shared[property], b.shared[property], value, position)
                },
            extras = near.extras.mapValues { (kind, value) -> mix(a.extras[kind], b.extras[kind], position) ?: value },
            macros =
                near.macros.mapIndexed { index, macro ->
                    val value = mix(a.macros.getOrNull(index)?.value, b.macros.getOrNull(index)?.value, position)
                    if (value == null) macro else macro.copy(value = value)
                },
        )
    }

    private fun sharedAt(
        property: SharedProperty,
        a: Float?,
        b: Float?,
        near: Float,
        position: Float,
    ): Float = if (property.kind == ValueKind.CONTINUOUS) mix(a, b, position) ?: near else near

    private fun mix(
        a: Float?,
        b: Float?,
        position: Float,
    ): Float? = if (a == null || b == null) null else a + (b - a) * position
}
