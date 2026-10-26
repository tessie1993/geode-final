package dev.geode.feature.customization.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.geode.design.forest.ForestMode
import dev.geode.design.forest.ForestTheme
import dev.geode.design.forest.LocalForestColors
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Layer
import dev.geode.feature.customization.api.LayerBlend
import dev.geode.feature.customization.api.LayerInfluence
import dev.geode.feature.customization.api.LoopPreset
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SoundSignal

// The Customize panel's previews (ADR-0003), in the water, forest and stone theme (:design).
// The plain ground behind the panel stands in for the live Visual, which the previews never show.

@Composable
private fun CustomizePreview(
    model: CustomizeModel = PreviewModels.editing,
    start: CustomizePage = CustomizePage.Basic,
    mode: ForestMode = ForestMode.Dark,
) {
    ForestTheme(mode = mode) {
        Box(Modifier.fillMaxSize().background(LocalForestColors.current.base)) {
            CustomizeScreen(model, onAction = {}, start = start)
        }
    }
}

private val bass = ReactionSource.Sound(SoundSignal.BASS)

@Preview(name = "Basic view", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeBasicPreview() = CustomizePreview()

@Preview(name = "Basic view, light", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeBasicLightPreview() = CustomizePreview(mode = ForestMode.Light)

@Preview(name = "Basic view, a fresh Style", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeFreshPreview() = CustomizePreview(PreviewModels.fresh)

@Preview(name = "Basic view, landscape", widthDp = 891, heightDp = 411)
@Composable
internal fun CustomizeLandscapePreview() = CustomizePreview()

@Preview(name = "Advanced, Reactions", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeReactionsPreview() = CustomizePreview(start = CustomizePage.Advanced(AdvancedSection.REACTIONS))

@Preview(name = "Advanced, patch board", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeBoardPreview() = CustomizePreview(start = CustomizePage.Advanced(AdvancedSection.REACTIONS, board = true))

@Preview(name = "Advanced, no links yet", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeNoLinksPreview() = CustomizePreview(PreviewModels.fresh, CustomizePage.Advanced(AdvancedSection.REACTIONS))

@Preview(name = "Modulators & Automation", widthDp = 411, heightDp = 891)
@Composable
internal fun ModulatorsAutomationPreview() = ModulatorsAutomationPreviewOf(PreviewModels.editing)

@Preview(name = "Modulators & Automation, nothing yet", widthDp = 411, heightDp = 891)
@Composable
internal fun ModulatorsAutomationFreshPreview() = ModulatorsAutomationPreviewOf(PreviewModels.fresh)

@Composable
private fun ModulatorsAutomationPreviewOf(model: CustomizeModel) {
    ForestTheme(mode = ForestMode.Dark) {
        Box(Modifier.fillMaxSize().background(LocalForestColors.current.base)) {
            ModulatorsAutomationScreen(model, onAction = {})
        }
    }
}

@Preview(name = "Advanced, Camera", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeCameraPreview() = CustomizePreview(start = CustomizePage.Advanced(AdvancedSection.CAMERA))

@Preview(name = "Advanced, Shared effects", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeEffectsPreview() = CustomizePreview(start = CustomizePage.Advanced(AdvancedSection.EFFECTS))

@Preview(name = "Advanced, Style Extras", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeExtrasPreview() = CustomizePreview(start = CustomizePage.Advanced(AdvancedSection.EXTRAS))

@Preview(name = "Advanced, Layers", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeLayersPreview() =
    CustomizePreview(
        PreviewModels.editing.copy(
            layer = Layer(SceneId("warp"), LayerBlend.SCREEN, LayerInfluence.BEND, LoopPreset.BREATHE.loop),
        ),
        CustomizePage.Advanced(AdvancedSection.LAYERS),
    )

@Preview(name = "Advanced, one Macro", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeMacroPreview() = CustomizePreview(start = CustomizePage.Advanced(AdvancedSection.MACROS, macro = 0))

@Preview(name = "New link, source", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeChooseSourcePreview() = CustomizePreview(start = CustomizePage.ChooseSource())

@Preview(name = "New link, target", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeChooseTargetPreview() = CustomizePreview(start = CustomizePage.ChooseTarget(source = bass))

@Preview(name = "New link, shape", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeReactionEditorPreview() = CustomizePreview(start = CustomizePage.EditReaction(PreviewModels.draft, isNew = true))

@Preview(name = "Save preset", widthDp = 411, heightDp = 891)
@Composable
internal fun CustomizeSavePresetPreview() = CustomizePreview(start = CustomizePage.SavePreset)
