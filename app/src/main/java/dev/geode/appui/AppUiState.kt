package dev.geode.appui

/**
 * Where the App UI can be (#182): the five places on the bar, and Settings. The App UI reducer owns
 * which of them is shown and the back stack under it; the navigation graph (#136) draws them.
 */
sealed interface AppRoute {
    /** Settings, opened from Home and stacked above it. Not a place on the bar. */
    data object Settings : AppRoute
}

/**
 * The places on the bar, in its order. The bar and the rail always show all five (#136, #180
 * decision 11), and they are the same in every Element.
 */
enum class Place : AppRoute {
    HOME,
    PLAYER,
    LIBRARY,
    VISUALS,
    STUDIO,
}

/** The Visuals tab's three sub-tabs, in their order (owner, #17). */
enum class VisualsTab {
    STYLE,
    CUSTOMIZE,
    MODULATORS_AND_AUTOMATION,
}

/** Where a Customize sheet was opened from (owner, #17): #12's entry points stay. */
enum class CustomizeEntry {
    FULL_SCREEN,
    RECORDING,
    PLAYER,
}

/** A bottom sheet over the screen. One is open at a time; each new sheet adds a member here. */
sealed interface AppSheet {
    /** Customize as a bottom sheet, besides its Visuals sub-tab (owner, #17). */
    data class Customize(
        val from: CustomizeEntry,
    ) : AppSheet
}

/**
 * The one app-wide error banner (ADR-0004), over every screen. It shows one failure at a time; a newer
 * one replaces the older, so they never stack.
 */
sealed interface Banner {
    data object Hidden : Banner

    /** A failure the banner has not drawn yet. Its time on screen has not started. */
    data class Waiting(
        val message: String,
    ) : Banner

    /** A failure on screen, for [BANNER_VISIBLE] from when it was drawn or until it is dismissed. */
    data class OnScreen(
        val message: String,
    ) : Banner
}

/**
 * The back stack and what sits over it. The stack is always Home, then at most one place above it:
 * a tab other than Home, or Settings. So Back from anywhere returns to Home, Back from Home leaves
 * the app, and choosing the tab already shown never stacks a duplicate (#136).
 *
 * [visualsTab] is the Visuals sub-tab, kept while away from Visuals. [searching] is the Search overlay,
 * drawn over the stack.
 */
data class Navigation(
    val backStack: List<AppRoute> = listOf(Place.HOME),
    val visualsTab: VisualsTab = VisualsTab.STYLE,
    val searching: Boolean = false,
) {
    /** The route on top of the stack: what is shown. */
    val current: AppRoute get() = backStack.last()

    /** The place the bar and the rail show as selected: Settings sits under Home. */
    val tab: Place get() = current as? Place ?: Place.HOME

    /** The stack with [route] shown: Home, then [route] unless it is Home. Showing it again stacks nothing. */
    fun to(route: AppRoute): Navigation = copy(backStack = if (route == Place.HOME) listOf(Place.HOME) else listOf(Place.HOME, route))
}

/**
 * The App UI's own state (#182, ADR-0006): what the App UI shows, as opposed to what the features
 * hold. Screen-local, short-lived state (a text field's draft, a scroll position, a menu that is
 * open) stays in Compose `remember`.
 *
 * The App UI is in one of two modes: [Browsing] the places with the bar, or the Visual in
 * [FullScreen] visual mode, which keeps the back stack under it. Either may have a [sheet] open and
 * the [banner] showing.
 */
sealed interface AppUiState {
    val navigation: Navigation
    val sheet: AppSheet?
    val banner: Banner

    /** Whether Back has something to close or pop here; when it has not, Back leaves the app. */
    val canGoBack: Boolean
        get() = sheet != null || this is FullScreen || navigation.searching || navigation.backStack.size > 1

    data class Browsing(
        override val navigation: Navigation = Navigation(),
        override val sheet: AppSheet? = null,
        override val banner: Banner = Banner.Hidden,
    ) : AppUiState

    /** Full-screen visual mode: the Visual over everything, the bar hidden. */
    data class FullScreen(
        override val navigation: Navigation = Navigation(),
        override val sheet: AppSheet? = null,
        override val banner: Banner = Banner.Hidden,
    ) : AppUiState

    companion object {
        /** A fresh start: Home, nothing open. */
        val Initial: AppUiState = Browsing()
    }
}

/** This state with the given parts changed, in the same mode. */
internal fun AppUiState.with(
    navigation: Navigation = this.navigation,
    sheet: AppSheet? = this.sheet,
    banner: Banner = this.banner,
): AppUiState =
    when (this) {
        is AppUiState.Browsing -> copy(navigation = navigation, sheet = sheet, banner = banner)
        is AppUiState.FullScreen -> copy(navigation = navigation, sheet = sheet, banner = banner)
    }
