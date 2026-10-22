package dev.geode.design.forest

/** Whether the forest theme is shown dark (the default) or in its "sunlit meadow" light version. */
enum class ForestMode {
    Dark,
    Light,
    ;

    companion object {
        /** The mode a fresh install starts with. */
        val Default = Dark
    }
}
