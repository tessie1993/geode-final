package dev.geode.design.forest

/**
 * The three themes of the water, forest and stone App UI (`docs/design/app-ui-design-system.md`).
 * The person picks one in Settings; each comes in a dark and a light [ForestMode].
 */
enum class ForestThemeId {
    /** Pine-black base, slate and granite glass, moss accent, amber highlights. The default. */
    DeepForest,

    /** Green-black base, glowing teal and firefly-yellow accents. */
    NightForest,

    /** Umber and basalt, pale lichen green and rust accents. */
    EarthLichen,
    ;

    companion object {
        /** The theme a fresh install starts with. */
        val Default = DeepForest
    }
}
