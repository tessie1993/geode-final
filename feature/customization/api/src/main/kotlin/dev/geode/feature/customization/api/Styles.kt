package dev.geode.feature.customization.api

import dev.geode.engine.types.SceneId

/** How a Style is drawn, which decides which of its variables become Extras. */
enum class StyleFamily {
    SHADER,
    LIFE,
    MYCELIUM,
    ACID,
    FLUID,
    CURL_FLOW,
    WATER,
    CYMATICS,

    /** Shared core only, as decided: projectM paces itself. */
    MILKDROP,
}

/**
 * What a Style declares (#11): its [extras], its 6 suggested [macros], its editable default
 * [reactions] (today's hard-wired sound couplings), whether it has its own camera flight
 * ([ownFlight]; the Camera adds on top), and [shared], the Shared core values it starts from where
 * they differ from the property defaults (a retired in-Style effect such as its own vignette lands
 * here, so the Style still looks the same).
 */
data class StyleManifest(
    val id: SceneId,
    val family: StyleFamily,
    val ownFlight: Boolean,
    val extras: List<ExtraSpec>,
    val shared: Map<SharedProperty, Float>,
    val macros: List<Macro>,
    val reactions: List<Reaction>,
)
