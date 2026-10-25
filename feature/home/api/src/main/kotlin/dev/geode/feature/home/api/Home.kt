package dev.geode.feature.home.api

/**
 * A top-level place of the App UI. Five sit on the bar ([bar]); Settings opens from Home. The places,
 * their order and their labels are the same in every Element.
 */
enum class Place {
    HOME,
    PLAYER,
    LIBRARY,
    VISUALS,
    STUDIO,
    SETTINGS,
    ;

    /** Whether the place has a spot on the bar. */
    val onBar: Boolean get() = this in bar

    companion object {
        /** The bar's places, first to last. Home is first. */
        val bar: List<Place> = listOf(HOME, PLAYER, LIBRARY, VISUALS, STUDIO)

        /** Where the App UI opens. */
        val Start = HOME
    }
}

/** What Home asks the app to do. */
sealed interface HomeAction {
    /** Go to [place], as a tap on its navigation object or shortcut asks. */
    data class Open(
        val place: Place,
    ) : HomeAction
}
