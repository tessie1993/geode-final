package dev.geode.feature.customization.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.api.Target
import kotlin.random.Random

/**
 * Customize's place (ADR-0003): the only part that touches the Customization store. It reads its state
 * and turns the panel's actions into Commands. It opens over the live Visual, which the place showing
 * it draws (the Visuals tab, full-screen visual mode, a Recording, or the player's shortcut, #12).
 * `:app` shows it as the Visuals tab's Customize sub-tab and as a sheet over full-screen mode (#17).
 *
 * [onShare] shares the current Customization as a preset file or code through the share sheet (the
 * app stays offline), and [onHide] leaves the Visual alone once the panel is swiped away. [looks],
 * [presets] and [live] are what the opening place offers: the look picker's Styles, this Style's saved
 * presets, and the engine's live values when it has them. [ignored] are the controls the current
 * Style leaves alone, greyed out (#365). [onBrowsePresets] opens the Presets browser (#144), which the
 * Visuals feature keeps.
 */
@Composable
fun CustomizeRoute(
    customization: CustomizationFeature,
    onShare: (Customization) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    looks: List<LookChip> = emptyList(),
    presets: List<PresetChip> = emptyList(),
    live: LiveMeters = LiveMeters.NONE,
    ignored: Set<Target> = emptySet(),
    onBrowsePresets: () -> Unit = {},
) {
    val state by customization.state.collectAsStateWithLifecycle()
    CustomizeScreen(
        model = state.toCustomizeModel(looks, presets, live, ignored),
        onAction = { action ->
            when (action) {
                is CustomizeAction.Send -> customization.send(action.command)
                CustomizeAction.SurpriseMe -> customization.send(surprise(state.current, Random.Default))
                CustomizeAction.Share -> onShare(state.current)
                CustomizeAction.Hide -> onHide()
                CustomizeAction.BrowsePresets -> onBrowsePresets()
            }
        },
        modifier = modifier,
    )
}

/**
 * Modulators & Automation's place (ADR-0003): the Visuals tab's third sub-tab (#17, owner decision
 * 2026-10-22). Like [CustomizeRoute] it reads the Customization store and sends the screen's Commands.
 */
@Composable
fun ModulatorsAutomationRoute(
    customization: CustomizationFeature,
    modifier: Modifier = Modifier,
) {
    val state by customization.state.collectAsStateWithLifecycle()
    ModulatorsAutomationScreen(
        model = state.toCustomizeModel(),
        onAction = { action -> if (action is CustomizeAction.Send) customization.send(action.command) },
        modifier = modifier,
    )
}
