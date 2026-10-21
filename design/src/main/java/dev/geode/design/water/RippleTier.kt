package dev.geode.design.water

/**
 * How a touch ripple is drawn on this phone (`docs/design/app-ui-design-system.md`, "Effects by
 * Android version"). Every tier is designed; none is a broken version of another.
 */
enum class RippleTier {
    /** Android 13 and later: an AGSL shader displaces the glass itself, with a faint light on the crest. */
    Shader,

    /** Android 12: a pre-rendered water ring is scaled and faded over the touched glass. */
    Ring,

    /** The person removed animations: a short, still opacity pulse instead of a moving ripple. */
    Pulse,
    ;

    companion object {
        /** The first Android version with `RuntimeShader` (AGSL). */
        const val SHADER_MIN_SDK = 33

        /** The tier for a phone on [sdkInt], with Android's animations [animationsEnabled] or removed. */
        fun select(
            sdkInt: Int,
            animationsEnabled: Boolean,
        ): RippleTier =
            when {
                !animationsEnabled -> Pulse
                sdkInt >= SHADER_MIN_SDK -> Shader
                else -> Ring
            }
    }
}
