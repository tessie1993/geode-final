package dev.geode.feature.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.design.element.ElementNavBar
import dev.geode.design.element.ElementNavItem
import dev.geode.design.theme.StoneIcon
import dev.geode.feature.home.api.Place

/**
 * The bar of places, in the current Element: Home, Player, Library, Visuals and Studio, always in that
 * order and with the same labels. On a place that is not on the bar (Settings), none is selected.
 */
@Composable
fun PlaceBar(
    current: Place,
    onOpen: (Place) -> Unit,
    modifier: Modifier = Modifier,
) {
    val places = Place.bar
    ElementNavBar(
        items = places.map { ElementNavItem(placeName(it), placeIcon(it)) },
        selected = places.indexOf(current),
        onSelect = { onOpen(places[it]) },
        modifier = modifier,
    )
}

/** What [place] is called on screen. */
@Composable
internal fun placeName(place: Place): String =
    stringResource(
        when (place) {
            Place.HOME -> R.string.place_home
            Place.PLAYER -> R.string.place_player
            Place.LIBRARY -> R.string.place_library
            Place.VISUALS -> R.string.place_visuals
            Place.STUDIO -> R.string.place_studio
            Place.SETTINGS -> R.string.place_settings
        },
    )

/** The glyph [place] is drawn with, until each Element's icon art (I1 to I5) replaces it. */
internal fun placeIcon(place: Place): StoneIcon =
    when (place) {
        Place.HOME -> StoneIcon.HOME
        Place.PLAYER -> StoneIcon.PLAY
        Place.LIBRARY -> StoneIcon.LIBRARY
        Place.VISUALS -> StoneIcon.VISUALIZER
        Place.STUDIO -> StoneIcon.STUDIO
        Place.SETTINGS -> StoneIcon.SETTINGS
    }
