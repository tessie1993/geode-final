package dev.geode.feature.home.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.design.element.ElementSkins
import dev.geode.design.element.ElementTheme
import dev.geode.design.element.ElementTokens
import dev.geode.feature.home.api.Place

// Previews of Home and the bar in each first-wave Element, which the screenshot tests render (ADR-0003).

private const val PREVIEW_WIDTH_DP = 411
private const val PREVIEW_HEIGHT_DP = 891

/** Home as the app shows it: the screen with the bar below, on Home. */
@Composable
internal fun HomePreview(element: ElementTokens) {
    ElementTheme(element) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { HomeScreen(HomeScreenModel(), onAction = {}) }
            PlaceBar(current = Place.HOME, onOpen = {})
        }
    }
}

/** The bar on its own, on [current]. */
@Composable
internal fun PlaceBarPreview(
    element: ElementTokens,
    current: Place = Place.LIBRARY,
) {
    ElementTheme(element) { PlaceBar(current = current, onOpen = {}) }
}

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun HomeWaterPreview() = HomePreview(ElementSkins.Water)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun HomeWoodPreview() = HomePreview(ElementSkins.Wood)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun HomeStonePreview() = HomePreview(ElementSkins.Stone)

@Preview(widthDp = PREVIEW_WIDTH_DP, heightDp = PREVIEW_HEIGHT_DP)
@Composable
internal fun HomeBioluminescencePreview() = HomePreview(ElementSkins.Bioluminescence)

@Preview(widthDp = PREVIEW_WIDTH_DP)
@Composable
internal fun PlaceBarWaterPreview() = PlaceBarPreview(ElementSkins.Water)

@Preview(widthDp = PREVIEW_WIDTH_DP)
@Composable
internal fun PlaceBarWoodPreview() = PlaceBarPreview(ElementSkins.Wood)

@Preview(widthDp = PREVIEW_WIDTH_DP)
@Composable
internal fun PlaceBarStonePreview() = PlaceBarPreview(ElementSkins.Stone)

@Preview(widthDp = PREVIEW_WIDTH_DP)
@Composable
internal fun PlaceBarBioluminescencePreview() = PlaceBarPreview(ElementSkins.Bioluminescence)
