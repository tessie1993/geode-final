package dev.geode.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.geode.R
import dev.geode.design.theme.StoneIcon

/** A place in the App UI's navigation graph: a bar tab, or a screen reached from one. */
sealed interface GeodeRoute

/**
 * The app's top-level places, in the order the navigation bar and rail show them.
 *
 * Each destination carries its own label and icon so the bar is built from this list rather
 * than from a parallel one — a top-level place cannot exist without a way to reach it, and a
 * `when` over these has no `else`, so adding one is a compile error until it is handled.
 * The same list serves every Element: an Element changes how the bar looks, never where things are.
 */
enum class GeodeDestination(
    @param:StringRes val labelRes: Int,
    val icon: StoneIcon,
) : GeodeRoute {
    HOME(R.string.nav_home, StoneIcon.HOME),
    PLAYER(R.string.nav_player, StoneIcon.PLAY),
    LIBRARY(R.string.nav_library, StoneIcon.LIBRARY),
    VISUALS(R.string.nav_visuals, StoneIcon.VISUALIZER),
    STUDIO(R.string.nav_studio, StoneIcon.STUDIO),
}

/** Settings, opened from Home and stacked above it. Not a bar tab. */
data object SettingsRoute : GeodeRoute

/**
 * The App UI's navigation state: the back stack [NavDisplay][androidx.navigation3.ui.NavDisplay]
 * shows, plus the overlays drawn over it.
 *
 * The stack is always Home, then at most one place above it: a tab, or Settings. So Back from
 * anywhere returns to Home, Back from Home leaves the app, and re-selecting a tab never stacks a
 * duplicate.
 */
@Stable
class GeodeAppState(
    route: GeodeRoute = GeodeDestination.HOME,
    expanded: Boolean = false,
    searching: Boolean = false,
) {
    val backStack = mutableStateListOf<GeodeRoute>(GeodeDestination.HOME)

    var expanded by mutableStateOf(expanded)

    var searching by mutableStateOf(searching)

    init {
        navigateTo(route)
    }

    val current: GeodeRoute get() = backStack.last()

    /** The tab the bar and rail show as selected: Settings sits under Home. */
    val tab: GeodeDestination get() = current as? GeodeDestination ?: GeodeDestination.HOME

    val onPlayer: Boolean get() = current == GeodeDestination.PLAYER

    fun openSearch() {
        searching = true
    }

    fun closeSearch() {
        searching = false
    }

    fun expand() {
        expanded = true
    }

    fun collapse() {
        expanded = false
    }

    fun navigateTo(route: GeodeRoute) {
        if (route == current) return
        backStack.retainAll { it == GeodeDestination.HOME }
        if (route != GeodeDestination.HOME) backStack.add(route)
    }

    fun openSettings() = navigateTo(SettingsRoute)

    /** Pops one place. Home is never popped; Back there is the system's (it leaves the app). */
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    companion object {
        private const val SETTINGS_NAME = "SETTINGS"

        private fun nameOf(route: GeodeRoute): String =
            when (route) {
                is GeodeDestination -> route.name
                SettingsRoute -> SETTINGS_NAME
            }

        /** An unknown saved name (a place since removed or renamed) falls back to Home. */
        internal fun routeNamed(name: Any?): GeodeRoute =
            if (name == SETTINGS_NAME) {
                SettingsRoute
            } else {
                GeodeDestination.entries.firstOrNull { it.name == name } ?: GeodeDestination.HOME
            }

        val Saver: Saver<GeodeAppState, List<Any>> =
            Saver(
                // Saved by name, not position: reordering the navigation bar then cannot silently
                // restore a process-death survivor onto a different screen.
                save = { listOf(nameOf(it.current), it.expanded, it.searching) },
                restore = {
                    GeodeAppState(
                        route = routeNamed(it[0]),
                        expanded = it[1] as Boolean,
                        searching = it[2] as Boolean,
                    )
                },
            )
    }
}

@Composable
fun rememberGeodeAppState(): GeodeAppState = rememberSaveable(saver = GeodeAppState.Saver) { GeodeAppState() }
