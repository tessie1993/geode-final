package dev.geode.design.ambient

/**
 * How the floating assets behind the App UI behave (`docs/design/app-ui-design-system.md`). The
 * person picks one in Settings. Never anything of the Visual, and never driven by the music.
 */
enum class AmbientMode {
    /** The assets drift slowly with gentle parallax. The default. */
    Drifting,

    /** The assets are shown where they were placed and do not move. */
    Still,

    /** No floating assets at all. */
    Off,
    ;

    companion object {
        /** The mode a fresh install starts with. */
        val Default = Drifting
    }
}

/** Resolves the mode the ambient layer actually shows from the person's choice and the system's state. */
object AmbientPolicy {
    /**
     * The mode to show. [Off][AmbientMode.Off] always wins; otherwise battery saver ([powerSaver])
     * or reduced motion ([reducedMotion], the system's "remove animations" or the app's own setting)
     * turn drifting into [Still][AmbientMode.Still].
     */
    fun effectiveMode(
        chosen: AmbientMode,
        powerSaver: Boolean,
        reducedMotion: Boolean,
    ): AmbientMode =
        when {
            chosen == AmbientMode.Off -> AmbientMode.Off
            powerSaver || reducedMotion -> AmbientMode.Still
            else -> chosen
        }
}
