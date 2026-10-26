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
import dev.geode.design.glass.GlassTheme
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.VisualsState

// The Visuals previews (ADR-0003). The screens are App UI drawn over the live Visual, so each preview
// puts a still stand-in behind them: a fixed gradient, never the Visual itself.

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
    GlassTheme(dark = dark) { VisualStandIn(content) }
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

@Composable
internal fun MilkdropPreviewOf(
    state: VisualsState,
    dark: Boolean = true,
) = VisualsPreview(dark) { MilkdropScreen(state.toMilkdropModel(), onAction = {}) }

@Preview(name = "MilkDrop, after an import", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MilkdropPreview() = MilkdropPreviewOf(PreviewStates.milkdrop)

@Preview(name = "MilkDrop, light")
@Composable
internal fun MilkdropLightPreview() = MilkdropPreviewOf(PreviewStates.milkdrop, dark = false)

@Preview(name = "MilkDrop, after a pack", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MilkdropPackPreview() = MilkdropPreviewOf(PreviewStates.milkdropPack)

@Preview(name = "MilkDrop, loading", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MilkdropLoadingPreview() = MilkdropPreviewOf(PreviewStates.milkdropLoading)

@Preview(name = "MilkDrop, importing", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun MilkdropImportingPreview() = MilkdropPreviewOf(PreviewStates.milkdropImporting)

@Composable
internal fun PresetsPreview(
    state: VisualsState,
    place: PresetPlace = PresetPlace.TOP,
    notice: PresetNotice? = null,
    dark: Boolean = true,
) = VisualsPreview(dark) { PresetsScreen(state.toPresetsModel(place, notice), onAction = {}) }

@Preview(name = "Presets", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PresetsTopPreview() = PresetsPreview(PreviewStates.ready)

@Preview(name = "Presets, light")
@Composable
internal fun PresetsLightPreview() = PresetsPreview(PreviewStates.ready, dark = false)

@Preview(name = "Presets, loading", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PresetsLoadingPreview() = PresetsPreview(PreviewStates.loading)

@Preview(name = "Presets, in a folder", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PresetsFolderPreview() = PresetsPreview(PreviewStates.ready, PresetPlace.Saved(PreviewStates.calmPresetFolder))

@Preview(name = "Presets, built-in", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PresetsBuiltInPreview() = PresetsPreview(PreviewStates.ready, PresetPlace.BuiltIn)

@Preview(name = "Presets, built-in for a Style", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PresetsBuiltInStylePreview() = PresetsPreview(PreviewStates.ready, PresetPlace.BuiltInFor(PreviewStates.tunnelStyle))

@Preview(name = "Presets, imported", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PresetsImportedPreview() = PresetsPreview(PreviewStates.ready, notice = PresetNotice.Imported("Sunday morning 2"))

@Preview(name = "Presets, none saved", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun PresetsNoneSavedPreview() =
    PresetsPreview(PreviewStates.ready.copy(presets = PreviewStates.shelf.copy(saved = emptyList(), folders = emptyList())))

private const val STAND_IN_DEEP = 0xFF0B2A3C
private const val STAND_IN_GLOW = 0xFF2E7D6B
private const val STAND_IN_WARM = 0xFFB0703A
