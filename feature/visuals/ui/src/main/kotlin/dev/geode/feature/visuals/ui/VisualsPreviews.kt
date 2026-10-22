package dev.geode.feature.visuals.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.design.forest.ForestMode
import dev.geode.design.forest.ForestTheme
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.VisualsState

// The previews the screenshot tests render (ADR-0003). The screens are App UI drawn over the live
// Visual, so each preview puts a still stand-in behind them: a fixed gradient, never the Visual itself,
// which the shader golden frames cover.

/** A still stand-in for the live Visual behind the screens. */
@Composable
internal fun VisualStandIn(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(STAND_IN_DEEP), Color(STAND_IN_GLOW), Color(STAND_IN_WARM)))),
    ) {
        content()
    }
}

@Composable
internal fun VisualsPreview(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ForestTheme(mode = if (dark) ForestMode.Dark else ForestMode.Light) { VisualStandIn(content) }
}

@Composable
internal fun TabPreview(
    state: VisualsState,
    query: String = "",
    folder: FolderId? = null,
    dark: Boolean = true,
) = VisualsPreview(dark) { VisualsTabScreen(state.toTabModel(query, folder), onAction = {}) }

@Preview(name = "Visuals tab", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VisualsTabPreview() = TabPreview(PreviewStates.ready)

@Preview(name = "Visuals tab, light")
@Composable
internal fun VisualsTabLightPreview() = TabPreview(PreviewStates.ready, dark = false)

@Preview(name = "Visuals tab, loading", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VisualsTabLoadingPreview() = TabPreview(PreviewStates.loading)

@Preview(name = "Visuals tab, in a folder", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VisualsTabFolderPreview() = TabPreview(PreviewStates.ready, folder = PreviewStates.calmFolder)

@Preview(name = "Visuals tab, searching", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VisualsTabSearchPreview() = TabPreview(PreviewStates.ready, query = "fluid")

@Preview(name = "Visuals tab, nothing found", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun VisualsTabNothingFoundPreview() = TabPreview(PreviewStates.ready, query = "zzz")

@Preview(name = "Full screen, controls shown", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun FullScreenControlsPreview() =
    VisualsPreview(dark = true) { FullScreenVisualScreen(PreviewStates.ready.toFullScreenModel(controlsShown = true), onAction = {}) }

@Preview(name = "Full screen, controls hidden", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun FullScreenHiddenPreview() =
    VisualsPreview(dark = true) { FullScreenVisualScreen(PreviewStates.ready.toFullScreenModel(controlsShown = false), onAction = {}) }

private const val STAND_IN_DEEP = 0xFF0B2A3C
private const val STAND_IN_GLOW = 0xFF2E7D6B
private const val STAND_IN_WARM = 0xFFB0703A
