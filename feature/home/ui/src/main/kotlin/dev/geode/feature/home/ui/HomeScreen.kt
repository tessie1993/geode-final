package dev.geode.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.geode.design.element.ElementSurface
import dev.geode.design.element.LocalElementTokens
import dev.geode.design.theme.StoneIconArt
import dev.geode.feature.home.api.HomeAction
import dev.geode.feature.home.api.Place

/** What Home shows. Until its Hero scene (#110) it is a placeholder with shortcuts to [shortcuts]. */
@Immutable
data class HomeScreenModel(
    /** The places Home offers a shortcut to, in order. Settings opens only from here. */
    val shortcuts: List<Place> = listOf(Place.SETTINGS),
)

/** Home's sizes and gaps, the same in every Element. */
internal object HomeDimens {
    val ScreenPadding = 24.dp
    val Gap = 12.dp
    val ShortcutGap = 16.dp
    val ShortcutPaddingHorizontal = 20.dp
    val ShortcutPaddingVertical = 12.dp
    val IconToLabel = 8.dp

    /** The least size of anything that takes a tap. */
    val TapTarget = 48.dp
}

/**
 * The Home tab: for now a calm placeholder in the current Element's flat colours, with a shortcut for
 * each of [model]'s places. Its Hero scene and navigation objects come with #110.
 */
@Composable
fun HomeScreen(
    model: HomeScreenModel,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalElementTokens.current
    ElementSurface(tokens.colors.background, art = null, modifier = modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(HomeDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(HomeDimens.Gap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineLarge,
                color = tokens.colors.textPrimary,
            )
            Text(
                stringResource(R.string.home_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Row(
                Modifier.padding(top = HomeDimens.Gap),
                horizontalArrangement = Arrangement.spacedBy(HomeDimens.ShortcutGap),
            ) {
                model.shortcuts.forEach { place -> Shortcut(place) { onAction(HomeAction.Open(place)) } }
            }
        }
    }
}

@Composable
private fun Shortcut(
    place: Place,
    onClick: () -> Unit,
) {
    val tokens = LocalElementTokens.current
    Surface(
        onClick = onClick,
        shape = tokens.shapes.button,
        color = tokens.colors.surface,
        contentColor = tokens.colors.textPrimary,
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = HomeDimens.TapTarget, minHeight = HomeDimens.TapTarget)
                .padding(horizontal = HomeDimens.ShortcutPaddingHorizontal, vertical = HomeDimens.ShortcutPaddingVertical),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(HomeDimens.IconToLabel)) {
                // The label names the shortcut, so the glyph is not read out as well.
                StoneIconArt(placeIcon(place), contentDescription = null, tint = tokens.colors.active)
                Text(placeName(place), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
