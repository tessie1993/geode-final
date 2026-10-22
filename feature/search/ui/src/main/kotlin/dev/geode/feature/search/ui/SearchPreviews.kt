package dev.geode.feature.search.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.design.forest.ForestMode
import dev.geode.design.forest.ForestTheme

// The previews the screenshot tests render (ADR-0003), in the default theme (:design).

@Composable
internal fun SearchPreview(
    model: SearchModel,
    mode: ForestMode = ForestMode.Dark,
) {
    ForestTheme(mode = mode) { SearchScreen(model, onAction = {}) }
}

@Preview(name = "Search, nothing typed", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SearchEmptyPreview() = SearchPreview(PreviewModels.empty)

@Preview(name = "Search, results", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SearchFoundPreview() = SearchPreview(PreviewModels.found)

@Preview(name = "Search, results, light")
@Composable
internal fun SearchFoundLightPreview() = SearchPreview(PreviewModels.found, ForestMode.Light)

@Preview(name = "Search, only presets", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SearchPresetsOnlyPreview() = SearchPreview(PreviewModels.presetsOnly)

@Preview(name = "Search, nothing found", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SearchNothingFoundPreview() = SearchPreview(PreviewModels.nothingFound)
