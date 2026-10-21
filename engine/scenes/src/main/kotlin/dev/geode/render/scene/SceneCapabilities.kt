package dev.geode.render.scene

import dev.geode.engine.types.SceneId

/**
 * Which renderer draws a Style: one entry per renderer, not per Style. A Style pack names its renderer
 * by [familyName] (`"family"` in its manifest); `geode/viz/StyleProfiles.hpp` lists the same names.
 */
enum class SceneKind(
    val familyName: String,
) {
    SHADER("shader"),

    LIFE("life"),

    MYCELIUM("mycelium"),

    ACID("acid"),

    MILKDROP("milkdrop"),

    FLUID("fluid"),

    CURL_FLOW("curl_flow"),

    WATER("water"),

    CYMATICS("cymatics"),
    ;

    companion object {
        /** The renderer a manifest's `"family"` names; null for a name no renderer has. */
        fun forFamily(name: String): SceneKind? = entries.firstOrNull { it.familyName == name }
    }
}

/** What a Style can do, read from its pack ([StylePacks]). */
object SceneCapabilities {
    /**
     * True for a Style that raymarches, and so spends the [MarchBudget] the Detail control sets
     * (`"detail": "march"` in its manifest).
     *
     * Per Style rather than per [SceneKind], because only some shader Styles march; scoping Detail to
     * `SceneKind.SHADER` would put a dead slider in front of anyone looking at Plasma, which is exactly
     * what [ParamScope]'s no-dead-controls rule exists to prevent.
     */
    fun isMarched(sceneId: SceneId): Boolean = StylePacks.find(sceneId)?.detail == DetailUse.MARCH

    /**
     * The renderer [sceneId] builds.
     *
     * An id no Style has resolves to the kind of [SceneIds.DEFAULT], because a stored id like that
     * loads as the default Style ([storedOrDefault]), so the Customize panel describes what will
     * actually be on screen rather than an id nothing can render.
     */
    fun kindOf(sceneId: SceneId): SceneKind = StylePacks.find(sceneId)?.kind ?: defaultKind

    /** True while some Style is built from [sceneId]. */
    fun isKnown(sceneId: SceneId): Boolean = StylePacks.find(sceneId) != null

    /**
     * The Style a stored id loads as: [sceneId] itself while a Style still has that id, otherwise
     * [SceneIds.DEFAULT].
     *
     * Saved presets, the live settings, templates, playlists and Studio projects outlive the Styles
     * they name. One that names a Style that has since been deleted loads the default Style
     * instead, with no error, and the rest of what it stores is kept.
     */
    fun storedOrDefault(sceneId: SceneId): SceneId = if (isKnown(sceneId)) sceneId else SceneIds.DEFAULT

    private val defaultKind: SceneKind by lazy {
        checkNotNull(StylePacks.find(SceneIds.DEFAULT)?.kind) { "SceneIds.DEFAULT (${SceneIds.DEFAULT}) names no Style" }
    }

    /** The Style's display name; an id no Style has is title-cased so it still reads as a name. */
    fun label(sceneId: SceneId): String =
        StylePacks.find(sceneId)?.label
            ?: sceneId.value.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun isKind(
        sceneId: SceneId,
        kind: SceneKind,
    ): Boolean = StylePacks.find(sceneId)?.kind == kind

    fun isFluid(sceneId: SceneId): Boolean = isKind(sceneId, SceneKind.FLUID)

    fun isWater(sceneId: SceneId): Boolean = isKind(sceneId, SceneKind.WATER)

    fun isCymatics(sceneId: SceneId): Boolean = isKind(sceneId, SceneKind.CYMATICS)

    fun isMilkdrop(sceneId: SceneId): Boolean = isKind(sceneId, SceneKind.MILKDROP)

    fun hasJourney(sceneId: SceneId): Boolean = isFluid(sceneId) || isKind(sceneId, SceneKind.CURL_FLOW) || isWater(sceneId)

    fun hasEmitters(sceneId: SceneId): Boolean = isFluid(sceneId) || isWater(sceneId)

    fun hasParticleLayer(sceneId: SceneId): Boolean = isFluid(sceneId) || isKind(sceneId, SceneKind.CURL_FLOW)

    fun usesPointSprites(sceneId: SceneId): Boolean = hasParticleLayer(sceneId)

    /**
     * The Cymatics geometry a Cymatics Style forces (its pack's `geometryOverride`), as an index into
     * [SceneParams.CYMATICS_GEOMETRIES]; null when the user's own choice applies.
     */
    fun cymaticsGeometryOverride(sceneId: SceneId): Int? =
        StylePacks
            .find(sceneId)
            ?.takeIf { it.kind == SceneKind.CYMATICS }
            ?.parameters
            ?.get(CYMATICS_GEOMETRY_OVERRIDE)
            ?.let { Math.round(it) }
            ?.takeIf { it >= 0 }

    /** The Cymatics family parameter naming a forced geometry (`styles/families.json`). */
    private const val CYMATICS_GEOMETRY_OVERRIDE = "geometryOverride"
}
